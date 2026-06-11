# Like RAG 项目书

## 一、项目概述

Like RAG 是一个面向私域知识库问答的检索增强生成系统。系统支持用户上传文档，自动完成文档解析、结构化切片、向量化存储、混合检索、上下文压缩和大模型问答生成，最终返回带引用来源的答案。

项目目标不是简单地把全文塞给大模型，而是通过高质量检索和上下文治理，让模型在有限上下文窗口内获得最相关、最去重、最可引用的知识片段，从而提升回答准确率、稳定性和可解释性。

## 二、建设目标

1. 支持文档上传、解析、切片、索引和重新索引。
2. 使用 PostgreSQL 作为统一知识库底座，结合 pgvector 实现密集向量检索。
3. 引入混合检索 Hybrid Search，同时覆盖关键词精确匹配和语义相似匹配。
4. 引入 RRF 融合排序和 MMR 语义去重，提升召回质量并减少重复上下文。
5. 引入 ContextCompressor，在上下文过长时进行句子级压缩和预算控制。
6. 接入 GPT-5.5 兼容接口，根据压缩后的上下文生成可引用答案。

## 三、整体架构

系统采用“文档入库 + 混合召回 + 融合去重 + 上下文压缩 + 大模型生成”的流程。

```text
文档上传
  ↓
文档解析与结构化切片
  ↓
写入 PostgreSQL / pgvector
  ↓
Hybrid Search
  ├─ Sparse Retrieval Top50
  └─ Dense Retrieval Top50
  ↓
RRF 融合排序
  ↓
MMR 语义去重与多样性选择
  ↓
Top10 候选上下文
  ↓
ContextCompressor 控制 6000 tokens 预算
  ↓
GPT-5.5 生成答案与引用
```

## 四、核心技术方案

### 1. 文档解析与切片

系统上传文档后，先解析原始文件内容，再按标题、段落、句子和 token 预算进行切片。每个切片保留以下元数据：

- `documentId`：文档 ID。
- `documentName`：文档名称。
- `chunkIndex`：切片序号。
- `sectionPath`：章节路径。
- `tokenCount`：估算 token 数。
- `text`：切片正文。
- `embedding`：切片向量。

切片阶段需要避免过大 chunk 和过碎 chunk。过大 chunk 会导致上下文污染，过碎 chunk 会破坏语义完整性。默认每个 chunk 控制在约 420 tokens，并保留适度 overlap。

### 2. 稀疏检索 Sparse Retrieval

稀疏检索用于处理关键词、术语、缩写、编号、ID、接口名、类名等精确匹配场景。

本项目优先使用 PostgreSQL 原生能力实现：

- `tsvector`：存储可检索文本向量。
- `GIN index`：加速全文检索。
- `websearch_to_tsquery` / `plainto_tsquery`：构造查询表达式。
- `ts_rank_cd`：计算关键词相关性分数。

为了兼顾中文场景，系统可额外维护 `keyword_text` 或 `search_text` 字段，将中文单字、双字词、英文 token、数字、ID、文件名和章节路径统一展开后写入全文检索字段，避免 PostgreSQL 默认分词对中文支持不足的问题。

稀疏召回策略：

```text
输入 query
  ↓
构造 tsquery
  ↓
使用 GIN 索引召回 Top50
  ↓
返回 sparseRank / sparseScore
```

### 3. 密集检索 Dense Retrieval

密集检索用于处理语义相似、口语化、长尾表达和非精确匹配问题。

系统使用 PostgreSQL + pgvector：

- 向量字段：`embedding vector(384)`。
- 索引类型：HNSW。
- 距离函数：`embedding <=> query_embedding`。
- 相似度分数：`1 - distance`。

密集召回策略：

```text
输入 query
  ↓
生成 query embedding
  ↓
使用 pgvector HNSW 召回 Top50
  ↓
返回 denseRank / denseScore
```

HNSW 只负责加速向量近邻搜索，不等同于混合检索，也不负责关键词召回。

### 4. 混合检索 Hybrid Search

Hybrid Search 同时执行稀疏检索和密集检索：

```text
Sparse Retrieval Top50
Dense Retrieval Top50
          ↓
      候选集合并
          ↓
      去重并记录双路排名
```

两路召回的优势互补：

- Sparse 对专有名词、缩写、ID、代码符号和精确短语敏感。
- Dense 对语义相似、自然语言问题和模糊表达更强。

### 5. RRF 融合排序

由于 BM25 / Full Text Search 分数和向量相似度分数不在同一尺度，不能直接简单相加。系统采用 RRF（Reciprocal Rank Fusion，倒数排名融合）进行排序融合。

