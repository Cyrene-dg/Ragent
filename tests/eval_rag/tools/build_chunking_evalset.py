#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def build_queries(index: dict, docs_root: Path, max_per_group: int) -> list[dict]:
    rows: list[dict] = []

    seeds = [
        ("sample-1", "原神多少级可以联机", None),
        ("sample-2", "纳西妲属于什么元素", None),
        ("sample-3", "夜兰武器推荐", None),
        ("sample-4", "什么是双暴", None),
        ("sample-5", "树脂如何规划", None),
    ]
    for qid, q, category in seeds:
        rows.append({"query_id": qid, "query": q, "gold_doc": "", "category": category})

    def append_group(group: str, templates: list[str], prefix: str) -> None:
        count = 0
        for item in index.get(group, []):
            if count >= max_per_group:
                break
            if not isinstance(item, dict):
                continue
            name = str(item.get("name") or "").strip()
            doc_path = str(item.get("docPath") or "").strip()
            if not name or not doc_path:
                continue
            path = Path(doc_path)
            if not path.exists():
                continue
            rel = str(path.relative_to(docs_root)).replace("\\", "/")

            for j, template in enumerate(templates, start=1):
                query = template.format(name=name)
                rows.append(
                    {
                        "query_id": f"{prefix}-{count + 1}-{j}",
                        "query": query,
                        "gold_doc": rel,
                        "category": group,
                        "variants": [
                            f"请问{query}",
                            query.replace("？", "").replace("?", ""),
                        ],
                    }
                )
            count += 1

    append_group(
        "characters",
        [
            "{name} 在队伍里通常是什么定位？",
            "{name} 的天赋升级优先级怎么排？",
        ],
        "char",
    )
    append_group(
        "weapons",
        [
            "{name} 这把武器适合谁用？",
            "{name} 的被动效果是什么？",
        ],
        "weapon",
    )
    append_group(
        "artifacts",
        [
            "{name} 2件套和4件套效果分别是什么？",
            "{name} 更适合哪些角色？",
        ],
        "artifact",
    )
    append_group(
        "materials",
        [
            "{name} 主要用途是什么？",
            "{name} 通常给哪些培养路线用？",
        ],
        "material",
    )
    append_group(
        "chapters",
        [
            "{name} 这一章讲了什么？",
        ],
        "chapter",
    )

    return rows


def patch_missing_gold(rows: list[dict], docs_root: Path) -> list[dict]:
    # For seed questions without direct gold docs, weakly bind to first matched doc by keyword.
    all_docs = sorted(docs_root.rglob("*.md"))
    cache = [(str(p.relative_to(docs_root)).replace("\\", "/"), p.read_text(encoding="utf-8")[:2000]) for p in all_docs]

    patched: list[dict] = []
    for row in rows:
        if row.get("gold_doc"):
            patched.append(row)
            continue

        query = str(row.get("query") or "")
        hit = ""
        for rel, text in cache:
            if any(token in text for token in query.split() if token):
                hit = rel
                break
        if not hit and cache:
            hit = cache[0][0]

        new_row = dict(row)
        new_row["gold_doc"] = hit
        new_row["weak_label"] = True
        patched.append(new_row)
    return patched


def write_jsonl(rows: list[dict], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description="Build chunking A/B evalset from genshin seed index.")
    parser.add_argument("--seed-root", default="resources/genshin_seed")
    parser.add_argument("--output", default="tests/eval_rag/data/chunking_evalset.genshin.jsonl")
    parser.add_argument("--max-per-group", type=int, default=40)
    args = parser.parse_args()

    seed_root = Path(args.seed_root).resolve()
    docs_root = (seed_root / "docs").resolve()
    index_path = seed_root / "config" / "entity_index.json"

    index = read_json(index_path)
    rows = build_queries(index=index, docs_root=docs_root, max_per_group=int(args.max_per_group))
    rows = patch_missing_gold(rows, docs_root=docs_root)

    out = Path(args.output).resolve()
    write_jsonl(rows, out)

    print("[build-chunking-evalset] Completed")
    print(f"[build-chunking-evalset] output: {out}")
    print(f"[build-chunking-evalset] samples: {len(rows)}")


if __name__ == "__main__":
    main()
