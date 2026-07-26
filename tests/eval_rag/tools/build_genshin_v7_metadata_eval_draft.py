#!/usr/bin/env python3
"""Build a reviewable first-pass Gold-label draft for Genshin v7 metadata retrieval.

This tool intentionally does *not* declare any candidate to be Gold.  It only
reads stable logical_chunk_key values from the v7 seed artifacts and gives a
human reviewer concrete evidence candidates to confirm or replace.
"""

from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict
from pathlib import Path
from typing import Any


def load_chunks(path: Path) -> list[dict[str, Any]]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def chunk_key_for(
    chunks: list[dict[str, Any]],
    *,
    entity_name: str,
    doc_type: str,
) -> str:
    matches = [
        row["logical_chunk_key"]
        for row in chunks
        if row.get("metadata", {}).get("document_title") == entity_name
        and row.get("metadata", {}).get("doc_type") == doc_type
    ]
    if len(matches) != 1:
        raise ValueError(f"Expected one {doc_type} chunk for {entity_name}, got {len(matches)}")
    return matches[0]


def entity_records_with_chunk_types(
    chunks: list[dict[str, Any]],
    *,
    category: str,
    required_doc_types: set[str],
) -> list[dict[str, Any]]:
    """Build stable, non-trial entity records from source chunks only."""
    grouped: dict[str, dict[str, Any]] = {}
    names: dict[str, set[str]] = defaultdict(set)
    for row in chunks:
        metadata = row.get("metadata", {})
        if metadata.get("category") != category:
            continue
        entity_id = str(metadata.get("entity_id", ""))
        name = str(metadata.get("document_title", ""))
        doc_type = str(metadata.get("doc_type", ""))
        if not entity_id or not name or doc_type not in required_doc_types:
            continue
        if TRIAL_SUFFIX.search(name) or name.startswith("(test)") or name == "空武器":
            continue
        record = grouped.setdefault(entity_id, {"entity_id": entity_id, "entity_name": name, "keys": {}})
        record["keys"][doc_type] = str(row["logical_chunk_key"])
        names[name].add(entity_id)

    # A query made from a non-unique visible name would be an ambiguous test, not a clean
    # single-entity Gold case. Keep that situation for dedicated ambiguity tests instead.
    records = [
        record for record in grouped.values()
        if required_doc_types.issubset(record["keys"])
        and len(names[record["entity_name"]]) == 1
    ]
    return sorted(records, key=lambda record: int(record["entity_id"]))


TRIAL_SUFFIX = re.compile(r"[（(]试用[）)]$")


def character_profiles_by_element(chunks: list[dict[str, Any]]) -> tuple[dict[str, list[dict[str, str]]], dict[str, str]]:
    unique: dict[str, dict[str, dict[str, str]]] = defaultdict(dict)
    for row in chunks:
        metadata = row.get("metadata", {})
        if metadata.get("category") != "character" or metadata.get("doc_type") != "character_profile":
            continue
        element = metadata.get("element")
        entity_id = metadata.get("entity_id")
        if not element or not entity_id:
            continue
        unique[str(element)][str(entity_id)] = {
            "entity_id": str(entity_id),
            "entity_name": str(metadata.get("document_title", "")),
            "logical_chunk_key": str(row["logical_chunk_key"]),
        }
    canonical_ids_by_name: dict[str, str] = {}
    for records in unique.values():
        for entity in records.values():
            name = entity["entity_name"]
            if not TRIAL_SUFFIX.search(name):
                canonical_ids_by_name.setdefault(name, entity["entity_id"])

    trial_equivalents: dict[str, str] = {}
    normalized: dict[str, list[dict[str, str]]] = {}
    for element, records in unique.items():
        kept: list[dict[str, str]] = []
        for entity in records.values():
            if TRIAL_SUFFIX.search(entity["entity_name"]):
                base_name = TRIAL_SUFFIX.sub("", entity["entity_name"])
                canonical_id = canonical_ids_by_name.get(base_name)
                if canonical_id and canonical_id != entity["entity_id"]:
                    trial_equivalents[entity["entity_id"]] = canonical_id
                    continue
            kept.append(entity)
        normalized[element] = sorted(kept, key=lambda row: row["entity_id"])
    return normalized, trial_equivalents


