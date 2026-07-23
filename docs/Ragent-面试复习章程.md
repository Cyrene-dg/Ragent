# Ragent 面试复习章程

> 依据：`我的简历-5catWF-副本-副本(1).pdf`（2026-07-21 核对）与当前工作区源码。
>
> 目标：不是背技术名词，而是能够在 2 分钟讲清系统、在 10 分钟讲清任一简历成果，并能把追问带回到自己的代码与取舍。

## 1. 先统一口径

### 60 秒项目介绍

Ragent 是一个面向企业内部知识问答和多轮对话的 RAG 系统。用户问题进入后，系统先加载会话记忆并改写/拆分问题，再由意图树将子问题路由到知识库、系统回答或 MCP 工具；知识库场景并行执行意图定向向量、全局向量和 Elasticsearch 关键词召回，随后做去重、Rerank，最后把检索上下文、MCP 结果和历史会话组装为 Prompt，以 SSE 流式返回。文档侧通过可编排的 ETL Pipeline 完成抓取、解析、增强、分块、向量化与 Milvus 入库；全链路保留 Trace，便于排障与调优。

### 画给面试官看的主链路

```text
浏览器
  -> GET /rag/v3/chat (SSE)
  -> RAGChatService
  -> 记忆 -> 改写/拆题 -> 意图识别/澄清
  -> [KB: 多路检索 -> 去重 -> Rerank] + [MCP: 参数提取 -> 工具调用]
  -> Prompt -> LLM 路由/流式客户端 -> SSE think/response/complete
  -> 前端渲染；Trace 记录每个阶段

文档 -> Fetch -> Parse -> Enhance -> Chunk -> Embed/Index -> Milvus (+ ES 回填)
```

### 讲述原则

1. 先讲业务问题和约束，再讲方案，最后给指标、边界和下一步。
2. 只将自己能展示代码、测试集、压测脚本或运行记录的内容称为“已完成并验证”；没有证据的数字改为“在离线评测中观察到，需按同一数据集复现”。
3. 被问到开源基础能力时，说清“我在该工程上完成/改造了什么”，不要暗示整套系统都从零开发。

## 2. 简历逐条 -> 源码定位 -> 必答内容

