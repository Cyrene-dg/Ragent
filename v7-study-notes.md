# v7 Plan 检索主链路 — 学习笔记

> 学习范围：`41282a8..HEAD`，53 个 Java 类中主链路的 7 个核心类 + 4 个数据模型类<br>
> 日期：2026-07-27

---

## 一、完整数据流（一幅图）

```
用户问题
  │
  ├─→ EntityResolver (String.contains, 不调 LLM)
  │   └─→ EntityResolution: NOT_FOUND / AMBIGUOUS / MATCHED
  │
  ├─→ DefaultIntentClassifier (调 LLM 语义理解)
  │   └─→ List<NodeScore>: [(intentCode, score), ...] 最多 3 个, score >= 0.35
  │
  ├─→ IntentRouteRuleService (查 JSON 配置表)
  │   └─→ routeFilters: {category, doc_type, element}
  │
  └─→ RetrievalPlanService.buildPlan()  ← 三条线汇聚
      └─→ RetrievalPlan
          └── IntentRetrievalPlan × N
              └── RetrievalFilterLevel × M  (从紧到松)
                  ├─ Layer 1: {entity_id, entity_type, category, doc_type, element}
                  ├─ Layer 2: {entity_id, entity_type, category, element}
                  ├─ Layer 3: {entity_id, entity_type, category}
                  ├─ Layer 4: {category, doc_type, element}          ← entity 已丢弃
                  ├─ ...
                  └─ Layer N: {}                                     ← 全库向量检索
                          │
                          ↓
            ProgressiveMilvusPlanRetriever    (向量检索执行器)
            ProgressiveKeywordEsPlanRetriever (关键词检索执行器)
                          │
                          ↓
            CandidatePoolLimiterPostProcessor (RRF 融合 + 截断)
                          │
                          ↓
                        Rerank
                          │
                          ↓
                       送 LLM 回答
```

---

## 二、已学文件清单

### 阶段 1：数据模型（全部完成）

| 文件 | 行数 | 一句话 |
|------|------|--------|
| `RetrievalPlan.java` | 46 | 顶层总计划：question + 容量参数 + 子计划列表 |
| `IntentRetrievalPlan.java` | 57 | 中层子计划：一个意图 + 路由 filter + 实体 + 分层策略 |
| `RetrievalFilterLevel.java` | 60 | 底层：一层 filter 的定义（顺序、过滤字段、停止条件） |
| `EntityResolution.java` | 82 | 底层：实体匹配结果（NOT_FOUND/AMBIGUOUS/MATCHED） |
| `EntityResolutionStatus.java` | 25 | 枚举：MATCHED / NOT_FOUND / AMBIGUOUS |

### 阶段 2：计划生成（全部完成）

| 文件 | 行数 | 一句话 |
|------|------|--------|
| `DefaultIntentClassifier.java` | 415 | LLM 意图分类：加载意图树 → 拼 prompt → 调大模型 → 解析 JSON |
| `EntityCatalogService.java` | 143 | 加载实体目录 JSONL 文件（懒加载 + double-checked locking） |
| `EntityResolver.java` | 173 | 规则匹配：String.contains 遍历所有别名，最长匹配策略 |
| `RetrievalPlanService.java` | 329 | 三线汇聚：意图+实体+路由 → 构建分层 plan（主干 + 11 个零件方法） |

### 额外看的配置/数据文件

| 文件 | 说明 |
|------|------|
| `resources/.../entity_catalog.jsonl` | 实体目录（6562 条游戏成就名，人工/Python 维护） |
| `resources/.../intent_routes.json` | 意图路由规则（intentCode → metadataFilters 的 JSON） |
| `RAGConstant.java` | MAX_INTENT_COUNT=3, INTENT_MIN_SCORE=0.35 |

---

## 三、关键技术点

### 意图 VS 实体：两套完全不同的识别方式

| | 意图分类 | 实体识别 |
|------|---------|---------|
| 识别引擎 | LLM（大模型） | 代码规则（String.contains） |
| 速度 | 慢（500~2000ms） | 极快（< 5ms） |
| 原理 | 语义理解 | 字符串包含 |
| 用途 | 决定搜哪个知识域 | 锁定具体实体 ID |
| 多结果处理 | 保留并排序，逐个执行 | 只取最长匹配一个，多则 AMBIGUOUS 退化为无实体 |