def needs_review(
    *,
    case_id: str,
    query: str,
    case_type: str,
    answerable: bool,
    expected_filters: dict[str, Any],
    candidate_keys: list[str] | None = None,
    expected_entities: list[dict[str, str]] | None = None,
    note: str,
) -> dict[str, Any]:
    return {
        "id": case_id,
        "query": query,
        "type": case_type,
        "answerable": answerable,
        "expected_filters": expected_filters,
        "candidate_gold_chunk_keys": candidate_keys or [],
        "candidate_expected_entities": expected_entities or [],
        "gold_chunk_keys": [],
        "expected_entity_ids": [],
        "review_status": "needs_human_confirmation",
        "review_note": note,
    }


def build_draft(chunks: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], dict[str, str]]:
    profiles, trial_equivalents = character_profiles_by_element(chunks)
    ayaka_skill = chunk_key_for(chunks, entity_name="神里绫华", doc_type="character_skill")
    diluc_skill = chunk_key_for(chunks, entity_name="迪卢克", doc_type="character_skill")
    wolf_passive = chunk_key_for(chunks, entity_name="狼的末路", doc_type="weapon_profile")

    return [
        needs_review(
            case_id="single_entity_ayaka_skill",
            query="神里绫华的元素战技是什么？",
            case_type="single_entity",
            answerable=True,
            expected_filters={"category": "character", "entity_id": "10000002", "doc_type": "character_skill"},
            candidate_keys=[ayaka_skill],
            note="确认该技能 Chunk 是否足以支撑技能名称和效果。",
        ),
        needs_review(
            case_id="single_entity_wolfs_gravestone_passive",
            query="狼的末路的被动效果是什么？",
            case_type="single_entity",
            answerable=True,
            expected_filters={"category": "weapon", "entity_id": "12502", "doc_type": "weapon_profile"},
            candidate_keys=[wolf_passive],
            note="确认该武器资料是否包含被动的完整效果与限制。",
        ),
        needs_review(
            case_id="single_entity_diluc_burst",
            query="迪卢克的元素爆发有什么效果？",
            case_type="single_entity",
            answerable=True,
            expected_filters={"category": "character", "entity_id": "10000016", "doc_type": "character_skill"},
            candidate_keys=[diluc_skill],
            note="确认该技能 Chunk 是否包含元素爆发的直接证据。",
        ),
        needs_review(
            case_id="alias_ayaka_burst",
            query="绫华的大招是什么？",
            case_type="alias",
            answerable=True,
            expected_filters={"category": "character", "entity_id": "10000002", "doc_type": "character_skill"},
            candidate_keys=[ayaka_skill],
            note="验证别名绫华能落到神里绫华；确认同一技能 Chunk 可作为 Gold。",
        ),
        needs_review(
            case_id="alias_wolf_passive",
            query="狼末的被动怎么触发？",
            case_type="alias",
            answerable=True,
            expected_filters={"category": "weapon", "entity_id": "12502", "doc_type": "weapon_profile"},
            candidate_keys=[wolf_passive],
            note="验证别名狼末能落到狼的末路；确认该资料包含触发条件。",
        ),
        needs_review(
            case_id="collection_cryo_characters",
            query="原神中冰系角色有哪些？",
            case_type="entity_collection",
            answerable=True,
            expected_filters={"category": "character", "element": "cryo", "doc_type": "character_profile"},
            expected_entities=profiles.get("cryo", []),
            note="确认 v7 数据中的冰元素角色集合；集合题用实体覆盖率而不是单一 Gold Chunk 计分。",
        ),
        needs_review(
            case_id="collection_pyro_characters",
            query="原神中火系角色有哪些？",
            case_type="entity_collection",
            answerable=True,
            expected_filters={"category": "character", "element": "pyro", "doc_type": "character_profile"},
            expected_entities=profiles.get("pyro", []),
            note="确认 v7 数据中的火元素角色集合；集合题用实体覆盖率而不是单一 Gold Chunk 计分。",
        ),
        needs_review(
            case_id="collection_anemo_characters",
            query="原神中风系角色有哪些？",
            case_type="entity_collection",
            answerable=True,
            expected_filters={"category": "character", "element": "anemo", "doc_type": "character_profile"},
            expected_entities=profiles.get("anemo", []),
            note="确认 v7 数据中的风元素角色集合；集合题用实体覆盖率而不是单一 Gold Chunk 计分。",
        ),
        needs_review(
            case_id="generic_character_skill",
            query="角色的元素战技会造成什么类型的伤害？",
            case_type="generic_no_entity_diagnostic",
            answerable=True,
            expected_filters={"category": "character", "doc_type": "character_skill"},
            note="无实体泛问：先只检查不应被 entity_id 或 element 硬过滤；不进入第一轮主指标。",
        ),
        needs_review(
            case_id="ood_silver_wolf_skill",
            query="银狼的技能是什么？",
            case_type="out_of_domain_diagnostic",
            answerable=False,
            expected_filters={},
            note="领域外诊断题：记录误路由和证据缺口，不纳入 Hit@5、MRR 主指标。",
        ),
    ], trial_equivalents


