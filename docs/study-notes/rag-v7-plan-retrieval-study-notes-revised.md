# RAG v7 Plan 检索主链路：学习笔记（校订版）

> 日期：2026-07-27<br>
> 范围：当前 v7 元数据检索改造，不覆盖 SSE、链路追踪、查询改写等其他 RAG 模块。<br>
> 目的：先理解主链路与设计理由；不要求逐行背完所有实现细节。

---

## 0. 先记住一句话

> `RetrievalPlanService` 不负责检索资料；它负责在检索前生成一张“去哪查、先查多严、查不到怎么退”的汇总导航计划。

```text
用户问题
  → 查询路由（意图分类）
  → 实体识别
  → RetrievalPlan
  → Milvus / ES 按同一计划检索
  → 合并、必要时 RRF 限流、Rerank
  → LLM 基于候选资料回答
```

这里推荐把整件事叫作“查询路由（Query Routing）”。

- **意图分类**：查询路由的一个输入，判断用户在问“角色技能”“武器被动”还是其他主题；
- **实体识别**：查询路由的另一个输入，判断是否明确提到了“神里绫华”“狼的末路”等对象；
- **RetrievalPlan**：查询路由的输出，即可执行的检索路线图。

---

## 1. 四套数据不要混淆

```text
意图树                 用户的问题属于什么语义目录？
intent_routes.json     该目录应翻译成哪些 Chunk 元数据条件？
Chunk 元数据           每个资料块实际上属于什么范围？
entity_catalog.jsonl   用户提到的名字/别名对应哪个稳定实体 ID？
```

它们的关系是：

```text
意图叶子 ID
  ↓ intent_routes.json
category / doc_type

实体名称或别名
  ↓ entity_catalog.jsonl
entity_type / entity_id

用户显式说“冰系”等
  ↓ ElementFacetResolver
element

以上条件
  ↓ RetrievalPlanService
多层 RetrievalPlan
```

### 1.1 意图树不是 Chunk 元数据

例如意图树中的叶子可能是：

```text
原神
└─ 角色
   └─ 角色技能与命座
```

真正给检索器使用的是该叶子在 `intent_routes.json` 中的映射：

```json
{
  "intentCode": "genshin_v7_character_skill",
  "metadataFilters": {
    "category": "character",
    "doc_type": ["character_skill", "character_constellation"]
  }
}
```

因此：

```text
意图树：语义目录，帮助理解用户要问什么。
category / doc_type：Chunk 的结构化检索字段，帮助数据库缩小资料范围。
```

不要依赖中文节点名称的字符串包含关系来推导字段；节点名改了，检索规则就会坏。显式路由表更稳定。

---

## 2. 完整数据流

```text
用户问题
  │
  ├─ IntentResolver / DefaultIntentClassifier
  │    └─ 子问题 + 已选中的知识库意图叶子（NodeScore）
  │
  ├─ EntityResolver
  │    └─ EntityResolution：MATCHED / AMBIGUOUS / NOT_FOUND
  │
  └─ RetrievalPlanService.buildPlan(...)
       ├─ 查 IntentRouteRuleService：category / doc_type
       ├─ 必要时查 ElementFacetResolver：element
       ├─ 判断实体与当前意图类别是否兼容
       └─ 生成 RetrievalPlan
            └─ IntentRetrievalPlan × N
                 └─ RetrievalFilterLevel × M
                       │
                       ├─ ProgressiveMilvusPlanRetriever
                       │    └─ 复用 MilvusRetrieverService 做带 filter 的向量检索
                       │
                       └─ ProgressiveKeywordEsPlanRetriever
                            └─ 复用 KeywordEsGateway 做带 filter 的关键词检索
                                  │
                                  ↓
                        原有多通道合并、去重
                                  │
                                  ↓
                 CandidatePoolLimiterPostProcessor（仅候选过多时才 RRF 限流）
                                  │
                                  ↓
                                Rerank → LLM
```

Milvus 与 ES 的区别只在“怎么搜索”：

```text
Milvus：在符合 metadata 条件的 Chunk 中找向量最相似的内容。
ES：在符合 metadata 条件的 Chunk 中按 BM25 找关键词相关内容。
```

二者必须消费**同一份 Plan**，否则可能出现“Milvus 只查绫华、ES 却混入其他角色”的边界不一致。

---

## 3. Plan 的四个核心数据模型

