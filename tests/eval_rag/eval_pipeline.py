from __future__ import annotations

import csv
import json
import math
import os
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

_CJK_OR_WORD = re.compile(r"[A-Za-z0-9_]+|[\u4e00-\u9fff]")
_STOP_TOKENS = {
    "的", "了", "是", "在", "和", "与", "及", "并", "或", "就", "都", "也",
    "吗", "呢", "啊", "吧", "请", "问", "个", "这", "那", "有", "为", "将",
    "to", "the", "a", "an", "is", "are", "of", "in", "on", "for", "and", "or",
}


@dataclass(slots=True)
class ChunkCandidate:
    chunk_id: str
    text: str
    score: float
    channel: str = ""
    channel_weight: float = 1.0
    source_rank: int = 0
    rerank_score: float | None = None

    @property
    def weighted_score(self) -> float:
        return float(self.score) * float(self.channel_weight)


@dataclass(slots=True)
class QuerySample:
    query_id: str
    query: str
    channels: list[dict[str, Any]]
    gold_chunk_ids: set[str] = field(default_factory=set)
    answerable: bool | None = None
    top_n: int | None = None
    metadata: dict[str, Any] = field(default_factory=dict)


@dataclass(slots=True)
class EvalConfig:
    dataset_path: Path
    artifact_dir: Path
    top_n: int = 5
    min_rerank_score: float = 0.20
    rerank_mode: str = "heuristic"
    dashscope_api_key: str | None = None
    dashscope_endpoint: str = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"
    dashscope_model: str = "qwen3-rerank"
    http_timeout_sec: float = 30.0
    enforce_assertions: bool = False
    min_answer_relevance_lift_abs: float = 0.0
    min_irrelevant_drop_rel: float = 0.0
    fail_on_rerank_error: bool = False
    query_limit: int | None = None
    run_tag: str | None = None

    @staticmethod
    def from_env(eval_root: Path) -> "EvalConfig":
        dataset_path = Path(
            os.getenv("RAG_EVAL_DATASET", str(eval_root / "data" / "evalset.sample.jsonl"))
        )
        artifact_dir = Path(
            os.getenv("RAG_EVAL_ARTIFACT_DIR", str(eval_root / "artifacts" / "latest"))
        )

        return EvalConfig(
            dataset_path=dataset_path,
            artifact_dir=artifact_dir,
            top_n=_env_int("RAG_EVAL_TOP_N", 5),
            min_rerank_score=_env_float("RAG_EVAL_MIN_RERANK_SCORE", 0.20),
            rerank_mode=os.getenv("RAG_EVAL_RERANK_MODE", "heuristic").strip().lower(),
            dashscope_api_key=os.getenv("RAG_EVAL_DASHSCOPE_API_KEY") or None,
            dashscope_endpoint=os.getenv(
                "RAG_EVAL_DASHSCOPE_ENDPOINT",
                "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank",
            ),
            dashscope_model=os.getenv("RAG_EVAL_DASHSCOPE_MODEL", "qwen3-rerank"),
            http_timeout_sec=_env_float("RAG_EVAL_HTTP_TIMEOUT_SEC", 30.0),
            enforce_assertions=_env_bool("RAG_EVAL_ENFORCE_ASSERTIONS", False),
            min_answer_relevance_lift_abs=_env_float("RAG_EVAL_MIN_ANSWER_REL_LIFT_ABS", 0.0),
            min_irrelevant_drop_rel=_env_float("RAG_EVAL_MIN_IRRELEVANT_DROP_REL", 0.0),
            fail_on_rerank_error=_env_bool("RAG_EVAL_FAIL_ON_RERANK_ERROR", False),
            query_limit=_env_int_or_none("RAG_EVAL_QUERY_LIMIT", None),
            run_tag=(os.getenv("RAG_EVAL_RUN_TAG") or "").strip() or None,
        )


@dataclass(slots=True)
class QueryEvalResult:
    query_id: str
    query: str
    baseline_top_ids: list[str]
    candidate_top_ids: list[str]
    baseline_hit: int
    candidate_hit: int
    baseline_relevant_count: int
    candidate_relevant_count: int
    baseline_total: int
    candidate_total: int
    baseline_irrelevant_count: int
    candidate_irrelevant_count: int
    baseline_precision: float
    candidate_precision: float
    baseline_ndcg: float
    candidate_ndcg: float


