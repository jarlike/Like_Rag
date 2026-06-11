# Like RAG

一个可运行的 RAG MVP，包含文档上传、自动解析、标题/段落感知切片、token 上限收口、OpenAI embedding、pgvector 存储、HNSW 检索、基于文档问答、引用来源、重新索引、基础日志和检索调试页。

## 功能

- 上传 `.txt`、`.md`、`.pdf`、`.docx`
- 自动解析、按标题/段落切片，再按 token 上限收口
- OpenAI embeddings 写入 PostgreSQL + pgvector
- HNSW 向量索引检索
- 基于 GPT-5.5 的 Responses API 问答生成
- 返回答案和引用来源
- 支持单文档重新索引
- 基础操作日志和检索调试页

## 启动

```bash
mvn spring-boot:run
```

默认连接本地 PostgreSQL：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:15432/rag
    username: rag
    password: rag123
```

OpenAI 配置：

```yaml
rag:
  openai:
    api-key: ${OPENAI_API_KEY}
    chat-model: gpt-5.5
    embedding-model: text-embedding-3-small
```

如果你已经启动 RocketMQ NameServer 和 Broker，可以使用 `rocketmq` profile：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=rocketmq
```

## 调试页

- `http://localhost:8080/debug`

## 接口

上传文档：

```bash
curl -F "file=@README.md" http://localhost:8080/api/documents
```

问答：

```bash
curl -H "Content-Type: application/json" \
  -d "{\"question\":\"这个系统支持哪些功能？\",\"topK\":5}" \
  http://localhost:8080/api/chat
```

检索：

```bash
curl "http://localhost:8080/api/search?q=RocketMQ&topK=5"
```

重新索引：

```bash
curl -X POST http://localhost:8080/api/documents/{documentId}/reindex
```

日志：

```bash
curl "http://localhost:8080/api/logs?limit=50"
```

## Docker

`docker-compose.yml` 已包含 `pgvector-db`、RocketMQ NameServer、Broker 和 Dashboard。

## 说明

- 没有配置 `OPENAI_API_KEY` 时，embedding 和问答会退回到本地兜底逻辑，方便开发。
- chunk 元数据会保存 `sectionPath` 和 `tokenCount`，便于调试召回。
