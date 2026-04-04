#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import shutil
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path


def load_json(path: Path):
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def dump_json(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def clean_markup(text: str) -> str:
    if not text:
        return ""
    text = text.replace("\\n", "\n")
    text = re.sub(r"</?color[^>]*>", "", text, flags=re.IGNORECASE)
    text = re.sub(r"</?[biu]>", "", text, flags=re.IGNORECASE)
    text = re.sub(r"\$[A-Z_]+", "", text)
    return text.strip()


def visible(text: str) -> bool:
    return bool(text and text.strip() and "$HIDDEN" not in text)


def safe_name(text: str) -> str:
    text = re.sub(r"[^0-9A-Za-z\u4e00-\u9fff\-_]+", "_", text or "")
    text = re.sub(r"_+", "_", text).strip("_")
    return text[:64] or "untitled"


def write_md(path: Path, title: str, sections: list[tuple[str, str]]):
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [f"# {title}", ""]
    for sec_title, sec_body in sections:
        lines.extend([f"## {sec_title}", sec_body.strip() if sec_body.strip() else "(empty)", ""])
    path.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")


class TextResolver:
    def __init__(self, chs: dict[str, str], en: dict[str, str]) -> None:
        self.chs = chs
        self.en = en

    @staticmethod
    def _alts(value: int):
        yield str(value)
        if value >= (1 << 31):
            yield str(value - (1 << 32))
        else:
            yield str(value + (1 << 32))

    def get(self, hash_value, lang: str = "chs") -> str:
        if hash_value is None:
            return ""
        table = self.chs if lang == "chs" else self.en
        if isinstance(hash_value, str):
            return clean_markup(table.get(hash_value, ""))
        if isinstance(hash_value, int):
            for key in self._alts(hash_value):
                if key in table:
                    return clean_markup(table[key])
        return ""


RARITY_MAP = {
    "QUALITY_ORANGE": "5-star",
    "QUALITY_PURPLE": "4-star",
    "QUALITY_BLUE": "3-star",
    "QUALITY_GREEN": "2-star",
    "QUALITY_WHITE": "1-star",
}

WEAPON_TYPE_MAP = {
    "WEAPON_SWORD_ONE_HAND": "Sword",
    "WEAPON_CLAYMORE": "Claymore",
    "WEAPON_POLE": "Polearm",
    "WEAPON_BOW": "Bow",
    "WEAPON_CATALYST": "Catalyst",
}

MATERIAL_PRIORITY = [
    "MATERIAL_AVATAR_MATERIAL",
    "MATERIAL_AVATAR_TALENT_MATERIAL",
    "MATERIAL_TALENT",
    "MATERIAL_ELEM_CRYSTAL",
    "MATERIAL_EXP_FRUIT",
    "MATERIAL_WEAPON_EXP_STONE",
    "MATERIAL_RELIQUARY_MATERIAL",
    "MATERIAL_FOOD",
    "MATERIAL_CONSUME",
    "MATERIAL_WIDGET",
]


def md_list(items: list[str]) -> str:
    rows = [f"- {x}" for x in items if x]
    return "\n".join(rows) if rows else "- (none)"


def build_seed(args):
    data_root = Path(args.data_root).resolve()
    output_root = Path(args.output_root).resolve()
    docs_root = output_root / "docs"
    config_root = output_root / "config"

    if args.clean and output_root.exists():
        shutil.rmtree(output_root)
    output_root.mkdir(parents=True, exist_ok=True)

    text_resolver = TextResolver(
        load_json(data_root / "TextMap" / "TextMapCHS.json"),
        load_json(data_root / "TextMap" / "TextMapEN.json"),
    )
    excel = data_root / "ExcelBinOutput"

    avatars = load_json(excel / "AvatarExcelConfigData.json")
    skill_depot = {x["id"]: x for x in load_json(excel / "AvatarSkillDepotExcelConfigData.json")}
    skills = {x["id"]: x for x in load_json(excel / "AvatarSkillExcelConfigData.json")}
    talents = {x["talentId"]: x for x in load_json(excel / "AvatarTalentExcelConfigData.json")}
    fetter_info = {x["avatarId"]: x for x in load_json(excel / "FetterInfoExcelConfigData.json")}
    fetter_story = defaultdict(list)
    for row in load_json(excel / "FetterStoryExcelConfigData.json"):
        fetter_story[row.get("avatarId", 0)].append(row)

    weapons = load_json(excel / "WeaponExcelConfigData.json")
    weapon_promote = defaultdict(list)
    for row in load_json(excel / "WeaponPromoteExcelConfigData.json"):
        weapon_promote[row.get("weaponPromoteId", 0)].append(row)
    affix_group = defaultdict(list)
    for row in load_json(excel / "EquipAffixExcelConfigData.json"):
        affix_group[row.get("id", 0)].append(row)
    for rows in affix_group.values():
        rows.sort(key=lambda x: x.get("level", 0))

    reliquary_sets = load_json(excel / "ReliquarySetExcelConfigData.json")
    reliquary_items = {x["id"]: x for x in load_json(excel / "ReliquaryExcelConfigData.json")}
    chapters = load_json(excel / "ChapterExcelConfigData.json")
    main_quests = load_json(excel / "MainQuestExcelConfigData.json")
    achievements = load_json(excel / "AchievementExcelConfigData.json")
    achievement_goals = {x["id"]: x for x in load_json(excel / "AchievementGoalExcelConfigData.json")}
    materials = load_json(excel / "MaterialExcelConfigData.json")

    index = {
        "characters": [],
        "weapons": [],
        "artifacts": [],
        "chapters": [],
        "mainQuests": [],
        "achievements": [],
        "materials": [],
    }

    for avatar in sorted(avatars, key=lambda x: x.get("id", 0)):
        name = text_resolver.get(avatar.get("nameTextMapHash"))
        if not (visible(name) and avatar.get("useType") == "AVATAR_FORMAL"):
            continue
        avatar_id = avatar["id"]
        info = fetter_info.get(avatar_id, {})
        depot = skill_depot.get(avatar.get("skillDepotId"), {})
        skill_ids = [sid for sid in depot.get("skills", []) if sid]
        if depot.get("energySkill") and depot["energySkill"] not in skill_ids:
            skill_ids.append(depot["energySkill"])
        skill_blocks = []
        for sid in skill_ids:
            skill = skills.get(sid)
            if not skill:
                continue
            sk_name = text_resolver.get(skill.get("nameTextMapHash"))
            sk_desc = text_resolver.get(skill.get("descTextMapHash"))
            if visible(sk_name):
                skill_blocks.append(f"### {sk_name} (ID:{sid})\n{sk_desc or '(none)'}")
        const_blocks = []
        for tid in [tid for tid in depot.get("talents", []) if tid]:
            talent = talents.get(tid)
            if not talent:
                continue
            t_name = text_resolver.get(talent.get("nameTextMapHash"))
            t_desc = text_resolver.get(talent.get("descTextMapHash"))
            if visible(t_name):
                const_blocks.append(f"### {t_name} (ID:{tid})\n{t_desc or '(none)'}")
        story_blocks = []
        for story in sorted(fetter_story.get(avatar_id, []), key=lambda x: x.get("fetterId", 0)):
            s_name = text_resolver.get(story.get("storyTitleTextMapHash"))
            s_desc = text_resolver.get(story.get("storyContextTextMapHash"))
            if visible(s_name):
                story_blocks.append(f"### {s_name}\n{s_desc or '(none)'}")

        path = docs_root / "characters" / f"avatar_{avatar_id}_{safe_name(name)}.md"
        write_md(path, f"Genshin Character: {name}", [
            ("Basic", md_list([
                f"ID: {avatar_id}",
                f"CN Name: {name}",
                f"EN Name: {text_resolver.get(avatar.get('nameTextMapHash'), 'en') or '(none)'}",
                f"Rarity: {RARITY_MAP.get(avatar.get('qualityType'), avatar.get('qualityType', 'unknown'))}",
                f"Weapon Type: {WEAPON_TYPE_MAP.get(avatar.get('weaponType'), avatar.get('weaponType', 'unknown'))}",
                f"Faction: {text_resolver.get(info.get('avatarNativeTextMapHash')) or '(none)'}",
                f"Title: {text_resolver.get(info.get('avatarTitleTextMapHash')) or '(none)'}",
            ])),
            ("Description", "\n\n".join([x for x in [
                text_resolver.get(avatar.get("descTextMapHash")),
                text_resolver.get(info.get("avatarDetailTextMapHash")),
            ] if visible(x)]) or "(none)"),
            ("Skills", "\n\n".join(skill_blocks) if skill_blocks else "(none)"),
            ("Constellations", "\n\n".join(const_blocks) if const_blocks else "(none)"),
            ("Stories", "\n\n".join(story_blocks) if story_blocks else "(none)"),
            ("Source", "AnimeGameData: TextMapCHS + ExcelBinOutput"),
        ])
        index["characters"].append({
            "id": avatar_id,
            "name": name,
            "nameEn": text_resolver.get(avatar.get("nameTextMapHash"), "en"),
            "intentCode": f"genshin_char_{avatar_id}",
            "docPath": str(path),
        })

    for weapon in sorted(weapons, key=lambda x: (-(x.get("rankLevel") or 0), x.get("id", 0))):
        name = text_resolver.get(weapon.get("nameTextMapHash"))
        if not visible(name):
            continue
        weapon_id = weapon["id"]
        skill_affix_id = int((weapon.get("skillAffix") or [0])[0] or 0)
        passive_text = "(none)"
        if skill_affix_id and skill_affix_id in affix_group:
            rows = affix_group[skill_affix_id]
            pname = text_resolver.get(rows[0].get("nameTextMapHash")) if rows else "(none)"
            detail = "\n".join([f"- R{int(r.get('level', 0)) + 1}: {text_resolver.get(r.get('descTextMapHash')) or '(none)'}" for r in rows])
            passive_text = f"Passive: {pname}\n\n{detail}"
        p_rows = weapon_promote.get(int(weapon.get("weaponPromoteId") or 0), [])
        max_unlock_level = max([int(x.get("unlockMaxLevel") or 0) for x in p_rows], default=0)
        path = docs_root / "weapons" / f"weapon_{weapon_id}_{safe_name(name)}.md"
        write_md(path, f"Genshin Weapon: {name}", [
            ("Basic", md_list([
                f"ID: {weapon_id}",
                f"CN Name: {name}",
                f"EN Name: {text_resolver.get(weapon.get('nameTextMapHash'), 'en') or '(none)'}",
                f"Rarity: {weapon.get('rankLevel', 0)}-star",
                f"Weapon Type: {WEAPON_TYPE_MAP.get(weapon.get('weaponType'), weapon.get('weaponType', 'unknown'))}",
            ])),
            ("Description", text_resolver.get(weapon.get("descTextMapHash")) or "(none)"),
            ("Passive", passive_text),
            ("Promotion", f"- Max Unlock Level: {max_unlock_level or '(unknown)'}"),
            ("Source", "AnimeGameData: TextMapCHS + ExcelBinOutput"),
        ])
        index["weapons"].append({
            "id": weapon_id,
            "name": name,
            "nameEn": text_resolver.get(weapon.get("nameTextMapHash"), "en"),
            "rarity": weapon.get("rankLevel", 0),
            "intentCode": f"genshin_weapon_{weapon_id}",
            "docPath": str(path),
        })

    for aset in sorted(reliquary_sets, key=lambda x: x.get("setId", 0)):
        set_id = aset.get("setId", 0)
        aid = aset.get("equipAffixId", 0)
        lv0 = next((x for x in affix_group.get(aid, []) if int(x.get("level", 0)) == 0), {})
        lv1 = next((x for x in affix_group.get(aid, []) if int(x.get("level", 0)) == 1), {})
        set_name = text_resolver.get(lv0.get("nameTextMapHash")) or f"ArtifactSet-{set_id}"
        pieces = []
        for piece_id in aset.get("containsList", []):
            piece = reliquary_items.get(piece_id, {})
            pname = text_resolver.get(piece.get("nameTextMapHash"))
            if visible(pname):
                pieces.append(f"{pname} ({piece.get('equipType', 'unknown')}, ID:{piece_id})")
        path = docs_root / "artifacts" / f"artifact_set_{set_id}_{safe_name(set_name)}.md"
        write_md(path, f"Genshin Artifact Set: {set_name}", [
            ("Basic", md_list([
                f"Set ID: {set_id}",
                f"CN Name: {set_name}",
                f"EN Name: {text_resolver.get(lv0.get('nameTextMapHash'), 'en') or '(none)'}",
            ])),
            ("Effects", md_list([
                f"2-piece: {text_resolver.get(lv0.get('descTextMapHash')) or '(none)'}",
                f"4-piece: {text_resolver.get(lv1.get('descTextMapHash')) or '(none)'}",
            ])),
            ("Pieces", md_list(pieces)),
            ("Source", "AnimeGameData: TextMapCHS + ExcelBinOutput"),
        ])
        index["artifacts"].append({
            "id": set_id,
            "name": set_name,
            "nameEn": text_resolver.get(lv0.get("nameTextMapHash"), "en"),
            "intentCode": f"genshin_artifact_set_{set_id}",
            "docPath": str(path),
        })

    chapter_name_map = {}
    for chapter in sorted(chapters, key=lambda x: x.get("id", 0)):
        chapter_name = text_resolver.get(chapter.get("chapterTitleTextMapHash"))
        if not visible(chapter_name):
            continue
        chapter_id = chapter.get("id", 0)
        chapter_no = text_resolver.get(chapter.get("chapterNumTextMapHash"))
        chapter_name_map[chapter_id] = chapter_name
        path = docs_root / "chapters" / f"chapter_{chapter_id}_{safe_name(chapter_name)}.md"
        write_md(path, f"Genshin Chapter: {(chapter_no + ' ' + chapter_name).strip()}", [
            ("Basic", md_list([
                f"Chapter ID: {chapter_id}",
                f"Chapter No: {chapter_no or '(none)'}",
                f"Chapter Name: {chapter_name}",
            ])),
            ("Source", "AnimeGameData: TextMapCHS + ExcelBinOutput"),
        ])
        index["chapters"].append({
            "id": chapter_id,
            "name": chapter_name,
            "chapterNo": chapter_no,
            "intentCode": f"genshin_chapter_{chapter_id}",
            "docPath": str(path),
        })

    quest_rows = []
    for row in main_quests:
        qname = text_resolver.get(row.get("titleTextMapHash"))
        if visible(qname) and "占坑" not in qname:
            quest_rows.append((row, qname))
    quest_rows.sort(key=lambda x: x[0].get("id", 0), reverse=True)
    quest_rows = quest_rows[: int(args.max_main_quests)]
    for row, qname in sorted(quest_rows, key=lambda x: x[0].get("id", 0)):
        qid = row.get("id")
        cid = row.get("chapterId")
        path = docs_root / "main_quests" / f"main_quest_{qid}_{safe_name(qname)}.md"
        write_md(path, f"Genshin Main Quest: {qname}", [
            ("Basic", md_list([
                f"Main Quest ID: {qid}",
                f"Title: {qname}",
                f"Chapter ID: {cid or '(none)'}",
                f"Chapter Name: {chapter_name_map.get(cid, '(uncategorized)')}",
                f"Recommend Level: {row.get('recommendLevel') or '(none)'}",
            ])),
            ("Summary", text_resolver.get(row.get("descTextMapHash")) or "(none)"),
            ("Source", "AnimeGameData: TextMapCHS + ExcelBinOutput"),
        ])
        index["mainQuests"].append({
            "id": qid,
            "name": qname,
            "chapterId": cid,
            "intentCode": f"genshin_main_quest_{qid}",
            "docPath": str(path),
        })

    ach_rows = []
    for row in achievements:
        aname = text_resolver.get(row.get("titleTextMapHash"))
        if visible(aname):
            ach_rows.append((row, aname))
    ach_rows.sort(key=lambda x: x[0].get("id", 0), reverse=True)
    ach_rows = ach_rows[: int(args.max_achievements)]
    for row, aname in sorted(ach_rows, key=lambda x: x[0].get("id", 0)):
        aid = row.get("id")
        goal = achievement_goals.get(row.get("goalId"), {})
        goal_name = text_resolver.get(goal.get("nameTextMapHash"))
        trigger_type = (row.get("triggerConfig") or {}).get("triggerType", "unknown")
        path = docs_root / "achievements" / f"achievement_{aid}_{safe_name(aname)}.md"
        write_md(path, f"Genshin Achievement: {aname}", [
            ("Basic", md_list([
                f"Achievement ID: {aid}",
                f"Title: {aname}",
                f"Goal Group: {goal_name or '(none)'}",
                f"Progress: {row.get('progress') or 1}",
                f"Trigger Type: {trigger_type}",
            ])),
            ("Description", text_resolver.get(row.get("descTextMapHash")) or "(none)"),
            ("Source", "AnimeGameData: TextMapCHS + ExcelBinOutput"),
        ])
        index["achievements"].append({
            "id": aid,
            "name": aname,
            "goalName": goal_name,
            "intentCode": f"genshin_achievement_{aid}",
            "docPath": str(path),
        })

    material_buckets = defaultdict(list)
    for row in materials:
        mname = text_resolver.get(row.get("nameTextMapHash"))
        if visible(mname):
            material_buckets[row.get("materialType", "MATERIAL_UNKNOWN")].append(row)
    selected_materials = []
    for mtype in MATERIAL_PRIORITY + sorted(set(material_buckets.keys()) - set(MATERIAL_PRIORITY)):
        for row in sorted(material_buckets.get(mtype, []), key=lambda x: x.get("id", 0)):
            if len(selected_materials) >= int(args.max_materials):
                break
            selected_materials.append(row)
        if len(selected_materials) >= int(args.max_materials):
            break
    for row in selected_materials:
        mid = row.get("id")
        mname = text_resolver.get(row.get("nameTextMapHash"))
        path = docs_root / "materials" / f"material_{mid}_{safe_name(mname)}.md"
        write_md(path, f"Genshin Material: {mname}", [
            ("Basic", md_list([
                f"Material ID: {mid}",
                f"CN Name: {mname}",
                f"EN Name: {text_resolver.get(row.get('nameTextMapHash'), 'en') or '(none)'}",
                f"Material Type: {row.get('materialType', 'unknown')}",
                f"Item Type: {row.get('itemType', 'unknown')}",
            ])),
            ("Description", text_resolver.get(row.get("descTextMapHash")) or "(none)"),
            ("Effect", text_resolver.get(row.get("effectDescTextMapHash")) or "(none)"),
            ("Source", "AnimeGameData: TextMapCHS + ExcelBinOutput"),
        ])
        index["materials"].append({
            "id": mid,
            "name": mname,
            "nameEn": text_resolver.get(row.get("nameTextMapHash"), "en"),
            "materialType": row.get("materialType"),
            "intentCode": f"genshin_material_{mid}",
            "docPath": str(path),
        })

    intents = [
        {
            "intentCode": "genshin_domain",
            "name": "原神全域问答",
            "level": 0,
            "parentCode": None,
            "description": "原神中文知识总入口",
            "examples": ["原神角色怎么培养", "原神主线有哪些"],
            "topK": 10,
            "kind": 0,
            "sortOrder": 1,
            "enabled": 1,
        }
    ]
    categories = [
        ("genshin_cat_characters", "角色系统"),
        ("genshin_cat_weapons", "武器系统"),
        ("genshin_cat_artifacts", "圣遗物系统"),
        ("genshin_cat_story", "剧情与任务"),
        ("genshin_cat_achievements", "成就系统"),
        ("genshin_cat_materials", "材料系统"),
    ]
    for i, (code, name) in enumerate(categories, start=10):
        intents.append({
            "intentCode": code,
            "name": name,
            "level": 1,
            "parentCode": "genshin_domain",
            "description": name,
            "examples": [f"{name}怎么查"],
            "topK": 8,
            "kind": 0,
            "sortOrder": i,
            "enabled": 1,
        })
    for item in index["characters"]:
        intents.append({"intentCode": item["intentCode"], "name": item["name"], "level": 2, "parentCode": "genshin_cat_characters", "description": f"角色专题：{item['name']}", "examples": [f"{item['name']}配队", f"{item['name']}命座"], "topK": 6, "kind": 0, "sortOrder": int(item["id"]), "enabled": 1})
    for item in [x for x in index["weapons"] if int(x.get("rarity", 0)) >= 4][:120]:
        intents.append({"intentCode": item["intentCode"], "name": item["name"], "level": 2, "parentCode": "genshin_cat_weapons", "description": f"武器专题：{item['name']}", "examples": [f"{item['name']}适配谁"], "topK": 5, "kind": 0, "sortOrder": int(item["id"]), "enabled": 1})
    for item in index["artifacts"]:
        intents.append({"intentCode": item["intentCode"], "name": item["name"], "level": 2, "parentCode": "genshin_cat_artifacts", "description": f"圣遗物专题：{item['name']}", "examples": [f"{item['name']}效果"], "topK": 6, "kind": 0, "sortOrder": int(item["id"]), "enabled": 1})
    for item in index["chapters"]:
        display_name = f"{item.get('chapterNo') or ''} {item['name']}".strip()
        intents.append({"intentCode": item["intentCode"], "name": display_name, "level": 2, "parentCode": "genshin_cat_story", "description": f"剧情章节：{item['name']}", "examples": [f"{item['name']}讲了什么"], "topK": 6, "kind": 0, "sortOrder": int(item["id"]), "enabled": 1})
    for item in index["mainQuests"][:300]:
        intents.append({"intentCode": item["intentCode"], "name": item["name"], "level": 2, "parentCode": "genshin_cat_story", "description": f"主线任务：{item['name']}", "examples": [f"{item['name']}怎么触发"], "topK": 5, "kind": 0, "sortOrder": int(item["id"]), "enabled": 1})
    for item in index["achievements"][:200]:
        intents.append({"intentCode": item["intentCode"], "name": item["name"], "level": 2, "parentCode": "genshin_cat_achievements", "description": f"成就专题：{item['name']}", "examples": [f"{item['name']}怎么做"], "topK": 5, "kind": 0, "sortOrder": int(item["id"]), "enabled": 1})
    for i, mtype in enumerate(sorted({x["materialType"] for x in index["materials"] if x.get("materialType")}), start=1):
        intents.append({"intentCode": f"genshin_material_type_{mtype.lower()}", "name": mtype, "level": 2, "parentCode": "genshin_cat_materials", "description": f"材料类型：{mtype}", "examples": [f"{mtype}有哪些"], "topK": 5, "kind": 0, "sortOrder": 90000 + i, "enabled": 1})

    mapping = {}
    def add_mapping(source: str, target: str, priority: int, remark: str):
        source = re.sub(r"\s+", " ", source or "").strip()
        target = re.sub(r"\s+", " ", target or "").strip()
        if not source or not target or source == target:
            return
        key = (source.lower(), target)
        if key not in mapping:
            mapping[key] = {
                "sourceTerm": source,
                "targetTerm": target,
                "matchType": 1,
                "priority": priority,
                "enabled": True,
                "remark": remark,
            }

    manual = {
        "抽卡": "祈愿",
        "池子": "祈愿卡池",
        "命座": "命之座",
        "普攻": "普通攻击",
        "战技": "元素战技",
        "大招": "元素爆发",
        "充能": "元素充能效率",
        "双爆": "暴击率与暴击伤害",
        "深渊": "深境螺旋",
        "树脂": "原粹树脂",
        "脆树脂": "脆弱树脂",
        "纠缠": "纠缠之缘",
        "相遇": "相遇之缘",
        "雷神": "雷电将军",
        "草神": "纳西妲",
        "水神": "芙宁娜",
        "风神": "温迪",
        "岩神": "钟离",
        "公子": "达达利亚",
        "万叶": "枫原万叶",
        "芙芙": "芙宁娜",
    }
    for src, tgt in manual.items():
        add_mapping(src, tgt, 5, "manual glossary")
    for group, priority, remark in [
        (index["characters"], 20, "character alias"),
        (index["weapons"], 30, "weapon alias"),
        (index["artifacts"], 40, "artifact alias"),
        (index["chapters"], 50, "chapter alias"),
        (index["materials"], 60, "material alias"),
    ]:
        for row in group:
            name = (row.get("name") or "").strip()
            name_en = (row.get("nameEn") or "").strip()
            if not name:
                continue
            add_mapping(re.sub(r"[·・\s\-_/]", "", name), name, priority, remark)
            if name_en:
                lower = name_en.lower()
                add_mapping(lower, name, priority, remark)
                add_mapping(re.sub(r"[^a-z0-9]", "", lower), name, priority, remark)

    pipeline_template = {
        "name": "原神中文知识入库管道",
        "description": "面向原神中文Markdown文档的数据清洗、切分与向量入库",
        "nodes": [
            {"nodeId": "fetch_source", "nodeType": "fetcher", "settings": {}, "condition": None, "nextNodeId": "parse_document"},
            {"nodeId": "parse_document", "nodeType": "parser", "settings": {"rules": [{"mimeType": "MARKDOWN", "options": {}}, {"mimeType": "TEXT", "options": {}}, {"mimeType": "ALL", "options": {}}]}, "condition": None, "nextNodeId": "chunk_document"},
            {"nodeId": "chunk_document", "nodeType": "chunker", "settings": {"strategy": "structure_aware", "chunkSize": 900, "overlapSize": 120, "separator": "\n\n"}, "condition": None, "nextNodeId": "index_document"},
            {"nodeId": "index_document", "nodeType": "indexer", "settings": {"metadataFields": ["domain", "category", "entity_type", "entity_id", "source", "lang", "version"]}, "condition": None, "nextNodeId": None},
        ],
    }

    sample_questions = [
        {"title": "角色定位", "question": "神里绫华在队伍里通常担任什么定位？"},
        {"title": "天赋优先级", "question": "那维莱特的天赋升级顺序怎么排？"},
        {"title": "武器对比", "question": "五星武器和四星武器差距大概有多大？"},
        {"title": "圣遗物搭配", "question": "黄金剧团适合哪些角色？"},
        {"title": "剧情章节", "question": "第三章主线剧情核心矛盾是什么？"},
        {"title": "成就解法", "question": "如何快速完成探索类成就？"},
        {"title": "材料规划", "question": "新角色从0到90级要准备哪些材料？"},
        {"title": "资源管理", "question": "树脂如何分配最有效率？"},
        {"title": "术语解释", "question": "什么是双暴和充能阈值？"},
        {"title": "配队思路", "question": "如何兼顾生存、输出和循环？"},
    ]

    dump_json(config_root / "entity_index.json", index)
    dump_json(config_root / "intent_nodes.json", intents)
    dump_json(config_root / "query_term_mappings.json", sorted(mapping.values(), key=lambda x: (x["priority"], x["sourceTerm"])))
    dump_json(config_root / "pipeline_template.json", pipeline_template)
    dump_json(config_root / "sample_questions.json", sample_questions)

    category_count = {}
    total_docs = 0
    for sub in sorted(docs_root.iterdir()):
        if sub.is_dir():
            count = len(list(sub.glob("*.md")))
            category_count[sub.name] = count
            total_docs += count
    manifest = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "docsRoot": str(docs_root),
        "totalDocs": total_docs,
        "categoryDocCount": category_count,
        "intentNodeCount": len(intents),
        "queryMappingCount": len(mapping),
    }
    dump_json(output_root / "manifest.json", manifest)

    print("=== Genshin seed build complete ===")
    print(f"Data root: {data_root}")
    print(f"Output root: {output_root}")
    print(f"Total docs: {total_docs}")
    for k, v in category_count.items():
        print(f"- {k}: {v}")
    print(f"Intent nodes: {len(intents)}")
    print(f"Query mappings: {len(mapping)}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Build Genshin seed for Ragent.")
    parser.add_argument("--data-root", default="external_data/AnimeGameData")
    parser.add_argument("--output-root", default="resources/genshin_seed")
    parser.add_argument("--max-main-quests", type=int, default=900)
    parser.add_argument("--max-achievements", type=int, default=900)
    parser.add_argument("--max-materials", type=int, default=1000)
    parser.add_argument("--clean", action="store_true")
    build_seed(parser.parse_args())
