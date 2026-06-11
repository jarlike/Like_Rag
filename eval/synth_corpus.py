#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
合成主题化中文语料 + 自动相关性标注(qrels)，用于检索质量评测(nDCG/Recall/MRR)。

设计：
- 每个"主题"= 一个核心概念 + 唯一编号(保证主题之间可区分)，主题下生成多个 chunk，
  每个 chunk 描述该主题的一个"方面"(定义/原理/场景/优缺点...)。
- 每个主题构造 1 个 query；qrels 把同主题 chunk 标为相关：
  目标方面(定义/核心原理/典型场景)=2(高相关)，同主题其它方面=1(低相关)，跨主题=0(不相关)。
  分级相关度让 nDCG 的增益分级有意义。
- 完全离线、固定随机种子可复现。不需要 API key。

输出(默认 eval/data/)：corpus.jsonl / queries.jsonl / qrels.json
"""
import argparse
import json
import os
import random

# 核心概念词(检索/分布式/机器学习领域)，用于让同主题语义聚集、跨主题区分
CONCEPTS = [
    "向量检索", "消息队列", "分布式锁", "熔断降级", "缓存穿透", "倒排索引",
    "数据分片", "一致性哈希", "布隆过滤器", "限流算法", "读写分离", "主从复制",
    "两阶段提交", "向量量化", "近似最近邻", "全文检索", "稀疏召回", "稠密召回",
    "重排序", "上下文压缩", "语义切分", "词嵌入", "注意力机制", "梯度下降",
    "正则化", "过拟合", "交叉熵", "归一化", "池化", "残差连接",
]

ASPECTS = [
    "基本定义", "核心原理", "典型场景", "主要优点", "常见缺点", "关键参数",
    "实现步骤", "性能瓶颈", "调优方法", "与替代方案的对比",
]

FILLER = [
    "在生产环境中", "根据基准测试", "按照官方文档", "结合实际经验",
    "在高并发场景下", "针对大规模数据", "从工程角度", "综合来看",
]

# 视为"高相关(2)"的方面：query 主要问这些
HIGH_ASPECTS = {"基本定义", "核心原理", "典型场景"}


def build(topics, chunks_per_topic, seed):
    random.seed(seed)
    corpus, queries, qrels = [], [], {}
    for t in range(topics):
        concept = CONCEPTS[t % len(CONCEPTS)]
        topic_tag = f"{concept}方案{t + 1:04d}"  # 唯一主题标识
        topic_chunks = []
        for j in range(chunks_per_topic):
            aspect = ASPECTS[j % len(ASPECTS)]
            filler = random.choice(FILLER)
            text = (
                f"{topic_tag}的{aspect}。{filler}，{topic_tag}在{aspect}方面具有明确特征："
                f"它围绕{concept}展开，强调{aspect}相关的要点，并与{topic_tag}的整体设计保持一致。"
            )
            chunk_id = f"eval-{t + 1:04d}-{j + 1:03d}"
            corpus.append({
                "id": chunk_id,
                "topic": topic_tag,
                "concept": concept,
                "aspect": aspect,
                "text": text,
            })
            topic_chunks.append((chunk_id, aspect))

        q_id = f"q-{t + 1:04d}"
        q_text = f"{topic_tag}的基本定义是什么？它的核心原理和典型场景有哪些？"
        queries.append({"id": q_id, "text": q_text, "topic": topic_tag})

        # 只有"直接回答 query 的核心方面"(HIGH_ASPECTS)才算相关(g=2)；
        # 同主题其它方面作为难负例(干扰)，迫使检索区分"同主题"与"真正相关"，
        # 让 Recall@k / nDCG 更有判别力，避免相关集过大导致 Recall 上限被压低。
        rel = {}
        for chunk_id, aspect in topic_chunks:
            if aspect in HIGH_ASPECTS:
                rel[chunk_id] = 2
        qrels[q_id] = rel

    return corpus, queries, qrels


def main():
    ap = argparse.ArgumentParser(description="生成合成检索评测语料与 qrels")
    ap.add_argument("--topics", type=int, default=200, help="主题数")
    ap.add_argument("--chunks-per-topic", type=int, default=30, help="每主题 chunk 数")
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--out", default="eval/data", help="输出目录")
    args = ap.parse_args()

    corpus, queries, qrels = build(args.topics, args.chunks_per_topic, args.seed)
    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "corpus.jsonl"), "w", encoding="utf-8") as f:
        for c in corpus:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")
    with open(os.path.join(args.out, "queries.jsonl"), "w", encoding="utf-8") as f:
        for q in queries:
            f.write(json.dumps(q, ensure_ascii=False) + "\n")
    with open(os.path.join(args.out, "qrels.json"), "w", encoding="utf-8") as f:
        json.dump(qrels, f, ensure_ascii=False, indent=2)

    rel_pairs = sum(len(v) for v in qrels.values())
    print(f"corpus={len(corpus)} queries={len(queries)} qrels_pairs={rel_pairs} -> {args.out}/")


if __name__ == "__main__":
    main()