| 简历条目 | 主代码（先读） | 辅助代码 | 面试官会追问什么 | 你必须答出的取舍 |
|---|---|---|---|---|
| SSE 流式问答、思考/正文分流、任务中断、首包探测与降级 | `bootstrap/.../rag/controller/RAGChatController.java`、`rag/service/impl/RAGChatServiceImpl.java`、`rag/service/handler/StreamChatEventHandler.java` | `StreamTaskManager.java`、`infra-ai/.../chat/FirstPacketAwaiter.java`、`RoutingLLMService.java`、`frontend/src/hooks/useStreamResponse.ts`、`frontend/src/stores/chatStore.ts` | 为什么用 SSE 而非 WebSocket？断开、取消、重复请求、模型无首包时各怎么处理？ | SSE 单向推送、HTTP 兼容好；任务 ID 绑定取消句柄；前端 Abort 不能代替后端取消；首包超时后尝试下一个模型并记录健康状态；完成、错误、取消都必须收尾并持久化。 |
| 结构感知分块：标题、段落、列表、代码块；长度分级与 overlap | `core/chunk/strategy/StructureAwareTextChunker.java` | `ChunkingStrategyFactory.java`、`ChunkingOptions.java`、`ingestion/node/ChunkerNode.java`、`ingestion/node/IndexerNode.java` | 为什么不能固定长度切分？如何防止标题丢失、超长块、重复上下文？ | 先按结构边界，再按 token/长度兜底；overlap 只用于语义连续，不应无限增大；chunkSize/overlap 要由检索评测决定；入库时要保留 chunkId、来源与元数据。 |
| Milvus 意图定向 + 全局向量 + Elasticsearch 关键词的混合检索 | `rag/core/retrieve/MultiChannelRetrievalEngine.java` | `channel/IntentDirectedSearchChannel.java`、`VectorGlobalSearchChannel.java`、`KeywordEsSearchChannel.java`、`retrieve/MilvusRetrieverService.java`、`rag/es/KeywordEsGateway.java` | 三路为何并行？什么时候开全局兜底？ES 与向量分数如何融合？ | 意图定向提高精度；全局向量用于低置信/无意图兜底；关键词补足实体词和精确术语；当前实现先并行召回、再统一后处理，不应谎称做了数值归一化融合，除非补齐代码与评测。 |
| 以 chunk_id 去重、Rerank 二次精排，相关性 18.57% -> 78.10% | `retrieve/postprocessor/DeduplicationPostProcessor.java`、`RerankPostProcessor.java` | `infra-ai/.../rerank/RerankService.java`、`RAGConfigProperties.java` | 为什么要先去重再 Rerank？Rerank 的复杂度、候选数和失败降级？这个提升如何测得？ | 先以稳定 chunk key 降低重复与 Rerank 成本；Rerank 仅处理候选 TopK；服务异常时需返回可用召回结果或 Noop；必须说清数据集、标注、topN、指标定义、对照组和样本量。当前仓库未找到 18.57/78.10 的可复现实验产物。 |
| MCP 工单实时查询：意图识别 -> 参数提取 -> 工具调用，支持状态/优先级筛选 | `retrieve/RetrievalEngine.java` | `mcp/LLMMCPParameterExtractor.java`、`DefaultMCPToolRegistry.java`、`mcp/client/RemoteMCPToolExecutor.java`、`mcp/client/HttpMCPClient.java`、`prompt/mcp-parameter-extract.st` | 为什么不是让 LLM 直接回答？参数错了、工具超时、工具不存在怎么办？ | MCP 返回的是事实数据，LLM 只负责路由、抽参和表达；抽参 JSON 仅白名单字段并补默认值；工具注册、超时、异常、空结果都有兜底；生产还需参数校验、鉴权、审计与幂等。 |
| 两阶段意图识别：规则/BM25/向量海选，再用 LLM 精判；2045 -> 114、Recall@80=100%、P95=15ms | `rag/core/intent/IntentHybridRecallService.java`、`DefaultIntentClassifier.java` | `rag/config/IntentPreFilterProperties.java`、`rag/Intent/IntentPrefilterAbEvalTests.java`、`intent_tree_snapshot_2026-04-17.txt` | 为什么要两阶段？BM25 如何算？如何保证不漏召回？为什么是 114？ | 海选目标是低成本、高召回地缩小 LLM Prompt 候选集；规则、BM25、向量并行，按权重融合；候选上限、最小候选数、阈值应由验证集调参，以 Recall@K 为硬约束、P95 为性能约束。该实现与测试目前是未提交工作区文件；2045/114/Recall/P95 数值需用固定评测集和脚本现场复现。 |

说明：表内 `bootstrap/...` 表示从 `bootstrap/src/main/java/com/nageoffer/ai/ragent/` 开始的路径；不要把它当作可复制的完整路径。

## 3. 重点源码阅读顺序（由入口到细节）

### 第 1 轮：先能跑通、说清主链路（3 小时）

1. `RAGChatController`：接口、SSE、停止接口、幂等注解。
2. `RAGChatServiceImpl`：整条请求编排；逐段标出“记忆、改写、意图、澄清、检索、Prompt、流式”的调用点。
3. `RetrievalEngine`：一个子问题如何同时获得 KB 与 MCP 上下文。
4. `MultiChannelRetrievalEngine`：多通道并发与后处理链。
5. `RAGPromptService` 和 `src/main/resources/prompt/*.st`：不同场景的 Prompt 如何装配。

验收：关闭 IDE 后，能手画第 1 节主链路，并用一句话解释每一站的输入、输出和失败兜底。

### 第 2 轮：吃透两条核心链路（4 小时）

