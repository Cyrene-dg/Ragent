from __future__ import annotations

import csv
import json
import math
import os
import re
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

TOKEN_RE = re.compile(r"[A-Za-z0-9_]+|[\u4e00-\u9fff]")
HEADING_RE = re.compile(r"^#{1,6}\s+.*$")
CODE_FENCE_RE = re.compile(r"^```.*$")
ATOMIC_IMAGE_RE = re.compile(r"^!\[[^]]*]\([^)]+\)(?:\s*\"[^\"]*\")?\s*$")
ATOMIC_LINK_RE = re.compile(r"^\[[^]]+]\([^)]+\)\s*$")
STOPWORDS = {
    "the", "a", "an", "is", "are", "of", "to", "and", "or", "in", "on", "for", "with",
    "的", "了", "是", "在", "和", "与", "及", "并", "或", "都", "也", "就", "吗", "呢", "啊", "吧",
    "请", "问", "一个", "这", "那", "有", "为", "将",
}


@dataclass(slots=True)
class ChunkingEvalConfig:
    dataset_path: Path
    docs_root: Path
    artifact_dir: Path
    top_k: int
    max_docs: int | None

    fixed_chunk_size: int
    fixed_overlap: int

    semantic_target: int
    semantic_max: int
    semantic_min: int
    semantic_overlap: int

    enforce_assertions: bool
    min_hit_lift_abs: float
    min_ir_drop_rel: float
    run_tag: str | None

    @staticmethod
    def from_env(eval_root: Path, repo_root: Path) -> "ChunkingEvalConfig":
        return ChunkingEvalConfig(
            dataset_path=Path(
                os.getenv("RAG_CHUNK_EVAL_DATASET", str(eval_root / "data" / "chunking_evalset.genshin.jsonl"))
            ),
            docs_root=Path(
                os.getenv("RAG_CHUNK_EVAL_DOCS_ROOT", str(repo_root / "resources" / "genshin_seed" / "docs"))
            ),
            artifact_dir=Path(
                os.getenv("RAG_CHUNK_EVAL_ARTIFACT_DIR", str(eval_root / "artifacts" / "chunking_latest"))
            ),
            top_k=_env_int("RAG_CHUNK_EVAL_TOP_K", 5),
            max_docs=_env_int_or_none("RAG_CHUNK_EVAL_MAX_DOCS", None),
            fixed_chunk_size=_env_int("RAG_CHUNK_EVAL_FIXED_CHUNK_SIZE", 512),
            fixed_overlap=_env_int("RAG_CHUNK_EVAL_FIXED_OVERLAP", 128),
            semantic_target=_env_int("RAG_CHUNK_EVAL_SEM_TARGET", 1400),
            semantic_max=_env_int("RAG_CHUNK_EVAL_SEM_MAX", 1800),
            semantic_min=_env_int("RAG_CHUNK_EVAL_SEM_MIN", 600),
            semantic_overlap=_env_int("RAG_CHUNK_EVAL_SEM_OVERLAP", 0),
            enforce_assertions=_env_bool("RAG_CHUNK_EVAL_ENFORCE_ASSERTIONS", False),
            min_hit_lift_abs=_env_float("RAG_CHUNK_EVAL_MIN_HIT_LIFT_ABS", 0.0),
            min_ir_drop_rel=_env_float("RAG_CHUNK_EVAL_MIN_IR_DROP_REL", 0.0),
            run_tag=(os.getenv("RAG_CHUNK_EVAL_RUN_TAG") or "").strip() or None,
        )


@dataclass(slots=True)
class QueryRow:
    query_id: str
    query: str
    gold_doc: str
    variants: list[str]


@dataclass(slots=True)
class ChunkDoc:
    chunk_id: str
    doc_id: str
    text: str
    length: int
    tf: dict[str, int]


