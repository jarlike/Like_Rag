# Like RAG 下一阶段能力建设项目书

## 一、项目背景

Like RAG 当前已经具备文档上传、解析切片、pgvector 存储、混合检索、RRF 融合、MMR 去重、上下文压缩和大模型问答生成等核心能力。下一阶段的重点不再只是补充单点功能，而是让系统具备可评测、可保护、可规划、可演进的工程能力。

本项目书作为主项目书 `docs/project-proposal.md` 的下一阶段专项补充，聚焦三项能力：

1. AI 辅助检索评测：加入 nDCG@K、Recall@K、MRR 等指标，让检索质量可量化、可回归。
2. Sentinel 限流与熔断：保护上传、索引、检索、embedding 和 LLM 调用等高成本链路。
3. Agent 下一阶段目标：选择 ReAct + Plan-and-Solve 组合，让系统具备任务拆解、工具调用和自我校验能力。

说明：用户提到的 `planandslove` 在本项目书中按常见 Agent 方法 `Plan-and-Solve` 理解。

## 二、建设目标

1. 建立检索质量评测体系，支持 dense / sparse / hybrid 三路对比。
2. 使用 nDCG@K 衡量 TopK 排序质量，使用 Recall@K 衡量证据覆盖，使用 MRR 衡量首个相关结果位置。
3. 引入 AI 辅助 query 改写、qrels 初标和失败样本诊断，降低评测集建设成本。
4. 接入 Sentinel，对 Web API、检索服务、embedding 调用、LLM 调用和批量索引任务进行限流、熔断、热点保护和降级。
5. 建设 Agent 能力，优先采用 Plan-and-Solve 做任务拆解，再用 ReAct 做工具调用闭环。
6. 将评测指标、限流事件、降级日志和 Agent 执行过程纳入可观测范围。

## 三、总体架构

```text
用户请求
  ↓
Sentinel 入口保护
  ├─ QPS 限流
  ├─ 并发隔离
  ├─ 热点参数限流
  └─ 熔断降级
  ↓
RAG 主流程
  ├─ 文档上传 / 重新索引
  ├─ Hybrid Search
  ├─ RRF + MMR
  ├─ ContextCompressor
  └─ GPT-5.5 问答生成
  ↓
Agent 增强流程
  ├─ Plan-and-Solve 任务拆解
  ├─ ReAct 工具调用
  ├─ 检索 / 重查 / 日志查询 / 评测
  └─ 引用校验与答案生成
  ↓
评测与回归
  ├─ nDCG@K
  ├─ Recall@K
  ├─ MRR
  └─ 失败样本诊断
```

## 四、AI 辅助评测方案

### 1. 评测对象

评测覆盖以下检索链路：

- dense retrieval：基于 pgvector 的语义向量召回。
- sparse retrieval：基于 PostgreSQL 全文检索和扩展关键词字段的稀疏召回。
- hybrid retrieval：dense + sparse 双路召回后使用 RRF 融合，并通过 MMR 去重。

### 2. 评测指标

核心指标如下：

- `nDCG@K`：衡量 TopK 排序质量，适合判断高相关结果是否排在前面。
- `Recall@K`：衡量 TopK 是否覆盖应召回证据，适合发现漏召回问题。
- `MRR`：衡量第一个相关结果出现的位置，适合评估首条命中体验。

nDCG@K 计算方式：

```text
DCG@K = Σ (2^rel_i - 1) / log2(i + 1)
IDCG@K = 理想排序下的 DCG@K
nDCG@K = DCG@K / IDCG@K
```

其中 `rel_i` 是第 `i` 个结果的相关性等级。建议采用 0~3 四级标注：

| 等级 | 含义 | 说明 |
| --- | --- | --- |
| 0 | 无关 | 不能回答问题 |
| 1 | 弱相关 | 只能提供背景信息 |
| 2 | 相关 | 能支撑部分回答 |
| 3 | 强相关 | 能直接支撑关键结论 |

### 3. 数据集建设

评测数据分三类：

- 合成语料：使用脚本生成主题化 corpus、query 和 qrels，用于快速回归。
- 真实样本：从用户高频问题、失败回答、低置信度回答中沉淀人工标注样本。
- AI 辅助样本：由大模型生成 query 改写、同义问法、噪声问法和相关性初标，再由人工抽检修正。

现有仓库已具备第一版评测基础：

- `eval/synth_corpus.py`：生成合成语料和 qrels。
- `eval/build_index.py`：调用 embedding 并写入 pgvector。
- `eval/run_eval.py`：输出 dense / sparse / hybrid 的 nDCG@K、Recall@K、MRR。
- `eval/README.md`：说明评测链路、依赖和运行方式。

### 4. AI 参与方式

AI 不直接替代评测指标，而是降低样本构建和诊断成本：

