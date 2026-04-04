# RAG 后处理离线评测（Pytest）

这套评测只新增 `tests/eval_rag`，不修改业务代码。

## 1. 评测目标

对同一批多通道召回候选做 A/B：

- A（Baseline）：直接按召回分取 TopN
- B（Candidate）：`chunk_id` 去重（保留高分）+ Rerank 二次精排 + 噪声阈值过滤

输出核心结论：

- 回答相关性：`X% -> Y%`
- 无关召回率：`X% -> Y%`

## 2. 目录结构

- `tests/eval_rag/conftest.py`：pytest 夹具
- `tests/eval_rag/eval_pipeline.py`：评测主逻辑
- `tests/eval_rag/test_postprocess_ab.py`：A/B 测试入口
- `tests/eval_rag/data/evalset.sample.jsonl`：样例数据
- `tests/eval_rag/artifacts/`：评测产物输出目录

## 3. 数据格式（JSONL）

每行一个样本，核心字段：

- `query`：查询问题
- `channels`：多通道候选
- `gold_chunk_ids`：相关 chunk 标注（用于算指标）
- `top_n`：可选，覆盖全局 TopN

候选格式支持 `chunk_id` / `id`，`text` / `content`，`score` / `relevance_score`。

## 4. 安装与运行

```powershell
python -m pip install -r tests/eval_rag/requirements.txt
python -m pytest tests/eval_rag -m eval -s
```

默认读取：`tests/eval_rag/data/evalset.sample.jsonl`

如果要基于仓库里的原神真实语料自动构建弱标注评测集：

```powershell
python tests/eval_rag/tools/build_genshin_evalset.py `
  --seed-root resources/genshin_seed `
  --output tests/eval_rag/data/evalset.genshin.weak.jsonl
```

然后用该数据集评测：

```powershell
$env:RAG_EVAL_DATASET = "G:\Ragent2026\ragent\tests\eval_rag\data\evalset.genshin.weak.jsonl"
python -m pytest tests/eval_rag -m eval -s
```

## 5. 常用环境变量

- `RAG_EVAL_DATASET`：评测集路径
- `RAG_EVAL_TOP_N`：全局 TopN（默认 `5`）
- `RAG_EVAL_MIN_RERANK_SCORE`：过滤阈值（默认 `0.20`）
- `RAG_EVAL_RERANK_MODE`：`heuristic` / `dashscope` / `none`
- `RAG_EVAL_DASHSCOPE_API_KEY`：DashScope Key（`dashscope` 模式必需）
- `RAG_EVAL_DASHSCOPE_MODEL`：默认 `qwen3-rerank`
- `RAG_EVAL_ENFORCE_ASSERTIONS`：是否开启门槛断言（默认 `false`）
- `RAG_EVAL_MIN_ANSWER_REL_LIFT_ABS`：回答相关性最小绝对提升
- `RAG_EVAL_MIN_IRRELEVANT_DROP_REL`：无关召回率最小相对下降
- `RAG_EVAL_ARTIFACT_DIR`：评测产物根目录
- `RAG_EVAL_RUN_TAG`：产物子目录名

## 6. 产物说明

每次运行会生成：

- `metrics.json`：总体指标与 delta
- `per_query.csv`：逐 query A/B 明细
- `summary.md`：可直接贴周报的结论摘要

## 7. 指标口径

- 回答相关性：在 `answerable != false` 的 query 上，`Hit@N` 占比
- 无关召回率：`TopN` 中无关 chunk 数量 / `TopN` 总 chunk 数量
- 辅助指标：`Precision@N`、`nDCG@N`、空上下文率

## 8. 对接真实数据建议

当前仓库未暴露“多通道原始候选 chunk 列表”公共接口，所以评测采用回放 JSONL。
你可以把线上/离线检索日志导出为上述格式后直接跑这套 pytest。
`build_genshin_evalset.py` 产出的数据是弱标注数据，适合先做策略对比，不建议直接当线上绝对效果口径。

## 9. 分块策略 A/B（固定分块 vs 结构感知分块）

这组评测用于测：

- A（Fixed）：`fixed_size`（`chunkSize + overlapSize`）
- B（Semantic）：`structure_aware`（标题/段落/代码块边界 + `min/target/max + overlap`）

先构建分块评测集：

```powershell
python tests/eval_rag/tools/build_chunking_evalset.py `
  --seed-root resources/genshin_seed `
  --output tests/eval_rag/data/chunking_evalset.genshin.jsonl
```

再执行 chunking A/B：

```powershell
$env:RAG_CHUNK_EVAL_DATASET = "G:\Ragent2026\ragent\tests\eval_rag\data\chunking_evalset.genshin.jsonl"
$env:RAG_CHUNK_EVAL_DOCS_ROOT = "G:\Ragent2026\ragent\resources\genshin_seed\docs"
python -m pytest tests/eval_rag/test_chunking_strategy_ab.py -m eval -s
```

可选参数（默认值与项目 Java 配置对齐）：

- `RAG_CHUNK_EVAL_FIXED_CHUNK_SIZE`（默认 `512`）
- `RAG_CHUNK_EVAL_FIXED_OVERLAP`（默认 `128`）
- `RAG_CHUNK_EVAL_SEM_TARGET`（默认 `1400`）
- `RAG_CHUNK_EVAL_SEM_MAX`（默认 `1800`）
- `RAG_CHUNK_EVAL_SEM_MIN`（默认 `600`）
- `RAG_CHUNK_EVAL_SEM_OVERLAP`（默认 `0`）
- `RAG_CHUNK_EVAL_TOP_K`（默认 `5`）

主要输出：

- `chunking_metrics.json`
- `chunking_per_query.csv`
- `chunking_summary.md`