**查询链路**：`QueryRewriteService` -> `IntentResolver` -> `DefaultIntentClassifier` -> `IntentHybridRecallService` -> `RetrievalEngine` -> `RAGPromptService` -> `RoutingLLMService` -> `StreamChatEventHandler`。

**入库链路**：`IngestionPipelineController` -> `IngestionTaskServiceImpl` -> `IngestionEngine` -> `FetcherNode` -> `ParserNode` -> `ChunkerNode` -> `EnricherNode/EnhancerNode` -> `IndexerNode` -> `MilvusVectorStoreService`。

验收：给出一个具体问题和一个 PDF/Markdown 文档，能逐步说出它经过的类、落在哪里、什么条件会提前返回。

### 第 3 轮：准备高频追问（4 小时）

1. 检索：向量相似度、Milvus 索引/过滤、ES 倒排、BM25、TopK、Rerank、Recall@K、MRR/NDCG。
2. LLM：上下文窗口、温度/TopP、Prompt 注入、模型路由、首包超时、熔断/健康状态。
3. 工程：线程池隔离、`CompletableFuture` 异常处理、SSE 断连、幂等、限流、Trace、配置热更新（若实际支持）。
4. 数据：chunk 粒度、元数据、增量更新、删除同步、评测集构造与人工标注。

验收：每题用“结论 -> 原因 -> 代码证据 -> 反例/边界”四句话回答，不超过 90 秒。

## 4. 七天冲刺日程

| 天数 | 主题 | 当天必须完成的产物 |
|---|---|---|
| Day 1 | 项目总览与启动 | 手绘架构图；2 分钟项目介绍录音 3 次；列出本地启动依赖与健康检查方式。 |
| Day 2 | SSE 与模型路由 | 画时序图；解释 `taskId`、取消、首包、失败降级；用浏览器/接口实际发起、停止一次流式请求。 |
| Day 3 | 入库与分块 | 选择一份真实文档，记录分块前后样例、chunk 数、size、overlap；回答“为何这样切”。 |
| Day 4 | 检索与重排 | 选 10 个问题，逐个记录意图、三个通道结果、去重、Rerank 前后 Top5；至少找 2 个失败样例。 |
| Day 5 | 意图与 MCP | 为 10 个问题标注正确意图；解释海选/精判；模拟一次工单查询的抽参与失败处理。 |
| Day 6 | 指标与系统设计 | 固化评测表：数据集版本、gold 标注、topK、Recall@K、P95、成本；准备扩容、权限、增量入库和故障降级方案。 |
| Day 7 | 模拟面试 | 完成 45 分钟自测：项目介绍 5 分钟、深挖 25 分钟、基础题 10 分钟、反问 5 分钟；将卡壳点回填到本章程。 |

若只剩一天：优先完成 Day 1、Day 2、Day 4、Day 5；不要背未经验证的性能数字。

## 5. 面试官问题库（要能落到代码）

1. “一次请求从前端到模型具体经过什么？”——按 `RAGChatServiceImpl.streamChat` 讲，说明澄清与空检索的提前返回。
2. “为什么意图定向和全局检索同时存在？”——精度与兜底的平衡；说明触发条件在 `SearchChannelProperties`。
3. “Rerank 为什么能提高效果，为什么不直接多取？”——语义匹配更细；但有网络成本与延迟，候选集必须限额，且失败降级。
4. “你怎么证明 78.10%？”——先给评测口径；不能给出数据集/脚本/对照组，就坦诚说该数字尚需复现，不把它当作已验证生产指标。
5. “MCP 和 Function Calling 有何不同？”——Function Calling 是模型调用工具的形式；MCP 是工具/资源互操作协议与生态。这里实现的是应用侧注册、抽参、远程工具执行和上下文整合。
6. “SSE 客户端断开会怎样？”——前端 Abort、服务端任务取消、底层 HTTP call 取消、回调收尾；说明仍需防止任务泄漏。
7. “2045 个意图如何不把 Prompt 撑爆？”——三路高召回海选，限定候选数后将候选描述交给 LLM 精判；需用 Recall@K 监控漏召回。
8. “分块大小怎么定？”——不是拍脑袋：按文档结构、Embedding 模型和评测结果调；观察 Recall、答案引用完整性、上下文 token 与延迟。
9. “如何做企业权限隔离？”——召回前将 tenant/部门/文档 ACL 写入 metadata 并作为 Milvus/ES 过滤条件；MCP 侧同样传递主体并审计。当前若未实现，不应说已上线。
10. “为什么用 `CompletableFuture`，怎么避免线程池互相拖垮？”——通道与批量 MCP 任务并行，但需要按 CPU/IO/模型调用隔离线程池、设置超时、限流与熔断。