def build_extended_draft(chunks: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], dict[str, str]]:
    """Create a 56-case review draft; no entry is promoted to Gold here."""
    cases, trial_equivalents = build_draft(chunks)
    characters = entity_records_with_chunk_types(
        chunks,
        category="character",
        required_doc_types={"character_profile", "character_skill"},
    )
    weapons = entity_records_with_chunk_types(
        chunks,
        category="weapon",
        required_doc_types={"weapon_profile"},
    )

    # Exclude already-covered characters and the multi-element traveller. The latter is useful
    # later as a dedicated ambiguity case, but not as a simple single-entity acceptance case.
    covered_character_ids = {"10000002", "10000016"}
    clean_characters = [
        record for record in characters
        if record["entity_id"] not in covered_character_ids and record["entity_name"] != "旅行者"
    ]
    skill_records = clean_characters[:16]
    profile_records = clean_characters[16:26]
    if len(skill_records) != 16 or len(profile_records) != 10:
        raise ValueError("Not enough unambiguous character records for the extended review set")

    for record in skill_records:
        cases.append(needs_review(
            case_id=f"single_entity_{record['entity_id']}_skill",
            query=f"{record['entity_name']}的元素战技是什么？",
            case_type="single_entity",
            answerable=True,
            expected_filters={
                "category": "character",
                "entity_id": record["entity_id"],
                "doc_type": "character_skill",
            },
            candidate_keys=[record["keys"]["character_skill"]],
            note="确认角色技能 Chunk 含有元素战技的直接证据。",
        ))

    for record in profile_records:
        cases.append(needs_review(
            case_id=f"single_entity_{record['entity_id']}_profile",
            query=f"{record['entity_name']}使用什么武器？",
            case_type="single_entity",
            answerable=True,
            expected_filters={
                "category": "character",
                "entity_id": record["entity_id"],
                "doc_type": "character_profile",
            },
            candidate_keys=[record["keys"]["character_profile"]],
            note="确认角色资料 Chunk 含有武器类型等基础资料。",
        ))

    # Prefer normal named weapons over test fixtures and 1-star starter records.
    weapon_records = [record for record in weapons if int(record["entity_id"]) >= 11400 and record["entity_id"] != "12502"][:10]
    if len(weapon_records) != 10:
        raise ValueError("Not enough weapon records for the extended review set")
    for record in weapon_records:
        cases.append(needs_review(
            case_id=f"single_entity_{record['entity_id']}_passive",
            query=f"{record['entity_name']}的被动效果是什么？",
            case_type="single_entity",
            answerable=True,
            expected_filters={
                "category": "weapon",
                "entity_id": record["entity_id"],
                "doc_type": "weapon_profile",
            },
            candidate_keys=[record["keys"]["weapon_profile"]],
            note="确认武器资料 Chunk 含有武器技能或被动效果。",
        ))

    profiles, _ = character_profiles_by_element(chunks)
    element_names = {"electro": "雷", "hydro": "水", "geo": "岩", "dendro": "草"}
    for element, element_name in element_names.items():
        cases.append(needs_review(
            case_id=f"collection_{element}_characters",
            query=f"原神中{element_name}元素角色有哪些？",
            case_type="entity_collection",
            answerable=True,
            expected_filters={"category": "character", "element": element, "doc_type": "character_profile"},
            expected_entities=profiles.get(element, []),
            note="确认当前 v7 语料中的元素角色集合；正式评测使用实体覆盖率。",
        ))

    aliases = [
        ("alias_raiden_shogun", "雷神的元素爆发是什么？", "雷电将军", "character_skill"),
        ("alias_nahida_skill", "草神的元素战技是什么？", "纳西妲", "character_skill"),
        ("alias_kazuha_burst", "万叶的大招是什么？", "枫原万叶", "character_skill"),
    ]
    for case_id, query, name, doc_type in aliases:
        entity = next(record for record in characters if record["entity_name"] == name)
        key = entity["keys"][doc_type]
        cases.append(needs_review(
            case_id=case_id,
            query=query,
            case_type="alias",
            answerable=True,
            expected_filters={"category": "character", "entity_id": entity["entity_id"], "doc_type": doc_type},
            candidate_keys=[key],
            note=f"确认别名可落到{name}，且该技能 Chunk 可作为 Gold。",
        ))

    cases.extend([
        needs_review(
            case_id="generic_weapon_passive",
            query="五星大剑的被动通常有哪些特点？",
            case_type="generic_no_entity_diagnostic",
            answerable=True,
            expected_filters={"category": "weapon", "doc_type": "weapon_profile"},
            note="无实体泛问：检查不应被 entity_id 硬过滤；暂不计入主指标。",
        ),
        needs_review(
            case_id="generic_hydro_character_skill",
            query="水元素角色的元素战技通常有什么效果？",
            case_type="generic_no_entity_diagnostic",
            answerable=True,
            expected_filters={"category": "character", "element": "hydro", "doc_type": "character_skill"},
            note="检查没有具体实体时，类别与元素条件是否仍可生效；暂不计入主指标。",
        ),
        needs_review(
            case_id="ood_honkai_character_skill",
            query="崩坏：星穹铁道中卡芙卡的技能是什么？",
            case_type="out_of_domain_diagnostic",
            answerable=False,
            expected_filters={},
            note="领域外诊断题：记录是否误路由，不纳入主指标。",
        ),
    ])
    if len(cases) != 56:
        raise AssertionError(f"Expected 56 extended cases, got {len(cases)}")
    return cases, trial_equivalents