### 3.1 `RetrievalPlan`

文件：[RetrievalPlan.java](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/plan/RetrievalPlan.java)

一次用户问题的总计划：

```java
RetrievalPlan(
    question,
    targetCandidateCount,
    maxCandidatePool,
    intentPlans
)
```

含义：这题有几条检索路线、每条路线要找多少候选、最后候选池最多允许多大。

### 3.2 `IntentRetrievalPlan`

文件：[IntentRetrievalPlan.java](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/plan/IntentRetrievalPlan.java)

一个意图一份子计划：

```java
IntentRetrievalPlan(
    intentCode, intentName, intentScore,
    kbId, collectionName,
    routeFilters,
    entityResolution,
    filterLevels
)
```

为什么按意图拆分？因为多意图问题不能把互斥条件硬拼成：

```text
category = character AND category = weapon   // 错误
```

而要变成：

```text
Plan A：角色 / 绫华 / 技能
Plan B：武器 / 狼的末路 / 被动
```

### 3.3 `RetrievalFilterLevel`

文件：[RetrievalFilterLevel.java](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/plan/RetrievalFilterLevel.java)

子计划中的一层检索范围：

```java
RetrievalFilterLevel(order, name, metadataFilters, stopCondition)
```

其中 `stopCondition` 有两种：

```text
ANY_HIT：本层只要有 1 条结果就停。
TARGET_REACHED：累计候选达到目标数量才停。
```

带 `entity_id` 或 `element` 的层使用 `ANY_HIT`。理由是它们已经是明确的硬约束：一旦命中，继续放宽反而会混入不相关内容。

### 3.4 `EntityResolution`

文件：[EntityResolution.java](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/plan/EntityResolution.java)

它不是 Plan 本身，而是 Plan 的重要输入：

```text
MATCHED    唯一、可信地匹配到一个实体，可使用 entity_id。
AMBIGUOUS  多个同强度候选，只记录候选，不硬塞 entity_id。
NOT_FOUND  没匹配到，不加实体条件。
```

当前第一版的取舍：`AMBIGUOUS` 的 `candidateEntityIds` 用于诊断，但不会生成 `entity_id=A OR entity_id=B` 的检索条件。这样牺牲部分精度，换取“不因错误锁定一个实体而漏掉另一个实体”。

---

## 4. `RetrievalPlanService.buildPlan()`：用人话理解主干

文件：[RetrievalPlanService.java](../../bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieve/plan/RetrievalPlanService.java)

它不调用 Milvus、ES 或 LLM。它做的事情是：

```text
1. 拿到已分类的意图叶子。
2. 找到问题中可能出现的实体。
3. 对每个意图，读取意图 → 元数据字段的路由规则。
4. 如果用户明确说“冰系/火系”等，再补 element 条件。
5. 判断实体是否属于这个意图的类别；不属于则在该类别里重新解析实体。
6. 按严格到宽松的顺序写出多层过滤条件。
7. 汇总成一张 RetrievalPlan 返回。
```

伪代码：

```java
entity = entityResolver.resolveAny(question);

for (intent : kbIntents) {
    route = intentRouteRuleService.findByIntentCode(intent.code);
    route = addExplicitElementFacet(question, route);
    compatibleEntity = entityCompatibleWithRoute
        ? entity
        : resolveAgainInsideRouteCategory(question, route.category);

    levels = buildFilterLevels(route, compatibleEntity);
    plans.add(new IntentRetrievalPlan(intent, route, compatibleEntity, levels));
}

return new RetrievalPlan(question, targetCount, maxCandidatePool, plans);
```

这就是“汇总版检索导航计划”。

---

## 5. 分层放宽到底怎么走

以“神里绫华的冰系技能”为例。假设：

```text
实体：神里绫华 → entity_type=character, entity_id=10000002
意图：角色技能 → category=character, doc_type=character_skill / character_constellation
用户显式条件：冰系 → element=cryo
```

Plan 会依次生成近似以下范围：

```text
1. entity + category + doc_type + element
2. entity + category + element
3. entity + category
4. category + doc_type + element
5. category + element
6. category + doc_type
7. category
8. v7 全库
```

关键点：**实体不是第一层失败就扔掉。**

```text
第 1～3 层都仍带 entity_id。
只有这几层全部为 0，才说明该实体在目标资料范围内可能缺块、元数据遗漏，或实体识别不可靠。
这时才退回意图范围，避免“严格条件不命中 = 整题无答案”。
```

