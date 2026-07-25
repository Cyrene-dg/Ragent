#!/usr/bin/env python3
"""Add authoritative character element metadata to the existing v7 chunk artifact.

This is intentionally a narrow, offline migration: it reads the local
AnimeGameData Excel exports and only updates ``metadata.element`` on character
chunks. It never calls an LLM or a running service.
"""

from __future__ import annotations

import argparse
import json
import shutil
from collections import Counter
from pathlib import Path


ELEMENT_MAP = {
    "Wind": "anemo",
    "Ice": "cryo",
    "Grass": "dendro",
    "Electric": "electro",
    "Rock": "geo",
    "Water": "hydro",
    "Fire": "pyro",
}


def load_list(path: Path) -> list[dict]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, list):
        raise ValueError(f"Expected JSON array: {path}")
    return payload


def build_element_by_avatar(avatar_path: Path, depot_path: Path, skill_path: Path) -> dict[str, str]:
    avatars = {str(row["id"]): row for row in load_list(avatar_path) if row.get("id") is not None}
    depots = {str(row["id"]): row for row in load_list(depot_path) if row.get("id") is not None}
    skills = {str(row["id"]): row for row in load_list(skill_path) if row.get("id") is not None}
    result: dict[str, str] = {}
    for avatar_id, avatar in avatars.items():
        depot = depots.get(str(avatar.get("skillDepotId")))
        if not depot:
            continue
        skill = skills.get(str(depot.get("energySkill")))
        raw_element = skill.get("costElemType") if skill else None
        normalized = ELEMENT_MAP.get(raw_element)
        if normalized:
            result[avatar_id] = normalized
    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seed-root", default="resources/genshin_seed_v7")
    parser.add_argument("--anime-data-root", default="external_data/AnimeGameData/ExcelBinOutput")
    parser.add_argument("--backup-path", default=".codex-runtime/genshin-v7-chunks-before-element.jsonl")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    seed_root = Path(args.seed_root).resolve()
    anime_root = Path(args.anime_data_root).resolve()
    chunk_path = seed_root / "artifacts" / "chunks.jsonl"
    chunks = [json.loads(line) for line in chunk_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    elements = build_element_by_avatar(
        anime_root / "AvatarExcelConfigData.json",
        anime_root / "AvatarSkillDepotExcelConfigData.json",
        anime_root / "AvatarSkillExcelConfigData.json",
    )

    target_character_ids = {
        str(chunk["metadata"].get("entity_id"))
        for chunk in chunks
        if chunk["metadata"].get("category") == "character"
    }
    matched_character_ids = target_character_ids & elements.keys()
    changed = 0
    for chunk in chunks:
        metadata = chunk["metadata"]
        if metadata.get("category") != "character":
            continue
        element = elements.get(str(metadata.get("entity_id")))
        if element is None:
            continue
        if metadata.get("element") != element:
            metadata["element"] = element
            changed += 1

    distribution = Counter(
        chunk["metadata"].get("element", "missing")
        for chunk in chunks if chunk["metadata"].get("category") == "character"
    )
    print(f"character_entities={len(target_character_ids)} mapped={len(matched_character_ids)} unmapped={len(target_character_ids - matched_character_ids)}")
    print(f"character_chunks_changed={changed} distribution={dict(sorted(distribution.items()))}")
    if not args.apply:
        print("Dry run only. Re-run with --apply to write chunks.jsonl.")
        return

    backup_path = Path(args.backup_path).resolve()
    backup_path.parent.mkdir(parents=True, exist_ok=True)
    if not backup_path.exists():
        shutil.copy2(chunk_path, backup_path)
    temp_path = chunk_path.with_suffix(".jsonl.tmp")
    with temp_path.open("w", encoding="utf-8", newline="\n") as handle:
        for chunk in chunks:
            handle.write(json.dumps(chunk, ensure_ascii=False, separators=(",", ":")))
            handle.write("\n")
    temp_path.replace(chunk_path)
    print(f"Updated {chunk_path}; backup={backup_path}")


if __name__ == "__main__":
    main()
