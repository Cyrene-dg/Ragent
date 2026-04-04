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
    with path.open("r", encoding="utf-8-sig") as f:
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


def unique_non_empty(items: list[str]) -> list[str]:
    out: list[str] = []
    seen = set()
    for item in items:
        value = re.sub(r"\s+", " ", item or "").strip()
        if not value or value in seen:
            continue
        seen.add(value)
        out.append(value)
    return out


def normalize_compact(text: str) -> str:
    return re.sub(r"[^0-9a-z\u4e00-\u9fff]+", "", (text or "").lower())


def load_external_entries(path: Path, default_value):
    if not path.exists():
        return default_value
    return load_json(path)


def pick_top(rows: list, top_n: int) -> list:
    if top_n <= 0:
        return rows
    return rows[:top_n]


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
    quest_steps = load_json(excel / "QuestExcelConfigData.json")
    achievements = load_json(excel / "AchievementExcelConfigData.json")
    achievement_goals = {x["id"]: x for x in load_json(excel / "AchievementGoalExcelConfigData.json")}
    materials = load_json(excel / "MaterialExcelConfigData.json")

    owner_file = Path(args.weapon_owner_file).resolve()
    lore_file = Path(args.lore_file).resolve()
    owner_links_raw = load_external_entries(owner_file, [])
    lore_entries_raw = load_external_entries(lore_file, []) if args.include_external_lore else []

    owner_links_by_weapon_id: dict[int, dict] = {}
    owner_links_by_weapon_compact_name: dict[str, dict] = {}
    for item in owner_links_raw:
        weapon_id = int(item.get("weaponId") or 0)
        weapon_name = (item.get("weaponName") or "").strip()
        if weapon_id > 0:
            owner_links_by_weapon_id[weapon_id] = item
        if weapon_name:
            owner_links_by_weapon_compact_name[normalize_compact(weapon_name)] = item

    quest_steps_by_main = defaultdict(list)
    for step in quest_steps:
        main_id = int(step.get("mainId") or 0)
        if main_id <= 0:
            continue
        quest_steps_by_main[main_id].append(step)
    for items in quest_steps_by_main.values():
        items.sort(key=lambda x: (int(x.get("order") or 0), int(x.get("subId") or 0)))

    index = {
        "characters": [],
        "weapons": [],
        "weaponOwnerLinks": [],
        "artifacts": [],
        "chapters": [],
        "mainQuests": [],
        "achievements": [],
        "materials": [],
        "loreExtensions": [],
    }

    avatar_name_by_id: dict[int, str] = {}

    for avatar in sorted(avatars, key=lambda x: x.get("id", 0)):
        name = text_resolver.get(avatar.get("nameTextMapHash"))
        if not (visible(name) and avatar.get("useType") == "AVATAR_FORMAL"):
            continue
        avatar_id = avatar["id"]
        avatar_name_by_id[int(avatar_id)] = name
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
        weapon_id = int(weapon["id"])
        compact_name = normalize_compact(name)
        owner_entry = owner_links_by_weapon_id.get(weapon_id) or owner_links_by_weapon_compact_name.get(compact_name)
        skill_affix_id = int((weapon.get("skillAffix") or [0])[0] or 0)
        passive_text = "(none)"
        if skill_affix_id and skill_affix_id in affix_group:
            rows = affix_group[skill_affix_id]
            pname = text_resolver.get(rows[0].get("nameTextMapHash")) if rows else "(none)"
            detail = "\n".join([f"- R{int(r.get('level', 0)) + 1}: {text_resolver.get(r.get('descTextMapHash')) or '(none)'}" for r in rows])
            passive_text = f"Passive: {pname}\n\n{detail}"

        owner_lines: list[str] = []
        owner_names: list[str] = []
        if owner_entry:
            owner_ids = [int(x) for x in (owner_entry.get("ownerCharacterIds") or []) if x]
            owner_names_raw = [str(x).strip() for x in (owner_entry.get("ownerNames") or []) if str(x).strip()]
            owner_names = owner_names_raw[:]
            for cid in owner_ids:
                cname = avatar_name_by_id.get(cid)
                if cname and cname not in owner_names:
                    owner_names.append(cname)
            owner_names = unique_non_empty(owner_names)
            relation_type = str(owner_entry.get("relationType") or "manual_link")
            confidence = str(owner_entry.get("confidence") or "medium")
            source = str(owner_entry.get("source") or "manual_curation")
            notes = str(owner_entry.get("notes") or "")
            owner_lines.extend([f"- Relation: {relation_type}", f"- Confidence: {confidence}", f"- Source: {source}"])
            if owner_names:
                owner_lines.extend([f"- Owner: {name_item}" for name_item in owner_names])
            if notes:
                owner_lines.append(f"- Notes: {notes}")

            owner_doc = docs_root / "weapon_owners" / f"weapon_owner_{weapon_id}_{safe_name(name)}.md"
            write_md(owner_doc, f"Genshin Weapon Owner Link: {name}", [
                ("Basic", md_list([
                    f"Weapon ID: {weapon_id}",
                    f"Weapon Name: {name}",
                    f"Weapon EN Name: {text_resolver.get(weapon.get('nameTextMapHash'), 'en') or '(none)'}",
                    f"Rarity: {weapon.get('rankLevel', 0)}-star",
                ])),
                ("Owners", "\n".join(owner_lines) if owner_lines else "(none)"),
                ("Source", "ManualCuration + AnimeGameData"),
            ])
            index["weaponOwnerLinks"].append({
                "weaponId": weapon_id,
                "weaponName": name,
                "ownerNames": owner_names,
                "intentCode": f"genshin_weapon_owner_{weapon_id}",
                "docPath": str(owner_doc),
            })

        p_rows = weapon_promote.get(int(weapon.get("weaponPromoteId") or 0), [])
        max_unlock_level = max([int(x.get("unlockMaxLevel") or 0) for x in p_rows], default=0)
        path = docs_root / "weapons" / f"weapon_{weapon_id}_{safe_name(name)}.md"
        sections = [
            ("Basic", md_list([
                f"ID: {weapon_id}",
                f"CN Name: {name}",
                f"EN Name: {text_resolver.get(weapon.get('nameTextMapHash'), 'en') or '(none)'}",
                f"Rarity: {weapon.get('rankLevel', 0)}-star",
                f"Weapon Type: {WEAPON_TYPE_MAP.get(weapon.get('weaponType'), weapon.get('weaponType', 'unknown'))}",
                f"Story ID: {weapon.get('storyId') or '(none)'}",
            ])),
            ("Description", text_resolver.get(weapon.get("descTextMapHash")) or "(none)"),
            ("Passive", passive_text),
            ("Promotion", f"- Max Unlock Level: {max_unlock_level or '(unknown)'}"),
        ]
        if owner_lines:
            sections.append(("OwnerLinks", "\n".join(owner_lines)))
        sections.append(("Source", "AnimeGameData: TextMapCHS + ExcelBinOutput"))
        write_md(path, f"Genshin Weapon: {name}", sections)
        index["weapons"].append({
            "id": weapon_id,
            "name": name,
            "nameEn": text_resolver.get(weapon.get("nameTextMapHash"), "en"),
            "rarity": weapon.get("rankLevel", 0),
            "intentCode": f"genshin_weapon_{weapon_id}",
            "docPath": str(path),
            "ownerNames": owner_names,
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
        if visible(qname) and "鍗犲潙" not in qname:
            quest_rows.append((row, qname))
    quest_rows.sort(key=lambda x: x[0].get("id", 0), reverse=True)
    quest_rows = pick_top(quest_rows, int(args.max_main_quests))
    main_quest_with_step_count = 0
    for row, qname in sorted(quest_rows, key=lambda x: x[0].get("id", 0)):
        qid = int(row.get("id") or 0)
        cid = row.get("chapterId")
        summary = text_resolver.get(row.get("descTextMapHash")) or "(none)"
        step_lines: list[str] = []
        step_items = quest_steps_by_main.get(qid, [])
        if args.include_subquest_steps and step_items:
            sliced = pick_top(step_items, int(args.max_subquest_steps_per_main))
            for step in sliced:
                sub_id = int(step.get("subId") or 0)
                order = int(step.get("order") or 0)
                step_desc = text_resolver.get(step.get("stepDescTextMapHash"))
                guide_desc = text_resolver.get(step.get("guideTipsTextMapHash"))
                if not visible(step_desc) and not visible(guide_desc):
                    continue
                merged = step_desc if visible(step_desc) else ""
                if visible(guide_desc) and guide_desc != step_desc:
                    merged = (merged + "; " + guide_desc).strip("; ")
                merged = re.sub(r"\s+", " ", merged).strip()
                if not merged:
                    continue
                step_lines.append(f"- [{order or '?'}|{sub_id}] {merged}")
        if step_lines:
            main_quest_with_step_count += 1
        if int(args.max_subquest_text_chars) > 0:
            compact = "\n".join(step_lines)
            compact = compact[: int(args.max_subquest_text_chars)]
            step_lines = compact.split("\n") if compact else []

        path = docs_root / "main_quests" / f"main_quest_{qid}_{safe_name(qname)}.md"
        write_md(path, f"Genshin Main Quest: {qname}", [
            ("Basic", md_list([
                f"Main Quest ID: {qid}",
                f"Title: {qname}",
                f"Chapter ID: {cid or '(none)'}",
                f"Chapter Name: {chapter_name_map.get(cid, '(uncategorized)')}",
                f"Recommend Level: {row.get('recommendLevel') or '(none)'}",
                f"Sub Quest Count: {len(step_items)}",
            ])),
            ("Summary", summary),
            ("Quest Steps", "\n".join(step_lines) if step_lines else "(none)"),
            ("Source", "AnimeGameData: MainQuestExcelConfigData + QuestExcelConfigData + TextMapCHS"),
        ])
        index["mainQuests"].append({
            "id": qid,
            "name": qname,
            "chapterId": cid,
            "subQuestCount": len(step_items),
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

    lore_count = 0
    for raw in lore_entries_raw:
        entry_id = str(raw.get("id") or "").strip() or f"lore_{lore_count + 1:04d}"
        title = str(raw.get("title") or "").strip()
        if not title:
            continue
        body = raw.get("content")
        if isinstance(body, list):
            body_text = "\n\n".join([str(x).strip() for x in body if str(x).strip()])
        else:
            body_text = str(body or "").strip()
        if not body_text:
            continue
        tags = [str(x).strip() for x in (raw.get("tags") or []) if str(x).strip()]
        aliases = [str(x).strip() for x in (raw.get("aliases") or []) if str(x).strip()]
        source = str(raw.get("source") or "manual_curation")
        related_type = str(raw.get("relatedEntityType") or "lore")
        related_id = str(raw.get("relatedEntityId") or "")

        path = docs_root / "lore_extensions" / f"lore_{safe_name(entry_id)}_{safe_name(title)}.md"
        write_md(path, f"Genshin Lore Extension: {title}", [
            ("Basic", md_list([
                f"Lore ID: {entry_id}",
                f"Title: {title}",
                f"Related Type: {related_type}",
                f"Related ID: {related_id or '(none)'}",
                f"Tags: {', '.join(tags) if tags else '(none)'}",
                f"Aliases: {', '.join(aliases) if aliases else '(none)'}",
            ])),
            ("Content", body_text),
            ("Source", source),
        ])
        index["loreExtensions"].append({
            "id": entry_id,
            "title": title,
            "tags": tags,
            "aliases": aliases,
            "intentCode": f"genshin_lore_{safe_name(entry_id).lower()}",
            "docPath": str(path),
        })
        lore_count += 1

    # Rebuild intents with ASCII-safe literals to avoid encoding issues.
    intents = [
        {
            "intentCode": "genshin_domain",
            "name": "genshin_domain",
            "level": 0,
            "parentCode": None,
            "description": "genshin knowledge entry",
            "examples": ["genshin character build", "genshin main quest"],
            "topK": 12,
            "kind": 0,
            "sortOrder": 1,
            "enabled": 1,
        }
    ]
    categories = [
        ("genshin_cat_characters", "characters"),
        ("genshin_cat_weapons", "weapons"),
        ("genshin_cat_weapon_owner", "weapon_owner"),
        ("genshin_cat_artifacts", "artifacts"),
        ("genshin_cat_story", "story"),
        ("genshin_cat_lore", "lore"),
        ("genshin_cat_achievements", "achievements"),
        ("genshin_cat_materials", "materials"),
    ]
    for i, (code, name) in enumerate(categories, start=10):
        intents.append({
            "intentCode": code,
            "name": name,
            "level": 1,
            "parentCode": "genshin_domain",
            "description": name,
            "examples": [name],
            "topK": 8,
            "kind": 0,
            "sortOrder": i,
            "enabled": 1,
        })
    for item in index["characters"]:
        intents.append({"intentCode": item["intentCode"], "name": item["name"], "level": 2, "parentCode": "genshin_cat_characters", "description": "character topic", "examples": [item["name"]], "topK": 6, "kind": 0, "sortOrder": int(item["id"]), "enabled": 1})
    for item in [x for x in index["weapons"] if int(x.get("rarity", 0)) >= 4][:200]:
        intents.append({"intentCode": item["intentCode"], "name": item["name"], "level": 2, "parentCode": "genshin_cat_weapons", "description": "weapon topic", "examples": [item["name"]], "topK": 6, "kind": 0, "sortOrder": int(item["id"]), "enabled": 1})
    for item in index["weaponOwnerLinks"]:
        intents.append({"intentCode": item["intentCode"], "name": item["weaponName"] + "_owner", "level": 2, "parentCode": "genshin_cat_weapon_owner", "description": "weapon owner relation", "examples": [item["weaponName"]], "topK": 6, "kind": 0, "sortOrder": int(item["weaponId"]), "enabled": 1})
    for item in index["artifacts"]:
        intents.append({"intentCode": item["intentCode"], "name": item["name"], "level": 2, "parentCode": "genshin_cat_artifacts", "description": "artifact topic", "examples": [item["name"]], "topK": 6, "kind": 0, "sortOrder": int(item["id"]), "enabled": 1})
    for item in index["chapters"]:
        display_name = f"{item.get('chapterNo') or ''} {item['name']}".strip()
        intents.append({"intentCode": item["intentCode"], "name": display_name, "level": 2, "parentCode": "genshin_cat_story", "description": "chapter topic", "examples": [item["name"]], "topK": 6, "kind": 0, "sortOrder": int(item["id"]), "enabled": 1})
    for item in pick_top(index["mainQuests"], int(args.max_main_quest_intents)):
        intents.append({"intentCode": item["intentCode"], "name": item["name"], "level": 2, "parentCode": "genshin_cat_story", "description": "main quest topic", "examples": [item["name"]], "topK": 6, "kind": 0, "sortOrder": int(item["id"]), "enabled": 1})
    for item in index["loreExtensions"]:
        intents.append({"intentCode": item["intentCode"], "name": item["title"], "level": 2, "parentCode": "genshin_cat_lore", "description": "lore topic", "examples": [item["title"]], "topK": 5, "kind": 0, "sortOrder": 700000 + len(item["title"]), "enabled": 1})
    for item in pick_top(index["achievements"], 250):
        intents.append({"intentCode": item["intentCode"], "name": item["name"], "level": 2, "parentCode": "genshin_cat_achievements", "description": "achievement topic", "examples": [item["name"]], "topK": 5, "kind": 0, "sortOrder": int(item["id"]), "enabled": 1})
    for i, mtype in enumerate(sorted({x["materialType"] for x in index["materials"] if x.get("materialType")}), start=1):
        intents.append({"intentCode": f"genshin_material_type_{mtype.lower()}", "name": mtype, "level": 2, "parentCode": "genshin_cat_materials", "description": "material type", "examples": [mtype], "topK": 5, "kind": 0, "sortOrder": 90000 + i, "enabled": 1})
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
        "??": "??",
        "??": "????",
        "??": "???",
        "??": "????",
        "??": "????",
        "??": "????",
        "??": "??????",
        "??": "????????",
        "??": "????",
        "??": "????",
        "???": "????",
        "??": "????",
        "??": "????",
        "??": "????",
        "??": "???",
        "??": "???",
        "??": "??",
        "??": "??",
        "??": "????",
        "??": "????",
        "??": "???",
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
            add_mapping(re.sub(r"[路銉籠s\-_/]", "", name), name, priority, remark)
            if name_en:
                lower = name_en.lower()
                add_mapping(lower, name, priority, remark)
                add_mapping(re.sub(r"[^a-z0-9]", "", lower), name, priority, remark)

    for group, priority, remark in [
        (index["characters"], 20, "character alias compact"),
        (index["weapons"], 30, "weapon alias compact"),
        (index["artifacts"], 40, "artifact alias compact"),
        (index["chapters"], 50, "chapter alias compact"),
        (index["materials"], 60, "material alias compact"),
    ]:
        for row in group:
            name = (row.get("name") or "").strip()
            if not name:
                continue
            add_mapping(re.sub(r"\s+", "", name), name, priority, remark)
            add_mapping(normalize_compact(name), name, priority, remark)

    for row in index["weaponOwnerLinks"]:
        weapon_name = row.get("weaponName") or ""
        owners = row.get("ownerNames") or []
        for owner in owners:
            add_mapping(f"{owner}专武", weapon_name, 12, "weapon owner link")
            add_mapping(f"{owner}武器", weapon_name, 12, "weapon owner link")
            add_mapping(f"{weapon_name}持有者", owner, 12, "weapon owner link")

    for row in index["loreExtensions"]:
        title = row.get("title") or ""
        for alias in row.get("aliases") or []:
            add_mapping(alias, title, 25, "lore alias")
        for tag in row.get("tags") or []:
            add_mapping(tag, title, 30, "lore tag")


    # Override potentially garbled literals with clean UTF-8 output payloads.
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
        {"title": "主线步骤", "question": "在伊黎耶深处这条主线具体要做哪几步？"},
        {"title": "主线触发", "question": "森林与公主这个任务如何触发？"},
        {"title": "武器持有者", "question": "护摩之杖是谁的专武，为什么这么评价？"},
        {"title": "武器背景", "question": "赤沙之杖有什么故事背景？"},
        {"title": "材料规划", "question": "新角色从0到90级要准备哪些材料？"},
        {"title": "术语解释", "question": "什么是双爆和充能阈值？"},
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
        "mainQuestWithStepCount": main_quest_with_step_count,
        "weaponOwnerLinkCount": len(index["weaponOwnerLinks"]),
        "loreEntryCount": len(index["loreExtensions"]),
        "sourceFiles": {
            "weaponOwnerFile": str(owner_file),
            "loreFile": str(lore_file),
        },
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
    print(f"Main quests with steps: {main_quest_with_step_count}")
    print(f"Weapon owner links: {len(index['weaponOwnerLinks'])}")
    print(f"Lore entries: {len(index['loreExtensions'])}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Build Genshin seed for Ragent.")
    parser.add_argument("--data-root", default="external_data/AnimeGameData")
    parser.add_argument("--output-root", default="resources/genshin_seed")
    parser.add_argument("--max-main-quests", type=int, default=3943)
    parser.add_argument("--max-main-quest-intents", type=int, default=700)
    parser.add_argument("--include-subquest-steps", action="store_true", default=True)
    parser.add_argument("--max-subquest-steps-per-main", type=int, default=60)
    parser.add_argument("--max-subquest-text-chars", type=int, default=4200)
    parser.add_argument("--max-achievements", type=int, default=900)
    parser.add_argument("--max-materials", type=int, default=1000)
    parser.add_argument("--weapon-owner-file", default="resources/genshin_seed/extensions/weapon_owner_links.json")
    parser.add_argument("--lore-file", default="resources/genshin_seed/extensions/lore_entries.json")
    parser.add_argument("--include-external-lore", action="store_true", default=True)
    parser.add_argument("--clean", action="store_true")
    build_seed(parser.parse_args())