当某个带 `entity_id` 或 `element` 的层命中至少一条时，当前策略用 `ANY_HIT` 停止，不再继续放宽。没有硬条件的普通类别层，则按 `TARGET_REACHED` 继续补候选。

---

## 6. 实体目录：它是什么，怎么来的

文件：[entity_catalog.jsonl](../../resources/genshin_seed_v7/config/entity_catalog.jsonl)

它是实体识别的核心字典数据源，当前有 6562 条，包含：

```text
主线任务、材料、成就、章节、武器、角色、圣遗物套装、少量 lore
```

它不是“6562 条成就名”，也不是每个 Chunk 一条。它是按：

```text
entity_type + entity_id + source_document
```

去重后的实体/来源文档目录。

真实角色记录示例：

```json
{
  "entity_type": "character",
  "entity_id": "10000002",
  "entity_name": "神里绫华",
  "aliases": ["神里绫华", "Kamisato Ayaka", "绫华"],
  "category": "character",
  "source_document": "characters/avatar_10000002_神里绫华.md",
  "source_version": "genshin_seed_v6"
}
```

### 6.1 每个字段的来源

| 字段 | 提取依据 |
|---|---|
| `entity_type` | 源文件所在目录和文件名模式，例如 `characters` → `character` |
| `entity_id` | 文件名正则提取的稳定游戏 ID，例如 `avatar_10000002_...md` → `10000002` |
| `entity_name` | Markdown 文档标题 |
| `aliases` | 中文正式名、`EN Name`，以及角色名末两字的自动别名 |
| `category` | 目录类别映射 |
| `source_document` | 相对源文件路径 |
| `source_version` | 上游知识文档版本，而不是当前索引版本 |

`source_version = genshin_seed_v6` 的意思是：v7 的结构化检索产物是从 v6 原始知识文档重建而来。它只用于数据血缘追踪，不参与检索。

初始构建脚本是 [build_genshin_v7.py](../../scripts/genshin/build_genshin_v7.py)，后续重建并补角色末两字别名的脚本是 [rebuild_entity_catalog_from_chunks.py](../../scripts/genshin/rebuild_entity_catalog_from_chunks.py)。

### 6.2 当前实体匹配并不“理解语义”

`EntityResolver` 的真实规则是：

```text
问题和别名先去空白、转小写
→ question.contains(alias)
→ 每个实体保留最长命中的别名
→ 全局只保留最长匹配候选
→ 唯一最长 = MATCHED；并列最长 = AMBIGUOUS；没有 = NOT_FOUND
```

因此：

```text
“绫华元素战技” → 可命中“绫华”。
“草神技能”     → 目前不会命中纳西妲，因为目录没有“草神”别名。
```

角色、武器、材料是高价值实体；任务、成就、章节名称在用户明确说出正式标题时也有价值，但不是主要检索手段。目录扫描约 6500 条内存记录，当前不是性能瓶颈；更值得关注的是高频别名缺失、短别名歧义和语义覆盖不足。

---

## 7. `element` 是后加的业务元数据

`category` 与 `doc_type` 来自意图路由规则；`element` 不属于意图树本身。

```text
用户明确说“冰系 / 冰元素 / 冰属性”
→ ElementFacetResolver 解析为 cryo
→ 仅当前意图属于 character 时，Plan 才加入 element=cryo
```

只说“绫华技能是什么”不会自动追加 `cryo`，即使系统知道绫华是冰系；因为不能把隐含推断当成硬过滤条件。

这个字段是在发现“冰系角色有哪些”会混入其他元素角色后补加的。它不是 LLM 从正文猜的，而是离线脚本 [enrich_genshin_v7_character_elements.py](../../scripts/genshin/enrich_genshin_v7_character_elements.py) 从本地 AnimeGameData 的角色、技能仓与技能配置确定性关联：

```text
Avatar ID → skillDepotId → energySkill → costElemType
Ice → cryo
```

并把 `element` 写入全部相关角色 Chunk 的元数据，再同步到 Milvus 与 ES。

元数据字段的演进原则：

```text
出现稳定、重复、会影响检索边界的业务条件
→ 有可靠数据来源
→ 新增字段
→ 只在用户明确提出时作为硬过滤
→ 用评测验证是否减少错召回
```

