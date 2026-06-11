# RAG MVP Design

## 模块

- `DocumentService`：上传文档、保存原文、触发索引任务、重新索引
- `IndexTaskPublisher`：发送 RocketMQ 索引消息，失败时按配置回退本地异步
- `IndexTaskConsumer`：消费 RocketMQ 消息并执行索引
- `IndexingService`：解析文档、标题/段落感知切片、token 上限收口、OpenAI embedding、写入 pgvector
- `VectorSearchService`：基于 pgvector HNSW 检索，并做轻量重排
- `AnswerService`：优先调用 GPT-5.5 生成答案，失败时退回抽取式回答
- `OperationLogService`：记录上传、索引、检索和问答日志

## 数据

- `rag_documents`：文档元数据
- `rag_chunks`：切片、向量、章节路径、token 数和元数据
- 原始文件保存在 `data/rag/uploads/`

向量检索使用 `pgvector` 的 HNSW 索引。

## OpenAI

- embedding：`text-embedding-3-small`
- 问答：`gpt-5.5`
- 接口：Responses API + Embeddings API

如果没有配置 `OPENAI_API_KEY`，应用会回退到本地哈希 embedding 和抽取式回答。

## RocketMQ

默认本地异步索引，真 RocketMQ 配置放在 `application-rocketmq.yml`。

如果 `convertAndSend` 失败，且 `rag.async-index-fallback=true`，系统会用 Spring `@Async` 在本地执行索引。
