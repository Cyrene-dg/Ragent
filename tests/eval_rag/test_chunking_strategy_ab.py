from __future__ import annotations

import pytest

from tests.eval_rag.chunking_eval import (
    ChunkingEvalConfig,
    assert_chunking_gates,
    evaluate_chunking_ab,
    format_chunking_console_summary,
    write_chunking_artifacts,
)


@pytest.mark.eval
def test_chunking_eval_dataset_schema(chunking_eval_queries, chunking_eval_docs):
    assert chunking_eval_queries, "chunking 评测集为空，请检查 RAG_CHUNK_EVAL_DATASET"
    assert chunking_eval_docs, "chunking 语料为空，请检查 RAG_CHUNK_EVAL_DOCS_ROOT"


@pytest.mark.eval
def test_chunking_strategy_ab_metrics(
    chunking_eval_config: ChunkingEvalConfig,
    chunking_eval_queries,
    chunking_eval_docs,
):
    run = evaluate_chunking_ab(
        cfg=chunking_eval_config,
        queries=chunking_eval_queries,
        docs=chunking_eval_docs,
    )
    artifacts = write_chunking_artifacts(chunking_eval_config, run)

    print(format_chunking_console_summary(run))
    print(f"[chunking-eval] Artifacts directory: {artifacts['out_dir']}")
    print(f"[chunking-eval] chunking_metrics.json: {artifacts['metrics']}")
    print(f"[chunking-eval] chunking_per_query.csv: {artifacts['per_query']}")
    print(f"[chunking-eval] chunking_summary.md: {artifacts['summary']}")

    assert run.fixed.total_queries > 0, "没有可评测 query"

    if chunking_eval_config.enforce_assertions:
        assert_chunking_gates(chunking_eval_config, run)