未来可能增加稀有度、武器类型、地区、文档版本等，但不能因为“可能有用”就堆字段。

---

## 8. 原项目与本次改造的边界

原项目并非完全没有元数据概念：

```text
KnowledgeChunk 有 metadata 容器。
RetrieveRequest 已预留 metadataFilters 字段。
```

但改造前的 `MilvusRetrieverService` 没有把 `metadataFilters` 组装成实际的 Milvus filter expression；也没有完整的“意图 → 实体 → Plan → 双通道同步执行 → 分层放宽”链路。

本次没有重写原有底座，而是在其上完成：

```text
已有：RAG 主流程、多通道框架、Milvus / ES 执行入口。
新增：结构化元数据、实体目录、路由表、RetrievalPlan、渐进执行器、评测闭环。
```

行业内“向量检索前按 metadata 缩小范围”是标准能力；区别不在“是否有字段”，而在字段是否来自可靠业务数据、规则是否可维护、两个通道是否一致、放宽策略是否可验证。

---

## 9. 候选收口：RRF 不会每次都触发

`CandidatePoolLimiterPostProcessor` 的职责不是“每次融合 Milvus 和 ES”。正常合并、去重沿用已有链路。

它是一个保护阀：

```text
跨通道去重后候选数 ≤ 配置上限
→ 原样交给 Rerank

候选数 > 配置上限（当前设计为大候选池场景）
→ 用 RRF 按各通道名次重新排序
→ 截断候选池
→ 再交给 Rerank
```

RRF 的直觉是：同一 Chunk 如果同时在多个通道排名靠前，就更值得优先保留；它避免直接比较 Milvus 相似度与 ES 的 BM25 分数，因为两者不在同一量纲。

---

## 10. 当前边界与后续方向

这些不是“已经解决”的能力，面试和文档中应如实说明：

1. **同类别多实体**：`AMBIGUOUS` 当前不会拆成多个实体 Plan，也不会使用 OR 条件；保守退回意图与混合检索。
2. **高频别名治理**：已通过受控人工别名表补充“草神 → 纳西妲”，并增加实体解析回归测试；新增别名仍应走数据治理，不能让 LLM 临时猜测。
3. **领域外问题**：顶层知识库路由已支持显式 `UNKNOWN`，并避免继续进入受管原神/商品库；但尚未建立统一分数口径的检索后证据门控，不能宣称具备完整拒答能力。
4. **上下文化检索**：Chunk 标题、章节路径、版本语境的 Contextual Retrieval 仍是后续独立改造，不能提前写成已经完成。
5. **元数据规模**：当前 6500 余实体的内存别名扫描并非性能瓶颈；未来首先优化匹配质量，不做无证据的性能微调。

---

## 11. 推荐学习顺序

### 第一轮：能讲清主线

1. `RetrievalPlan`、`IntentRetrievalPlan`、`RetrievalFilterLevel`、`EntityResolution`
2. `RetrievalPlanService.buildPlan()`
3. `EntityCatalogService`、`EntityResolver`
4. `IntentRouteRuleService`、`ElementFacetResolver`

目标：能用“绫华冰系技能”解释一张 Plan 是如何生成的。

### 第二轮：能讲清执行

1. `ProgressivePlanStopPolicy`
2. `ProgressiveMilvusPlanRetriever`
3. `ProgressiveKeywordEsPlanRetriever`
4. `MilvusRetrieverService`、`KeywordEsGateway`

目标：能说明为什么两个检索器不自己判断路由，而是消费同一份 Plan。

### 第三轮：能讲清效果与边界

1. `CandidatePoolLimiterPostProcessor`
2. 评测接口、Gold 标注、A/B 报告
3. 当前未解决的别名、领域外拒答、Contextual Retrieval

目标：不只会说“加了元数据”，还能回答“如何证明减少错召回、还有什么没做”。

---

## 12. 面试版 30 秒表述

> 我在原有多通道 RAG 检索框架上增加了查询路由与检索计划层：意图分类确定资料类别和文档类型，规则实体识别补充具体对象 ID，随后把它们生成可记录的多层 metadata Plan，由 Milvus 向量检索和 ES 关键词检索共同执行。严格实体或元素范围命中后停止，未命中才逐级放宽，避免精确问题在全库中被无关 Chunk 稀释；同时保留非实体兜底路径，防止别名缺失导致直接无答案。
