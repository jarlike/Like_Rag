# AI 评测 / Sentinel 限流 / Agent 实施说明

本文件记录 `docs/ai-evaluation-sentinel-agent-proposal.md` 三项能力（检索评测、Sentinel 限流熔断、Plan-and-Solve + ReAct Agent）的落地实现，供验收与后续维护对照。

> 构建/测试需 JDK 17：`JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 mvn -q test`（本机默认 `java` 为 JRE21 无 javac，`/usr/bin/javac` 为 JDK8，直接 `mvn` 会报 `release version 17 not supported`）。

## 一、检索评测（项目书第四章）

| 模块 | 位置 |
| --- | --- |
| 指标实现 | `cn.like.rag.eval.RetrievalMetrics`（nDCG@K 指数增益、Recall@K、MRR，纯函数） |
| 评测服务 | `cn.like.rag.eval.RetrievalEvalService`（dense/sparse/hybrid 三路对比 + hybrid 门禁） |
| 入口 | `POST /api/eval/run`（`EvalController`） |
| 配置 | `rag.eval.*`（ks、top-n、ndcg-gate、recall-gate、mrr-gate） |
| 单测 | `RetrievalMetricsTest` |

- 三路来源复用线上检索：dense=`VectorSearchService.search`，sparse=`VectorSearchService.searchSparse`，hybrid=`HybridSearchService.search`（RRF+MMR）。
- `relevanceField` 默认 `document`（按 documentId 标注，无需知道 chunk UUID，最易构建评测集）；可选 `chunk`。
- 门禁取 K=10（若提供）对 hybrid 判定，阈值来自配置；输出 `gate.passed` 与说明。
- 既有的 Python 评测链路（`eval/synth_corpus.py`、`build_index.py`、`run_eval.py`）保持不变，作为合成语料离线回归基线；Java 评测服务面向"线上库内真实数据"的回归与 Agent 工具调用。

请求示例：

```bash
curl -X POST localhost:8091/api/eval/run -H 'Content-Type: application/json' -d '{
  "relevanceField": "document",
  "ks": [5, 10],
  "queries": [
    {"id": "q1", "text": "RocketMQ 如何参与索引？", "qrels": {"<documentId>": 3}}
  ]
}'
```

## 二、Sentinel 限流与熔断（项目书第五章）

| 模块 | 位置 |
| --- | --- |
| 资源名常量 | `cn.like.rag.sentinel.SentinelResources` |
| 规则加载 | `cn.like.rag.sentinel.SentinelConfig`（`@PostConstruct` 编程式加载，无需控制台） |
| 调用包装器 | `cn.like.rag.sentinel.SentinelGuard`（`SphU.entry` 显式埋点 + 降级 + 异常上报熔断） |
| 限流异常 | `cn.like.rag.sentinel.RateLimitException` → `ApiExceptionHandler` 翻译为 HTTP 429 |
| 配置 | `rag.sentinel.*` |

资源与规则：

| 资源 | 规则 | 触发后 |
| --- | --- | --- |
| `api.document.upload` | QPS=uploadQps | 429 RATE_LIMITED |
| `api.document.reindex` | 并发=reindexConcurrency | 429 RATE_LIMITED |
| `api.chat` | QPS=chatQps | 429 RATE_LIMITED |
| `api.agent` | QPS=chatQps | 429 RATE_LIMITED |
| `service.hybridSearch` | 并发=hybridSearchMaxConcurrency | 429 RATE_LIMITED |
| `service.embedding` | 并发=embeddingMaxConcurrency + 异常比例熔断 | 索引失败/429 |
| `service.llmGenerate` | 并发=llmMaxConcurrency + 慢调用(RT)熔断 + 异常比例熔断 | 见下 |
| `agent.toolLoop` | 并发隔离（Agent 运行） | 429 RATE_LIMITED |

降级而非误报"无答案"（项目书第五章第 4 节、第十章风险 5）：

- **LLM 被限流/熔断**：`AnswerService` 返回 `provider="degraded"` 的回答，正文明确说明"生成服务繁忙/被熔断"并附 TopK 证据片段；**不静默把模型降级为兜底**（与 force-GPT 策略一致——单次失败仍抛错并计入熔断统计，只有真正限流/熔断才给降级回答）。
- **入口限流**：统一 429 + `{code: "RATE_LIMITED", resource, blockType, retryable, suggestion}`，明确区分"被保护性限流"与"知识库无答案"。
- **降级事件**：`SentinelGuard` 命中 BlockException 时写 `SENTINEL_BLOCK` 操作日志；LLM 降级写 `LLM_DEGRADED`。