RRF 公式：

```text
rrf_score(d) = Σ 1 / (k + rank_i(d))
```

其中：

- `d` 是候选切片。
- `rank_i(d)` 是该切片在第 `i` 路召回中的排名。
- `k` 是平滑参数，默认取 60。

融合逻辑：

```text
如果 chunk 同时出现在 sparse 和 dense：
  rrf = 1 / (60 + sparseRank) + 1 / (60 + denseRank)

如果 chunk 只出现在 sparse：
  rrf = 1 / (60 + sparseRank)

如果 chunk 只出现在 dense：
  rrf = 1 / (60 + denseRank)
```

RRF 的优势：

- 不依赖不同检索器的原始分数尺度。
- 能保留某一路排名特别靠前的结果。
- 对混合检索结果更稳定。
- 工程实现简单，适合 PostgreSQL + 应用层融合。

### 6. MMR 语义去重与多样性控制

用户提到的“mrr 去重”在工程实现中应使用 MMR（Maximal Marginal Relevance，最大边际相关性）。MRR 通常是检索效果评估指标，而 MMR 用于结果选择、近重复去重和多样性控制。

MMR 目标是在“相关性”和“多样性”之间取平衡：

```text
MMR(d) = λ * relevance(d, query) - (1 - λ) * max(similarity(d, selected))
```

其中：

- `relevance(d, query)` 使用 RRF 分数或融合后的归一化分数。
- `similarity(d, selected)` 使用 chunk embedding 的余弦相似度。
- `λ` 默认取 0.7，表示更重视相关性，同时抑制重复内容。

处理策略：

1. 先按 RRF 得到候选排序。
2. 从高到低选择候选。
3. 如果候选与已选上下文语义相似度过高，例如 `cosine >= 0.92`，视为近重复，跳过。
4. 在不重复的前提下继续选择，直到得到 Top10。

这样可以避免多个 chunk 语义几乎完全一样，最终占满上下文窗口。

### 7. Top10 上下文选择

系统不直接把 Top50 全部交给大模型，而是在 RRF + MMR 后只保留 Top10 作为候选上下文。

Top10 选择规则：

- 优先选择 RRF 排名靠前的 chunk。
- 跳过语义高度重复的 chunk。
- 保留不同文档、不同章节中的高价值证据。
- 保留引用元数据，确保回答可溯源。

最终进入 ContextCompressor 的数据结构建议包括：

```json
{
  "rank": 1,
  "documentId": "doc-id",
  "documentName": "xxx.md",
  "chunkIndex": 3,
  "sectionPath": "章节路径",
  "rrfScore": 0.0322,
  "denseScore": 0.8123,
  "sparseScore": 0.4211,
  "text": "切片正文"
}
```

### 8. ContextCompressor 上下文压缩

当 Top10 chunk 仍然过长时，系统使用 ContextCompressor 控制上下文总预算。默认上下文预算为 6000 tokens。

压缩目标：

- 不改变事实含义。
- 不丢失引用关系。
- 优先保留和 query 最相关的句子。
- 删除重复、低相关、模板化和噪声内容。
- 保证最终 prompt 不超过模型上下文预算。

压缩流程：

```text
Top10 chunk
  ↓
估算总 token
  ↓
如果 <= 6000 tokens：直接使用
  ↓
如果 > 6000 tokens：
  1. 对每个 chunk 做句子切分
  2. 计算句子与 query 的关键词命中和语义相关性
  3. 保留标题、章节路径和高相关句子
  4. 删除重复句子和低信息密度句子
  5. 按 RRF / MMR 排名分配预算
  6. 超预算时截断低排名 chunk
  ↓
输出压缩后的 Context
```

预算分配建议：

- 总预算：6000 tokens。
- 系统提示和用户问题预留：800 tokens。
- Context 可用预算：约 5200 tokens。
- 单个 chunk 最大预算：800 tokens。
- Top3 chunk 优先保留更多内容。
- 低排名 chunk 只保留最相关句子和必要元数据。

压缩后的上下文格式：

```text
[1] document=xxx.md | section=xxx | chunk=3 | rrf=0.0322
压缩后的关键内容...

[2] document=yyy.md | section=yyy | chunk=8 | rrf=0.0281
压缩后的关键内容...
```

### 9. 大模型问答生成

最终只把压缩后的上下文交给 GPT-5.5。模型必须遵守以下约束：

- 只能基于 Context 回答。
- 不使用外部知识自由发挥。
- 每个关键结论必须带引用编号。
- 如果 Context 不足以回答，要明确说明无法从现有知识库得出答案。
- 回答语言与用户问题保持一致。