def write_review_markdown(
    cases: list[dict[str, Any]],
    trial_equivalents: dict[str, str],
    chunks_by_key: dict[str, dict[str, Any]],
    output: Path,
) -> None:
    lines = [
        "# 原神 v7 元数据检索：首轮 Gold 标注待确认清单",
        "",
        "这是脚本根据 `chunks.jsonl` 自动找出的候选证据，尚未成为 Gold 标注。",
        "请只确认两件事：候选 Chunk 是否确实能回答问题；集合题的角色列表是否符合当前 v7 语料。",
        "确认后会把候选字段复制到 `gold_chunk_keys` / `expected_entity_ids`，再运行 A/B。",
        "",
    ]
    if trial_equivalents:
        lines.extend([
            "## 试用副本归一化规则",
            "",
            "集合题以逻辑角色计数：试用副本不单独计入预期实体，若检索到它则映射回正式角色并记录为重复变体。",
            "",
        ])
        for trial_id, canonical_id in sorted(trial_equivalents.items()):
            lines.append(f"- 试用实体 `{trial_id}` → 正式实体 `{canonical_id}`")
        lines.append("")
    for index, case in enumerate(cases, start=1):
        lines.extend([
            f"## {index}. {case['id']}",
            "",
            f"- 问题：{case['query']}",
            f"- 类型：{case['type']}",
            f"- 预期过滤：`{json.dumps(case['expected_filters'], ensure_ascii=False)}`",
            f"- 请确认：{case['review_note']}",
        ])
        if case["candidate_gold_chunk_keys"]:
            lines.append("- 候选 Chunk：")
            for key in case["candidate_gold_chunk_keys"]:
                lines.append(f"  - `{key}`")
                content = str(chunks_by_key.get(key, {}).get("content", "")).strip().replace("\n", " ")
                if content:
                    excerpt = content[:700] + ("…" if len(content) > 700 else "")
                    lines.append(f"    - 证据摘录：{excerpt}")
        if case["candidate_expected_entities"]:
            lines.append(f"- 候选实体（{len(case['candidate_expected_entities'])} 个）：")
            lines.extend([
                f"  - `{entity['entity_id']}` {entity['entity_name']}"
                for entity in case["candidate_expected_entities"]
            ])
        lines.append("")
    output.write_text("\n".join(lines), encoding="utf-8")


