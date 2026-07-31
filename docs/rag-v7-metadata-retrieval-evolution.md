# RAG v7 元数据检索：技术演进与学习档案

> 本文记录原神 v7 检索改造从问题发现、方案取舍、实现到评测验证的完整过程。
> 它以代码、Git 提交和评测产物为准，不尝试逐字复原早期对话；无法由仓库证据确认的内容，统一标注为“讨论结论”。

## 0. 一句话版本

我们没有把“神里绫华”“冰系角色”直接拿去查数据库，而是先把用户问题转成可执行的检索计划：

```text
问题
  → 意图路由 + 实体解析
  → RetrievalPlan（类别、文档类型、实体、元素）
  → Milvus 向量检索 + Elasticsearch 关键词检索
  → 逐级放宽、去重、RRF 安全融合、Rerank
  → TopN 证据块
```

最终在 56 道人工确认题的离线 A/B 中，`element` 元数据使元素集合题的实体覆盖率@20 从 **24.34%** 提升至 **100%**，Top5 错误召回率从 **33.90%** 降至 **14.41%**，单实体题 Recall@20 保持 **100%**。

正式数据与完整口径见：[56 题正式评测报告](genshin-v7-metadata-retrieval-evaluation-report-2026-07-26-formal.md)。

---

## 1. 起点：为什么不能只靠“向量搜一下”

初始讨论从简历中的“两阶段意图路由与歧义治理”开始，但在查看原神知识库时发现一个更基础的问题：

- 旧 v4 / v6 数据、意图树和运行态混在一起，难以解释一条查询究竟查了哪份资料；
- 角色、武器、技能、命座、故事等 Chunk 的范围不清晰；
- “神里绫华的元素战技”与“冰系角色有哪些”不是同一类检索问题：前者需要锁定实体，后者需要覆盖一个集合；
- 只做全库向量相似度，容易把相近但不属于当前角色、元素或文档类型的块混入候选池。

因此本次改造的目标不是再接一个新模型，而是让检索先回答两个问题：

1. **应当在哪个资料范围内检索？**
2. **范围过窄时如何安全放宽，而不是直接查全库？**

---

## 2. 关键概念：意图树、实体和 Chunk 各做什么

这是过程中最容易混淆、也最值得面试讲清楚的一点。

| 概念 | 它回答的问题 | 例子 | 不做什么 |
| --- | --- | --- | --- |
| 意图 | 用户想问哪类资料 | `character_skill`、`weapon_profile` | 不直接指向一篇具体文档 |
| 实体 | 用户明确提到了谁/什么物品 | 神里绫华 → `10000002` | 找不到时不强行猜一个 ID |
| 元数据 | Chunk 属于什么范围 | `category=character`、`doc_type=character_skill`、`element=cryo` | 不替代正文语义 |
| Chunk | 真正提供答案证据的文本 | 神里绫华技能块 | 不负责决定检索边界 |

因此，意图树不是“给人看的目录”，也不是直接物理隔离文档。它的价值是将自然语言问题转换为**检索范围条件**；实体命中后再把范围收紧到具体 `entity_id`。最后仍由向量和 BM25 在这个范围里寻找最相关的 Chunk。

### 例子：神里绫华的元素战技

```text
意图：角色技能
实体：神里绫华（10000002）
第一层条件：
  category=character
  entity_id=10000002
  doc_type in [character_skill, character_constellation]

在该范围内：Milvus 搜语义相近文本，ES/BM25 搜关键词，最后重排。
```

### 例子：冰元素角色有哪些

```text
意图：角色资料
实体：无（这是集合问题，不是一个角色）
条件：
  category=character
  doc_type=character_profile
  element=cryo
```

这里的 `element` 是高价值、稳定的筛选维度；并不意味着给所有可能的自然语言属性无限增加字段。比如“强度高”“适合新手”这类主观或频繁变化的概念，不能简单做成硬过滤元数据。

---

## 3. 数据层改造：从旧运行态到 v7

### 3.1 讨论结论：只保留可解释的 v7 路径

旧 v4 / v6 原神运行态会干扰路由和测试，因此保留公司知识库等非原神数据，同时把原神检索重建到独立的 v7 种子与 collection。

核心产物：

```text
resources/genshin_seed_v7/
├── config/intent_nodes.json
├── config/intent_routes.json
├── config/entity_catalog.jsonl
└── artifacts/chunks.jsonl
```

