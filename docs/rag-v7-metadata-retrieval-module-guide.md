# RAG v7 元数据检索模块指南

> 用途：按“类为什么存在、输入输出是什么、与谁协作”理解 v7 主链路。
> 不要求逐行背代码。面试时应先说明职责和数据流，再按追问展开关键策略。

## 0. 总体调用图

```text
用户问题
  │
  ├─ DefaultIntentClassifier / IntentHybridRecallService
  │     └─ 得到意图节点
  │
  ├─ EntityCatalogService / EntityResolver
  │     └─ 得到唯一、歧义或未命中的实体结果
  │
  └─ RetrievalPlanService
        └─ RetrievalPlan（多条意图计划 + 多层 metadata filter）
              ├─ ProgressiveMilvusPlanRetriever → MilvusRetrieverService
              └─ ProgressiveKeywordEsPlanRetriever → KeywordEsGateway
                       ↓
                  合并、去重、RRF 限流、Rerank
                       ↓
                    TopN Chunk
```

建议阅读顺序：**数据结构 → Plan 生成 → 双通道执行 → 候选治理 → 评测**。

---

## 1. 配置与数据结构

### `MetadataRetrievalProperties`

**路径**：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/MetadataRetrievalProperties.java`

**职责**：集中保存 v7 元数据检索的开关和阈值。

**关键字段**：

- `enabled`：是否启用 metadata Plan；
- `elementFacetEnabled`：是否把元素条件加入角色 Plan；A/B 的唯一变量；
- `targetCandidateCount`、`maxCandidatePool`：单意图目标候选池和总候选上限；
- `maxRerankCandidates`、RRF 参数：候选池过大时的安全阈值；
- `intentRoutesLocation`、`entityCatalogLocation`：v7 路由和实体目录位置；
- `evaluationEnabled`：仅本地评测端点开关。

**为什么存在**：把策略参数从业务代码移出。调阈值或做 A/B 不需要改多处 `if/else`。

**协作对象**：`RetrievalPlanService`、候选池处理器、评测服务、`application.yaml`。

---

### `RetrievalPlan` / `IntentRetrievalPlan` / `RetrievalFilterLevel`

**路径**：`rag/core/retrieve/plan/`

**职责**：描述“应如何检索”，而不负责实际搜索。

```text
RetrievalPlan
  └─ 多条 IntentRetrievalPlan
        └─ 多层 RetrievalFilterLevel
              └─ metadataFilters + stopCondition
```

**为什么存在**：把“判断”和“执行”分离。这样 Milvus 与 ES 可以读同一份计划，测试也能直接断言条件是否正确。

**面试要点**：Plan 不是一个无脑传给数据库的 JSON；它是一份可执行的分层策略。多意图必须拆成多条计划，不能拼成互相冲突的 AND 条件。

---

## 2. 意图与实体解析

### `IntentHybridRecallService`

**路径**：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/IntentHybridRecallService.java`

**职责**：当意图叶子数较多时，用规则、BM25 和向量召回生成候选意图集合。

**输入**：用户问题、可用意图节点。

**输出**：带分数的候选意图列表。

**核心机制**：多路候选“海选”优先保证覆盖，再由 LLM 精判。Top80 是候选规模上限，不是最终意图结果数。

**协作对象**：`DefaultIntentClassifier`、Embedding、关键词索引、意图树。

**常见误解**：它不负责查知识 Chunk，只负责缩小“LLM 要在什么意图集合中做分类”。

---

### `DefaultIntentClassifier`

**路径**：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/intent/DefaultIntentClassifier.java`

**职责**：最终决定意图分类策略并调用 LLM 分类。

**输入**：问题、当前知识库的叶子意图。

**输出**：选中的意图节点及置信信息。

**核心机制**：

```text
叶子数 <= 阈值：直接在小树中判定
叶子数 > 阈值：先调用 IntentHybridRecallService，再在候选中精判
```

**本次改动**：增加策略诊断日志，能看到 `direct` / `two-stage`、总叶子数和候选数。

**面试要点**：两阶段不是越多越好；小树仍走海选只会增加复杂度。

---

### `EntityCatalogService` 与 `EntityResolver`

**路径**：

- `rag/core/retrieve/plan/EntityCatalogService.java`
- `rag/core/retrieve/plan/EntityResolver.java`

**职责**：从 v7 实体目录中识别角色、武器等实体，并返回状态而非简单真假值。

**输入**：用户问题与 `entity_catalog.jsonl`。

**输出**：`EntityResolution`，常见状态包括唯一命中、多个候选、未命中。

**核心机制**：实体识别只负责生成可用的 `entity_id` 条件；找不到或候选过多时不能硬塞错误 ID，应把范围交回意图、向量和 ES 继续判断。

**协作对象**：`RetrievalPlanService`、`IntentResolver`。

**当前真实缺口**：实体目录缺少“草神 → 纳西妲”，正式评测中该别名未命中 Top5。这是数据治理任务，不应通过盲目放宽检索来掩盖。

---

### `IntentRouteRuleService`

**路径**：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/plan/IntentRouteRuleService.java`