@dataclass(slots=True)
class AggregateMetrics:
    total_queries: int
    answerable_queries: int
    answer_relevance_rate: float
    irrelevant_recall_rate: float
    precision_at_n: float
    ndcg_at_n: float
    empty_context_rate: float


@dataclass(slots=True)
class EvaluationRun:
    baseline: AggregateMetrics
    candidate: AggregateMetrics
    per_query: list[QueryEvalResult]


class RerankerBase:
    def score(self, query: str, candidates: list[ChunkCandidate]) -> list[float]:
        raise NotImplementedError


class HeuristicReranker(RerankerBase):
    def score(self, query: str, candidates: list[ChunkCandidate]) -> list[float]:
        if not candidates:
            return []

        weighted_scores = [c.weighted_score for c in candidates]
        min_score = min(weighted_scores)
        max_score = max(weighted_scores)
        denominator = max(max_score - min_score, 1e-9)

        query_tokens = _tokenize(query)
        out: list[float] = []
        for candidate in candidates:
            content_tokens = _tokenize(candidate.text)
            overlap = _token_overlap(query_tokens, content_tokens)
            score_norm = (candidate.weighted_score - min_score) / denominator
            combined = 0.85 * overlap + 0.15 * score_norm
            out.append(float(combined))
        return out


class NoopReranker(RerankerBase):
    def score(self, query: str, candidates: list[ChunkCandidate]) -> list[float]:
        del query
        if not candidates:
            return []
        weighted_scores = [c.weighted_score for c in candidates]
        min_score = min(weighted_scores)
        max_score = max(weighted_scores)
        denominator = max(max_score - min_score, 1e-9)
        return [float((score - min_score) / denominator) for score in weighted_scores]


class DashScopeReranker(RerankerBase):
    def __init__(
        self,
        api_key: str,
        endpoint: str,
        model: str,
        timeout_sec: float,
        fail_on_error: bool,
        fallback: RerankerBase,
    ) -> None:
        self._api_key = api_key
        self._endpoint = endpoint
        self._model = model
        self._timeout_sec = timeout_sec
        self._fail_on_error = fail_on_error
        self._fallback = fallback

    def score(self, query: str, candidates: list[ChunkCandidate]) -> list[float]:
        if not candidates:
            return []

        payload = {
            "model": self._model,
            "input": {
                "query": query,
                "documents": [c.text for c in candidates],
            },
            "parameters": {
                "top_n": len(candidates),
                "return_documents": False,
            },
        }

        req = urllib.request.Request(
            url=self._endpoint,
            method="POST",
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self._api_key}",
            },
            data=json.dumps(payload).encode("utf-8"),
        )

        try:
            with urllib.request.urlopen(req, timeout=self._timeout_sec) as response:
                body = response.read().decode("utf-8")
            parsed = json.loads(body)
            results = (((parsed or {}).get("output") or {}).get("results") or [])
            if not isinstance(results, list) or not results:
                raise ValueError("DashScope rerank returned empty results")

            scores = [0.0 for _ in candidates]
            for row in results:
                if not isinstance(row, dict):
                    continue
                index = row.get("index")
                value = row.get("relevance_score")
                if isinstance(index, int) and 0 <= index < len(candidates) and isinstance(value, (int, float)):
                    scores[index] = float(value)
            return scores
        except Exception as exc:  # noqa: BLE001
            if self._fail_on_error:
                raise
            # fallback to deterministic local reranker to keep eval runnable
            print(f"[eval-rag] DashScope rerank failed, fallback to heuristic: {exc}")
            return self._fallback.score(query, candidates)