> 注：本实现用显式 `SphU.entry` 包装而非 `@SentinelResource` AOP，避免对 AspectJ/自调用代理的依赖，契合本项目"始终可运行"的设计，且降级逻辑可直接访问注入的服务。

## 三、Agent：Plan-and-Solve + ReAct（项目书第六章）

| 模块 | 位置 |
| --- | --- |
| 规划器（Plan-and-Solve） | `cn.like.rag.agent.AgentPlanner` |
| 工具循环（ReAct） | `cn.like.rag.agent.AgentToolExecutor` |
| 编排器 | `cn.like.rag.agent.AgentService` |
| 共享预算/证据/截止 | `cn.like.rag.agent.AgentContext` |
| 引用校验 | `cn.like.rag.agent.CitationVerifier` |
| 轨迹记录 | `cn.like.rag.agent.AgentTraceService` |
| 入口 | `POST /api/agent/chat`、`GET /api/agent/traces`、`GET /api/agent/traces/{id}` |
| 配置 | `rag.agent.*` |

执行闭环：`Plan-and-Solve 拆子任务 → 种子检索 → 逐子任务 ReAct 工具循环 → 基于累积证据 GPT-5.5 生成带 [n] 引用答案 → CitationVerifier 校验（不合格做一次修复重写）`。

第一批工具（`cn.like.rag.agent.tool.AgentTool` 实现，Spring 自动收集）：

| 工具 | 作用 |
| --- | --- |
| `hybrid_search` | 混合检索证据（结果计入引用证据池） |
| `document_lookup` | 查看文档列表 / 某文档 chunk 元数据 |
| `operation_log_query` | 查询上传/索引/问答/限流日志 |
| `run_retrieval_eval` | 运行检索评测（三路指标 + 门禁） |
| `citation_check` | 校验答案 [n] 引用是否有效（证据数由执行器注入） |

安全边界（项目书第六章第 4 节，集中在 `AgentContext` + 配置）：

- 单轮最大工具调用次数：`maxToolCalls`（默认 6）。
- 单轮最大执行时间：`maxRunMillis`（默认 30000ms）。
- 子任务最大数：`maxSubTasks`（默认 5）；子任务内最大 ReAct 步数：`maxReactStepsPerSubtask`（默认 4）。
- 工具调用失败转为观察文本继续，不无限重试；触达上限基于已有证据保守作答。
- 最终答案保留引用来源；证据不足时明确说明。
- 不绕过 Sentinel：检索/LLM 仍走受保护的服务方法。

请求示例：

```bash
curl -X POST localhost:8091/api/agent/chat -H 'Content-Type: application/json' \
  -d '{"question": "对比文档里 RRF 和 MMR 的作用，并说明各自参数"}'
```

## 四、验收对照（项目书第九章）

| 类别 | 验收项 | 实现 |
| --- | --- | --- |
| 评测 | 指标输出 | `RetrievalMetrics` + `/api/eval/run` 输出 nDCG@K/Recall@K/MRR |
| 评测 | 三路对比 | `RetrievalEvalService` 输出 dense/sparse/hybrid |
| 评测 | 回归门禁 | `EvalReport.Gate` 按 `rag.eval.*` 阈值判定 hybrid |
| 限流 | API 保护 | upload/chat/agent QPS、reindex 并发规则 |
| 限流 | 降级响应 | 429 RATE_LIMITED / LLM `degraded` 回答区分"无答案" |
| 限流 | 日志记录 | `SENTINEL_BLOCK`、`LLM_DEGRADED`、`SENTINEL_INIT` 操作日志 |
| Agent | 计划能力 | `AgentPlanner` 生成可执行子任务 |
| Agent | 工具调用 | 5 个工具 + ReAct 循环 |
| Agent | 安全边界 | `AgentContext` 限次/限时 + 配置 |
| Agent | 引用质量 | `CitationVerifier` + 一次修复重写 |

## 五、验证状态

- 全量 `mvn -q -o test-compile` 通过（JDK 17）。
- 核心纯逻辑（`RetrievalMetrics` / `CitationVerifier` / `AgentContext`）经独立运行断言全部通过（20 项）。
- `mvn test`（surefire）在当前沙箱内因 surefire provider jar 未缓存且中央仓库连接超时而无法执行；在可联网或已缓存 surefire 的环境可直接 `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 mvn test`。