**职责**：把意图代码映射为基础 metadata 范围。

**例子**：

```text
character_skill
  → category=character
  → doc_type in [character_skill, character_constellation]
```

**为什么存在**：意图树的语义描述不应散落在检索器的硬编码条件中。规则文件可以随知识域扩展。

**协作对象**：`RetrievalPlanService`、`intent_routes.json`。

---

## 3. Plan 生成

### `RetrievalPlanService`

**路径**：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/plan/RetrievalPlanService.java`

**职责**：把意图、实体和问题转为一个或多个可执行检索计划。

**输入**：

- 原始问题；
- 已选意图节点；
- `EntityResolution`；
- `elementFacetEnabled` 开关。

**输出**：`RetrievalPlan`。

**核心流程**：

```text
意图 → IntentRouteRuleService 取 category/doc_type
实体唯一命中 → 补 entity_type/entity_id
角色集合问题 + 开关开启 → ElementFacetResolver 补 element
生成严格到宽松的 RetrievalFilterLevel
```

**为什么存在**：这是“自然语言理解”与“检索器执行”的边界层。Controller 不应该知道检索条件，Milvus/ES 也不应该自行猜业务含义。

**协作对象**：实体目录、路由规则、元素解析、两个 Progressive Retriever。

**面试要点**：Plan 让同一策略能被解释、单测和双通道复用。

---

### `ElementFacetResolver`

**路径**：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/plan/ElementFacetResolver.java`

**职责**：从问题中识别冰、火、风、雷、水、岩、草等稳定元素条件。

**输入**：问题文本与已有 route filters。

**输出**：可选 `element` metadata 值。

**关键边界**：只在角色类别计划中使用。它不是把所有修饰词都变成索引字段的通用 NLP 系统。

**验证**：关闭/开启它是正式 A/B 的唯一策略变量。

---

### `ProgressivePlanStopPolicy`

**路径**：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/plan/ProgressivePlanStopPolicy.java`

**职责**：决定每一层过滤后是停止，还是继续放宽。

**核心规则**：

```text
唯一实体严格层命中 >= 1：停止跨实体放宽
严格层 0：进入下一层
无实体 / 歧义实体：按普通目标候选量继续
```

**为什么存在**：避免把终止判断复制进 Milvus 和 ES 两处，造成策略分叉。

---

## 4. 执行层：Milvus 与 ES

### `ProgressiveMilvusPlanRetriever`

**路径**：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/plan/ProgressiveMilvusPlanRetriever.java`

**职责**：按 Plan 的 filter levels 编排 Milvus 向量检索。

**输入**：问题向量、单条 `IntentRetrievalPlan`、候选池上限。

**输出**：去重后的向量候选 Chunk。

**核心流程**：

```text
只生成一次 query vector
→ 对每个 level 构造 RetrieveRequest.metadataFilters
→ 调用 MilvusRetrieverService.retrieveByVector
→ 按 chunkId 去重并累计
→ 调用 ProgressivePlanStopPolicy 决定是否继续
```

**为什么存在**：把“逐层策略”从底层向量客户端剥离；`MilvusRetrieverService` 因而只负责一次搜索。

**测试**：`ProgressiveMilvusPlanRetrieverTest`。

---

### `MilvusRetrieverService`

**路径**：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MilvusRetrieverService.java`

**职责**：执行一次 Milvus 向量检索。

**输入**：`RetrieveRequest`，其中包括 query/向量、collection、topK、metadata filters。

**输出**：`List<RetrievedChunk>`。

**核心机制**：将 metadata Map 转为 Milvus JSON filter，例如：

```text
metadata["category"] == "character"
&& metadata["entity_id"] == "10000002"
&& metadata["doc_type"] in ["character_skill", "character_constellation"]
```

**面试要点**：metadata 负责“允许搜哪些块”，向量相似度负责“允许范围内哪个块更语义相关”。

---

### `ProgressiveKeywordEsPlanRetriever`

**路径**：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/plan/ProgressiveKeywordEsPlanRetriever.java`

**职责**：用与 Milvus 相同的 Plan / StopPolicy 执行 ES 关键词检索。

**输入输出**：与 `ProgressiveMilvusPlanRetriever` 对应，但底层调用 ES/BM25。

**为什么存在**：避免“向量严格过滤、关键词全库搜索”的边界不一致。

**测试**：`ProgressiveKeywordEsPlanRetrieverTest`。

---

### `KeywordEsGateway`

