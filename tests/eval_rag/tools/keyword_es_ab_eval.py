from __future__ import annotations

import csv
import json
import os
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
KNOWLEDGE_DIR = ROOT / "bootstrap" / "src" / "main" / "resources" / "file"
ARTIFACT_ROOT = ROOT / "tests" / "eval_rag" / "artifacts" / "keyword_ab"


@dataclass(slots=True)
class QueryCase:
    query_id: str
    query: str
    gold_chunk_id: str


def _env(name: str, default: str) -> str:
    raw = os.getenv(name)
    return raw.strip() if raw and raw.strip() else default


def _request(url: str, method: str = "GET", body: dict[str, Any] | None = None) -> dict[str, Any]:
    data = None
    headers = {"Content-Type": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url=url, method=method, headers=headers, data=data)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as ex:
        detail = ex.read().decode("utf-8", errors="ignore")
        raise RuntimeError(f"HTTP {ex.code}: {detail}") from ex


def _build_cases() -> list[QueryCase]:
    return [
        QueryCase("q1", "开票信息税号", "group/group-finance/开票信息.md"),
        QueryCase("q2", "IT支持账号密码", "group/it/IT支持.md"),
        QueryCase("q3", "招聘入职流程", "group/hr/招聘信息.md"),
        QueryCase("q4", "员工培训体系", "group/hr/员工培训.md"),
        QueryCase("q5", "薪资福利政策", "group/hr/薪资与福利政策.md"),
        QueryCase("q6", "人事制度规范", "group/hr/人事制度.md"),
        QueryCase("q7", "公司规章制度", "group/hr/公司规章制度.md"),
        QueryCase("q8", "OA系统数据安全规范", "biz/biz-oa/OA系统数据安全规范文档.md"),
        QueryCase("q9", "互联网保险系统数据安全规范", "biz/biz-ins/互联网保险系统数据安全规范.md"),
        QueryCase("q10", "发票抬头信息", "group/group-finance/开票信息.md"),
        QueryCase("q11", "入职办理流程", "group/hr/招聘信息.md"),
        QueryCase("q12", "企业培训发展", "group/hr/员工培训.md"),
    ]


def _iter_docs() -> list[dict[str, Any]]:
    docs: list[dict[str, Any]] = []
    for file_path in sorted(KNOWLEDGE_DIR.rglob("*.md")):
        rel = file_path.relative_to(KNOWLEDGE_DIR).as_posix()
        title = file_path.stem
        text = file_path.read_text(encoding="utf-8", errors="ignore")
        lines = [line for line in text.splitlines() if line.strip()]
        content_lines = [line for line in lines if not line.strip().startswith("#")]
        content = "\n".join(content_lines).strip()
        docs.append(
            {
                "chunk_id": rel,
                "kb_id": rel.split("/")[0],
                "doc_id": rel,
                "title": title,
                "keywords": title,
                "content": content,
                "enabled": 1,
            }
        )
    return docs


def _recreate_index(es_base: str, index: str) -> None:
    try:
        _request(f"{es_base}/{index}", "DELETE")
    except RuntimeError:
        pass
    _request(
        f"{es_base}/{index}",
        "PUT",
        {
            "settings": {"number_of_shards": 1, "number_of_replicas": 0},
            "mappings": {
                "properties": {
                    "chunk_id": {"type": "keyword"},
                    "kb_id": {"type": "keyword"},
                    "doc_id": {"type": "keyword"},
                    "title": {"type": "text"},
                    "keywords": {"type": "text"},
                    "content": {"type": "text"},
                    "enabled": {"type": "integer"},
                }
            },
        },
    )


