# Like RAG 重排序与多轮对话项目书

## 一、项目背景

Like RAG 已完成核心检索问答链路（解析切片 → pgvector 存储 → Hybrid Search → RRF 融合 → MMR 去重 → ContextCompressor → GPT-5.5 生成），并已落地检索质量评测（`eval/` 的 nDCG@K / Recall@K / MRR）与 Sentinel 限流熔断保护。

本项目书作为下一阶段「质量与产品形态」专项，聚焦两项已落地能力：

1. **语义重排序（Reranker）**：在 Hybrid(RRF+MMR) 之后、上下文压缩之前插入一段 LLM listwise 相关性重排，直接提升 nDCG/MRR 等核心检索质量指标。
2. **多轮对话与会话记忆（带硬限制）**：让系统从单轮"搜索问答"升级为可衔接上下文的多轮助手，并通过明确的限制条件防止内存与上下文膨胀。

设计贯穿项目既有原则：**始终可运行 + 优雅降级**——任何新增能力在"用不了"时都退回原有行为，绝不阻断问答主链路。

## 二、能力一：语义重排序（Reranker）

### 1. 在链路中的位置

原链路在 `HybridSearchService` 内 RRF + MMR 后直接裁剪到 `finalTopK`。重排序的价值在于"看到更多候选再择优"，因此调整为：

```text
Hybrid Search (sparse Top50 + dense Top50)
  ↓
RRF 融合 → MMR 去重 → 候选池 Top-candidateK(默认 20)
  ↓
Reranker：LLM 对候选 listwise 相关性打分(0~3) → 重排 → 裁剪到 finalTopK(默认 10)
  ↓
ContextCompressor → GPT-5.5
```

`AnswerService` 在重排序开启时按 `candidateK = max(finalK, rerank.candidateK)` 从 Hybrid 多取候选，交 `RerankService` 重排后裁剪到 `finalK`；关闭时直接取 `finalK`，等价旧行为。`/api/search/hybrid` 调试接口保持纯检索语义不变，便于单独观察检索层。

### 2. 设计：LLM listwise 打分 + 优雅降级

采用 **LLM listwise 相关性打分**而非引入额外的交叉编码模型依赖，复用既有 `OpenAiClientService` 与 sub2api 兼容接口，契合"force-GPT、单技术栈"的现状：