每个 v7 Chunk 至少带有：

```json
{
  "category": "character",
  "doc_type": "character_skill",
  "entity_type": "character",
  "entity_id": "10000002",
  "element": "cryo"
}
```

### 3.2 Chunk 粒度的取舍

讨论中否定了“把文档切得越小越好”的想法。过碎会让一个技能的名称、效果、触发条件分散，过大则混入多个无关技能。

v7 采用面向资料结构的切分：角色资料、技能、命座、故事等各自形成语义完整的块；Chunk 同时保留原文、稳定 `logical_chunk_key` 和检索元数据。`logical_chunk_key` 用于评测，不使用会随重建变化的数据库 Snowflake ID。

---

## 4. 路由策略：小树直判，规模变大后再两阶段

原项目已有“规则 / BM25 / 向量候选海选 → LLM 精判”的两阶段意图识别。它解决的是意图叶子很多时，不能把全部候选塞进一个 Prompt 的问题。

但 v7 原神树当前叶子数量很小。讨论后确定：

```text
叶子数 <= 80：直接让 LLM 在小树中判定
叶子数 > 80：规则 + BM25 + 向量海选候选，再由 LLM 精判
```

这不是废弃两阶段，而是按规模选择策略：小树时避免“为了 Top80 而 Top80”的无效复杂度；大树时保留已有的候选覆盖和 Prompt 缩减能力。

相关提交：

```text
41282a8 两阶段意图识别
6a2ede4 add v7 metadata pipeline and adaptive intent routing
```

---

## 5. 从意图和实体生成 RetrievalPlan

### 5.1 为什么需要 Plan

早期容易把它理解为“一份 JSON 无脑扔给 Milvus”。实际不是：Plan 是一份**可解释、可执行、可逐层放宽的检索清单**。

它整合意图路由规则和实体解析结果，但每一层仍会独立执行。多意图问题不会拼出矛盾条件，例如不会生成：

```text
category=character AND category=weapon
```

而是拆成两个独立计划，分别检索后合并。

核心代码：

- [RetrievalPlanService.java](../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/plan/RetrievalPlanService.java)
- [EntityCatalogService.java](../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/plan/EntityCatalogService.java)
- [IntentRouteRuleService.java](../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/plan/IntentRouteRuleService.java)

### 5.2 分层放宽

以“神里绫华元素战技”为例：

```text
1. 实体 + 类别 + 文档类型
2. 实体 + 类别
3. 类别 + 文档类型
4. 类别
5. v7 全库
```

后来进一步纠正了“严格层只要不足 20 条就继续放宽”的策略。对**唯一实体**而言，只要严格层已经有结果，再继续放宽很可能把其他角色挤进候选池；因此改为：

```text
唯一实体严格层命中 >= 1 条：停止跨实体放宽
严格层 0 条：再进入下一层
无实体或多实体歧义：按原有分层策略补足
```

这体现了本次的优先级：宁可少一些候选，也不能为了凑满 20 条主动污染唯一实体的边界。

相关提交：

```text
aba0372 实现第一阶段意图实体检索计划
07ddb39 实现实体优先检索与命中停止策略
```

---

## 6. 双通道执行：Milvus 与 Elasticsearch 必须一致

### 6.1 纠正的认识

Milvus 不是“只看一个黑盒向量、因此不需要条件”的系统。向量相似度用于在候选范围内排序；元数据过滤决定候选范围。两者职责不同：

```text
metadata filter：先排除不该出现的块
vector similarity：在允许范围内判断语义相关性
```

ES 也不是简单正则匹配。它用倒排索引和 BM25，为稀有专名、技能名、别名和精确词面提供关键词召回。若 Milvus 做了角色过滤而 ES 没做，ES 会重新把其他角色关键词块混回候选池，混合检索就失去边界。

### 6.2 最终实现

同一份 `RetrievalPlan` 被两个执行器读取：

```text
RetrievalPlan
  ├── ProgressiveMilvusPlanRetriever
  └── ProgressiveKeywordEsPlanRetriever
```

两者都按相同的 metadata level 循环，只是底层调用分别是向量搜索和 BM25 搜索。

核心代码：

