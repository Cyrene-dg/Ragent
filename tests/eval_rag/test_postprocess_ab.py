from __future__ import annotations

import pytest

from tests.eval_rag.eval_pipeline import (
    EvalConfig,
    assert_gates,
    evaluate_dataset,
    format_console_summary,
    write_artifacts,
)


@pytest.mark.eval
def test_eval_dataset_schema(eval_samples):
    assert eval_samples, "评测集为空，请检查 RAG_EVAL_DATASET"


@pytest.mark.eval
def test_postprocess_ab_metrics(eval_config: EvalConfig, eval_samples, reranker):
    run = evaluate_dataset(eval_samples, eval_config, reranker)
    artifacts = write_artifacts(eval_config, run)

    print(format_console_summary(run))
    print(f"[eval-rag] Artifacts directory: {artifacts['out_dir']}")
    print(f"[eval-rag] metrics.json: {artifacts['metrics']}")
    print(f"[eval-rag] per_query.csv: {artifacts['per_query']}")
    print(f"[eval-rag] summary.md: {artifacts['summary']}")

    assert run.baseline.total_queries > 0, "没有可评测 query"

    if eval_config.enforce_assertions:
        assert_gates(eval_config, run)
