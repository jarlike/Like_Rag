#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""读 corpus.jsonl → embedding(sub2api 或 --fake-embed) → 入库 rag_chunks（docker exec psql）。

入库内容与 app 索引一致：embedding 向量、search_text(tokenize 展开)、search_vector(to_tsvector)。
所有数据挂在 document_id='eval-synth' 下，便于 --reset 清理。
"""
import argparse
import hashlib
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import (EMBED_DIM, build_search_text, embed_texts, esc, run_psql, vec_literal)

EVAL_DOC_ID = "eval-synth"


def fake_embed(texts):
    """确定性伪向量（基于 token hash），仅用于验证入库链路，无语义。"""
    out = []
    for t in texts:
        v = [0.0] * EMBED_DIM
        for tok in t.split():
            h = int(hashlib.md5(tok.encode("utf-8")).hexdigest(), 16)
            v[h % EMBED_DIM] += 1.0 if (h & 1) else -1.0
        norm = math.sqrt(sum(x * x for x in v)) or 1.0
        out.append([x / norm for x in v])
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="eval/data")
    ap.add_argument("--embed-batch", type=int, default=64)
    ap.add_argument("--insert-batch", type=int, default=200)
    ap.add_argument("--fake-embed", action="store_true", help="用伪向量验证入库（不调 sub2api）")
    ap.add_argument("--reset", action="store_true", help="先删除已有 eval-synth 数据")
    args = ap.parse_args()

    corpus = [json.loads(l) for l in open(os.path.join(args.data, "corpus.jsonl"), encoding="utf-8")]
    print("读入 corpus=%d" % len(corpus))

    if args.reset:
        run_psql("DELETE FROM rag_chunks WHERE document_id='%s'; "
                 "DELETE FROM rag_documents WHERE id='%s';" % (EVAL_DOC_ID, EVAL_DOC_ID))
        print("已清理旧 eval-synth 数据")

    run_psql(
        "INSERT INTO rag_documents (id,file_name,content_type,size,storage_path,status,"
        "chunk_count,created_at,updated_at,indexed_at) VALUES "
        "('%s','eval-synth','text/plain',0,'eval','INDEXED',%d,now(),now(),now()) "
        "ON CONFLICT (id) DO UPDATE SET chunk_count=EXCLUDED.chunk_count, updated_at=now();"
        % (EVAL_DOC_ID, len(corpus)))

    embed_fn = fake_embed if args.fake_embed else embed_texts
    vectors = {}
    for i in range(0, len(corpus), args.embed_batch):
        batch = corpus[i:i + args.embed_batch]
        embs = embed_fn([c["text"] for c in batch])
        for c, e in zip(batch, embs):
            if len(e) != EMBED_DIM:
                raise SystemExit("维度不符: got %d expect %d" % (len(e), EMBED_DIM))
            vectors[c["id"]] = e
        print("embedding %d/%d" % (min(i + args.embed_batch, len(corpus)), len(corpus)))

    total = 0
    for i in range(0, len(corpus), args.insert_batch):
        batch = corpus[i:i + args.insert_batch]
        rows = []
        for idx, c in enumerate(batch):
            st = build_search_text(c["text"], c.get("topic", ""))
            rows.append(
                "('%s','%s','%s',%d,'%s','%s'::vector,'%s',0,'{}'::jsonb,now(),'%s',to_tsvector('simple','%s'))"
                % (esc(c["id"]), EVAL_DOC_ID, EVAL_DOC_ID, i + idx, esc(c["text"]),
                   vec_literal(vectors[c["id"]]), esc(c.get("topic", "")), esc(st), esc(st)))
        sql = ("INSERT INTO rag_chunks (id,document_id,document_name,chunk_index,text,embedding,"
               "section_path,token_count,metadata,created_at,search_text,search_vector) VALUES "
               + ",".join(rows)
               + " ON CONFLICT (id) DO UPDATE SET embedding=EXCLUDED.embedding, text=EXCLUDED.text, "
                 "search_text=EXCLUDED.search_text, search_vector=EXCLUDED.search_vector;")
        run_psql(sql)
        total += len(batch)
        print("入库 %d/%d" % (total, len(corpus)))

    print("完成：%d chunk 写入 rag_chunks (document_id=%s)" % (total, EVAL_DOC_ID))


if __name__ == "__main__":
    main()