def create_reranker(config: EvalConfig) -> RerankerBase:
    mode = (config.rerank_mode or "heuristic").strip().lower()
    heuristic = HeuristicReranker()

    if mode == "none":
        return NoopReranker()
    if mode == "dashscope":
        if not config.dashscope_api_key:
            if config.fail_on_rerank_error:
                raise ValueError("RAG_EVAL_DASHSCOPE_API_KEY is required when RAG_EVAL_RERANK_MODE=dashscope")
            print("[eval-rag] No DashScope API key, fallback to heuristic reranker")
            return heuristic
        return DashScopeReranker(
            api_key=config.dashscope_api_key,
            endpoint=config.dashscope_endpoint,
            model=config.dashscope_model,
            timeout_sec=config.http_timeout_sec,
            fail_on_error=config.fail_on_rerank_error,
            fallback=heuristic,
        )
    return heuristic


def load_samples(dataset_path: Path, query_limit: int | None = None) -> list[QuerySample]:
    if not dataset_path.exists():
        raise FileNotFoundError(f"Eval dataset not found: {dataset_path}")

    samples: list[QuerySample] = []
    with dataset_path.open("r", encoding="utf-8-sig") as file:
        for index, raw_line in enumerate(file, start=1):
            line = raw_line.strip()
            if not line or line.startswith("#"):
                continue
            payload = json.loads(line)
            samples.append(_parse_sample(payload, index))
            if query_limit is not None and len(samples) >= query_limit:
                break

    if not samples:
        raise ValueError(f"No valid sample found in {dataset_path}")
    return samples


def evaluate_dataset(samples: list[QuerySample], config: EvalConfig, reranker: RerankerBase) -> EvaluationRun:
    per_query: list[QueryEvalResult] = []

    for sample in samples:
        top_n = sample.top_n if sample.top_n is not None else config.top_n
        flattened = _flatten_candidates(sample)

        baseline_top = _baseline_top_n(flattened, top_n)
        candidate_top = _candidate_top_n(
            query=sample.query,
            flattened=flattened,
            top_n=top_n,
            min_rerank_score=config.min_rerank_score,
            reranker=reranker,
        )

        baseline_eval = _evaluate_selection(baseline_top, sample.gold_chunk_ids)
        candidate_eval = _evaluate_selection(candidate_top, sample.gold_chunk_ids)

        per_query.append(
            QueryEvalResult(
                query_id=sample.query_id,
                query=sample.query,
                baseline_top_ids=[c.chunk_id for c in baseline_top],
                candidate_top_ids=[c.chunk_id for c in candidate_top],
                baseline_hit=baseline_eval["hit"],
                candidate_hit=candidate_eval["hit"],
                baseline_relevant_count=baseline_eval["relevant_count"],
                candidate_relevant_count=candidate_eval["relevant_count"],
                baseline_total=baseline_eval["total"],
                candidate_total=candidate_eval["total"],
                baseline_irrelevant_count=baseline_eval["irrelevant_count"],
                candidate_irrelevant_count=candidate_eval["irrelevant_count"],
                baseline_precision=baseline_eval["precision"],
                candidate_precision=candidate_eval["precision"],
                baseline_ndcg=baseline_eval["ndcg"],
                candidate_ndcg=candidate_eval["ndcg"],
            )
        )

    return EvaluationRun(
        baseline=_aggregate_metrics(samples, per_query, branch="baseline"),
        candidate=_aggregate_metrics(samples, per_query, branch="candidate"),
        per_query=per_query,
    )