**路径**：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/es/KeywordEsGateway.java`

**职责**：封装 ES 索引写入、查询 DSL 构造、BM25 结果转换和 metadata 字段处理。

**输入**：关键词、collection/知识库范围、metadata filters。

**输出**：关键词通道的 `RetrievedChunk`。

**面试要点**：BM25 基于倒排索引的词频、逆文档频率和长度归一化，尤其擅长专有名词、武器名、技能名等词面精确召回；它与向量检索互补。

---

## 5. 候选治理与重排

### `CandidatePoolLimiterPostProcessor`

**路径**：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/postprocessor/CandidatePoolLimiterPostProcessor.java`

**职责**：候选数超过安全阈值时，用 RRF 裁剪候选池，再交给 Rerank。

**输入**：来自不同检索通道的候选列表。

**输出**：受上限保护、按 RRF 重新排序的候选。

**关键原因**：ES 分数和 Milvus 分数不能直接比较；RRF 改用“在各自通道排名第几”融合。

**触发条件**：只在超过 `maxRerankCandidates` 时触发，避免给小候选池增加不必要处理。

---

## 6. 评测模块

### `MetadataRetrievalEvaluationService`

**路径**：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/evaluation/MetadataRetrievalEvaluationService.java`

**职责**：在同一个请求中运行 Baseline / Candidate 两个 metadata 策略分支。

**输入**：问题、可选 TopK。

**输出**：

- 一次意图解析和实体解析结果；
- `element=false` 的 Plan、Top20、检索耗时；
- `element=true` 的 Plan、Top20、检索耗时。

**关键设计**：只解析一次意图，再让两条分支复用同一结果。这样 A/B 差异只来自元素条件，不来自 LLM 分类随机性。

**额外职责**：评测模式下从持久化 Chunk 读取 `chunkIndex` 和 metadata，帮助旧乱码正文映射回稳定 Gold Key；该能力不进入普通问答返回。

---

### `MetadataRetrievalEvaluationController`

**路径**：`bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/MetadataRetrievalEvaluationController.java`

**职责**：提供本地评测 HTTP 入口。

**关键保护**：

- 只有 `evaluationEnabled=true` 才创建；
- 只允许 loopback 请求；
- 不走普通前端聊天与最终答案生成链路。

**为什么存在**：让 Python 评测脚本可重复调用真实检索链路，同时不把调试入口暴露成常规业务 API。

---

### `build_genshin_v7_metadata_eval_draft.py`

**路径**：`tests/eval_rag/tools/build_genshin_v7_metadata_eval_draft.py`

**职责**：从 v7 `chunks.jsonl` 生成待确认的测试问题、候选 Gold Chunk、元素集合候选实体和 Markdown 审阅清单。

**输入**：v7 Chunk 工件。

**输出**：draft JSONL 与 Gold review Markdown；确认后可生成正式 Gold JSONL。

**关键边界**：脚本只生成候选，不能自己宣布 Gold；最终确认来自人工审阅。

---

### `run_genshin_v7_metadata_eval.py`

**路径**：`tests/eval_rag/tools/run_genshin_v7_metadata_eval.py`

**职责**：调用本地 A/B 接口、保存 Top20、计算指标并输出可复算产物。

**计算指标**：

- 单实体 / 别名：Hit@5、MRR@10、Recall@20；
- 元素集合：实体覆盖率@20；
- 所有计分题：Top5 错误召回率；
- 两条分支：检索 P50 / P95；
- 数据质量：未映射 Chunk 数。

**关键边界**：不测最终 LLM 回答质量，也不把诊断题混进主指标。

---

## 7. 测试类如何读

| 测试类 | 重点验证 |
| --- | --- |
| `RetrievalPlanServiceTest` | 意图/实体/元素怎样组成正确 Plan；开关关闭时不加元素。 |
| `ProgressiveMilvusPlanRetrieverTest` | 向量层是否正确执行每层、去重、达到阈值后停止。 |
| `ProgressiveKeywordEsPlanRetrieverTest` | ES 是否与 Milvus 执行同一套分层与停止策略。 |
| `CandidatePoolLimiterPostProcessorTest` | 超过阈值时 RRF 是否裁剪；未超过时是否不干扰。 |
| `IntentPrefilterAbEvalTests` | 大树两阶段预过滤的候选覆盖和延迟口径。 |

看测试不是为了背断言，而是为了回答：**这条策略如果被改坏，最先会在哪个场景暴露？**

---

## 8. 一次查询的推荐调试顺序

以“草神的元素战技是什么？”为例：

1. 看 `DefaultIntentClassifier` 日志：走的是 `direct` 还是 `two-stage`；
2. 看 `EntityResolution`：是否识别 `草神 → 纳西妲`；
3. 看 `RetrievalPlan`：是否生成 `entity_id`、`category`、`doc_type`、`element`；
4. 看 Milvus / ES 每个 level 的 returned / uniqueAdded / pooled；
5. 看合并后 Gold Chunk 是第几名；
6. 若实体未命中，优先检查实体目录和别名，而不是立刻放宽到全库；
7. 若领域外仍返回 Chunk，检查是否需要证据门控与拒答。

这套顺序能避免“看见结果不对就到处改 Prompt”的盲目排查。
