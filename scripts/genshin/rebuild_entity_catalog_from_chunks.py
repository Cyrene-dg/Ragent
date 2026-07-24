#!/usr/bin/env python3
"""Rebuild the v7 entity catalog from the authoritative chunk metadata.

The v7 chunks retain the correctly normalized document title and entity
metadata.  This utility makes the catalog derived from those artifacts rather
than from legacy source titles that may contain encoding-damaged text.
"""

from __future__ import annotations

import argparse
import json
import re
from collections import OrderedDict
from pathlib import Path


EN_NAME_RE = re.compile(r"^-\s+EN Name:\s*(.+?)\s*$", re.MULTILINE)
CJK_NAME_RE = re.compile(r"[\u4e00-\u9fff]{2,}")


def read_english_name(content: str) -> str | None:
    match = EN_NAME_RE.search(content)
    return match.group(1).strip() if match else None


def aliases_for(entity_type: str, entity_name: str, english_name: str | None) -> list[str]:
    aliases: list[str] = [entity_name]
    if english_name:
        aliases.append(english_name)

    # Character questions commonly use the final two Chinese characters, e.g.
    # "神里绫华" -> "绫华".  Collisions are handled by EntityResolver rather than
    # silently selecting one entity.
    if entity_type == "character":
        for name_part in CJK_NAME_RE.findall(entity_name):
            if len(name_part) >= 3:
                aliases.append(name_part[-2:])

    return list(OrderedDict.fromkeys(alias.strip() for alias in aliases if alias and alias.strip()))


def rebuild(chunks_path: Path, output_path: Path) -> int:
    entries: OrderedDict[tuple[str, str, str], dict] = OrderedDict()
    with chunks_path.open(encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            metadata = row.get("metadata", {})
            entity_type = metadata.get("entity_type")
            entity_id = metadata.get("entity_id")
            source_document = metadata.get("source_document")
            entity_name = metadata.get("document_title")
            if not all((entity_type, entity_id, source_document, entity_name)):
                continue

            key = (str(entity_type), str(entity_id), str(source_document))
            if key in entries:
                continue
            english_name = read_english_name(row.get("content", ""))
            entries[key] = {
                "entity_type": entity_type,
                "entity_id": str(entity_id),
                "entity_name": entity_name,
                "aliases": aliases_for(str(entity_type), str(entity_name), english_name),
                "category": metadata.get("category"),
                "source_document": source_document,
                "source_version": metadata.get("source_version"),
            }

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8", newline="\n") as handle:
        for entry in entries.values():
            handle.write(json.dumps(entry, ensure_ascii=False, separators=(",", ":")))
            handle.write("\n")
    return len(entries)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--chunks",
        type=Path,
        default=Path("resources/genshin_seed_v7/artifacts/chunks.jsonl"),
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("resources/genshin_seed_v7/config/entity_catalog.jsonl"),
    )
    args = parser.parse_args()
    count = rebuild(args.chunks, args.output)
    print(f"rebuilt entity catalog: {count} entries -> {args.output}")


if __name__ == "__main__":
    main()
