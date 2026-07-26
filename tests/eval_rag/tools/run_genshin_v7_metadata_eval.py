#!/usr/bin/env python3
"""Run the local v7 metadata-retrieval A/B evaluation endpoint.

The endpoint returns runtime chunk IDs.  This runner maps returned text back to
stable logical_chunk_key values from the seed artifact, so a rebuild does not
invalidate Gold labels.  It refuses to run a draft whose human review is not
complete.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import statistics
import time
import urllib.request
from collections import Counter
from pathlib import Path
from typing import Any


TRIAL_SUFFIXES = ("(试用)", "（试用）")


def sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    with path.open(encoding="utf-8-sig") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def load_chunk_index(
    path: Path,
) -> tuple[dict[str, dict[str, Any]], dict[tuple[str, str, int], dict[str, Any]], dict[str, str], dict[str, str]]:
    chunks = load_jsonl(path)
    by_hash: dict[str, dict[str, Any]] = {}
    by_metadata_identity: dict[tuple[str, str, int], dict[str, Any]] = {}
    ambiguous_metadata_identities: set[tuple[str, str, int]] = set()
    entity_by_name: dict[str, str] = {}
    for chunk in chunks:
        metadata = chunk.get("metadata", {})
        by_hash[chunk["content_hash"]] = chunk
        entity_id = str(metadata.get("entity_id", ""))
        doc_type = str(metadata.get("doc_type", ""))
        try:
            chunk_index = int(str(chunk["logical_chunk_key"]).rsplit("#", 1)[1])
        except (IndexError, ValueError):
            chunk_index = -1
        if entity_id and doc_type and chunk_index >= 0:
            identity = (entity_id, doc_type, chunk_index)
            # An ambiguous metadata tuple is never a safe fallback. Content hashes remain the
            # primary identity; this only supports legacy rows whose Chinese text was corrupted
            # during an old import but whose persisted metadata is intact.
            if identity in ambiguous_metadata_identities:
                continue
            if identity in by_metadata_identity:
                by_metadata_identity.pop(identity, None)
                ambiguous_metadata_identities.add(identity)
            else:
                by_metadata_identity[identity] = chunk
        name = str(metadata.get("document_title", ""))
        entity_id = str(metadata.get("entity_id", ""))
        if name and entity_id and not name.endswith(TRIAL_SUFFIXES):
            entity_by_name.setdefault(name, entity_id)

    trial_equivalents: dict[str, str] = {}
    for chunk in chunks:
        metadata = chunk.get("metadata", {})
        name = str(metadata.get("document_title", ""))
        entity_id = str(metadata.get("entity_id", ""))
        for suffix in TRIAL_SUFFIXES:
            if name.endswith(suffix):
                canonical = entity_by_name.get(name[: -len(suffix)])
                if canonical and canonical != entity_id:
                    trial_equivalents[entity_id] = canonical
    return by_hash, by_metadata_identity, trial_equivalents, entity_by_name


def validate_gold(cases: list[dict[str, Any]]) -> None:
    incomplete = [case["id"] for case in cases if case.get("review_status") != "confirmed"]
    missing_single = [
        case["id"] for case in cases
        if case.get("answerable") is True
        and case.get("type") in {"single_entity", "alias"}
        and not case.get("gold_chunk_keys")
    ]
    missing_collection = [
        case["id"] for case in cases
        if case.get("type") == "entity_collection" and not case.get("expected_entity_ids")
    ]
    if incomplete or missing_single or missing_collection:
        raise ValueError(
            "Gold 标注尚未完成："
            f"review_status!=confirmed={incomplete}; "
            f"missing gold_chunk_keys={missing_single}; "
            f"missing expected_entity_ids={missing_collection}"
        )


def post_compare(url: str, question: str, top_k: int, timeout: float) -> dict[str, Any]:
    body = json.dumps({"question": question, "topK": top_k}, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url, data=body, method="POST", headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        payload = json.loads(response.read().decode("utf-8"))
    data = payload.get("data", payload)
    if not isinstance(data, dict):
        raise ValueError(f"Unexpected evaluation response: {payload}")
    return data


def normalize_chunk(
    raw: dict[str, Any],
    by_hash: dict[str, dict[str, Any]],
    by_metadata_identity: dict[tuple[str, str, int], dict[str, Any]],
    trial_equivalents: dict[str, str],
) -> dict[str, Any]:
    text = str(raw.get("text", ""))
    source = by_hash.get(sha256(text))
    mapping_method = "content_hash" if source else None
    persisted_metadata: dict[str, Any] = {}
    if raw.get("metadataJson"):
        try:
            persisted_metadata = json.loads(str(raw["metadataJson"]))
        except json.JSONDecodeError:
            persisted_metadata = {}
    if source is None and persisted_metadata and raw.get("chunkIndex") is not None:
        identity = (
            str(persisted_metadata.get("entity_id", "")),
            str(persisted_metadata.get("doc_type", "")),
            int(raw["chunkIndex"]),
        )
        source = by_metadata_identity.get(identity)
        if source:
            mapping_method = "metadata_identity"
    metadata = source.get("metadata", {}) if source else {}
    if not metadata:
        metadata = persisted_metadata
    entity_id = str(metadata.get("entity_id", ""))
    return {
        "runtime_chunk_id": str(raw.get("id", "")),
        "logical_chunk_key": source.get("logical_chunk_key") if source else None,
        "mapping_status": "mapped" if source else "unmapped",
        "mapping_method": mapping_method,
        "score": raw.get("score"),
        "entity_id": entity_id or None,
        "canonical_entity_id": trial_equivalents.get(entity_id, entity_id) or None,
        "category": metadata.get("category"),
        "doc_type": metadata.get("doc_type"),
        "element": metadata.get("element"),
        "text_sha256": sha256(text),
        "text_preview": text[:240],
    }


def rank_keys(chunks: list[dict[str, Any]]) -> list[str]:
    return [str(chunk["logical_chunk_key"]) for chunk in chunks if chunk.get("logical_chunk_key")]


def first_rank(keys: list[str], gold: set[str], max_rank: int) -> int | None:
    for index, key in enumerate(keys[:max_rank], start=1):
        if key in gold:
            return index
    return None


def assess_case(case: dict[str, Any], chunks: list[dict[str, Any]]) -> dict[str, Any]:
    case_type = case.get("type")
    if case.get("answerable") is False or case_type.endswith("diagnostic"):
        return {"scored": False, "reason": "diagnostic"}

    keys = rank_keys(chunks)
    top5 = chunks[:5]
    if case_type in {"single_entity", "alias"}:
        gold = set(case["gold_chunk_keys"])
        rank5 = first_rank(keys, gold, 5)
        rank10 = first_rank(keys, gold, 10)
        rank20 = first_rank(keys, gold, 20)
        expected = case.get("expected_filters", {})
        wrong_top5 = sum(
            1 for chunk in top5
            if chunk.get("mapping_status") != "mapped"
            or (expected.get("entity_id") and chunk.get("canonical_entity_id") != expected["entity_id"])
            or (expected.get("category") and chunk.get("category") != expected["category"])
        )
        return {
            "scored": True,
            "metric_kind": "chunk",
            "hit_at_5": int(rank5 is not None),
            "mrr_at_10": 0.0 if rank10 is None else 1.0 / rank10,
            "recall_at_20": int(rank20 is not None),
            "top5_wrong_count": wrong_top5,
            "top5_total": len(top5),
        }

    if case_type == "entity_collection":
        expected_ids = set(case["expected_entity_ids"])
        returned_ids = {chunk["canonical_entity_id"] for chunk in chunks[:20] if chunk.get("canonical_entity_id")}
        expected_filters = case.get("expected_filters", {})
        wrong_top5 = sum(
            1 for chunk in top5
            if chunk.get("mapping_status") != "mapped"
            or chunk.get("category") != expected_filters.get("category")
            or chunk.get("element") != expected_filters.get("element")
        )
        return {
            "scored": True,
            "metric_kind": "collection",
            "entity_coverage_at_20": len(expected_ids & returned_ids) / max(len(expected_ids), 1),
            "covered_entity_ids": sorted(expected_ids & returned_ids),
            "top5_wrong_count": wrong_top5,
            "top5_total": len(top5),
        }
    return {"scored": False, "reason": "unsupported_type"}


def percentile95(values: list[float]) -> float:
    if not values:
        return 0.0
    sorted_values = sorted(values)
    index = max(0, math.ceil(len(sorted_values) * 0.95) - 1)
    return sorted_values[index]


def summarize(branch_rows: list[dict[str, Any]]) -> dict[str, Any]:
    scored = [row["assessment"] for row in branch_rows if row["assessment"].get("scored")]
    chunk = [item for item in scored if item.get("metric_kind") == "chunk"]
    collection = [item for item in scored if item.get("metric_kind") == "collection"]
    wrong = sum(item.get("top5_wrong_count", 0) for item in scored)
    total = sum(item.get("top5_total", 0) for item in scored)
    return {
        "scored_queries": len(scored),
        "chunk_queries": len(chunk),
        "collection_queries": len(collection),
        "hit_at_5": sum(item["hit_at_5"] for item in chunk) / max(len(chunk), 1),
        "mrr_at_10": sum(item["mrr_at_10"] for item in chunk) / max(len(chunk), 1),
        "recall_at_20": sum(item["recall_at_20"] for item in chunk) / max(len(chunk), 1),
        "entity_coverage_at_20": sum(item["entity_coverage_at_20"] for item in collection) / max(len(collection), 1),
        "top5_wrong_recall_rate": wrong / max(total, 1),
        "retrieval_latency_ms": {
            "p50": statistics.median([row["retrieval_latency_ms"] for row in branch_rows]),
            "p95": percentile95([row["retrieval_latency_ms"] for row in branch_rows]),
        },
        "unmapped_chunk_count": sum(
            1 for row in branch_rows for chunk_row in row["chunks"]
            if chunk_row.get("mapping_status") != "mapped"
        ),
    }


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, default=Path("tests/eval_rag/data/genshin_v7_metadata_gold.jsonl"))
    parser.add_argument("--chunks", type=Path, default=Path("resources/genshin_seed_v7/artifacts/chunks.jsonl"))
    parser.add_argument("--api-url", default="http://127.0.0.1:9090/api/ragent/rag/evaluation/metadata-retrieval/compare")
    parser.add_argument("--artifact-dir", type=Path, default=Path("tests/eval_rag/artifacts/metadata_eval"))
    parser.add_argument("--run-id", default=time.strftime("%Y%m%d_%H%M%S"))
    parser.add_argument("--top-k", type=int, default=20)
    parser.add_argument("--warmups", type=int, default=3)
    parser.add_argument("--runs-per-query", type=int, default=5)
    parser.add_argument("--timeout-seconds", type=float, default=90.0)
    parser.add_argument("--validate-only", action="store_true")
    args = parser.parse_args()

    cases = load_jsonl(args.dataset)
    validate_gold(cases)
    by_hash, by_metadata_identity, trial_equivalents, _ = load_chunk_index(args.chunks)
    if args.validate_only:
        print(f"Gold validation passed: cases={len(cases)}, trial_equivalents={len(trial_equivalents)}")
        return

    for _ in range(max(args.warmups, 0)):
        post_compare(args.api_url, cases[0]["query"], args.top_k, args.timeout_seconds)

    baseline_rows: list[dict[str, Any]] = []
    candidate_rows: list[dict[str, Any]] = []
    for case in cases:
        for run_index in range(max(args.runs_per_query, 1)):
            result = post_compare(args.api_url, case["query"], args.top_k, args.timeout_seconds)
            for branch_name, target_rows in (("baseline", baseline_rows), ("candidate", candidate_rows)):
                branch = result[branch_name]
                chunks = [
                    normalize_chunk(raw, by_hash, by_metadata_identity, trial_equivalents)
                    for raw in branch.get("chunks", [])
                ]
                target_rows.append({
                    "case_id": case["id"],
                    "query": case["query"],
                    "run_index": run_index,
                    "intent_resolution_latency_ms": result.get("intentResolutionLatencyMs"),
                    "retrieval_latency_ms": branch.get("retrievalLatencyMs"),
                    "retrieval_plan": branch.get("retrievalPlan"),
                    "chunks": chunks,
                    "assessment": assess_case(case, chunks),
                })

    out = args.artifact_dir / args.run_id
    out.mkdir(parents=True, exist_ok=False)
    write_jsonl(out / "baseline_top20.jsonl", baseline_rows)
    write_jsonl(out / "candidate_top20.jsonl", candidate_rows)
    metrics = {"baseline": summarize(baseline_rows), "candidate": summarize(candidate_rows)}
    metrics["delta"] = {
        key: metrics["candidate"][key] - metrics["baseline"][key]
        for key in ("hit_at_5", "mrr_at_10", "recall_at_20", "entity_coverage_at_20", "top5_wrong_recall_rate")
    }
    (out / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (out / "summary.md").write_text(
        "# 原神 v7 元数据检索 A/B 试运行\n\n"
        f"- Baseline Hit@5: {metrics['baseline']['hit_at_5']:.4f}\n"
        f"- Candidate Hit@5: {metrics['candidate']['hit_at_5']:.4f}\n"
        f"- Baseline Recall@20: {metrics['baseline']['recall_at_20']:.4f}\n"
        f"- Candidate Recall@20: {metrics['candidate']['recall_at_20']:.4f}\n"
        f"- Baseline retrieval P95: {metrics['baseline']['retrieval_latency_ms']['p95']:.0f}ms\n"
        f"- Candidate retrieval P95: {metrics['candidate']['retrieval_latency_ms']['p95']:.0f}ms\n",
        encoding="utf-8",
    )
    print(f"Artifacts written to {out}")


if __name__ == "__main__":
    main()
