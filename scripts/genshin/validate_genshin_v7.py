#!/usr/bin/env python3
"""Validate a generated Genshin v7 seed without touching runtime services."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


REQUIRED_METADATA = {
    "schema_version",
    "source_version",
    "source_document",
    "document_title",
    "category",
    "doc_type",
    "entity_type",
    "entity_id",
    "section_path",
    "language",
}


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def load_jsonl(path: Path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seed-root", default="resources/genshin_seed_v7")
    parser.add_argument("--max-chars", type=int, default=1100)
    args = parser.parse_args()

    root = Path(args.seed_root).resolve()
    manifest = load_json(root / "manifest.json")
    nodes = load_json(root / "config" / "intent_nodes.json")
    entities = load_jsonl(root / "config" / "entity_catalog.jsonl")
    chunks = load_jsonl(root / "artifacts" / "chunks.jsonl")

    assert manifest["totalChunks"] == len(chunks), "manifest chunk count mismatch"
    assert manifest["entityCount"] == len(entities), "manifest entity count mismatch"
    assert len({entity["source_document"] for entity in entities}) == manifest["totalDocuments"], "entity source coverage mismatch"
    assert len({chunk["logical_chunk_key"] for chunk in chunks}) == len(chunks), "duplicate logical chunk key"
    assert all(chunk["content"].strip() for chunk in chunks), "empty chunk content"
    assert all(chunk["char_count"] <= args.max_chars for chunk in chunks), "oversized chunk found"
    assert all(REQUIRED_METADATA <= set(chunk["metadata"]) for chunk in chunks), "missing metadata field"

    codes = {node["intentCode"] for node in nodes}
    assert len(codes) == len(nodes), "duplicate semantic intent code"
    roots = [node for node in nodes if node.get("parentCode") is None]
    assert len(roots) == 1, "semantic tree must have exactly one root"
    assert all(node.get("parentCode") is None or node["parentCode"] in codes for node in nodes), "orphan intent node"
    assert all(not node["intentCode"].startswith("genshin_char_") for node in nodes), "entity leaked into semantic tree"

    print("v7 seed validation passed")
    print(f"documents={manifest['totalDocuments']}, chunks={len(chunks)}, semantic_nodes={len(nodes)}")


if __name__ == "__main__":
    main()
