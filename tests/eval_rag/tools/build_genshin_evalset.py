#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import math
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

TOKEN_RE = re.compile(r"[A-Za-z0-9_]+|[\u4e00-\u9fff]")
STOPWORDS = {
    "的", "了", "是", "在", "和", "与", "及", "并", "或", "都", "也", "就", "吗", "呢", "啊", "吧",
    "请", "问", "个", "这", "那", "有", "为", "将", "如何", "什么", "哪些", "怎么", "多少",
    "to", "the", "a", "an", "is", "are", "of", "in", "on", "for", "and", "or", "with",
}


@dataclass(slots=True)
class DocRecord:
    chunk_id: str
    category: str
    path: Path
    text: str
    tokens: set[str]
    popularity: float


@dataclass(slots=True)
class QuerySeed:
    query_id: str
    query: str
    expected_category: str | None
    explicit_gold_ids: list[str]


def tokenize(text: str) -> set[str]:
    return {
        tok.lower().strip()
        for tok in TOKEN_RE.findall(text or "")
        if tok and tok.lower().strip() not in STOPWORDS
    }


def overlap(query_tokens: set[str], doc_tokens: set[str]) -> float:
    if not query_tokens or not doc_tokens:
        return 0.0
    hit = len(query_tokens & doc_tokens)
    return hit / max(len(query_tokens), 1)


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def list_docs(docs_root: Path) -> list[DocRecord]:
    all_paths = sorted(docs_root.rglob("*.md"))
    if not all_paths:
        raise FileNotFoundError(f"No markdown docs under {docs_root}")

    lengths: list[int] = []
    raw: list[tuple[Path, str, str, set[str], int]] = []
    for path in all_paths:
        text = path.read_text(encoding="utf-8")
        clipped = text.replace("\r\n", "\n")[:2400]
        category = path.parent.name
        tokens = tokenize(clipped)
        text_len = max(len(clipped), 1)
        lengths.append(text_len)
        raw.append((path, category, clipped, tokens, text_len))

    max_len = max(lengths)
    docs: list[DocRecord] = []
    for path, category, clipped, tokens, text_len in raw:
        popularity = math.sqrt(text_len / max_len)
        docs.append(
            DocRecord(
                chunk_id=str(path.relative_to(docs_root)).replace("\\", "/"),
                category=category,
                path=path,
                text=clipped,
                tokens=tokens,
                popularity=popularity,
            )
        )
    return docs


def load_mappings(path: Path) -> list[tuple[str, str]]:
    rows = read_json(path)
    out: list[tuple[str, str]] = []
    for row in rows:
        if not isinstance(row, dict):
            continue
        src = str(row.get("sourceTerm") or "").strip().lower()
        tgt = str(row.get("targetTerm") or "").strip()
        if src and tgt:
            out.append((src, tgt))
    # long source first to avoid partial replacement priority inversion
    out.sort(key=lambda x: len(x[0]), reverse=True)
    return out


def apply_mappings(query: str, mappings: list[tuple[str, str]]) -> str:
    out = query
    low = out.lower()
    for src, tgt in mappings:
        if src in low:
            out = out.replace(src, tgt)
            low = out.lower()
    return out


def infer_category(query: str, explicit: str | None) -> str | None:
    if explicit:
        return explicit

    q = query.lower()
    rules: list[tuple[str, list[str]]] = [
        ("characters", ["角色", "配队", "命座", "天赋", "定位", "夜兰", "万叶", "纳西妲"]),
        ("weapons", ["武器", "专武", "弓", "单手剑", "双手剑", "法器", "长柄"]),
        ("artifacts", ["圣遗物", "套装", "四件", "两件"]),
        ("materials", ["材料", "突破", "树脂", "升级"]),
        ("chapters", ["剧情", "章节", "主线", "任务"]),
        ("achievements", ["成就", "达成"]),
    ]

    for category, keywords in rules:
        if any(k in q for k in keywords):
            return category
    return None


def build_query_pool(seed_root: Path, docs_root: Path, index: dict, max_per_group: int) -> list[QuerySeed]:
    samples = read_json(seed_root / "config" / "sample_questions.json")
    out: list[QuerySeed] = []

    for i, row in enumerate(samples, start=1):
        if not isinstance(row, dict):
            continue
        question = str(row.get("question") or "").strip()
        if not question:
            continue
        out.append(QuerySeed(query_id=f"sample-{i}", query=question, expected_category=None, explicit_gold_ids=[]))

    def from_entity(group_name: str, template: str, prefix: str) -> None:
        rows = index.get(group_name) or []
        count = 0
        for row in rows:
            if count >= max_per_group:
                break
            if not isinstance(row, dict):
                continue
            name = str(row.get("name") or "").strip()
            doc_path = str(row.get("docPath") or "").strip()
            if not name or not doc_path:
                continue
            path = Path(doc_path)
            if not path.exists():
                continue
            gold = str(path.relative_to(docs_root)).replace("\\", "/")
            question = template.format(name=name)
            out.append(
                QuerySeed(
                    query_id=f"{prefix}-{count + 1}",
                    query=question,
                    expected_category=group_name,
                    explicit_gold_ids=[gold],
                )
            )
            count += 1

    from_entity("characters", "{name} 在队伍里通常担任什么定位？", "char")
    from_entity("weapons", "{name} 适合哪些角色使用？", "weapon")
    from_entity("artifacts", "{name} 套装的效果是什么？", "artifact")
    from_entity("materials", "{name} 主要有什么用途？", "material")

    return out