@dataclass(slots=True)
class BranchMetrics:
    total_queries: int
    hit_at_k: float
    mrr_at_k: float
    ndcg_at_k: float
    irrelevant_recall_rate: float
    stability_hit_rate: float
    avg_chunk_len: float
    chunk_count: int


@dataclass(slots=True)
class QueryResult:
    query_id: str
    query: str
    fixed_top_docs: list[str]
    semantic_top_docs: list[str]
    fixed_hit: int
    semantic_hit: int
    fixed_mrr: float
    semantic_mrr: float
    fixed_ndcg: float
    semantic_ndcg: float


@dataclass(slots=True)
class ChunkingEvalRun:
    fixed: BranchMetrics
    semantic: BranchMetrics
    per_query: list[QueryResult]


@dataclass(slots=True)
class Block:
    kind: str
    start: int
    end: int


class BM25Index:
    def __init__(self, chunks: list[ChunkDoc]) -> None:
        self._chunks = chunks
        self._avg_len = sum(c.length for c in chunks) / max(len(chunks), 1)
        self._k1 = 1.2
        self._b = 0.75

        self._postings: dict[str, list[tuple[int, int]]] = {}
        for idx, chunk in enumerate(chunks):
            for token, freq in chunk.tf.items():
                self._postings.setdefault(token, []).append((idx, freq))

        n_docs = len(chunks)
        self._idf: dict[str, float] = {}
        for token, posting in self._postings.items():
            df = len(posting)
            self._idf[token] = math.log((n_docs - df + 0.5) / (df + 0.5) + 1.0)

    def top_k_doc_ids(self, query: str, top_k: int) -> list[str]:
        q_tokens = tokenize(query)
        if not q_tokens:
            return []

        scores: dict[int, float] = {}
        for token in q_tokens:
            posting = self._postings.get(token)
            if not posting:
                continue
            idf = self._idf.get(token, 0.0)
            for chunk_idx, tf in posting:
                chunk = self._chunks[chunk_idx]
                denom = tf + self._k1 * (1.0 - self._b + self._b * (chunk.length / max(self._avg_len, 1.0)))
                score = idf * (tf * (self._k1 + 1.0)) / max(denom, 1e-12)
                scores[chunk_idx] = scores.get(chunk_idx, 0.0) + score

        if not scores:
            return []

        ranked = sorted(scores.items(), key=lambda x: x[1], reverse=True)
        doc_ids: list[str] = []
        seen: set[str] = set()
        for chunk_idx, _ in ranked:
            doc_id = self._chunks[chunk_idx].doc_id
            if doc_id in seen:
                continue
            seen.add(doc_id)
            doc_ids.append(doc_id)
            if len(doc_ids) >= top_k:
                break
        return doc_ids


def tokenize(text: str) -> list[str]:
    return [
        tok.lower().strip()
        for tok in TOKEN_RE.findall(text or "")
        if tok and tok.lower().strip() not in STOPWORDS
    ]


def tf_map(tokens: Iterable[str]) -> dict[str, int]:
    out: dict[str, int] = {}
    for token in tokens:
        out[token] = out.get(token, 0) + 1
    return out


def normalize_for_fixed(text: str) -> str:
    return text.replace("\r", "")


def fixed_size_chunks(text: str, chunk_size: int, overlap: int) -> list[str]:
    content = normalize_for_fixed(text)
    if not content.strip():
        return []

    chunk_size = max(1, chunk_size)
    overlap = max(0, overlap)
    if chunk_size > 1:
        overlap = min(overlap, chunk_size - 1)
    else:
        overlap = 0

    chunks: list[str] = []
    length = len(content)
    start = 0
    last_end = -1

    while start < length:
        target_end = min(start + chunk_size, length)
        end = target_end

        if end <= start or end <= last_end:
            end = target_end

        piece = content[start:end]
        if piece.strip():
            chunks.append(piece)

        last_end = end
        if end >= length:
            break

        next_start = max(0, end - overlap)
        if next_start <= start:
            next_start = end
        start = next_start

    return chunks


