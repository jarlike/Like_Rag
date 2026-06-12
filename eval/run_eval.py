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
from common import EMBED_DIM, chat_complete, embed_texts, esc, run_psql, to_tsquery_expr, vec_literal

EVAL_DOC_ID = "eval-synth"
RRF_K = 60
MMR_LAMBDA = 0.7
MMR_DUP = 0.92

# 与 app RerankService.SYSTEM_PROMPT 同义：LLM listwise 相关性打分(0~3)，输出 JSON 数组。
RERANK_SYSTEM = (
    "你是检索结果重排序器。给定一个问题和若干候选片段，判定每个候选与问题的相关性等级：\n"
    "0=无关，1=弱相关，2=相关，3=强相关。\n"
    "只输出一个 JSON 数组，每个元素形如 {\"id\":候选编号,\"score\":相关性等级}，"
    "覆盖所有候选编号，按相关性从高到低排序。不要输出 JSON 以外的任何文字。"
)


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


def fetch_texts(ids, max_chars):
    """取候选片段正文（折叠空白、按 max_chars 截断），供重排序 prompt 用。"""
    if not ids:
        return {}
    idlist = ",".join("'%s'" % esc(i) for i in ids)
    sql = ("SELECT id, left(regexp_replace(text, E'[\\n\\t\\r]+', ' ', 'g'), %d) "
           "FROM rag_chunks WHERE id IN (%s)" % (max_chars, idlist))
    out = {}
    for r in run_psql(sql, fetch=True).splitlines():
        if not r:
            continue
        p = r.split("\t", 1)
        out[p[0]] = p[1] if len(p) > 1 else ""
    return out


def parse_rerank_scores(output, count):
    """复现 app RerankService.parseScores：容忍围栏/多余文字，取首个 JSON 数组，id→[0,3]。"""
    s, e = output.find("["), output.rfind("]")
    if s < 0 or e <= s:
        return {}
    try:
        arr = json.loads(output[s:e + 1])
    except Exception:
        return {}
    if not isinstance(arr, list):
        return {}
    res = {}
    for item in arr:
        if not isinstance(item, dict) or "id" not in item:
            continue
        try:
            i = int(item["id"])
        except (TypeError, ValueError):
            continue
        if i < 1 or i > count:
            continue
        try:
            sc = float(item.get("score", 0))
        except (TypeError, ValueError):
            sc = 0.0
        res.setdefault(i, max(0.0, min(3.0, sc)))
    return res


def llm_rerank(query, items, topk, max_chars):
    """items: [(chunk_id, text)] 按融合顺序。LLM listwise 重排后返回 chunk_id 列表(裁剪到 topk)。
    任何失败/解析不出 → 退回原顺序，与 app 的优雅降级一致。"""
    if not items:
        return []
    lines = ["问题：%s" % query, "", "候选片段："]
    for i, (_, text) in enumerate(items, 1):
        lines.append("[%d] %s" % (i, (text or "")[:max_chars]))
        lines.append("")
    try:
        out = chat_complete(RERANK_SYSTEM, "\n".join(lines), max_tokens=512)
    except SystemExit:
        raise
    except Exception:
        return [cid for cid, _ in items][:topk]
    scores = parse_rerank_scores(out, len(items))
    if not scores:
        return [cid for cid, _ in items][:topk]
    # 稳定降序：分数相等保持原相对顺序（Python sort 稳定）。
    order = sorted(range(len(items)), key=lambda idx: scores.get(idx + 1, -1.0), reverse=True)
    return [items[idx][0] for idx in order][:topk]


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
    ap.add_argument("--rerank", action="store_true",
                    help="额外评测 hybrid+rerank（每条 query 调用一次 LLM，会产生费用，需 OPENAI_API_KEY）")
    ap.add_argument("--rerank-candidates", type=int, default=20, help="送入重排序的候选池大小")
    ap.add_argument("--rerank-max-chars", type=int, default=500, help="每个候选进入重排序 prompt 的最大字符数")
    args = ap.parse_args()

    queries = [json.loads(l) for l in open(os.path.join(args.data, "queries.jsonl"), encoding="utf-8")]
    qrels = json.load(open(os.path.join(args.data, "qrels.json"), encoding="utf-8"))
    embed_fn = fake_embed if args.fake_embed else embed_texts
    maxk = max(args.k)
    routes = ["dense", "sparse", "hybrid"] + (["hybrid+rerank"] if args.rerank else [])
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
        if args.rerank:
            pool = mmr(fused, vecs, max(args.rerank_candidates, maxk))
            texts = fetch_texts(pool, args.rerank_max_chars)
            items = [(cid, texts.get(cid, "")) for cid in pool]
            results["hybrid+rerank"] = llm_rerank(q["text"], items, maxk, args.rerank_max_chars)
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
    print("%-14s %s" % ("route", cols))
    for r in routes:
        vals = ["%.4f" % (agg[r][("ndcg", k)] / n) for k in args.k]
        vals += ["%.4f" % (agg[r][("recall", k)] / n) for k in args.k]
        vals.append("%.4f" % (agg[r]["mrr"] / n))
        print("%-14s %s" % (r, "   ".join(vals)))


if __name__ == "__main__":
    main()