def score_vector(query_tokens: set[str], doc: DocRecord, canonical_query: str) -> float:
    # Deliberately noisy global channel to mimic broad recall.
    lexical = overlap(query_tokens, doc.tokens)
    has_phrase = 1.0 if any(token in doc.text for token in canonical_query[:24].split()) else 0.0
    return 0.58 * lexical + 0.37 * doc.popularity + 0.05 * has_phrase


def score_intent(query_tokens: set[str], doc: DocRecord, category: str | None, canonical_query: str) -> float:
    lexical = overlap(query_tokens, doc.tokens)
    category_bonus = 1.0 if category and doc.category == category else 0.0
    exact_name_bonus = 0.0
    for term in TOKEN_RE.findall(canonical_query):
        if len(term) >= 2 and term in doc.text:
            exact_name_bonus = 1.0
            break
    return 0.62 * lexical + 0.25 * category_bonus + 0.13 * exact_name_bonus


def top_candidates(
    docs: Iterable[DocRecord],
    scorer,
    top_k: int,
) -> list[tuple[DocRecord, float]]:
    scored = []
    for doc in docs:
        score = scorer(doc)
        if score <= 0:
            continue
        scored.append((doc, float(score)))
    scored.sort(key=lambda x: x[1], reverse=True)
    return scored[:top_k]


def build_evalset(
    docs_root: Path,
    seed_root: Path,
    output_path: Path,
    top_k_channel: int,
    max_per_group: int,
) -> tuple[int, int]:
    docs = list_docs(docs_root)
    index = read_json(seed_root / "config" / "entity_index.json")
    mappings = load_mappings(seed_root / "config" / "query_term_mappings.json")
    queries = build_query_pool(seed_root, docs_root, index, max_per_group=max_per_group)

    lines: list[str] = []
    dropped = 0

    for q in queries:
        canonical = apply_mappings(q.query, mappings)
        q_tokens = tokenize(canonical)
        if not q_tokens:
            dropped += 1
            continue

        category = infer_category(canonical, q.expected_category)
        intent_docs = docs if not category else [d for d in docs if d.category == category]

        vector = top_candidates(
            docs=docs,
            scorer=lambda d: score_vector(q_tokens, d, canonical),
            top_k=top_k_channel,
        )
        intent = top_candidates(
            docs=intent_docs,
            scorer=lambda d: score_intent(q_tokens, d, category, canonical),
            top_k=top_k_channel,
        )

        if not vector and not intent:
            dropped += 1
            continue

        gold_ids = set(q.explicit_gold_ids)
        if not gold_ids:
            # weak label: pick highest intent hit and a close second when score gap is small
            if intent:
                gold_ids.add(intent[0][0].chunk_id)
                if len(intent) > 1 and (intent[1][1] >= intent[0][1] * 0.95):
                    gold_ids.add(intent[1][0].chunk_id)
            elif vector:
                gold_ids.add(vector[0][0].chunk_id)

        if not gold_ids:
            dropped += 1
            continue

        payload = {
            "query_id": q.query_id,
            "query": q.query,
            "top_n": 5,
            "answerable": True,
            "gold_chunk_ids": sorted(gold_ids),
            "channels": [
                {
                    "name": "intent-directed",
                    "weight": 1.0,
                    "candidates": [
                        {
                            "chunk_id": doc.chunk_id,
                            "text": doc.text[:260],
                            "score": round(score, 6),
                        }
                        for doc, score in intent
                    ],
                },
                {
                    "name": "vector-global",
                    "weight": 1.0,
                    "candidates": [
                        {
                            "chunk_id": doc.chunk_id,
                            "text": doc.text[:260],
                            "score": round(score, 6),
                        }
                        for doc, score in vector
                    ],
                },
            ],
            "metadata": {
                "weak_label": True,
                "category": category,
            },
        }

        lines.append(json.dumps(payload, ensure_ascii=False))

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return len(lines), dropped


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Build weakly-labeled Genshin evalset for pytest RAG evaluation.")
    parser.add_argument("--seed-root", default="resources/genshin_seed")
    parser.add_argument("--output", default="tests/eval_rag/data/evalset.genshin.weak.jsonl")
    parser.add_argument("--top-k-channel", type=int, default=15)
    parser.add_argument("--max-per-group", type=int, default=50)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    seed_root = Path(args.seed_root).resolve()
    docs_root = (seed_root / "docs").resolve()
    out = Path(args.output).resolve()

    count, dropped = build_evalset(
        docs_root=docs_root,
        seed_root=seed_root,
        output_path=out,
        top_k_channel=int(args.top_k_channel),
        max_per_group=int(args.max_per_group),
    )

    print("[build-evalset] Completed")
    print(f"[build-evalset] output: {out}")
    print(f"[build-evalset] samples: {count}")
    print(f"[build-evalset] dropped: {dropped}")


if __name__ == "__main__":
    main()