def segment_blocks(text: str) -> list[Block]:
    blocks: list[Block] = []
    n = len(text)
    pos = 0

    in_fence = False
    fence_start = -1
    in_para = False
    para_start = -1

    while pos < n:
        line_end = text.find("\n", pos)
        if line_end < 0:
            line_end = n
        line_end_nl = line_end + 1 if line_end < n else line_end
        line = text[pos:line_end]
        trimmed = line.rstrip(" \t")

        if (not in_fence) and CODE_FENCE_RE.match(trimmed):
            if in_para:
                blocks.append(Block("para", para_start, pos))
                in_para = False
            in_fence = True
            fence_start = pos
            pos = line_end_nl
            continue

        if in_fence:
            if CODE_FENCE_RE.match(trimmed):
                blocks.append(Block("code", fence_start, line_end_nl))
                in_fence = False
            pos = line_end_nl
            continue

        if not trimmed:
            if in_para:
                blocks.append(Block("para", para_start, pos))
                in_para = False
            pos = line_end_nl
            continue

        if HEADING_RE.match(trimmed):
            if in_para:
                blocks.append(Block("para", para_start, pos))
                in_para = False
            blocks.append(Block("heading", pos, line_end_nl))
            pos = line_end_nl
            continue

        if ATOMIC_IMAGE_RE.match(trimmed) or ATOMIC_LINK_RE.match(trimmed):
            if in_para:
                blocks.append(Block("para", para_start, pos))
                in_para = False
            blocks.append(Block("atomic", pos, line_end_nl))
            pos = line_end_nl
            continue

        if not in_para:
            in_para = True
            para_start = pos
        pos = line_end_nl

    if in_fence:
        blocks.append(Block("code", fence_start, n))
    elif in_para:
        blocks.append(Block("para", para_start, n))

    return coalesce_trailing_blanks(blocks, text)


def coalesce_trailing_blanks(blocks: list[Block], text: str) -> list[Block]:
    if not blocks:
        return blocks

    out: list[Block] = []
    prev = blocks[0]
    for cur in blocks[1:]:
        if is_all_blank(text, prev.end, cur.start):
            prev = Block(prev.kind, prev.start, cur.start)
        out.append(prev)
        prev = cur
    out.append(prev)
    return out


def is_all_blank(text: str, start: int, end: int) -> bool:
    for i in range(start, end):
        if text[i] not in {" ", "\t", "\r", "\n"}:
            return False
    return True


