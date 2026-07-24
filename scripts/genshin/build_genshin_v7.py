#!/usr/bin/env python3
"""Build the non-destructive v7 Genshin routing and chunking seed.

The v6 folder remains the canonical source corpus.  This script only reads it
and produces v7 configuration/manifest files; it neither calls an LLM nor
connects to MySQL, Milvus, Elasticsearch, or Redis.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Iterable


HEADING_RE = re.compile(r"^(#{1,6})\s+(.+?)\s*$", re.MULTILINE)
KEY_VALUE_RE = re.compile(r"^-\s+([^:：]+)\s*[:：]\s*(.+?)\s*$", re.MULTILINE)
SAFE_ALIAS_RE = re.compile(r"\s+")

TARGET_CHARS = 760
MAX_CHARS = 1100
MIN_CHARS = 340
OVERLAP_CHARS = 80


@dataclass(frozen=True)
class MarkdownBlock:
    section_path: tuple[str, ...]
    content: str


def dump_json(path: Path, data: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def dump_jsonl(path: Path, rows: Iterable[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")))
            handle.write("\n")


def normalized(value: str) -> str:
    return SAFE_ALIAS_RE.sub(" ", value or "").strip()


def title_from_markdown(text: str, fallback: str) -> str:
    match = re.search(r"^#\s+(.+?)\s*$", text, flags=re.MULTILINE)
    raw = match.group(1).strip() if match else fallback
    return raw.split(":", 1)[-1].strip()


def read_basic_value(text: str, label: str) -> str | None:
    for key, value in KEY_VALUE_RE.findall(text):
        if normalized(key).lower() == label.lower():
            return normalized(value)
    return None


def markdown_blocks(text: str, title: str) -> list[MarkdownBlock]:
    """Return semantic H2/H3 blocks, preserving the title in every block."""
    matches = list(HEADING_RE.finditer(text))
    h2_positions = [index for index, match in enumerate(matches) if len(match.group(1)) == 2]
    if not h2_positions:
        return [MarkdownBlock((title,), f"# {title}\n\n{text.strip()}")]

    blocks: list[MarkdownBlock] = []
    for position_index, match_index in enumerate(h2_positions):
        h2 = matches[match_index]
        h2_name = h2.group(2).strip()
        if h2_name.lower() == "source":
            # Provenance is retained as metadata; it is not useful retrieval
            # evidence and should not become an isolated tiny chunk.
            continue
        next_h2_index = h2_positions[position_index + 1] if position_index + 1 < len(h2_positions) else len(matches)
        section_end = matches[next_h2_index].start() if next_h2_index < len(matches) else len(text)
        nested = [candidate for candidate in matches[match_index + 1:next_h2_index] if len(candidate.group(1)) >= 3]

        prefix_end = nested[0].start() if nested else section_end
        prefix = text[h2.end():prefix_end].strip()
        if prefix:
            blocks.append(MarkdownBlock((title, h2_name), f"# {title}\n\n## {h2_name}\n{prefix}"))

        for nested_index, child in enumerate(nested):
            child_name = child.group(2).strip()
            child_end = nested[nested_index + 1].start() if nested_index + 1 < len(nested) else section_end
            child_body = text[child.end():child_end].strip()
            if child_body:
                blocks.append(MarkdownBlock(
                    (title, h2_name, child_name),
                    f"# {title}\n\n## {h2_name}\n\n### {child_name}\n{child_body}",
                ))

    return blocks or [MarkdownBlock((title,), f"# {title}\n\n{text.strip()}")]


def sentence_fragments(text: str, maximum: int) -> list[str]:
    """Split only an over-large semantic block, preferring paragraph then sentence boundaries."""
    paragraphs = [part.strip() for part in re.split(r"\n\s*\n", text) if part.strip()]
    if len(paragraphs) <= 1:
        paragraphs = [part.strip() for part in re.split(r"(?<=[。！？!?；;])\s*", text) if part.strip()]

    pieces: list[str] = []
    current = ""
    for paragraph in paragraphs:
        if len(paragraph) > maximum:
            if current:
                pieces.append(current)
                current = ""
            for offset in range(0, len(paragraph), maximum):
                pieces.append(paragraph[offset:offset + maximum])
            continue
        candidate = f"{current}\n\n{paragraph}".strip() if current else paragraph
        if current and len(candidate) > maximum:
            pieces.append(current)
            current = paragraph
        else:
            current = candidate
    if current:
        pieces.append(current)
    return pieces or [text]


def split_block(block: MarkdownBlock) -> list[MarkdownBlock]:
    if len(block.content) <= MAX_CHARS:
        return [block]
    header = "\n\n".join(
        [f"# {block.section_path[0]}"]
        + [f"{'#' * (index + 2)} {name}" for index, name in enumerate(block.section_path[1:])]
    )
    body = block.content[len(header):].strip()
    # Reserve room for headers and the overlap so every emitted chunk stays
    # below MAX_CHARS, including non-first chunks.
    budget = max(200, MAX_CHARS - len(header) - OVERLAP_CHARS - 4)
    fragments = sentence_fragments(body, budget)
    out: list[MarkdownBlock] = []
    for index, fragment in enumerate(fragments):
        if index and OVERLAP_CHARS > 0 and out:
            previous_body = out[-1].content[len(header):].strip()
            overlap = previous_body[-OVERLAP_CHARS:]
            fragment = f"{overlap}\n\n{fragment}"
        out.append(MarkdownBlock(block.section_path, f"{header}\n\n{fragment}".strip()))
    return out


def chunk_doc_type(category: str, path: tuple[str, ...]) -> str:
    section = " ".join(path[1:]).lower()
    if category == "characters":
        if "skill" in section:
            return "character_skill"
        if "constellation" in section:
            return "character_constellation"
        if "story" in section or "stori" in section:
            return "character_story"
        return "character_profile"
    if category == "weapons":
        return "weapon_passive" if "passive" in section else "weapon_profile"
    if category == "weapon_owners":
        return "weapon_owner_relation"
    if category == "achievements":
        return "achievement_condition"
    if category == "artifacts":
        return "artifact_set"
    if category == "chapters":
        return "story_chapter"
    if category == "main_quests":
        return "quest_flow"
    if category == "materials":
        return "material_information"
    if category == "lore_extensions":
        return "lore_entry"
    return "document"


def default_doc_type(category: str) -> str:
    return {
        "characters": "character_profile",
        "weapons": "weapon_profile",
        "weapon_owners": "weapon_owner_relation",
        "achievements": "achievement_condition",
        "artifacts": "artifact_set",
        "chapters": "story_chapter",
        "main_quests": "quest_flow",
        "materials": "material_information",
        "lore_extensions": "lore_entry",
    }.get(category, "document")


def category_for_folder(folder: str) -> str:
    return {
        "characters": "character",
        "weapons": "weapon",
        "weapon_owners": "weapon",
        "achievements": "achievement",
        "artifacts": "artifact",
        "chapters": "story",
        "main_quests": "quest",
        "materials": "material",
        "lore_extensions": "lore",
    }.get(folder, "document")


def entity_info(relative_path: Path, title: str, text: str) -> dict:
    folder = relative_path.parts[0] if relative_path.parts else ""
    filename = relative_path.stem
    patterns = {
        "characters": (r"^avatar_(\d+)_", "character"),
        "weapons": (r"^weapon_(\d+)_", "weapon"),
        "weapon_owners": (r"^weapon_owner_(\d+)_", "weapon"),
        "achievements": (r"^achievement_(\d+)_", "achievement"),
        "artifacts": (r"^artifact_(\d+)_", "artifact_set"),
        "chapters": (r"^chapter_(\d+)_", "chapter"),
        "main_quests": (r"^main_quest_(\d+)_", "main_quest"),
        "materials": (r"^material_(\d+)_", "material"),
    }
    pattern, entity_type = patterns.get(folder, (None, category_for_folder(folder)))
    match = re.match(pattern, filename) if pattern else None
    entity_id = match.group(1) if match else filename
    aliases = [title]
    english_name = read_basic_value(text, "EN Name")
    if english_name:
        aliases.append(english_name)
    aliases = list(dict.fromkeys(alias for alias in map(normalized, aliases) if alias))
    return {
        "entity_type": entity_type,
        "entity_id": entity_id,
        "entity_name": title,
        "aliases": aliases,
    }


def pack_blocks(blocks: list[MarkdownBlock], category: str) -> list[tuple[MarkdownBlock, str]]:
    """Pack nearby blocks of the same semantic type; preserve all short documents intact."""
    if len(blocks) == 1 and len(blocks[0].content) <= MAX_CHARS:
        return [(blocks[0], chunk_doc_type(category, blocks[0].section_path))]

    grouped: list[tuple[MarkdownBlock, str]] = []
    current: MarkdownBlock | None = None
    current_type: str | None = None
    for original in blocks:
        for block in split_block(original):
            doc_type = chunk_doc_type(category, block.section_path)
            if current is None:
                current, current_type = block, doc_type
                continue
            combined = f"{current.content}\n\n{block.content}"
            if doc_type == current_type and len(combined) <= TARGET_CHARS:
                common_length = 0
                for left, right in zip(current.section_path, block.section_path):
                    if left != right:
                        break
                    common_length += 1
                current = MarkdownBlock(current.section_path[:common_length], combined)
                continue
            grouped.append((current, current_type))
            current, current_type = block, doc_type
    if current is not None:
        grouped.append((current, current_type or "document"))
    return grouped


def semantic_tree() -> tuple[list[dict], list[dict]]:
    root = "genshin_v7_domain"
    nodes = [
        (root, "原神知识问答", 0, None, "原神官方资料、任务、角色、武器、成就与材料查询", ["神里绫华有什么技能", "旧日的阴云怎么做"]),
        ("genshin_v7_character", "角色", 1, root, "角色基础资料、技能、命之座和背景故事", ["神里绫华是什么武器", "琴的命座效果"]),
        ("genshin_v7_character_profile", "基础资料", 2, "genshin_v7_character", "角色等级、稀有度、武器类型、阵营和称号", ["神里绫华是几星", "神里绫华用什么武器类型"]),
        ("genshin_v7_character_skill", "技能与命座", 2, "genshin_v7_character", "角色普通攻击、元素战技、元素爆发与命之座效果", ["神里绫华大招是什么", "神里绫华一命效果"]),
        ("genshin_v7_character_story", "背景与故事", 2, "genshin_v7_character", "角色背景、角色故事和设定", ["神里绫华的背景故事"]),
        ("genshin_v7_weapon", "武器", 1, root, "武器基础属性、被动效果和突破信息", ["黎明破晓之史的被动", "若水是什么武器"]),
        ("genshin_v7_weapon_detail", "属性与被动", 2, "genshin_v7_weapon", "武器星级、类型、描述、被动与精炼效果", ["黎明破晓之史 R1 效果"]),
        ("genshin_v7_artifact", "圣遗物", 1, root, "圣遗物套装资料与属性", ["某套圣遗物有什么效果"]),
        ("genshin_v7_artifact_detail", "套装资料", 2, "genshin_v7_artifact", "圣遗物套装及部位资料", ["某套圣遗物的套装效果"]),
        ("genshin_v7_quest", "任务与剧情", 1, root, "主线任务、章节、流程与剧情资料", ["旧日的阴云怎么做", "任务前置是什么"]),
        ("genshin_v7_quest_flow", "任务流程", 2, "genshin_v7_quest", "任务摘要、前置和连续步骤", ["旧日的阴云下一步做什么"]),
        ("genshin_v7_story", "章节与设定", 2, "genshin_v7_quest", "剧情章节与世界观设定", ["浪花不再归海是哪一章"]),
        ("genshin_v7_achievement", "成就", 1, root, "成就名称、所属组和达成条件", ["被遗忘的纸带怎么完成"]),
        ("genshin_v7_achievement_condition", "条件与前置", 2, "genshin_v7_achievement", "成就触发条件、进度和前置", ["被遗忘的纸带条件"]),
        ("genshin_v7_material", "材料", 1, root, "材料类型、用途和资料", ["某材料是什么"]),
        ("genshin_v7_material_detail", "材料资料", 2, "genshin_v7_material", "材料名称、类型与用途", ["某材料有什么用"]),
        ("genshin_v7_lore", "补充设定", 1, root, "补充世界观和设定资料", ["原神某地区设定"]),
    ]
    intent_nodes = [
        {
            "intentCode": code,
            "name": name,
            "level": level,
            "parentCode": parent,
            "description": description,
            "examples": examples,
            "topK": 8 if level < 2 else 6,
            "kind": 0,
            "sortOrder": index + 1,
            "enabled": 1,
        }
        for index, (code, name, level, parent, description, examples) in enumerate(nodes)
    ]
    routes = [
        {"intentCode": "genshin_v7_character_profile", "metadataFilters": {"category": "character", "doc_type": "character_profile"}},
        {"intentCode": "genshin_v7_character_skill", "metadataFilters": {"category": "character", "doc_type": ["character_skill", "character_constellation"]}},
        {"intentCode": "genshin_v7_character_story", "metadataFilters": {"category": "character", "doc_type": "character_story"}},
        {"intentCode": "genshin_v7_weapon_detail", "metadataFilters": {"category": "weapon", "doc_type": ["weapon_profile", "weapon_passive"]}},
        {"intentCode": "genshin_v7_artifact_detail", "metadataFilters": {"category": "artifact"}},
        {"intentCode": "genshin_v7_quest_flow", "metadataFilters": {"category": "quest", "doc_type": "quest_flow"}},
        {"intentCode": "genshin_v7_story", "metadataFilters": {"category": ["story", "lore"]}},
        {"intentCode": "genshin_v7_achievement_condition", "metadataFilters": {"category": "achievement"}},
        {"intentCode": "genshin_v7_material_detail", "metadataFilters": {"category": "material"}},
    ]
    return intent_nodes, routes


def build(args: argparse.Namespace) -> dict:
    source_root = Path(args.source_root).resolve()
    output_root = Path(args.output_root).resolve()
    docs_root = source_root / "docs"
    if not docs_root.is_dir():
        raise SystemExit(f"source docs not found: {docs_root}")
    if args.clean and output_root.exists():
        shutil.rmtree(output_root)
    config_root = output_root / "config"
    artifact_root = output_root / "artifacts"
    output_root.mkdir(parents=True, exist_ok=True)

    chunk_rows: list[dict] = []
    entity_rows: list[dict] = []
    split_docs: list[dict] = []
    split_docs_by_category: Counter[str] = Counter()
    category_docs: Counter[str] = Counter()
    category_chunks: Counter[str] = Counter()
    doc_type_chunks: Counter[str] = Counter()

    markdown_files = sorted(docs_root.rglob("*.md"))
    for source_file in markdown_files:
        relative = source_file.relative_to(docs_root)
        folder = relative.parts[0] if len(relative.parts) > 1 else "misc"
        raw = source_file.read_text(encoding="utf-8")
        title = title_from_markdown(raw, source_file.stem)
        entity = entity_info(relative, title, raw)
        category = category_for_folder(folder)
        # Atomic facts should remain atomic.  H2/H3 parsing is reserved for
        # documents that actually exceed the configured cap.
        if len(raw) <= MAX_CHARS:
            packed = [(MarkdownBlock((title,), raw.strip()), default_doc_type(folder))]
        else:
            blocks = markdown_blocks(raw, title)
            packed = pack_blocks(blocks, folder)
        category_docs[category] += 1
        entity_rows.append({
            **entity,
            "category": category,
            "source_document": relative.as_posix(),
            "source_version": "genshin_seed_v6",
        })
        if len(raw) > MAX_CHARS and len(packed) > 1:
            split_docs.append({
                "source_document": relative.as_posix(),
                "document_title": title,
                "chunk_count": len(packed),
                "char_count": len(raw),
            })
            split_docs_by_category[category] += 1
        for index, (block, doc_type) in enumerate(packed):
            logical_key = f"{relative.as_posix()}#{index}"
            chunk_rows.append({
                "logical_chunk_key": logical_key,
                "content": block.content,
                "content_hash": hashlib.sha256(block.content.encode("utf-8")).hexdigest(),
                "char_count": len(block.content),
                "metadata": {
                    "schema_version": "v7",
                    "source_version": "genshin_seed_v6",
                    "source_document": relative.as_posix(),
                    "document_title": title,
                    "category": category,
                    "doc_type": doc_type,
                    "entity_type": entity["entity_type"],
                    "entity_id": entity["entity_id"],
                    "section_path": " > ".join(block.section_path),
                    "language": "zh-CN",
                },
            })
            category_chunks[category] += 1
            doc_type_chunks[doc_type] += 1

    intents, routes = semantic_tree()
    dump_json(config_root / "intent_nodes.json", intents)
    dump_json(config_root / "intent_routes.json", routes)
    dump_jsonl(config_root / "entity_catalog.jsonl", entity_rows)
    dump_jsonl(artifact_root / "chunks.jsonl", chunk_rows)
    dump_json(artifact_root / "split_documents.json", split_docs)

    manifest = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "sourceDocsRoot": str(docs_root),
        "sourceVersion": "genshin_seed_v6",
        "schemaVersion": "v7",
        "totalDocuments": len(markdown_files),
        "totalChunks": len(chunk_rows),
        "semanticIntentNodeCount": len(intents),
        "entityCount": len(entity_rows),
        "documentsSplit": len(split_docs),
        "splitDocumentCountByCategory": dict(sorted(split_docs_by_category.items())),
        "categoryDocumentCount": dict(sorted(category_docs.items())),
        "categoryChunkCount": dict(sorted(category_chunks.items())),
        "docTypeChunkCount": dict(sorted(doc_type_chunks.items())),
        "chunkPolicy": {
            "targetChars": TARGET_CHARS,
            "maxChars": MAX_CHARS,
            "minChars": MIN_CHARS,
            "overlapChars": OVERLAP_CHARS,
            "notes": "Only oversized semantic sections are split; short documents remain whole.",
        },
        "nextStep": "Import into a new KB and new indexes only after validation; never overwrite v4/v6.",
    }
    dump_json(output_root / "manifest.json", manifest)
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description="Build non-destructive Genshin v7 routing/chunk seed")
    parser.add_argument("--source-root", default="resources/genshin_seed_v6")
    parser.add_argument("--output-root", default="resources/genshin_seed_v7")
    parser.add_argument("--clean", action="store_true", help="Delete only the selected output root before rebuilding")
    args = parser.parse_args()
    manifest = build(args)
    print("=== Genshin v7 seed build complete ===")
    print(f"Source documents: {manifest['totalDocuments']}")
    print(f"Generated chunks: {manifest['totalChunks']}")
    print(f"Semantic intent nodes: {manifest['semanticIntentNodeCount']}")
    print(f"Documents split: {manifest['documentsSplit']}")


if __name__ == "__main__":
    main()