- 根据真实问题生成同义改写、口语化问法、错别字问法和缩写问法。
- 对候选 chunk 进行相关性初标，输出 0~3 的相关性等级和理由。
- 对低 nDCG@K 样本生成诊断报告，判断问题来自切片、召回、排序、去重还是上下文压缩。
- 对评测结果给出优化建议，例如调整 sparseTopK、denseTopK、RRF 参数、MMR 阈值或切片策略。

### 5. 回归门禁

建议初始门禁：

| 指标 | 初始目标 | 说明 |
| --- | --- | --- |
| hybrid nDCG@10 | >= 0.75 | 核心排序质量门禁 |
| hybrid Recall@10 | >= 0.80 | 核心召回覆盖门禁 |
| hybrid MRR | >= 0.70 | 首条命中体验门禁 |
| hybrid nDCG@10 退化 | <= 3% | 相比上一稳定版本允许的小幅波动 |

门禁阈值需要随评测集难度动态调整。早期合成语料偏简单，不建议把阈值定得过于绝对。

## 五、Sentinel 限流与熔断方案

### 1. 保护目标

RAG 系统中最容易成为瓶颈的资源包括：

- 文档上传和解析。
- 批量 embedding。
- 向量检索和全文检索。
- GPT-5.5 生成。
- 批量重建索引。
- Agent 多轮工具调用。

Sentinel 的目标是让系统在突发流量、热点请求、外部模型服务变慢和批量任务堆积时优雅降级，而不是整体不可用。

### 2. 资源定义

建议定义以下 Sentinel 资源：

| 资源名 | 对应入口 | 保护重点 |
| --- | --- | --- |
| `api.document.upload` | `POST /api/documents` | 上传 QPS、文件大小、用户维度 |
| `api.document.reindex` | `POST /api/documents/{id}/reindex` | 重建索引并发数 |
| `api.chat` | `POST /api/chat` | 问答 QPS、并发线程数 |
| `service.hybridSearch` | `HybridSearchService.search` | 热点 query、大 TopK 查询 |
| `service.embedding` | `EmbeddingService` | embedding 并发、异常比例 |
| `service.llmGenerate` | `OpenAiClientService` | 慢调用、异常比例、熔断 |
| `agent.toolLoop` | Agent 工具调用循环 | 最大调用次数和总耗时 |

### 3. 规则设计

建议初始参数：

| 参数 | 建议值 | 说明 |
| --- | --- | --- |
| uploadQps | 1 | 单实例文档上传 QPS |
| chatQps | 5 | 单实例问答 QPS |
| reindexConcurrency | 1 | 单实例重建索引并发数 |
| embeddingMaxConcurrency | 4 | embedding 最大并发 |
| llmMaxConcurrency | 3 | LLM 生成最大并发 |
| slowCallRt | 5000ms | LLM 慢调用阈值 |
| exceptionRatio | 0.3 | 异常比例熔断阈值 |
| circuitBreakWindow | 30s | 熔断恢复窗口 |

### 4. 降级策略

降级时需要明确告诉调用方发生了什么，避免把限流误认为“知识库没有答案”。

- LLM 不可用：返回 TopK 证据片段，并提示生成服务暂不可用。
- embedding 不可用：新文档进入待索引状态，不影响已索引文档问答。
- dense 检索不可用：短期降级为 sparse 检索，并记录降级日志。
- sparse 检索不可用：短期降级为 dense 检索，并记录降级日志。
- 问答入口限流：返回明确错误码、重试建议和限流原因。
- Agent 工具调用超限：停止继续调用工具，基于已获得证据生成保守回答。

## 六、Agent 下一阶段目标选择

### 1. 选择结论

下一阶段建议 ReAct 和 Plan-and-Solve 都选。两者不是互斥关系，而是分工互补：

- Plan-and-Solve 负责先想清楚：把复杂问题拆成计划、子问题和校验步骤。
- ReAct 负责边想边做：根据当前证据决定是否调用检索、日志、评测等工具。

组合后，Like RAG 可以从单轮“检索 + 生成”升级为多步“计划 + 检索 + 校验 + 生成”。

### 2. Plan-and-Solve 目标

Plan-and-Solve 适合以下场景：

- 长文档分析。
- 多文档对比。
- 复杂问题拆解。
- 检索失败样本诊断。
- 对答案进行引用完整性检查。

示例流程：

```text
用户复杂问题
  ↓
生成计划
  ↓
拆分子问题
  ↓
分别检索证据
  ↓
合并证据并校验
  ↓
生成答案
```

### 3. ReAct 目标

ReAct 适合以下场景：

- 证据不足时自动改写 query 再检索。
- 检索结果冲突时二次检索。
- 用户追问时读取历史上下文和操作日志。
- 评测指标异常时调用评测工具定位问题。
- 对生成答案做引用校验。

建议第一批工具：