def write_artifacts(config: EvalConfig, run: EvaluationRun) -> dict[str, Path]:
    run_id = config.run_tag or time.strftime("%Y%m%d_%H%M%S")
    out_dir = config.artifact_dir / run_id
    out_dir.mkdir(parents=True, exist_ok=True)

    metrics_path = out_dir / "metrics.json"
    csv_path = out_dir / "per_query.csv"
    summary_path = out_dir / "summary.md"

    metrics_payload = {
        "config": {
            "dataset_path": str(config.dataset_path),
            "top_n": config.top_n,
            "min_rerank_score": config.min_rerank_score,
            "rerank_mode": config.rerank_mode,
        },
        "baseline": _metrics_to_dict(run.baseline),
        "candidate": _metrics_to_dict(run.candidate),
        "delta": {
            "answer_relevance_lift_abs": run.candidate.answer_relevance_rate - run.baseline.answer_relevance_rate,
            "irrelevant_recall_drop_abs": run.baseline.irrelevant_recall_rate - run.candidate.irrelevant_recall_rate,
            "precision_lift_abs": run.candidate.precision_at_n - run.baseline.precision_at_n,
            "ndcg_lift_abs": run.candidate.ndcg_at_n - run.baseline.ndcg_at_n,
        },
    }

    with metrics_path.open("w", encoding="utf-8") as file:
        json.dump(metrics_payload, file, ensure_ascii=False, indent=2)

    with csv_path.open("w", encoding="utf-8", newline="") as file:
        writer = csv.writer(file)
        writer.writerow(
            [
                "query_id",
                "query",
                "baseline_top_ids",
                "candidate_top_ids",
                "baseline_hit",
                "candidate_hit",
                "baseline_precision",
                "candidate_precision",
                "baseline_ndcg",
                "candidate_ndcg",
                "baseline_irrelevant_count",
                "candidate_irrelevant_count",
            ]
        )
        for row in run.per_query:
            writer.writerow(
                [
                    row.query_id,
                    row.query,
                    "|".join(row.baseline_top_ids),
                    "|".join(row.candidate_top_ids),
                    row.baseline_hit,
                    row.candidate_hit,
                    f"{row.baseline_precision:.6f}",
                    f"{row.candidate_precision:.6f}",
                    f"{row.baseline_ndcg:.6f}",
                    f"{row.candidate_ndcg:.6f}",
                    row.baseline_irrelevant_count,
                    row.candidate_irrelevant_count,
                ]
            )

    summary_lines = [
        "# RAG 后处理 A/B 评测结果",
        "",
        f"- 数据集: `{config.dataset_path}`",
        f"- 样本数: `{run.baseline.total_queries}`",
        f"- TopN: `{config.top_n}`",
        f"- Rerank 模式: `{config.rerank_mode}`",
        "",
        "## 核心结论",
        "",
        f"- 回答相关性: `{run.baseline.answer_relevance_rate * 100:.2f}% -> {run.candidate.answer_relevance_rate * 100:.2f}%`",
        f"- 无关召回率: `{run.baseline.irrelevant_recall_rate * 100:.2f}% -> {run.candidate.irrelevant_recall_rate * 100:.2f}%`",
        "",
        "## 辅助指标",
        "",
        f"- Precision@N: `{run.baseline.precision_at_n:.4f} -> {run.candidate.precision_at_n:.4f}`",
        f"- nDCG@N: `{run.baseline.ndcg_at_n:.4f} -> {run.candidate.ndcg_at_n:.4f}`",
        f"- 空上下文率: `{run.baseline.empty_context_rate * 100:.2f}% -> {run.candidate.empty_context_rate * 100:.2f}%`",
    ]
    summary_path.write_text("\n".join(summary_lines) + "\n", encoding="utf-8")

    return {
        "out_dir": out_dir,
        "metrics": metrics_path,
        "per_query": csv_path,
        "summary": summary_path,
    }


def format_console_summary(run: EvaluationRun) -> str:
    return (
        "[eval-rag] Answer Relevance: "
        f"{run.baseline.answer_relevance_rate * 100:.2f}% -> {run.candidate.answer_relevance_rate * 100:.2f}% | "
        "Irrelevant Recall: "
        f"{run.baseline.irrelevant_recall_rate * 100:.2f}% -> {run.candidate.irrelevant_recall_rate * 100:.2f}%"
    )


def assert_gates(config: EvalConfig, run: EvaluationRun) -> None:
    answer_lift = run.candidate.answer_relevance_rate - run.baseline.answer_relevance_rate
    if answer_lift < config.min_answer_relevance_lift_abs:
        raise AssertionError(
            "Answer relevance lift below gate: "
            f"actual={answer_lift:.6f}, required>={config.min_answer_relevance_lift_abs:.6f}"
        )

    baseline_ir = run.baseline.irrelevant_recall_rate
    candidate_ir = run.candidate.irrelevant_recall_rate
    if baseline_ir <= 1e-12:
        relative_drop = 1.0 if candidate_ir <= 1e-12 else -1.0
    else:
        relative_drop = (baseline_ir - candidate_ir) / baseline_ir

    if relative_drop < config.min_irrelevant_drop_rel:
        raise AssertionError(
            "Irrelevant recall relative drop below gate: "
            f"actual={relative_drop:.6f}, required>={config.min_irrelevant_drop_rel:.6f}"
        )