- [ProgressiveMilvusPlanRetriever.java](../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/plan/ProgressiveMilvusPlanRetriever.java)
- [ProgressiveKeywordEsPlanRetriever.java](../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/plan/ProgressiveKeywordEsPlanRetriever.java)
- [MilvusRetrieverService.java](../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/MilvusRetrieverService.java)
- [KeywordEsGateway.java](../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/es/KeywordEsGateway.java)

相关提交：

```text
f519006 实现 Milvus 分层元数据检索
4e497ea 实现 ES 元数据分层检索
```

---

## 7. 候选合并与 RRF 安全阈值

另一个中途发现的问题是：Milvus 分数、ES/BM25 分数不是同一统计尺度，不能直接混在一起排序。

现有链路会将两个通道的候选交给 Rerank，但当候选过多时成本和不稳定性都会上升。因此加入安全限制：候选池超过 80 时先按 RRF（Reciprocal Rank Fusion）进行基于排名的融合与裁剪，再交给 Rerank。

RRF 的关键直觉是：不比较“0.8 分”和“12.4 分”谁大，而是看一个结果在多个通道分别排第几。它是保护未来语料扩大后的安全网，并不宣称当前每次都会触发。

相关提交：

```text
05b7c97 增加候选池 RRF 安全限流
```

---

## 8. 元素元数据：本轮能被验证的改良点

### 8.1 问题

“冰系角色有哪些”并没有唯一实体。若只按 `category=character` 和 `doc_type=character_profile` 检索，向量与关键词候选会在所有元素角色之间竞争，造成：

- 需要的冰元素角色覆盖不全；
- 前几个位置混入火、雷、水等角色；
- 后续 Rerank 与 LLM 需要花成本清理本可在检索前排除的数据。

### 8.2 改造

给角色 Chunk 补齐 `element`，并让它同时进入 Milvus 与 ES 的过滤条件。通过 `elementFacetEnabled` 开关，能够在**同一数据集、同一请求、同一意图结果**下对比开关前后差异。

相关提交：

```text
71c9748 补充角色元素元数据检索与安全重建
```

---

## 9. 评测闭环：不要凭感觉宣布“检索更好了”

### 9.1 评测资产

```text
tests/eval_rag/
├── data/
│   └── genshin_v7_metadata_gold.extended.jsonl
├── tools/
│   ├── build_genshin_v7_metadata_eval_draft.py
│   └── run_genshin_v7_metadata_eval.py
└── artifacts/metadata_eval/
    └── formal-20260726-extended-152025/
```

评测采用传统信息检索的做法：先人工确认问题与 Gold 证据，再让两种方案跑同一批问题，脚本计算指标。AI 可以协助生成候选题和候选 Chunk，但不能自动替代 Gold 确认。

### 9.2 从 10 题试运行到 56 题正式集

第一轮只用 10 题跑通流程，验证：

- Gold 使用稳定 `logical_chunk_key` 而非运行时 ID；
- 试用角色不会重复计数；
- Baseline / Candidate 的变量只差 `element`；
- 输出能保存每题 Top20、Plan 和延迟。

确认流程可靠后扩展到 56 题：44 道单实体/别名题、7 道元素集合题、5 道诊断题。每题预热后运行 5 次。完整规范见：[评测章程](genshin-v7-metadata-retrieval-evaluation-charter.md)。

### 9.3 数据质量修正

评测时发现少数历史导入 Chunk 的中文正文已变为 `?`。如果只用正文哈希映射 Gold，会把实际命中的块误算成未命中。

因此仅在本地评测端点中补充持久化元数据，映射优先级为：

```text
正文 SHA-256 精确匹配
  → 若失败且三元组唯一：entity_id + doc_type + chunk_index
```

最终正式运行的未映射 Chunk 数为 0。注意：这是评测可追溯性修复，不代表乱码数据已经根治。

评测端点只在本地开关打开时可用：

- [MetadataRetrievalEvaluationService.java](../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/evaluation/MetadataRetrievalEvaluationService.java)
- [MetadataRetrievalEvaluationController.java](../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/controller/MetadataRetrievalEvaluationController.java)

---

## 10. 结果、失败案例与诚实边界

### 10.1 已被证明的结果

在 56 题正式集中：

```text
元素集合实体覆盖率@20：24.34% → 100.00%
Top5 错误召回率：      33.90% → 14.41%
单实体 Recall@20：     100.00% → 100.00%
检索 P95：             585ms → 542ms（单机批次，不作为性能承诺）
```