def pack_blocks_to_ranges(blocks: list[Block], text_len: int, min_chars: int, target_chars: int, max_chars: int) -> list[tuple[int, int]]:
    del text_len  # parity with Java comments; packing is primarily constrained by min/max.
    ranges: list[tuple[int, int]] = []
    i = 0

    while i < len(blocks):
        chunk_start = blocks[i].start
        chunk_end = blocks[i].end
        size = chunk_end - chunk_start
        j = i + 1

        while j < len(blocks):
            b = blocks[j]
            after_add = b.end - chunk_start
            if after_add <= max_chars:
                chunk_end = b.end
                size = after_add
                j += 1
            else:
                if size < min_chars:
                    chunk_end = b.end
                    size = after_add
                    j += 1
                break

        ranges.append((chunk_start, chunk_end))
        i = j

    if len(ranges) >= 2:
        last_start, last_end = ranges[-1]
        if (last_end - last_start) < min(min_chars, max(1, target_chars // 2)):
            prev_start, prev_end = ranges[-2]
            if (last_end - prev_start) <= (max_chars * 2):
                ranges[-2] = (prev_start, last_end)
                ranges.pop()

    return ranges


def tail_by_chars(text: str, n: int) -> str:
    if n <= 0:
        return ""
    return text if len(text) <= n else text[-n:]


def structure_aware_chunks(text: str, target_chars: int, max_chars: int, min_chars: int, overlap_chars: int) -> list[str]:
    if not text.strip():
        return []

    blocks = segment_blocks(text)
    if not blocks:
        return [text]

    ranges = pack_blocks_to_ranges(blocks, len(text), min_chars, target_chars, max_chars)
    out: list[str] = []
    prev_tail = ""

    for start, end in ranges:
        body = text[start:end]
        if overlap_chars > 0 and prev_tail:
            body = prev_tail + body
        if body.strip():
            out.append(body)
        if overlap_chars > 0:
            prev_tail = tail_by_chars(text[start:end], overlap_chars)

    return out


def load_queries(dataset_path: Path) -> list[QueryRow]:
    if not dataset_path.exists():
        raise FileNotFoundError(f"Chunking eval dataset not found: {dataset_path}")

    rows: list[QueryRow] = []
    with dataset_path.open("r", encoding="utf-8-sig") as f:
        for idx, raw in enumerate(f, start=1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            payload = json.loads(line)
            query = str(payload.get("query") or "").strip()
            gold_doc = str(payload.get("gold_doc") or payload.get("gold_doc_path") or "").replace("\\", "/").strip()
            if not query or not gold_doc:
                continue
            query_id = str(payload.get("query_id") or f"q{idx}")
            variants_raw = payload.get("variants") or []
            variants = [str(v).strip() for v in variants_raw if str(v).strip()]
            rows.append(QueryRow(query_id=query_id, query=query, gold_doc=gold_doc, variants=variants))

    if not rows:
        raise ValueError(f"No valid query rows found in {dataset_path}")
    return rows


def load_docs(docs_root: Path, max_docs: int | None = None) -> list[tuple[str, str]]:
    if not docs_root.exists():
        raise FileNotFoundError(f"Docs root not found: {docs_root}")

    files = sorted(docs_root.rglob("*.md"))
    if max_docs is not None:
        files = files[:max_docs]

    docs: list[tuple[str, str]] = []
    for path in files:
        rel = str(path.relative_to(docs_root)).replace("\\", "/")
        text = path.read_text(encoding="utf-8")
        docs.append((rel, text))

    if not docs:
        raise ValueError(f"No markdown docs under {docs_root}")
    return docs


def build_chunks_fixed(docs: list[tuple[str, str]], cfg: ChunkingEvalConfig) -> list[ChunkDoc]:
    out: list[ChunkDoc] = []
    for doc_id, text in docs:
        chunks = fixed_size_chunks(text, cfg.fixed_chunk_size, cfg.fixed_overlap)
        for i, piece in enumerate(chunks):
            tokens = tokenize(piece)
            out.append(
                ChunkDoc(
                    chunk_id=f"{doc_id}#f{i}",
                    doc_id=doc_id,
                    text=piece,
                    length=max(1, len(tokens)),
                    tf=tf_map(tokens),
                )
            )
    return out


def build_chunks_semantic(docs: list[tuple[str, str]], cfg: ChunkingEvalConfig) -> list[ChunkDoc]:
    out: list[ChunkDoc] = []
    for doc_id, text in docs:
        chunks = structure_aware_chunks(
            text,
            target_chars=cfg.semantic_target,
            max_chars=cfg.semantic_max,
            min_chars=cfg.semantic_min,
            overlap_chars=cfg.semantic_overlap,
        )
        for i, piece in enumerate(chunks):
            tokens = tokenize(piece)
            out.append(
                ChunkDoc(
                    chunk_id=f"{doc_id}#s{i}",
                    doc_id=doc_id,
                    text=piece,
                    length=max(1, len(tokens)),
                    tf=tf_map(tokens),
                )
            )
    return out


def query_variants(query: QueryRow) -> list[str]:
    auto = [
        query.query,
        f"请问{query.query}",
        query.query.replace("？", "").replace("?", "").strip(),
    ]
    all_q = []
    seen = set()
    for q in auto + query.variants:
        norm = q.strip()
        if norm and norm not in seen:
            seen.add(norm)
            all_q.append(norm)
    return all_q


def hit_mrr_ndcg(top_docs: list[str], gold_doc: str) -> tuple[int, float, float, int, int]:
    if not top_docs:
        return 0, 0.0, 0.0, 0, 0

    hit = int(gold_doc in top_docs)
    irr = sum(1 for doc in top_docs if doc != gold_doc)
    total = len(top_docs)

    mrr = 0.0
    dcg = 0.0
    for rank, doc in enumerate(top_docs, start=1):
        rel = 1.0 if doc == gold_doc else 0.0
        if rel > 0 and mrr == 0.0:
            mrr = 1.0 / rank
        dcg += rel / math.log2(rank + 1)

    idcg = 1.0  # only one gold doc
    ndcg = dcg / idcg
    return hit, mrr, ndcg, irr, total


def evaluate_chunking_ab(cfg: ChunkingEvalConfig, queries: list[QueryRow], docs: list[tuple[str, str]]) -> ChunkingEvalRun:
    fixed_chunks = build_chunks_fixed(docs, cfg)
    semantic_chunks = build_chunks_semantic(docs, cfg)

    fixed_index = BM25Index(fixed_chunks)
    semantic_index = BM25Index(semantic_chunks)

    rows: list[QueryResult] = []

    fixed_hit_sum = 0.0
    sem_hit_sum = 0.0
    fixed_mrr_sum = 0.0
    sem_mrr_sum = 0.0
    fixed_ndcg_sum = 0.0
    sem_ndcg_sum = 0.0

    fixed_ir = 0
    sem_ir = 0
    fixed_total = 0
    sem_total = 0

    fixed_stability = 0.0
    sem_stability = 0.0

    for q in queries:
        fixed_top = fixed_index.top_k_doc_ids(q.query, cfg.top_k)
        sem_top = semantic_index.top_k_doc_ids(q.query, cfg.top_k)

        f_hit, f_mrr, f_ndcg, f_irr, f_total = hit_mrr_ndcg(fixed_top, q.gold_doc)
        s_hit, s_mrr, s_ndcg, s_irr, s_total = hit_mrr_ndcg(sem_top, q.gold_doc)

        fixed_hit_sum += f_hit
        sem_hit_sum += s_hit
        fixed_mrr_sum += f_mrr
        sem_mrr_sum += s_mrr
        fixed_ndcg_sum += f_ndcg
        sem_ndcg_sum += s_ndcg

        fixed_ir += f_irr
        sem_ir += s_irr
        fixed_total += f_total
        sem_total += s_total

        variants = query_variants(q)
        f_var_hits = 0
        s_var_hits = 0
        for v in variants:
            v_fixed = fixed_index.top_k_doc_ids(v, cfg.top_k)
            v_sem = semantic_index.top_k_doc_ids(v, cfg.top_k)
            f_var_hits += int(q.gold_doc in v_fixed)
            s_var_hits += int(q.gold_doc in v_sem)

        fixed_stability += (f_var_hits / max(len(variants), 1))
        sem_stability += (s_var_hits / max(len(variants), 1))

        rows.append(
            QueryResult(
                query_id=q.query_id,
                query=q.query,
                fixed_top_docs=fixed_top,
                semantic_top_docs=sem_top,
                fixed_hit=f_hit,
                semantic_hit=s_hit,
                fixed_mrr=f_mrr,
                semantic_mrr=s_mrr,
                fixed_ndcg=f_ndcg,
                semantic_ndcg=s_ndcg,
            )
        )

    total_q = len(queries)

    fixed_metrics = BranchMetrics(
        total_queries=total_q,
        hit_at_k=fixed_hit_sum / max(total_q, 1),
        mrr_at_k=fixed_mrr_sum / max(total_q, 1),
        ndcg_at_k=fixed_ndcg_sum / max(total_q, 1),
        irrelevant_recall_rate=(fixed_ir / max(fixed_total, 1)),
        stability_hit_rate=(fixed_stability / max(total_q, 1)),
        avg_chunk_len=(sum(len(c.text) for c in fixed_chunks) / max(len(fixed_chunks), 1)),
        chunk_count=len(fixed_chunks),
    )

    semantic_metrics = BranchMetrics(
        total_queries=total_q,
        hit_at_k=sem_hit_sum / max(total_q, 1),
        mrr_at_k=sem_mrr_sum / max(total_q, 1),
        ndcg_at_k=sem_ndcg_sum / max(total_q, 1),
        irrelevant_recall_rate=(sem_ir / max(sem_total, 1)),
        stability_hit_rate=(sem_stability / max(total_q, 1)),
        avg_chunk_len=(sum(len(c.text) for c in semantic_chunks) / max(len(semantic_chunks), 1)),
        chunk_count=len(semantic_chunks),
    )

    return ChunkingEvalRun(fixed=fixed_metrics, semantic=semantic_metrics, per_query=rows)


def write_chunking_artifacts(cfg: ChunkingEvalConfig, run: ChunkingEvalRun) -> dict[str, Path]:
    run_id = cfg.run_tag or time.strftime("%Y%m%d_%H%M%S")
    out_dir = cfg.artifact_dir / run_id
    out_dir.mkdir(parents=True, exist_ok=True)

    metrics_path = out_dir / "chunking_metrics.json"
    csv_path = out_dir / "chunking_per_query.csv"
    summary_path = out_dir / "chunking_summary.md"

    payload = {
        "config": {
            "dataset_path": str(cfg.dataset_path),
            "docs_root": str(cfg.docs_root),
            "top_k": cfg.top_k,
            "fixed_chunk_size": cfg.fixed_chunk_size,
            "fixed_overlap": cfg.fixed_overlap,
            "semantic_target": cfg.semantic_target,
            "semantic_max": cfg.semantic_max,
            "semantic_min": cfg.semantic_min,
            "semantic_overlap": cfg.semantic_overlap,
        },
        "fixed": _branch_to_dict(run.fixed),
        "semantic": _branch_to_dict(run.semantic),
        "delta": {
            "hit_at_k_lift_abs": run.semantic.hit_at_k - run.fixed.hit_at_k,
            "mrr_at_k_lift_abs": run.semantic.mrr_at_k - run.fixed.mrr_at_k,
            "ndcg_at_k_lift_abs": run.semantic.ndcg_at_k - run.fixed.ndcg_at_k,
            "irrelevant_recall_drop_abs": run.fixed.irrelevant_recall_rate - run.semantic.irrelevant_recall_rate,
            "stability_lift_abs": run.semantic.stability_hit_rate - run.fixed.stability_hit_rate,
        },
    }
    metrics_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")

    with csv_path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.writer(f)
        writer.writerow([
            "query_id",
            "query",
            "fixed_top_docs",
            "semantic_top_docs",
            "fixed_hit",
            "semantic_hit",
            "fixed_mrr",
            "semantic_mrr",
            "fixed_ndcg",
            "semantic_ndcg",
        ])
        for row in run.per_query:
            writer.writerow([
                row.query_id,
                row.query,
                "|".join(row.fixed_top_docs),
                "|".join(row.semantic_top_docs),
                row.fixed_hit,
                row.semantic_hit,
                f"{row.fixed_mrr:.6f}",
                f"{row.semantic_mrr:.6f}",
                f"{row.fixed_ndcg:.6f}",
                f"{row.semantic_ndcg:.6f}",
            ])

    summary = [
        "# Chunking Strategy A/B Evaluation",
        "",
        f"- Dataset: `{cfg.dataset_path}`",
        f"- Docs root: `{cfg.docs_root}`",
        f"- Queries: `{run.fixed.total_queries}`",
        f"- TopK: `{cfg.top_k}`",
        "",
        "## Core",
        f"- Hit@K: `{run.fixed.hit_at_k * 100:.2f}% -> {run.semantic.hit_at_k * 100:.2f}%`",
        f"- Stability Hit Rate: `{run.fixed.stability_hit_rate * 100:.2f}% -> {run.semantic.stability_hit_rate * 100:.2f}%`",
        f"- Irrelevant Recall Rate: `{run.fixed.irrelevant_recall_rate * 100:.2f}% -> {run.semantic.irrelevant_recall_rate * 100:.2f}%`",
        "",
        "## Secondary",
        f"- MRR@K: `{run.fixed.mrr_at_k:.4f} -> {run.semantic.mrr_at_k:.4f}`",
        f"- nDCG@K: `{run.fixed.ndcg_at_k:.4f} -> {run.semantic.ndcg_at_k:.4f}`",
        f"- Chunk Count: `{run.fixed.chunk_count} -> {run.semantic.chunk_count}`",
        f"- Avg Chunk Length: `{run.fixed.avg_chunk_len:.1f} -> {run.semantic.avg_chunk_len:.1f}`",
    ]
    summary_path.write_text("\n".join(summary) + "\n", encoding="utf-8")

    return {
        "out_dir": out_dir,
        "metrics": metrics_path,
        "per_query": csv_path,
        "summary": summary_path,
    }


def format_chunking_console_summary(run: ChunkingEvalRun) -> str:
    return (
        "[chunking-eval] Hit@K: "
        f"{run.fixed.hit_at_k * 100:.2f}% -> {run.semantic.hit_at_k * 100:.2f}% | "
        "Stability: "
        f"{run.fixed.stability_hit_rate * 100:.2f}% -> {run.semantic.stability_hit_rate * 100:.2f}% | "
        "Irrelevant Recall: "
        f"{run.fixed.irrelevant_recall_rate * 100:.2f}% -> {run.semantic.irrelevant_recall_rate * 100:.2f}%"
    )


def assert_chunking_gates(cfg: ChunkingEvalConfig, run: ChunkingEvalRun) -> None:
    hit_lift = run.semantic.hit_at_k - run.fixed.hit_at_k
    if hit_lift < cfg.min_hit_lift_abs:
        raise AssertionError(
            f"Hit@K lift below gate: actual={hit_lift:.6f}, required>={cfg.min_hit_lift_abs:.6f}"
        )

    base_ir = run.fixed.irrelevant_recall_rate
    sem_ir = run.semantic.irrelevant_recall_rate
    if base_ir <= 1e-12:
        drop_rel = 1.0 if sem_ir <= 1e-12 else -1.0
    else:
        drop_rel = (base_ir - sem_ir) / base_ir

    if drop_rel < cfg.min_ir_drop_rel:
        raise AssertionError(
            f"Irrelevant recall relative drop below gate: actual={drop_rel:.6f}, required>={cfg.min_ir_drop_rel:.6f}"
        )


def _branch_to_dict(metrics: BranchMetrics) -> dict[str, float | int]:
    return {
        "total_queries": metrics.total_queries,
        "hit_at_k": metrics.hit_at_k,
        "mrr_at_k": metrics.mrr_at_k,
        "ndcg_at_k": metrics.ndcg_at_k,
        "irrelevant_recall_rate": metrics.irrelevant_recall_rate,
        "stability_hit_rate": metrics.stability_hit_rate,
        "avg_chunk_len": metrics.avg_chunk_len,
        "chunk_count": metrics.chunk_count,
    }


def _env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "y", "on"}


def _env_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None:
        return default
    return int(raw.strip())


def _env_int_or_none(name: str, default: int | None) -> int | None:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    return int(raw.strip())


def _env_float(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None:
        return default
    return float(raw.strip())