# ----------------------------
# Internal helpers
# ----------------------------

def _parse_sample(raw: dict[str, Any], index: int) -> QuerySample:
    if not isinstance(raw, dict):
        raise ValueError(f"Invalid sample at line {index}: not an object")

    query = str(raw.get("query", "")).strip()
    if not query:
        raise ValueError(f"Invalid sample at line {index}: query is required")

    channels = raw.get("channels")
    if not isinstance(channels, list) or not channels:
        raise ValueError(f"Invalid sample at line {index}: channels must be a non-empty list")

    query_id = str(raw.get("query_id") or raw.get("id") or f"q{index}")
    gold_ids_raw = raw.get("gold_chunk_ids") or []
    gold_ids = {str(item).strip() for item in gold_ids_raw if str(item).strip()}

    answerable = raw.get("answerable")
    if answerable is not None:
        answerable = bool(answerable)

    top_n = raw.get("top_n")
    if top_n is not None:
        top_n = int(top_n)

    metadata = raw.get("metadata") if isinstance(raw.get("metadata"), dict) else {}

    return QuerySample(
        query_id=query_id,
        query=query,
        channels=channels,
        gold_chunk_ids=gold_ids,
        answerable=answerable,
        top_n=top_n,
        metadata=metadata,
    )


def _flatten_candidates(sample: QuerySample) -> list[ChunkCandidate]:
    flattened: list[ChunkCandidate] = []
    for channel in sample.channels:
        if not isinstance(channel, dict):
            continue
        channel_name = str(channel.get("name") or channel.get("channel") or "unknown")
        channel_weight = float(channel.get("weight", 1.0))
        candidates = channel.get("candidates")
        if not isinstance(candidates, list):
            continue

        for idx, row in enumerate(candidates):
            if not isinstance(row, dict):
                continue
            chunk_id = str(row.get("chunk_id") or row.get("id") or "").strip()
            if not chunk_id:
                continue
            text = str(row.get("text") or row.get("content") or "")
            score_val = row.get("score", row.get("relevance_score", 0.0))
            score = float(score_val)
            flattened.append(
                ChunkCandidate(
                    chunk_id=chunk_id,
                    text=text,
                    score=score,
                    channel=channel_name,
                    channel_weight=channel_weight,
                    source_rank=idx,
                )
            )
    return flattened


def _baseline_top_n(flattened: list[ChunkCandidate], top_n: int) -> list[ChunkCandidate]:
    return sorted(flattened, key=lambda c: c.weighted_score, reverse=True)[:top_n]


def _candidate_top_n(
    query: str,
    flattened: list[ChunkCandidate],
    top_n: int,
    min_rerank_score: float,
    reranker: RerankerBase,
) -> list[ChunkCandidate]:
    deduped = _dedup_by_chunk_id(flattened)
    if not deduped:
        return []

    rerank_scores = reranker.score(query, deduped)
    for candidate, score in zip(deduped, rerank_scores):
        candidate.rerank_score = float(score)

    filtered = [candidate for candidate in deduped if (candidate.rerank_score or 0.0) >= min_rerank_score]
    ranked = sorted(filtered, key=lambda c: c.rerank_score or 0.0, reverse=True)
    return ranked[:top_n]


def _dedup_by_chunk_id(flattened: list[ChunkCandidate]) -> list[ChunkCandidate]:
    by_id: dict[str, ChunkCandidate] = {}
    for item in flattened:
        existing = by_id.get(item.chunk_id)
        if existing is None or item.weighted_score > existing.weighted_score:
            by_id[item.chunk_id] = ChunkCandidate(
                chunk_id=item.chunk_id,
                text=item.text,
                score=item.score,
                channel=item.channel,
                channel_weight=item.channel_weight,
                source_rank=item.source_rank,
            )
    return list(by_id.values())