## 6. 简历风险清单与修订建议

### 当前可在源码中直接佐证

- SSE、任务取消、思考与正文事件分流、前端流读取。
- 可编排入库 Pipeline、结构感知分块、Milvus 写入。
- 多通道检索、去重、Rerank 接口与 MCP 抽参/执行链路。
- 两阶段意图海选/精判的实现和一个 A/B 评测测试文件。

### 需要补齐证据后再强讲

- “18.57% -> 78.10%”：当前未在工作区找到该数值的评测数据、脚本或报告。
- “2045 -> 114、Recall@80=100%、P95=15ms”：代码有候选上限和测试雏形，但这些精确数值需固定数据集、参数快照、原始结果与重复运行记录支持。
- “工单”是一个业务示例；当前仓库可见 MCP 通用客户端/执行器。若没有真实 MCP Server、工具 schema 和演示记录，表述为“实现了通用 MCP 工具接入链路，并以工单查询场景验证”。
- “优探云购平台”不在当前工作区，无法由该仓库映射。面试前需单独准备源码、压测报告、数据库索引 DDL 与消息可靠性证据。

## 7. 面试前 30 分钟清单

- 用自己的话复述 60 秒介绍与两张链路图。
- 打开上表中的 6 组主代码，不依赖搜索也能定位关键方法。
- 准备一份评测表和两个失败案例，而不是只准备漂亮数字。
- 准备“我做了什么 / 原项目已有内容 / 我还会如何改”的边界说法。
- 反问面试官：团队的 RAG 场景是知识库、Agent 工具调用还是业务 Copilot？当前如何评测召回和答案质量？





把整个 streamChat 方法想象成一次智能客服对话的流水线，一共 7 个环节： | 步骤 | 做什么 | 通俗理解 | |------|--------|----------| | ① 记忆加载 | memoryService.loadAndAppend() | 从数据库把之前的聊天记录拉出来，附上用户刚说的话 | | ② 改写拆分 | queryRewriteService.rewriteWithSplit() | 把用户口语化的问题改规范，复杂问题拆成多个子问题 | | ③ 意图解析 | intentResolver.resolve() | 判断用户到底想问什么、属于哪个知识领域 | | ④ 歧义引导 | guidanceService.detectAmbiguity() | 如果问题太模糊，先反问用户澄清（不继续往下走） | | ⑤ 检索 | retrievalEngine.retrieve() | 拿着意图去知识库和 MCP 中搜索相关文档 | | ⑥ Prompt 组装 | promptBuilder.buildStructuredMessages() | 把检索结果 + 历史对话 + 改写问题拼成给大模型的提示词 | | ⑦ 流式输出 | llmService.streamChat() | 调用大模型，生成的字一个一个通过 SSE 推到前端 |
此外还有一个短路逻辑：如果所有意图都能被系统内置答案覆盖，就跳过⑤⑥，直接走内置回复。



原始提示词

原始提示词+记忆

原始提示词+记忆+拆分子问题

原始提示词+记忆+拆分子问题+意图识别和澄清

根据意图去向量库里面或者使用mcp来检索

拼装

最终成品，每一个改写好的子问题和对应的历史和从库中检索的内容



