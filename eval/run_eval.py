#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""三路检索(dense / sparse / hybrid) × 检索质量指标(nDCG@k / Recall@k / MRR)。

- 检索全部限定 document_id='eval-synth'，与库内其它数据隔离。
- dense: pgvector 余弦(embedding <=>)；sparse: ts_rank_cd + to_tsquery；
  hybrid: Python 端复现 app 的 RRF(k=60) + MMR(λ=0.7, 近重复阈值0.92)。
- query 的 embedding 必须与入库时同源：真实跑用 sub2api，自测用 --fake-embed（与 build_index 同算法）。
"""
import argparse
import hashlib
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import EMBED_DIM, embed_texts, esc, run_psql, to_tsquery_expr, vec_literal

EVAL_DOC_ID = "eval-synth"
RRF_K = 60
MMR_LAMBDA = 0.7
MMR_DUP = 0.92


def fake_embed(texts):
    """与 build_index.fake_embed 逐字一致，保证 query 与 chunk 伪向量同空间。"""
    out = []
    for t in texts:
        v = [0.0] * EMBED_DIM
        for tok in t.split():
            h = int(hashlib.md5(tok.encode("utf-8")).hexdigest(), 16)
            v[h % EMBED_DIM] += 1.0 if (h & 1) else -1.0
        norm = math.sqrt(sum(x * x for x in v)) or 1.0
        out.append([x / norm for x in v])
    return out


def parse_vec(s):
    s = s.strip().strip("[]")
    return [float(x) for x in s.split(",")] if s else []


def cosine(a, b):
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = na = nb = 0.0
    for x, y in zip(a, b):
        dot += x * y
        na += x * x
        nb += y * y
    if na == 0 or nb == 0:
        return 0.0
    return dot / (math.sqrt(na) * math.sqrt(nb))


def dense_search(qvec, topn):
    sql = ("SELECT id, embedding::text FROM rag_chunks "
           "WHERE document_id='%s' AND embedding IS NOT NULL "
           "ORDER BY embedding <=> '%s'::vector LIMIT %d" % (EVAL_DOC_ID, vec_literal(qvec), topn))
    out = []
    for r in run_psql(sql, fetch=True).splitlines():
        if not r:
            continue
        p = r.split("\t")
        out.append((p[0], parse_vec(p[1])))
    return out  # [(id, vec)]


def sparse_search(query, topn):
    expr = to_tsquery_expr(query)
    if not expr:
        return []
    sql = ("SELECT id FROM rag_chunks "
           "WHERE document_id='%s' AND search_vector @@ to_tsquery('simple','%s') "
           "ORDER BY ts_rank_cd(search_vector, to_tsquery('simple','%s')) DESC LIMIT %d"
           % (EVAL_DOC_ID, esc(expr), esc(expr), topn))
    return [r for r in run_psql(sql, fetch=True).splitlines() if r]


def fetch_vecs(ids):
    if not ids:
        return {}
    idlist = ",".join("'%s'" % esc(i) for i in ids)
    sql = "SELECT id, embedding::text FROM rag_chunks WHERE id IN (%s)" % idlist
    vecs = {}
    for r in run_psql(sql, fetch=True).splitlines():
        if not r:
            continue
        p = r.split("\t")
        vecs[p[0]] = parse_vec(p[1])
    return vecs


def rrf_fuse(dense_ids, sparse_ids, k=RRF_K):
    score = {}
    for rank, did in enumerate(dense_ids, 1):
        score[did] = score.get(did, 0.0) + 1.0 / (k + rank)
    for rank, sid in enumerate(sparse_ids, 1):
        score[sid] = score.get(sid, 0.0) + 1.0 / (k + rank)
    return sorted(score, key=lambda x: score[x], reverse=True)


def mmr(cands, vecs, topk, lam=MMR_LAMBDA, dup=MMR_DUP):
    if not cands:
        return []
    rel = {cid: (len(cands) - i) for i, cid in enumerate(cands)}  # 按融合排名给相关性
    maxrel = max(rel.values()) or 1
    selected, remaining = [], list(cands)
    while remaining and len(selected) < topk:
        best, best_s = None, -1e9
        for c in remaining:
            msim = 0.0
            for s in selected:
                msim = max(msim, cosine(vecs.get(c, []), vecs.get(s, [])))
            if msim >= dup:
                continue
            s = lam * (rel[c] / maxrel) - (1 - lam) * msim
            if s > best_s:
                best_s, best = s, c
        if best is None:
            break
        selected.append(best)
        remaining.remove(best)
    return selected


# ---- 指标 ----
def dcg(rels):
    return sum(rel / math.log2(i + 2) for i, rel in enumerate(rels))


def ndcg_at_k(ranked_ids, qrel, k):
    gains = [qrel.get(cid, 0) for cid in ranked_ids[:k]]
    idcg = dcg(sorted(qrel.values(), reverse=True)[:k])
    return (dcg(gains) / idcg) if idcg > 0 else 0.0


def recall_at_k(ranked_ids, qrel, k):
    rel_set = {cid for cid, g in qrel.items() if g > 0}
    if not rel_set:
        return 0.0
    hit = sum(1 for cid in ranked_ids[:k] if cid in rel_set)
    return hit / len(rel_set)


def mrr(ranked_ids, qrel):
    rel_set = {cid for cid, g in qrel.items() if g > 0}
    for i, cid in enumerate(ranked_ids, 1):
        if cid in rel_set:
            return 1.0 / i
    return 0.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="eval/data")
    ap.add_argument("--k", type=int, nargs="+", default=[5, 10])
    ap.add_argument("--topn", type=int, default=50, help="每路召回候选数（hybrid 融合用）")
    ap.add_argument("--fake-embed", action="store_true")
    args = ap.parse_args()

    queries = [json.loads(l) for l in open(os.path.join(args.data, "queries.jsonl"), encoding="utf-8")]
    qrels = json.load(open(os.path.join(args.data, "qrels.json"), encoding="utf-8"))
    embed_fn = fake_embed if args.fake_embed else embed_texts
    maxk = max(args.k)
    routes = ["dense", "sparse", "hybrid"]
    agg = {r: {"mrr": 0.0} for r in routes}
    for r in routes:
        for k in args.k:
            agg[r][("ndcg", k)] = 0.0
            agg[r][("recall", k)] = 0.0

    qvecs = embed_fn([q["text"] for q in queries])
    for q, qv in zip(queries, qvecs):
        qrel = qrels.get(q["id"], {})
        dense = dense_search(qv, args.topn)
        dense_ids = [d[0] for d in dense]
        vecs = {d[0]: d[1] for d in dense}
        sparse_ids = sparse_search(q["text"], args.topn)
        vecs.update(fetch_vecs([s for s in sparse_ids if s not in vecs]))
        fused = rrf_fuse(dense_ids, sparse_ids)
        hybrid_ids = mmr(fused, vecs, maxk)
        results = {"dense": dense_ids, "sparse": sparse_ids, "hybrid": hybrid_ids}
        for r in routes:
            ids = results[r]
            for k in args.k:
                agg[r][("ndcg", k)] += ndcg_at_k(ids, qrel, k)
                agg[r][("recall", k)] += recall_at_k(ids, qrel, k)
            agg[r]["mrr"] += mrr(ids, qrel)

    n = len(queries)
    print("\n=== 检索质量评测 (queries=%d, 数据集=%s%s) ===" %
          (n, EVAL_DOC_ID, ", FAKE向量" if args.fake_embed else ""))
    cols = "  ".join("nDCG@%d" % k for k in args.k) + "   " + \
           "  ".join("Recall@%d" % k for k in args.k) + "   MRR"
    print("%-8s %s" % ("route", cols))
    for r in routes:
        vals = ["%.4f" % (agg[r][("ndcg", k)] / n) for k in args.k]
        vals += ["%.4f" % (agg[r][("recall", k)] / n) for k in args.k]
        vals.append("%.4f" % (agg[r]["mrr"] / n))
        print("%-8s %s" % (r, "   ".join(vals)))


if __name__ == "__main__":
    main()