| 工具 | 作用 |
| --- | --- |
| `hybrid_search` | 执行混合检索 |
| `document_lookup` | 查看文档和 chunk 元数据 |
| `operation_log_query` | 查询上传、索引、问答日志 |
| `run_retrieval_eval` | 调用评测脚本或评测服务 |
| `citation_check` | 检查答案是否带引用且引用存在 |

### 4. Agent 安全边界

Agent 必须有硬限制：

- 单轮最大工具调用次数：6。
- 单轮最大执行时间：30 秒。
- 单轮最大 token 预算：按模型上下文动态配置。
- 不允许绕过 Sentinel 直接调用高成本资源。
- 工具调用失败时必须降级，不允许无限重试。
- 最终答案必须保留引用来源，证据不足时明确说明。

## 七、模块设计

建议新增或调整以下模块：

- `RetrievalEvalService`：统一运行 dense / sparse / hybrid 评测，输出 nDCG@K、Recall@K、MRR。
- `EvalDatasetBuilder`：构建合成语料、真实样本和 AI 辅助 qrels。
- `EvalDiagnosisService`：对低分样本生成诊断和优化建议。
- `SentinelConfig`：定义资源名、限流规则、熔断规则和热点参数规则。
- `RateLimitFallbackHandler`：统一处理限流、熔断和降级响应。
- `AgentPlanner`：实现 Plan-and-Solve 任务拆解。
- `AgentToolExecutor`：实现 ReAct 工具调用循环。
- `CitationVerifier`：检查答案引用是否存在、是否覆盖关键结论。
- `AgentTraceService`：记录计划、工具调用、证据选择和最终回答。

## 八、实施路线

第一阶段：评测体系落地

- 固化 `eval/` 评测脚本运行方式。
- 输出 dense / sparse / hybrid 的 nDCG@5、nDCG@10、Recall@5、Recall@10、MRR。
- 建立合成语料评测基线。
- 增加 AI 辅助 query 改写和相关性初标流程。

第二阶段：Sentinel 稳定性保护

- 引入 Sentinel 依赖和基础配置。
- 定义上传、问答、重建索引、检索、embedding、LLM 等资源名。
- 配置 QPS、并发、慢调用、异常比例和热点参数规则。
- 增加统一降级响应和操作日志记录。

第三阶段：Plan-and-Solve

- 实现任务计划生成。
- 将复杂问题拆分为子问题。
- 对每个子问题调用现有检索链路。
- 合并证据并做引用校验。

第四阶段：ReAct

- 封装检索、日志、评测、引用校验等工具。
- 实现工具调用循环和停止条件。
- 支持证据不足时自动改写 query 并重试。
- 记录 Agent 执行轨迹，便于调试和复盘。

第五阶段：回归与验收

- 将评测指标纳入版本回归。
- 对 Sentinel 限流和熔断进行压测验证。
- 对 Agent 工具调用次数、耗时和答案引用质量进行专项验收。

## 九、验收标准

| 类别 | 验收项 | 标准 |
| --- | --- | --- |
| 评测 | 指标输出 | 能输出 nDCG@K、Recall@K、MRR |
| 评测 | 三路对比 | 能比较 dense / sparse / hybrid |
| 评测 | 回归门禁 | 能发现 hybrid 指标明显退化 |
| 限流 | API 保护 | 上传、问答、重建索引具有限流规则 |
| 限流 | 降级响应 | 限流、熔断、无答案能明确区分 |
| 限流 | 日志记录 | 降级事件写入操作日志 |
| Agent | 计划能力 | 复杂问题能生成可执行子任务 |
| Agent | 工具调用 | 能调用检索、日志、评测或引用校验工具 |
| Agent | 安全边界 | 工具调用次数、耗时和失败重试受控 |
| Agent | 引用质量 | 关键结论能绑定来源引用 |

## 十、风险与应对

1. 评测集过于简单导致指标虚高。
   - 应对：持续加入真实问题、失败样本、同义改写、噪声 query 和人工审核 qrels。

2. AI 辅助标注可能引入错误相关性。
   - 应对：AI 只做初标，核心样本保留人工抽检，并记录标注来源和置信度。

3. Sentinel 阈值过低影响正常使用。
   - 应对：先使用保守默认值，再根据 QPS、RT、异常率和线程数动态调整。

4. Sentinel 阈值过高无法保护系统。
   - 应对：对 LLM、embedding 和 reindex 这类高成本资源优先设置并发隔离。

5. 限流或熔断被误解为知识库无答案。
   - 应对：统一错误码和响应文案，明确提示服务繁忙、模型不可用或被限流。

6. Agent 工具调用可能进入循环。
   - 应对：设置最大工具调用次数、最大执行时间、最大 token 预算和明确停止条件。

7. Agent 调用成本过高。
   - 应对：先使用 Plan-and-Solve 控制计划范围，再让 ReAct 在有限工具集合内执行。

8. Agent 生成答案仍可能缺少引用。
   - 应对：加入 CitationVerifier，引用不完整时要求重写或返回证据不足。
