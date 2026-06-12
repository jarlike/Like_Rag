# 检索质量评测工具（nDCG@k / Recall@k / MRR）

对 like-rag 的 **dense / sparse / hybrid** 三路检索做质量评测。合成主题化语料 +
自动相关性标注(qrels)，真实 embedding 经 sub2api 入库，纯 Python 评测（不依赖 app 跑、本机无需 JDK）。

## 依赖
- `python3` + `requests`（已具备）
- 运行中的 `pgvector-db` 容器（通过 `docker exec` 访问，无需本地 psql）
- **sub2api 的 API key**（真实 embedding 必需）

## 运行步骤

```bash
# 0) 设置 key（真实 embedding 必需；脚本只从环境读，不写入代码）
export OPENAI_API_KEY=<你的 sub2api key>
# 可选覆盖：OPENAI_BASE_URL(默认 http://localhost:8080/v1)、EMBED_MODEL、EMBED_DIM(默认384)、CHAT_MODEL(默认gpt-5.5)、PG_CONTAINER

# 1) 生成合成语料 + qrels（离线，默认 200 主题 × 30 = 6000 chunk / 200 query）
python3 eval/synth_corpus.py --topics 200 --chunks-per-topic 30

# 2) 真实 embedding 并入库（挂在 document_id='eval-synth'，--reset 先清旧数据）
python3 eval/build_index.py --reset

# 3) 评测三路检索
python3 eval/run_eval.py --k 5 10

# 4) 额外评测 hybrid+rerank（复现 app RerankService：LLM listwise 重排）
#    每条 query 调用一次 LLM（默认 200 条），会产生费用与耗时；指标列多出 hybrid+rerank 一行
python3 eval/run_eval.py --k 5 10 --rerank
```

## 规模与成本
- 质量指标几千~几万 chunk 即可稳定；默认 6000 chunk ≈ 调 sub2api embedding ~100 次（batch 64）。
- 调大：`--topics / --chunks-per-topic`；调大会线性增加 embedding 调用与耗时。
- **不要用真实 embedding 灌 1GB**（≈200 万 chunk，调用量不可行）。要测大数据性能，用
  `python3 eval/build_index.py --fake-embed`（伪向量、离线、快），但此时 dense 指标无语义意义。

## 注意点
- **query 与 chunk 必须同源 embedding**：真实跑时 `run_eval.py` 不要加 `--fake-embed`；
  `--fake-embed` 仅用于管线连通性自测（query 和入库都用同一伪向量算法）。
- 评测全程限定 `document_id='eval-synth'`，**不影响**你库里的真实文档。
- 清理评测数据：`python3 eval/build_index.py --reset`（不灌新数据时手动跑亦可）或
  `docker exec pgvector-db psql -U rag -d rag -c "DELETE FROM rag_chunks WHERE document_id='eval-synth'"`。
- hybrid 在 Python 端复现 app 的 RRF(k=60)+MMR(λ=0.7, 近重复0.92)，与 `HybridSearchService` 逻辑一致。
- `--rerank` 在 Python 端复现 app 的 `RerankService`（同一 0~3 listwise 打分 prompt 与解析/降级逻辑），
  对 hybrid 候选池(默认20)重排后裁剪到 maxk；用于离线量化重排序对 nDCG/MRR 的增益。
- 合成语料的主题标识是强关键词，sparse 往往偏高；要更难的评测可后续加入 query 改写/同义替换/噪声。

## 文件
- `synth_corpus.py` — 合成语料 + 自动 qrels
- `build_index.py` — sub2api embedding + 入库（支持 `--fake-embed` 自测）
- `run_eval.py` — dense/sparse/hybrid 三路 → nDCG@k/Recall@k/MRR（`--rerank` 增评 hybrid+rerank）
- `common.py` — tokenize(复现 app) / embedding / chat / docker-psql / 向量字面量
