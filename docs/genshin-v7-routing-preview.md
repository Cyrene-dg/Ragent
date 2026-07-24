# Genshin v7 路由与切块预览（只读 Demo）

> 目的：用现有 v6 原始 Markdown 展示改造后的数据形态。本文不修改 MySQL、Redis、Milvus、Elasticsearch，也不删除现有 v4/v6 数据。

## 1. 新的意图树：只描述问题类型

```text
原神问答
├─ 角色
│  ├─ 基础资料
│  ├─ 技能与命座
│  └─ 背景与故事
├─ 武器
│  └─ 属性、被动与突破
├─ 圣遗物
│  └─ 属性与套装资料
├─ 任务与剧情
│  ├─ 前置与流程
│  └─ 设定与背景
├─ 成就
│  └─ 条件与前置
└─ 材料
   └─ 获取与用途
```

角色名、武器名、任务名不再作为意图叶子；它们进入实体索引。

> 这棵树刻意没有写“配装建议”“配队建议”“培养路线”。当前语料的角色文档主要包含基础、技能、命座与故事，尚没有可验证的攻略推荐正文；在补充可靠攻略数据之前，不能把不存在的知识能力伪装成树节点。

## 2. Demo A：神里绫华（大角色文档，结构化拆分）

**原始文件：** `resources/genshin_seed_v6/docs/characters/avatar_10000002_神里绫华.md`

当前版本把整篇角色资料存为一个 Chunk。v7 预览会按现有 Markdown 的 `## Basic`、`## Skills`、`## Constellations`、`## Stories` 分段，并继续按 `###` 小节处理超长故事内容。

实体记录：

```json
{
  "entity_type": "character",
  "entity_id": "10000002",
  "name": "神里绫华",
  "aliases": ["绫华", "Kamisato Ayaka", "Ayaka"],
  "source_document": "avatar_10000002_神里绫华.md"
}
```

生成后的 Chunk 示意：

```json
{
  "chunk_id": "character:10000002:profile",
  "section_path": ["神里绫华", "基础资料"],
  "category": "character",
  "doc_type": "character_profile",
  "entity_type": "character",
  "entity_id": "10000002",
  "content_preview": "ID: 10000002；名称：神里绫华；英文名：Kamisato Ayaka；五星、单手剑；社奉行；称号：白鹭霜华。"
}
```

```json
{
  "chunk_id": "character:10000002:skills",
  "section_path": ["神里绫华", "技能"],
  "category": "character",
  "doc_type": "character_skill",
  "entity_type": "character",
  "entity_id": "10000002",
  "content_preview": "普通攻击、神里流·冰华、神里流·霰步、神里流·霜灭及其效果说明。"
}
```

```json
{
  "chunk_id": "character:10000002:constellations",
  "section_path": ["神里绫华", "命之座"],
  "category": "character",
  "doc_type": "character_constellation",
  "entity_type": "character",
  "entity_id": "10000002",
  "content_preview": "霜杀墨染樱、三重雪关扉等 1 至 6 命效果。"
}
```

问题 `“神里绫华大招是什么？”` 的计划不是“找名为神里绫华的意图”，而是：

```text
实体：character / 10000002（强筛选）
主题：角色 > 技能与命座（高置信）
优先过滤：doc_type in [character_skill, character_constellation]
结果不足时：放宽 doc_type，但保留 entity_id
```

## 3. Demo B：黎明破晓之史（中等文档，按语义段保留）

**原始文件：** `resources/genshin_seed_v6/docs/weapons/weapon_15515_黎明破晓之史.md`

该文档包括基础资料、描述、R1 至 R5 被动效果和突破信息。预览中不按固定字符数生切，而是按 `Basic / Description / Passive / Promotion` 分段；`Passive` 太长时在 R1-R3 与 R4-R5 的边界拆开。

```json
{
  "category": "weapon",
  "doc_type": "weapon_passive",
  "entity_type": "weapon",
  "entity_id": "15515",
  "section_path": ["黎明破晓之史", "被动效果", "R1-R3"],
  "content_preview": "渐起的晓风：脱离战斗后获得增伤；普通攻击、元素战技、元素爆发命中后叠加对应类别增伤。"
}
```

短的基础信息与描述可合并为一块；被动效果按精炼等级分段，避免用户问 R1 时带入 R5 的大量无关内容。

## 4. Demo C：被遗忘的纸带（短文档，不拆）

**原始文件：** `resources/genshin_seed_v6/docs/achievements/achievement_81386_被遗忘的纸带.md`

该文件只有成就 ID、所属组和触发说明，保持一文一块更好：

```json
{
  "category": "achievement",
  "doc_type": "achievement_condition",
  "entity_type": "achievement",
  "entity_id": "81386",
  "section_path": ["被遗忘的纸带"],
  "content": "帮助布罗意回收并整理全部的「水文观测数据」。"
}
```

这类数据不应该为了凑到固定 Token 数而和其他成就拼接。

## 5. Demo D：旧日的阴云（任务流程，保持步骤语义）

**原始文件：** `resources/genshin_seed_v6/docs/main_quests/main_quest_10120_旧日的阴云.md`

该文件有任务 ID、章节“浪花不再归海”、摘要及两条步骤。当前长度不大，先保持为一个 Chunk；如果未来某个任务含大量步骤，再按连续步骤组拆分。

```json
{
  "category": "quest",
  "doc_type": "quest_flow",
  "entity_type": "main_quest",
  "entity_id": "10120",
  "chapter_id": "2019",
  "section_path": ["旧日的阴云", "任务流程"],
  "content_preview": "琴委托旅行者调查劳伦斯家族；接近成员受挫后，安柏推荐寻找优菈。"
}
```

## 6. 关键边界

- `entity_id`、文档类型、章节路径是 Chunk 的客观元数据，不等于一个唯一意图。
- 语义意图是运行时生成的检索偏好；它可以是一个主题、多个主题，或低置信时不做硬过滤。
- 新数据会进入独立的新 KB 和新索引；旧树、旧 Chunk、旧索引在完成 A/B 验证前保持不变。
- 此 Demo 没有调用 LLM，也没有调用 embedding 服务。
