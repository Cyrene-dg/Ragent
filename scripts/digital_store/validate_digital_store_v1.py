#!/usr/bin/env python3
"""Validate digital_store_v1 artifacts without calling databases or models."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from pathlib import Path
from typing import Any


DEFAULT_SEED_ROOT = Path("external_data/digital_store_v1/catalog_rag_seed_v1")
DEFAULT_ROUTE_FILE = Path("resources/digital_store_v1/config/intent_routes.json")
REQUIRED_METADATA = {
    "schema_version", "source_version", "source_document", "document_title",
    "category", "product_kind", "doc_type", "entity_type", "entity_id",
    "brand", "product_code", "section_path", "language",
}


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as error:
                raise ValueError(f"{path}:{line_number}: invalid JSON: {error}") from error
    return rows


def validate(seed_root: Path, route_file: Path) -> list[str]:
    errors: list[str] = []
    paths = {
        "manifest": seed_root / "manifest.json",
        "taxonomy": seed_root / "config/product_taxonomy.json",
        "intents": seed_root / "config/intent_nodes.json",
        "entities": seed_root / "config/entity_catalog.jsonl",
        "contract": seed_root / "config/metadata_contract.json",
        "chunks": seed_root / "artifacts/chunks.jsonl",
        "routes": route_file,
    }
    missing = [str(path) for path in paths.values() if not path.is_file()]
    if missing:
        return [f"missing required file: {path}" for path in missing]

    manifest = load_json(paths["manifest"])
    taxonomy = load_json(paths["taxonomy"])
    intents = load_json(paths["intents"])
    entities = load_jsonl(paths["entities"])
    contract = load_json(paths["contract"])
    chunks = load_jsonl(paths["chunks"])
    routes = load_json(paths["routes"])

    taxonomy_codes = {node.get("code") for node in taxonomy.get("nodes", [])}
    intent_by_code = {node.get("intentCode"): node for node in intents}
    if len(intent_by_code) != len(intents) or None in intent_by_code:
        errors.append("intent codes must be present and unique")
    for code, node in intent_by_code.items():
        parent = node.get("parentCode")
        if parent is not None and parent not in intent_by_code:
            errors.append(f"intent {code} references missing parent {parent}")

    entity_ids = [row.get("entity_id") for row in entities]
    entity_id_set = set(entity_ids)
    if None in entity_id_set or len(entity_id_set) != len(entity_ids):
        errors.append("entity ids must be present and unique")
    for row in entities:
        if not row.get("aliases"):
            errors.append(f"entity {row.get('entity_id')} has no aliases")
        category = row.get("category")
        if row.get("entity_type") == "product" and category not in taxonomy_codes:
            errors.append(f"entity {row.get('entity_id')} uses unknown category {category}")

    logical_keys: set[str] = set()
    doc_types: Counter[str] = Counter()
    categories: Counter[str] = Counter()
    max_chars = int(manifest.get("chunk_policy", {}).get("max_chars", 0))
    for index, chunk in enumerate(chunks, 1):
        key = chunk.get("logical_chunk_key")
        content = chunk.get("content")
        metadata = chunk.get("metadata") or {}
        if not key or key in logical_keys:
            errors.append(f"chunk line {index} has missing or duplicate logical_chunk_key: {key}")
        logical_keys.add(key)
        if not isinstance(content, str) or not content.strip():
            errors.append(f"chunk {key} has empty content")
            continue
        actual_hash = hashlib.sha256(content.encode("utf-8")).hexdigest()
        if chunk.get("content_hash") != actual_hash:
            errors.append(f"chunk {key} content_hash mismatch")
        if chunk.get("char_count") != len(content):
            errors.append(f"chunk {key} char_count mismatch")
        if max_chars and len(content) > max_chars:
            errors.append(f"chunk {key} exceeds max_chars: {len(content)} > {max_chars}")
        missing_metadata = sorted(REQUIRED_METADATA - metadata.keys())
        if missing_metadata:
            errors.append(f"chunk {key} missing metadata: {', '.join(missing_metadata)}")
        if metadata.get("entity_id") not in entity_id_set:
            errors.append(f"chunk {key} references missing entity {metadata.get('entity_id')}")
        if metadata.get("category") not in taxonomy_codes:
            errors.append(f"chunk {key} uses unknown category {metadata.get('category')}")
        if metadata.get("language") != "zh-CN":
            errors.append(f"chunk {key} language is not zh-CN")
        doc_types[str(metadata.get("doc_type"))] += 1
        categories[str(metadata.get("category"))] += 1

    expected = {
        "total_chunks": len(chunks),
        "entity_count": len(entities),
        "intent_node_count": len(intents),
        "category_chunk_count": dict(categories),
        "doc_type_chunk_count": dict(doc_types),
    }
    for field, actual in expected.items():
        if manifest.get(field) != actual:
            errors.append(f"manifest {field} mismatch: expected {manifest.get(field)!r}, actual {actual!r}")

    filter_fields = set(contract.get("filter_ready_fields", {}))
    for route in routes:
        intent_code = route.get("intentCode")
        if intent_code not in intent_by_code:
            errors.append(f"route references missing intent {intent_code}")
        for field, value in (route.get("metadataFilters") or {}).items():
            if field not in filter_fields:
                errors.append(f"route {intent_code} uses undeclared filter field {field}")
            if field == "doc_type":
                values = value if isinstance(value, list) else [value]
                for item in values:
                    if item not in doc_types:
                        errors.append(f"route {intent_code} uses absent doc_type {item}")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate digital_store_v1 seed artifacts")
    parser.add_argument("--seed-root", type=Path, default=DEFAULT_SEED_ROOT)
    parser.add_argument("--route-file", type=Path, default=DEFAULT_ROUTE_FILE)
    args = parser.parse_args()
    errors = validate(args.seed_root, args.route_file)
    if errors:
        print(f"FAILED: {len(errors)} validation error(s)")
        for error in errors[:50]:
            print(f"- {error}")
        if len(errors) > 50:
            print(f"- ... {len(errors) - 50} more")
        return 1
    print(f"OK: digital_store_v1 artifacts are internally consistent: {args.seed_root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