def _bulk_index(es_base: str, index: str, docs: list[dict[str, Any]]) -> None:
    lines: list[str] = []
    for doc in docs:
        lines.append(json.dumps({"index": {"_index": index, "_id": doc["chunk_id"]}}, ensure_ascii=False))
        lines.append(json.dumps(doc, ensure_ascii=False))
    payload = ("\n".join(lines) + "\n").encode("utf-8")
    req = urllib.request.Request(
        url=f"{es_base}/_bulk?refresh=true",
        method="POST",
        headers={"Content-Type": "application/x-ndjson"},
        data=payload,
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        result = json.loads(resp.read().decode("utf-8"))
    if result.get("errors"):
        raise RuntimeError(f"bulk index has errors: {result}")


def _baseline_query(es_base: str, index: str, query: str, topk: int) -> list[str]:
    body = {
        "size": topk,
        "query": {
            "bool": {
                "filter": [{"term": {"enabled": {"value": 1}}}],
                "must": [{"match": {"content": {"query": query, "operator": "and"}}}],
            }
        },
    }
    result = _request(f"{es_base}/{index}/_search", "POST", body)
    return [hit["_source"]["chunk_id"] for hit in result.get("hits", {}).get("hits", [])]


def _hybrid_query(es_base: str, index: str, query: str, topk: int) -> list[str]:
    body = {
        "size": topk,
        "query": {
            "bool": {
                "filter": [{"term": {"enabled": {"value": 1}}}],
                "should": [
                    {
                        "multi_match": {
                            "query": query,
                            "fields": ["title^3", "keywords^2", "content"],
                            "type": "best_fields",
                            "operator": "or",
                        }
                    },
                    {"match_phrase": {"content": {"query": query, "boost": 1.2}}},
                ],
                "minimum_should_match": 1,
            }
        },
    }
    result = _request(f"{es_base}/{index}/_search", "POST", body)
    return [hit["_source"]["chunk_id"] for hit in result.get("hits", {}).get("hits", [])]


def _mrr(top_ids: list[str], gold: str) -> float:
    for i, item in enumerate(top_ids, start=1):
        if item == gold:
            return 1.0 / i
    return 0.0


def main() -> None:
    es_base = _env("KEYWORD_AB_ES_BASE", "http://localhost:9200")
    index = _env("KEYWORD_AB_INDEX", "ragent_keyword_ab_v1")
    topk = int(_env("KEYWORD_AB_TOPK", "5"))
    run_id = time.strftime("%Y%m%d_%H%M%S")

    docs = _iter_docs()
    if not docs:
        raise RuntimeError("no markdown docs found for benchmark")

    _recreate_index(es_base, index)
    _bulk_index(es_base, index, docs)

    cases = _build_cases()
    rows: list[dict[str, Any]] = []
    baseline_hits = 0
    hybrid_hits = 0
    baseline_mrr = 0.0
    hybrid_mrr = 0.0

    for case in cases:
        baseline_ids = _baseline_query(es_base, index, case.query, topk)
        hybrid_ids = _hybrid_query(es_base, index, case.query, topk)
        baseline_hit = int(case.gold_chunk_id in baseline_ids)
        hybrid_hit = int(case.gold_chunk_id in hybrid_ids)
        baseline_hits += baseline_hit
        hybrid_hits += hybrid_hit
        baseline_mrr += _mrr(baseline_ids, case.gold_chunk_id)
        hybrid_mrr += _mrr(hybrid_ids, case.gold_chunk_id)
        rows.append(
            {
                "query_id": case.query_id,
                "query": case.query,
                "gold_chunk_id": case.gold_chunk_id,
                "baseline_top_ids": baseline_ids,
                "hybrid_top_ids": hybrid_ids,
                "baseline_hit": baseline_hit,
                "hybrid_hit": hybrid_hit,
            }
        )

    total = len(cases)
    metrics = {
        "total_queries": total,
        "baseline_hit_at_k": baseline_hits / total,
        "hybrid_hit_at_k": hybrid_hits / total,
        "baseline_mrr_at_k": baseline_mrr / total,
        "hybrid_mrr_at_k": hybrid_mrr / total,
        "hit_lift_abs": (hybrid_hits - baseline_hits) / total,
        "mrr_lift_abs": (hybrid_mrr - baseline_mrr) / total,
        "topk": topk,
        "index": index,
        "es_base": es_base,
    }

    out_dir = ARTIFACT_ROOT / run_id
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "metrics.json").write_text(json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8")
    with (out_dir / "per_query.csv").open("w", encoding="utf-8", newline="") as fp:
        writer = csv.DictWriter(
            fp,
            fieldnames=[
                "query_id",
                "query",
                "gold_chunk_id",
                "baseline_top_ids",
                "hybrid_top_ids",
                "baseline_hit",
                "hybrid_hit",
            ],
        )
        writer.writeheader()
        for row in rows:
            row = dict(row)
            row["baseline_top_ids"] = "|".join(row["baseline_top_ids"])
            row["hybrid_top_ids"] = "|".join(row["hybrid_top_ids"])
            writer.writerow(row)

    summary = (
        "# Keyword ES A/B Result\n\n"
        f"- Total Queries: `{total}`\n"
        f"- Hit@{topk}: `{metrics['baseline_hit_at_k']:.4f} -> {metrics['hybrid_hit_at_k']:.4f}`\n"
        f"- MRR@{topk}: `{metrics['baseline_mrr_at_k']:.4f} -> {metrics['hybrid_mrr_at_k']:.4f}`\n"
        f"- Hit Lift (abs): `{metrics['hit_lift_abs']:.4f}`\n"
        f"- MRR Lift (abs): `{metrics['mrr_lift_abs']:.4f}`\n"
    )
    (out_dir / "summary.md").write_text(summary, encoding="utf-8")

    print(f"[keyword-ab] metrics: {out_dir / 'metrics.json'}")
    print(summary)


if __name__ == "__main__":
    main()

