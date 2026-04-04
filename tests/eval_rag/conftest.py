from __future__ import annotations

from pathlib import Path

import pytest

from tests.eval_rag.chunking_eval import ChunkingEvalConfig, load_docs, load_queries
from tests.eval_rag.eval_pipeline import EvalConfig, create_reranker, load_samples


@pytest.fixture(scope="session")
def eval_root() -> Path:
    return Path(__file__).resolve().parent


@pytest.fixture(scope="session")
def repo_root(eval_root: Path) -> Path:
    return eval_root.parent.parent


@pytest.fixture(scope="session")
def eval_config(eval_root: Path) -> EvalConfig:
    return EvalConfig.from_env(eval_root)


@pytest.fixture(scope="session")
def eval_samples(eval_config: EvalConfig):
    return load_samples(eval_config.dataset_path, query_limit=eval_config.query_limit)


@pytest.fixture(scope="session")
def reranker(eval_config: EvalConfig):
    return create_reranker(eval_config)


@pytest.fixture(scope="session")
def chunking_eval_config(eval_root: Path, repo_root: Path) -> ChunkingEvalConfig:
    return ChunkingEvalConfig.from_env(eval_root, repo_root)


@pytest.fixture(scope="session")
def chunking_eval_queries(chunking_eval_config: ChunkingEvalConfig):
    return load_queries(chunking_eval_config.dataset_path)


@pytest.fixture(scope="session")
def chunking_eval_docs(chunking_eval_config: ChunkingEvalConfig):
    return load_docs(chunking_eval_config.docs_root, max_docs=chunking_eval_config.max_docs)