def _evaluate_selection(selected: list[ChunkCandidate], gold_ids: set[str]) -> dict[str, Any]:
    total = len(selected)
    relevant = sum(1 for item in selected if item.chunk_id in gold_ids)
    irrelevant = total - relevant
    hit = int(relevant > 0)
    precision = (relevant / total) if total else 0.0
    ndcg = _ndcg(selected, gold_ids)
    return {
        "total": total,
        "relevant_count": relevant,
        "irrelevant_count": irrelevant,
        "hit": hit,
        "precision": precision,
        "ndcg": ndcg,
    }


def _aggregate_metrics(samples: list[QuerySample], rows: list[QueryEvalResult], branch: str) -> AggregateMetrics:
    assert branch in {"baseline", "candidate"}

    total_queries = len(rows)
    answerable_indices = [
        idx for idx, sample in enumerate(samples)
        if sample.answerable is not False
    ]
    answerable_queries = len(answerable_indices)

    hit_attr = f"{branch}_hit"
    total_attr = f"{branch}_total"
    irrelevant_attr = f"{branch}_irrelevant_count"
    precision_attr = f"{branch}_precision"
    ndcg_attr = f"{branch}_ndcg"

    if answerable_queries > 0:
        answer_relevance_rate = sum(getattr(rows[idx], hit_attr) for idx in answerable_indices) / answerable_queries
    else:
        answer_relevance_rate = sum(getattr(row, hit_attr) for row in rows) / max(total_queries, 1)

    total_selected = sum(getattr(row, total_attr) for row in rows)
    irrelevant_selected = sum(getattr(row, irrelevant_attr) for row in rows)
    irrelevant_recall_rate = (irrelevant_selected / total_selected) if total_selected else 0.0

    precision_at_n = sum(getattr(row, precision_attr) for row in rows) / max(total_queries, 1)
    ndcg_at_n = sum(getattr(row, ndcg_attr) for row in rows) / max(total_queries, 1)
    empty_context_rate = sum(1 for row in rows if getattr(row, total_attr) == 0) / max(total_queries, 1)

    return AggregateMetrics(
        total_queries=total_queries,
        answerable_queries=answerable_queries,
        answer_relevance_rate=answer_relevance_rate,
        irrelevant_recall_rate=irrelevant_recall_rate,
        precision_at_n=precision_at_n,
        ndcg_at_n=ndcg_at_n,
        empty_context_rate=empty_context_rate,
    )


def _ndcg(selected: list[ChunkCandidate], gold_ids: set[str]) -> float:
    if not selected:
        return 0.0

    dcg = 0.0
    for rank, item in enumerate(selected, start=1):
        rel = 1.0 if item.chunk_id in gold_ids else 0.0
        dcg += rel / math.log2(rank + 1)

    ideal_hits = min(len(gold_ids), len(selected))
    if ideal_hits <= 0:
        return 0.0

    idcg = 0.0
    for rank in range(1, ideal_hits + 1):
        idcg += 1.0 / math.log2(rank + 1)
    return dcg / max(idcg, 1e-12)


def _metrics_to_dict(metrics: AggregateMetrics) -> dict[str, Any]:
    return {
        "total_queries": metrics.total_queries,
        "answerable_queries": metrics.answerable_queries,
        "answer_relevance_rate": metrics.answer_relevance_rate,
        "irrelevant_recall_rate": metrics.irrelevant_recall_rate,
        "precision_at_n": metrics.precision_at_n,
        "ndcg_at_n": metrics.ndcg_at_n,
        "empty_context_rate": metrics.empty_context_rate,
    }


def _tokenize(text: str) -> set[str]:
    tokens = {
        token.lower().strip()
        for token in _CJK_OR_WORD.findall(text or "")
        if token and token.lower().strip() not in _STOP_TOKENS
    }
    return tokens


def _token_overlap(query_tokens: set[str], content_tokens: set[str]) -> float:
    if not query_tokens or not content_tokens:
        return 0.0
    intersection = len(query_tokens & content_tokens)
    return intersection / max(len(query_tokens), 1)


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