- 输入：问题 + 候选片段列表（每个候选截断到 `maxCandidateChars` 控制成本）。
- 模型输出：严格 JSON 数组 `[{"id":候选编号,"score":相关性等级}]`，等级 0~3（0 无关 / 1 弱相关 / 2 相关 / 3 强相关）。
- 解析：容忍 ```json 围栏与多余文字，只取首个 `[`…`]`；等级裁剪到 [0,3]，越界 id 丢弃。
- 重排：按等级降序**稳定排序**（等级相同保持原相对顺序），归一化等级（level/3）写入 `SearchHit.score`，缺失候选记 0 分排到末尾，最后裁剪到 `finalK`。

**降级（best-effort）**：以下任一情况都原样退回输入排序（裁剪到 finalK），并记录降级日志：
- `rag.rerank.enabled=false`、候选 ≤ 1、问题为空、未配置 API Key；
- 被 Sentinel `service.rerank` 并发隔离拦截（`callOrElse` 直接回退）；
- LLM 调用失败、输出解析不出（`RerankService` 内 try/catch 兜底）。

### 3. 关键参数

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `rag.rerank.enabled` | true | 总开关，关闭即回退旧行为 |
| `rag.rerank.candidate-k` | 20 | 送入重排序的候选池大小 |
| `rag.rerank.max-candidate-chars` | 500 | 单候选进入 prompt 的最大字符数（控成本） |
| `rag.rerank.max-output-tokens` | 512 | 重排序 LLM 最大输出 token |
| `rag.sentinel.rerank-max-concurrency` | 3 | `service.rerank` 并发隔离阈值 |

### 4. Sentinel 保护

新增资源 `service.rerank`，在 `SentinelConfig` 以线程数并发隔离规则加载（阈值 `rerankMaxConcurrency`）。重排序为 best-effort，无需独立熔断规则——并发超限或调用异常都走降级回原排序，不影响主链路。

### 5. 离线评测与预期增益

`eval/run_eval.py` 增加 `--rerank` 开关，在 Python 端**复现 app `RerankService` 的同一 0~3 listwise prompt 与解析/降级逻辑**，对 hybrid 候选池重排后输出新增一路 `hybrid+rerank` 指标：

```bash
python3 eval/run_eval.py --k 5 10 --rerank   # 每条 query 调用一次 LLM，会产生费用
```

预期 `hybrid+rerank` 的 nDCG@10 / MRR 相对 `hybrid` 有正向提升；增益大小随评测集难度变化，需以实际跑分为准（合成语料偏简单时提升幅度有限）。

## 三、能力二：多轮对话与会话记忆

### 1. 目标

支持以 `sessionId` 维系的多轮对话：对依赖上文的追问（指代、省略主语）做改写以提升召回，并在生成时注入历史帮助模型理解追问。`sessionId` 为空时完全退化为独立单轮（与旧行为一致）。

### 2. 设计：会话记忆 + 追问改写

- **`ConversationMemoryService`（会话记忆）**：进程内存储（`ConcurrentHashMap`），**不落库**，重启即清空。按 TTL 与容量上限自动淘汰。提供 `recentTurns / historyText / record / clear`。
- **`QueryRewriteService`（追问改写）**：有历史时调用 LLM 把追问改写成"可独立检索的完整问题"（指代消解、补全主语）；改写结果做清洗与防御（去围栏/引号、取首行、异常膨胀则丢弃）。**改写仅用于检索**，最终答案仍针对用户原始问题生成。
- **生成注入历史**：`OpenAiClientService.generateAnswer(question, hits, conversationContext)` 把历史作为"仅供理解追问、不作为作答证据"的参考块注入，证据仍只来自 Context。

流程：

```text
用户问题 (+sessionId)
  ↓
取会话历史 → 追问改写(指代消解)  ──(无历史/关闭/失败)──→ 原问题
  ↓ searchQuery
Hybrid → RRF → MMR → Reranker → ContextCompressor
  ↓
GPT-5.5 生成(注入历史，仅供理解追问) → 答案 + 引用
  ↓