生成链路：

```text
用户问题
  ↓
Hybrid Search + RRF + MMR
  ↓
ContextCompressor
  ↓
GPT-5.5
  ↓
答案 + 引用 + 召回片段
```

## 五、数据库设计补充

现有 `rag_chunks` 表保留向量字段，同时增加稀疏检索字段。

建议字段：

```sql
ALTER TABLE rag_chunks
ADD COLUMN IF NOT EXISTS search_text TEXT;

ALTER TABLE rag_chunks
ADD COLUMN IF NOT EXISTS search_vector TSVECTOR;
```

建议索引：

```sql
CREATE INDEX IF NOT EXISTS idx_rag_chunks_embedding_hnsw
ON rag_chunks USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 64);

CREATE INDEX IF NOT EXISTS idx_rag_chunks_search_vector_gin
ON rag_chunks USING gin(search_vector);
```

如果需要增强缩写、ID、类名、接口名和模糊关键词匹配，可以增加 `pg_trgm`：

```sql
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IF NOT EXISTS idx_rag_chunks_search_text_trgm
ON rag_chunks USING gin(search_text gin_trgm_ops);
```

## 六、模块设计

建议新增或调整以下模块：

- `HybridSearchService`：统一执行稀疏检索和密集检索。
- `SparseSearchRepository` 或 `ChunkRepository.searchSparse`：负责 PostgreSQL 全文检索召回。
- `DenseSearchRepository` 或 `ChunkRepository.searchDense`：负责 pgvector 召回。
- `RrfFusionService`：负责 RRF 融合排序。
- `MmrDedupService`：负责语义近重复去重和多样性选择。
- `ContextCompressor`：负责上下文预算控制和句子级压缩。
- `AnswerService`：接收压缩后的上下文，调用 GPT-5.5 生成答案。

## 七、关键参数

| 参数 | 建议值 | 说明 |
| --- | --- | --- |
| sparseTopK | 50 | 稀疏检索召回数量 |
| denseTopK | 50 | 密集检索召回数量 |
| finalTopK | 10 | RRF + MMR 后进入上下文压缩的数量 |
| rrfK | 60 | RRF 平滑参数 |
| mmrLambda | 0.7 | 相关性与多样性的平衡 |
| duplicateThreshold | 0.92 | 语义近重复阈值 |
| contextTokenBudget | 6000 | 最终上下文总预算 |
| maxChunkContextTokens | 800 | 单个 chunk 最大保留预算 |

## 八、预期效果

引入 Hybrid Search、RRF、MMR 和 ContextCompressor 后，系统预期获得以下提升：

1. 对关键词、ID、术语和缩写类问题的召回更准确。
2. 对语义模糊、自然语言问法的召回更稳定。
3. 减少多个相似 chunk 重复占用上下文。
4. 控制最终 prompt 长度，降低超上下文和无关内容干扰。
5. 提高 GPT-5.5 回答的事实一致性和引用质量。
6. 保持 PostgreSQL 单技术栈，降低系统复杂度和部署成本。

## 九、实施路线

第一阶段：混合检索基础能力

- 为 `rag_chunks` 增加 `search_text` 和 `search_vector`。
- 索引阶段写入稀疏检索字段。
- 实现 sparse Top50 和 dense Top50 双路召回。

第二阶段：融合排序与去重

- 实现 RRF 融合排序。
- 实现 MMR 语义近重复去重。
- 输出最终 Top10 候选上下文。

第三阶段：上下文压缩

- 实现 ContextCompressor。
- 控制总上下文预算为 6000 tokens。
- 支持句子级裁剪、低相关过滤和重复句删除。

第四阶段：问答生成与调试

- 将压缩后的上下文接入 GPT-5.5。
- 前端展示 hybrid 分数、RRF 分数、来源和压缩后引用。
- 增加检索调试接口，便于观察 sparse / dense / RRF / MMR 的中间结果。

## 十、风险与应对

1. PostgreSQL 默认中文分词能力有限。
   - 应对：维护 `search_text` 扩展字段，使用中文单字、双字词、英文 token 和 ID 展开。

2. sparse 和 dense 分数尺度不同。
   - 应对：使用 RRF 按排名融合，而不是直接分数相加。

3. Top10 仍可能包含重复内容。
   - 应对：使用 MMR 和向量相似度阈值过滤近重复 chunk。

4. 上下文过长影响回答质量。
   - 应对：使用 ContextCompressor 进行预算控制和句子级压缩。

5. LLM 可能脱离上下文发挥。
   - 应对：在系统提示中要求仅基于 Context 回答，并强制引用来源编号。