### 分层渐进检索的核心思想

```
不是"固定多少块"，而是"从紧到松逐层放宽，够了就停"：

Layer 1: entity_id + 全量 route → 搜出 N 条
Layer 2: entity_id + category     → 搜出 M 条（积攒）
...
如果 N+M >= targetCandidateCount → 立即停止
如果层走完还不够 → 继续往下一层
如果走到 {} 层还不够 → 那已经全库搜了，尽力了
```

停止策略：有 entity_id 或 element → ANY_HIT（命中即停）；否则 → TARGET_REACHED（凑够目标数）

### 值得学习的工程实践

- `record` 做不可变数据载体
- `volatile` + `synchronized` + double-checked locking 做线程安全懒加载
- 静态工厂方法（`EntityResolution.notFound()`）替代裸露的 `new`
- `@JsonProperty` 处理 JSON 下划线和 Java 驼峰映射
- `LinkedHashMap` 保持 filter 字段顺序
- `Map.copyOf()` / `List.copyOf()` 做防御性拷贝
- `try-with-resources` 自动关流
- `List.of()` 返回不可变空列表（非 null）
- 功能开关 + 兜底逻辑（entity fallback）

---

## 四、待学习清单

### 阶段 2 剩余

| 文件 | 预计行数 | 内容 |
|------|---------|------|
| `IntentRouteRuleService.java` | ~90 | 懒加载 intent_routes.json，提供 `findByIntentCode(intentCode)` |
| `IntentResolver.java` | ~270 | 拆分用户问题为子问题、调用分类器、按阈值+数量过滤、筛选 KB/MCP 意图 |
| `ElementFacetResolver.java` | ~60 | 从用户问题解析 element（火/水/风），仅 category=character 时启用 |
| `IntentHybridRecallService.java` | ~100 | 两阶段意图预筛选：叶子节点太多时先用关键词+向量粗筛 |

### 阶段 3：计划执行

| 文件 | 预计行数 | 内容 |
|------|---------|------|
| `ProgressiveMilvusPlanRetriever.java` | ~274 | 按 plan 在 Milvus 向量库逐层渐进检索，命中即停（已看过思路，未注释） |
| `ProgressiveKeywordEsPlanRetriever.java` | ~187 | 同上，查 ES 关键词索引 |
| `ProgressivePlanStopPolicy.java` | ~30 | 判断某层后是否停止（是否命中了足够结果） |

### 阶段 4：结果收口

| 文件 | 预计行数 | 内容 |
|------|---------|------|
| `CandidatePoolLimiterPostProcessor.java` | ~165 | RRF 融合向量+ES 两路结果，按 maxCandidatePool 截断，输出最终候选池 |

### 非主线但相关的修改类

| 文件 | 作用 |
|------|------|
| `MultiChannelRetrievalEngine.java` | 接收 plan，分发给各 searchChannel 执行 |
| `SearchContext.java` | 把 plan 挂进检索上下文 |
| `KeywordEsGateway.java` | ES 检索入口，支持元数据过滤 |
| `IntentDirectedSearchChannel.java` | 意图导向检索通道（V7 新通道） |
| `KnowledgeChunk*` 系列 | chunk 元数据（entity_id/category/doc_type）的 CRUD |
| `MilvusVectorStoreService.java` | 写入 chunk 时同步元数据到 Milvus |

---

## 五、下次学习建议

**推荐顺序**（按数据流，不走回头路）：

1. `IntentRouteRuleService`（短，90行，理解路由表工作机制）
2. `IntentResolver`（中，270行，理解子问题拆分 + 意图筛选全流程）
3. `ElementFacetResolver`（短，理解 element 维度）
4. `ProgressivePlanStopPolicy`（超短，30行，理解停止逻辑）
5. `ProgressiveMilvusPlanRetriever`（重看，加注释）
6. `ProgressiveKeywordEsPlanRetriever`（看，理解 ES 对称实现）
7. `CandidatePoolLimiterPostProcessor`（看，理解 RRF 融合 + 截断）

这份笔记保存在项目根目录 `v7-study-notes.md`。