写入会话记忆(仅真实回答；受 maxTurns / TTL / maxSessions 限制)
```

### 3. 硬限制条件（重点）

所有限制项均可配置，目的是防止内存无界增长与 prompt 膨胀：

| 配置项 | 默认值 | 限制作用 |
| --- | --- | --- |
| `rag.conversation.enabled` | true | 总开关，关闭则忽略 sessionId、按单轮处理 |
| `rag.conversation.rewrite-enabled` | true | 追问改写开关，关闭则始终用原问题检索 |
| `rag.conversation.max-turns` | 6 | 单会话保留的最大历史轮数（超出丢弃最旧轮） |
| `rag.conversation.max-history-chars` | 4000 | 拼入 prompt 的历史文本字符上限（保留最近轮） |
| `rag.conversation.max-stored-answer-chars` | 1000 | 单条问答写入记忆前的截断长度 |
| `rag.conversation.session-ttl-seconds` | 1800 | 会话空闲过期时间，过期会话下次访问被清除 |
| `rag.conversation.max-sessions` | 1000 | 进程内最大会话数，超出淘汰最久未访问者 |

补充约束：仅当本轮拿到**真实 GPT 回答**（非限流/熔断降级文案）时才写入记忆，避免"服务繁忙"降级文案污染后续改写；问答入口仍受 Sentinel `api.chat` 的 QPS 限流约束。

### 4. API 变更

- `POST /api/chat`：请求体 `ChatRequest` 新增可选 `sessionId`；响应 `ChatResponse` 新增 `sessionId`（回显）与 `rewrittenQuestion`（发生改写时返回，便于排查）。
- `DELETE /api/chat/session/{sessionId}`：清空指定会话记忆（客户端"清空对话"），幂等。

### 5. 降级策略

| 场景 | 行为 |
| --- | --- |
| 会话关闭 / sessionId 为空 | 独立单轮，无历史无改写无记忆 |
| 无历史（首轮） | 直接用原问题检索 |
| 改写关闭 / 调用失败 | 退回原问题检索 |
| LLM 限流熔断 | 返回 TopK 证据降级文案，且**不写入记忆** |

## 四、模块与文件清单

新增：

- `service/RerankService.java` — LLM listwise 重排 + 解析/降级。
- `service/ConversationMemoryService.java` — 会话记忆与硬限制（TTL、容量、轮数、截断）。
- `service/QueryRewriteService.java` — 历史感知的追问改写。
- 测试：`RerankServiceTest`（解析/重排逻辑）、`ConversationMemoryServiceTest`（各项限制）。

调整：

- `config/RagProperties.java` — 新增 `Rerank`、`Conversation` 配置块；`Sentinel` 新增 `rerankMaxConcurrency`。
- `service/OpenAiClientService.java` — 复用的 `complete(...)`；`generateAnswer(...)` 增加注入历史的重载。
- `service/AnswerService.java` — 串联 历史 → 改写 → 检索 → 重排 → 压缩 → 生成 → 记忆。
- `sentinel/SentinelResources.java` + `SentinelConfig.java` — 新增 `service.rerank` 资源与并发规则。
- `controller/ChatController.java` + `model/ChatRequest.java` + `model/ChatResponse.java` — 多轮入参/回显与会话重置接口。
- `eval/run_eval.py` + `eval/common.py` + `eval/README.md` — `--rerank` 离线增益评测。

## 五、配置一览

见 `application.yml` 的 `rag.rerank.*`、`rag.conversation.*`、`rag.sentinel.rerank-max-concurrency`。第二、三章参数表已逐项说明，默认值即开箱可用。

## 六、验收标准

| 类别 | 验收项 | 标准 |
| --- | --- | --- |
| 重排序 | 链路接入 | 开启后问答走"候选池→重排→裁剪"，关闭等价旧行为 |
| 重排序 | 优雅降级 | 禁用/未配置 Key/限流/解析失败均退回原排序，不报错 |
| 重排序 | 指标可量化 | `run_eval.py --rerank` 能输出 `hybrid+rerank` 一路指标 |
| 多轮 | 上下文衔接 | 复用 sessionId 时追问能被正确改写并检索 |
| 多轮 | 硬限制生效 | 轮数、会话数、历史/回答字符、TTL 均按配置裁剪/淘汰 |
| 多轮 | 降级正确 | 无历史/改写失败回退原问题；降级回答不入记忆 |
| 多轮 | 会话重置 | `DELETE /api/chat/session/{id}` 清空记忆且幂等 |

## 七、风险与应对

1. 重排序增加一次 LLM 调用，提升问答延迟与成本。
   - 应对：仅重排候选池（默认 20）、候选文本截断、`service.rerank` 并发隔离；可按场景关闭。
2. LLM 重排输出不规范导致解析失败。
   - 应对：强约束 JSON 输出 + 容错解析 + 失败退回原排序。
3. 会话记忆内存膨胀。
   - 应对：maxTurns / maxSessions / maxHistoryChars / maxStoredAnswerChars / TTL 多重硬限制 + LRU 淘汰。
4. 追问改写"过度改写"污染检索。
   - 应对：改写仅用于检索、结果清洗与异常膨胀回退、可整体关闭；答案仍针对原始问题。
5. 合成评测集偏简单，重排增益不明显。
   - 应对：后续引入 query 改写/同义/噪声样本提高难度（见后续路线）。

## 八、后续路线（接总体演进）

本项目书完成后，下一阶段建议按 ROI 推进：**评测门禁入 Java/CI**（让 `rag.eval.*` 阈值真正拦截回归）→ **可观测性**（检索分段延迟、LLM 成本、Sentinel 与重排降级事件指标化）→ **鉴权与多租户** → 补完 **Agent（Plan-and-Solve + ReAct）**（其依赖本项目交付的多轮上下文与执行可观测设施）。
