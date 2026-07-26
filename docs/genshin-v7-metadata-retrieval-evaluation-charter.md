# 原神 v7 元数据检索评测章程（第一版）

## 1. 目标

验证本次元数据检索改造是否解决以下问题：

1. 用户询问明确角色或武器时，检索结果不会混入其他实体资料；
2. 用户询问“冰系角色”等集合问题时，`element` 元数据能缩小范围，且不会因过滤过严漏掉资料。

本轮只评估“检索到了什么”，不评估大模型最终回答的表达质量。

## 2. 对照方案

| 方案 | 含义 |
| --- | --- |
| Baseline | v7 同一份数据、同一模型与同一 Rerank，但关闭 `element` 条件 |
| Candidate | 当前元数据检索方案，开启 `element` 条件 |

原则：一次只改变 `element` 这一项；实体目录、意图树、Milvus、ES 与 Rerank 均保持一致。

后续需要补充可配置开关，例如：

```yaml
rag:
  metadata-retrieval:
    element-facet-enabled: false # Baseline；true 为 Candidate
```

这样无需切换 Git 版本、重导数据或维护两套数据库。

## 3. 测试集组成

先做 10 道“试运行集”，确认流程与标注口径正确后，再扩展到 50～60 道正式集。

| 类型 | 首轮数量 | 示例 | 主要验证 |
| --- | ---: | --- | --- |
| 单实体事实题 | 3 | 绫华元素战技、狼末被动 | 实体边界、Hit@5、MRR |
| 别名题 | 2 | 绫华、狼末 | 别名是否正确落到实体 |
| 元素集合题 | 3 | 冰系、火系、风系角色 | `element` 过滤、实体覆盖率 |
| 无实体泛问 | 1 | 五星大剑的被动通常有哪些特点 | 不应被错误硬过滤 |
| 领域外诊断题 | 1 | 银狼的技能是什么 | 记录当前误路由，不计入主分数 |

正式集建议：

```text
20 道单实体事实题
10 道别名题
10 道无实体泛问
10 道元数据组合题
5～10 道集合题或领域外诊断题
```

## 4. 标注规则

每道题由人工确认；自动生成的候选只能作为草稿，不能直接充当 Gold 标注。

普通事实题示例：

```json
{
  "id": "char_skill_001",
  "query": "神里绫华的元素战技是什么？",
  "type": "single_entity",
  "answerable": true,
  "gold_chunk_keys": [
    "characters/avatar_10000002_神里绫华.md#技能对应块"
  ],
  "expected_entity_ids": ["10000002"],
  "expected_filters": {
    "category": "character",
    "entity_id": "10000002"
  }
}
```

集合题示例：

```json
{
  "id": "element_cryo_001",
  "query": "原神中冰系角色有哪些？",
  "type": "entity_collection",
  "answerable": true,
  "expected_filters": {
    "category": "character",
    "element": "cryo"
  },
  "expected_entity_ids": ["若干冰系角色 ID"]
}
```

`gold_chunk_keys` 应使用 Chunk 的稳定 `logical_chunk_key`，而不是运行时数据库 Snowflake ID；后者会随导入或重建变化。

## 5. 执行流程

```text
冻结测试题与人工标注
    ↓
Baseline：关闭 element 条件，逐题保存 Top20、过滤计划与 Trace 耗时
    ↓
Candidate：开启 element 条件，保存同样数据
    ↓
脚本计算指标
    ↓
人工复核新旧结果不同的题目
    ↓
输出报告与原始结果，确保可复跑
```

每次运行先预热 3 次；正式统计时每题运行 5 次。延迟同时记录中位数与 P95，以避免首次加载和偶发网络抖动影响结论。

## 6. 指标与口径

| 指标 | 口径 | 是否核心 |
| --- | --- | --- |
| Hit@5 | 前 5 条中是否至少有一个 Gold Chunk | 是 |
| MRR@10 | 第一条 Gold Chunk 的倒数排名 | 是 |
| Recall@20 | Gold Chunk 是否进入前 20 | 是，防漏召回 |
| 集合实体覆盖率@20 | 前 20 覆盖的预期实体数 / 全部预期实体数 | 是，仅集合题 |
| Top5 错误召回率 | 前 5 中错实体、错元素、错类别的比例 | 是 |
| 检索 P95 | Plan、ES、Milvus、Rerank、检索总耗时 | 是 |
| LLM 最终答案质量 | 暂不统计 | 否 |
| 生成总耗时、P99、并发压测 | 暂不统计 | 否 |
| nDCG、CI 自动门禁 | 暂不统计 | 否 |

第一轮不预设“必须提升 X%”的门槛。先得到真实基线，再决定合理阈值。当前唯一硬性要求是：元素条件开启后，不得系统性漏掉核心 Gold Chunk。

## 7. 评测产物

每次评测输出固定产物：

```text
tests/eval_rag/data/genshin_v7_metadata_gold.jsonl
tests/eval_rag/artifacts/metadata_eval/<run-id>/baseline_top20.jsonl
tests/eval_rag/artifacts/metadata_eval/<run-id>/candidate_top20.jsonl
tests/eval_rag/artifacts/metadata_eval/<run-id>/metrics.json
tests/eval_rag/artifacts/metadata_eval/<run-id>/summary.md
```

报告应使用可复核的自然语言结论，例如：

> 在 X 道人工标注题中，元素集合题的错误跨元素召回从 X 降至 X；Hit@5 为 X → X，Recall@20 为 X → X；检索 P95 为 Xms → Xms。未发现单实体题因元素过滤导致的 Gold Chunk 漏召回。

## 8. 分工

| 角色 | 职责 |
| --- | --- |
| 项目负责人 | 确认题目像真实用户会问的问题，并人工确认 Gold Chunk 是否能支撑答案 |
| 开发实现 | 提供策略开关、运行脚本、结果导出、指标计算与差异定位 |
| 共同复核 | 审阅新旧方案存在差异的题目，判断指标变化是否真实有意义 |

## 9. 当前状态与下一步

- 当前已完成：v7 元数据检索、元素元数据回填、ES/Milvus 同步过滤及运行态验证。
- 当前未开始：策略开关、10 道试运行集、评测运行器与正式指标统计。
- 下一步：先实现策略开关并建立 10 道人工复核草稿集，跑通完整评测链路后再扩展样本规模。