### 10.2 发现但尚未修复的缺口

1. **草神别名**：`草神的元素战技是什么？` 未解析到纳西妲，Gold 首次出现在第 9 位而不是 Top5。根因是实体目录别名缺失，不是元素过滤造成。
2. **领域外拒答**：银狼、卡芙卡等领域外问题仍可能触发原神意图或全局回退并返回无关 Chunk；当前不能声称已实现可靠拒答。
3. **历史导入编码**：少数旧数据正文乱码，需要独立定位批次、备份并重建，不能直接覆盖。

这些失败案例比“全部 100%”更有价值：它们说明评测真的能够发现问题，也提供了下一步改造的明确入口。

---

## 11. 面试复习主线

可以按下面的顺序讲，不要先背技术名词：

```text
发现问题：集合问题会混入跨元素资料，单靠向量相似度无法保证边界
→ 分析原因：意图树只决定资料类型，Chunk 缺少能表达元素范围的元数据
→ 设计方案：意图 + 实体生成检索计划，Milvus 与 ES 都执行同一份分层过滤
→ 稳定性设计：唯一实体命中后不跨实体放宽；候选过多时 RRF 限流
→ 验证方法：同语料、同意图结果，只切换 element 开关做人工 Gold A/B
→ 结果与边界：集合覆盖率提升，但草神别名和领域外拒答仍待解决
```

高频追问与回答重点：

| 面试官可能问什么 | 回答重点 |
| --- | --- |
| 意图树是不是直接定位文档？ | 不是。它生成类别/资料类型范围，真实证据仍由检索 Chunk 提供。 |
| 有实体为什么还要向量检索？ | 实体只缩小“谁”的范围，技能、故事、命座等仍需根据问题语义排序。 |
| 为什么 ES 还要做元数据过滤？ | 否则关键词通道会重新混入范围外资料，破坏混合检索边界。 |
| 为什么不把所有条件都做成字段？ | 只给稳定、高价值、可验证的维度建字段；主观或高变化属性交给文本检索/重排。 |
| 为什么不是命中越多越好？ | 唯一实体问题中，跨实体补满候选会引入噪声；应保留命中边界。 |
| 评测是不是模型自己判的？ | Gold 由人工确认；脚本只负责调用、导出、计算指标。 |
| 结果为什么 Hit@5 没提高？ | 单实体路径原本已较强，改造的主要收益是元素集合问题的覆盖与纯度，不能夸大。 |

---

## 12. 与简历五点的关系

本次主要支撑下面两类能力：

1. **两阶段意图路由与歧义治理**：保留“大树两阶段、小树直判”的自适应策略；v7 的意图和实体共同构造可解释 Plan。
2. **混合检索、去重与 Rerank 后处理**：本轮已证明的是“元数据分层混合检索”，尚未完成 Contextual Retrieval，不能提前这样写。
3. **分层离线评测与策略 A/B 验证**：56 条人工确认题、固定 Gold、同请求双分支对照、Top20 原始产物和可复算指标。

与 SSE 首包探测、模型故障转移、AOP + TTL 全链路追踪相比，本档案不展开它们的实现细节；它们属于项目的其他核心亮点，应单独建立对应学习文档。

当前更稳妥的简历表述：

> 针对元素集合问题跨类别候选混杂，补齐角色 `element` 元数据并同步接入 Milvus 与 Elasticsearch 分层过滤；在 56 条人工确认题的离线 A/B 中，元素集合实体覆盖率@20 从 24.34% 提升至 100%，Top5 错误召回率从 33.90% 降至 14.41%，单实体题 Recall@20 保持 100%。

---

## 13. 后续路线图

```text
DONE  通过受控人工别名表补草神 → 纳西妲，并新增实体解析回归测试
PART  顶层路由支持显式 UNKNOWN 并排除受管语料；检索后证据门控仍待统一分数口径
P1  定位并重建正文乱码的历史导入批次
P1  Gold 集扩展到 80–100 题，设置可接受回归阈值
P2  再评估 Contextual Retrieval：先做真实上下文依赖错误集，再做 A/B
```

这条路线刻意避免“因为某技术流行就接入”。每一步都应从可观测的错误样本出发，保留对照、指标和失败案例。
