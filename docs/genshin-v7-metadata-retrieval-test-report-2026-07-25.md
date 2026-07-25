# 原神 v7 元数据检索链路测试报告

> 测试日期：2026-07-25
> 对应提交：`07ddb39 实现实体优先检索与命中停止策略`
> 验证方式：前端真实提问 + 后端运行日志核对
> 范围：实体识别、意图分类、检索 Plan、ES / Milvus 分层检索；**不以生成文本本身作为正确性金标评测**。

## 1. 本次验证的目标

验证唯一实体被识别后，系统是否会将 `entity_id` 作为检索边界，避免为了凑满候选数量而把其他实体资料混入最终上下文。

本次规则如下：

```text
实体范围层（metadata 中含 entity_id）命中 >= 1 个 Chunk
→ 当前检索通道停止放宽

实体范围层为 0
→ 才允许继续下一层

没有唯一实体
→ 沿用原有“累计候选达到 20 条才停止”的逐级放宽策略
```

ES 与 Milvus 使用同一份 `RetrievalPlan` 中的层级和停止条件，但分别按 BM25 与向量相似度执行检索。

## 2. 测试环境

| 项目 | 值 |
| --- | --- |
| 知识库 | 原神 v7（`kbId=2080272025011851264`） |
| Milvus Collection | `genshinroutingv7` |
| ES 索引 | `ragent_chunk_v1` |
| 后端端口 | `9090` |
| 前端端口 | `5173` |
| 单元测试 | 17 passed / 0 failed（Plan、ES、Milvus 相关测试） |

## 3. 真实问题验证结果

| 编号 | 测试问题 | 实体识别 / 意图 | ES 与 Milvus 行为 | 结论 |
| --- | --- | --- | --- | --- |
| 1 | 狼的末路被动效果是什么？ | `weapon/12502`；武器详情 | 均在 `entity + category + doc_type` 第 1 层命中 1 个 Chunk 并停止 | 通过：未混入其他武器 |
| 2 | 神里绫华的元素战技是什么？ | `character/10000002`；角色技能 | 均在第 1 层命中 2 个 Chunk 并停止 | 通过：未放宽到其他角色 |
| 3 | 五星大剑的被动通常有哪些特点？ | 未匹配唯一实体；武器详情 | 均在 `category=weapon + doc_type` 范围取得 Top 20 | 符合无实体查询策略 |
| 4 | 原神中冰系角色有哪些？ | 未匹配唯一实体；角色资料 | 初测在 `category=character + doc_type=character_profile` 范围取得 Top 20 | 已转化为元素元数据改造项，见第 7 节 |
| 5 | 银狼的技能是什么？ | 未匹配唯一实体；被误判为角色技能 | 在原神角色技能范围取得 Top 20 | 暴露领域外拒答缺口 |

## 4. 关键日志证据

### 4.1 唯一实体：狼的末路

```text
实体：weapon / entity_id=12502
意图：genshin_v7_weapon_detail

Milvus：entity-category-doc-type，returned=1，executedLevels=1
ES：entity-category-doc-type，returned=1，levels=1
```

检索条件为：

```text
category=weapon
AND entity_type=weapon
AND entity_id=12502
AND doc_type IN [weapon_profile, weapon_passive]
```

两路检索均未进入“所有武器 + 被动”层，验证了“精确实体命中即停止跨实体放宽”规则。

### 4.2 唯一实体：神里绫华

```text
实体：character / entity_id=10000002
意图：genshin_v7_character_skill

Milvus：entity-category-doc-type，returned=2，executedLevels=1
ES：entity-category-doc-type，returned=2，levels=1
```

两路均限定在绫华的技能/命座资料范围内。

### 4.3 无唯一实体：五星大剑被动

```text
实体：NOT_FOUND
意图：genshin_v7_weapon_detail

过滤：category=weapon
AND doc_type IN [weapon_profile, weapon_passive]

ES returned=20
Milvus returned=20
```

该问题本身询问的是武器集合，不应强制绑定某个 `entity_id`；当前行为符合预期。

## 5. 本次确认的结论

1. 实体识别、意图识别和元数据 Plan 已完成串联。
2. ES 与 Milvus 都会读取同一份 Plan 中的停止条件；不会出现 ES 停止、Milvus 继续跨实体放宽的边界不一致。
3. 当唯一实体的精确层返回 1 个或多个 Chunk 时，候选数量不足 20 不再触发跨实体补候选。
4. 无唯一实体的问题仍保留原来的类别级检索和候选池策略，避免因过滤过严造成漏召回。

## 6. 已发现的后续改造项

### P1：领域外 / 证据不足拒答

“银狼”不属于原神实体目录，但当前分类器仅根据“技能”选择了原神角色技能意图，导致系统检索到无关的原神角色资料。

目标：当问题包含疑似实体、实体目录未命中且检索证据不足时，拒绝回答或明确提示当前知识库不覆盖该对象，而不是使用同类别资料强行作答。

### 已完成：角色元素元数据

“冰系角色”是集合查询，不应该绑定单个 `entity_id`；但当前 Chunk 元数据没有 `element` 字段，无法构造：

```text
category=character
AND element=cryo
```

已完成的实现与验证：

1. 从本机 `AnimeGameData` 的角色、技能组与元素爆发数据推导元素，不依赖正文关键词或 LLM 猜测；115 个固定元素角色映射成功，旅行者等 6 个无固定元素的实体保持缺失。
2. 为 1,233 个角色 Chunk 写入 `element`；例如神里绫华的 9 个运行态 Chunk 均为 `element=cryo`。
3. ES 为 `element` 建立 `keyword` mapping；回填后原神 v7 知识库中 ES 的 `element=cryo` Chunk 数为 189。
4. 查询中出现明确短语（如“冰系”“冰元素”）时，角色意图的首层过滤变为：

```text
category=character
AND element=cryo
AND doc_type=...
```

元素范围一旦命中即停止跨元素放宽；只有该元素范围完全为空时，才退回原有类别级 Plan，避免用其他元素角色把候选池补满。
5. 修复文档重建时对百炼 `text-embedding-v4` 一次提交超过 10 个输入会报错的问题：现在按 10 条分批 Embedding，并在所有向量生成成功后才替换旧 Milvus 文档索引，避免失败时误删已有向量。

### P2：归一化规则重复替换

“神里绫华的元素战技”在归一化日志中曾出现“元素元素战技”，最终改写结果仍正确。后续应检查同义词映射规则，避免重复替换增加无谓噪声。

## 7. 下一步建议

1. 先实现并验证“领域外 / 证据不足拒答”；
2. 建立小型人工标注集，对比新旧方案的 Hit@5、MRR、错误召回数量和 P95 延迟；
3. 在评测数据支持下，再决定是否继续实现多实体 Plan、实体歧义候选保留和对话历史辅助消歧。