def confirm_cases(cases: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Promote reviewed candidates to a reproducible Gold set after human approval."""
    confirmed: list[dict[str, Any]] = []
    for original in cases:
        case = dict(original)
        case["gold_chunk_keys"] = list(case.pop("candidate_gold_chunk_keys", []))
        entities = case.pop("candidate_expected_entities", [])
        case["expected_entity_ids"] = [entity["entity_id"] for entity in entities]
        case["review_status"] = "confirmed"
        case["review_note"] = "Approved from the first-pass v7 Gold review."
        confirmed.append(case)
    return confirmed


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--chunks", type=Path, default=Path("resources/genshin_seed_v7/artifacts/chunks.jsonl"))
    parser.add_argument("--output", type=Path,
                        default=Path("tests/eval_rag/data/genshin_v7_metadata_gold.draft.jsonl"))
    parser.add_argument("--review-output", type=Path,
                        default=Path("docs/genshin-v7-metadata-gold-review.md"))
    parser.add_argument("--confirm-candidates", action="store_true",
                        help="Promote the reviewed candidate evidence to final Gold fields.")
    parser.add_argument("--suite", choices=("pilot", "extended"), default="pilot",
                        help="pilot creates 10 cases; extended creates the 56-case review draft.")
    args = parser.parse_args()

    chunks = load_chunks(args.chunks)
    builder = build_extended_draft if args.suite == "extended" else build_draft
    draft_cases, trial_equivalents = builder(chunks)
    cases = draft_cases
    if args.confirm_candidates:
        cases = confirm_cases(draft_cases)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as handle:
        for case in cases:
            handle.write(json.dumps(case, ensure_ascii=False) + "\n")
    args.review_output.parent.mkdir(parents=True, exist_ok=True)
    write_review_markdown(
        draft_cases,
        trial_equivalents,
        {str(chunk["logical_chunk_key"]): chunk for chunk in chunks},
        args.review_output,
    )
    print(f"{'confirmed' if args.confirm_candidates else 'draft'}_cases={len(cases)}")
    print(f"jsonl={args.output}")
    print(f"review={args.review_output}")


if __name__ == "__main__":
    main()
