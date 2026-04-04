# Genshin Seed Guide (Ragent)

This guide helps you bootstrap a large Chinese RAG seed for Genshin.

## 1. Prepare data source

The scripts expect `AnimeGameData` under:

- `external_data/AnimeGameData`

Already validated in this workspace.

## 2. Generate seed assets

Run:

```powershell
python scripts/genshin/build_genshin_seed.py --clean --max-main-quests 3943 --max-main-quest-intents 700 --max-achievements 900 --max-materials 1000
```

New in this version:

- Main quest docs include sub-quest steps from `QuestExcelConfigData`.
- Optional weapon-owner enrichment from `resources/genshin_seed/extensions/weapon_owner_links.json`.
- Optional lore extension docs from `resources/genshin_seed/extensions/lore_entries.json`.

Output directory:

- `resources/genshin_seed/docs` (markdown corpus)
- `resources/genshin_seed/config/intent_nodes.json`
- `resources/genshin_seed/config/query_term_mappings.json`
- `resources/genshin_seed/config/pipeline_template.json`
- `resources/genshin_seed/config/sample_questions.json`
- `resources/genshin_seed/manifest.json`

## 3. Import into Ragent

If backend is running at `http://localhost:9090/api/ragent`, run:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/genshin/bootstrap_genshin_rag.ps1 `
  -ApiBaseUrl "http://localhost:9090/api/ragent" `
  -Username "admin" `
  -Password "admin" `
  -SeedRoot "resources/genshin_seed" `
  -KnowledgeBaseName "genshin_kb_bailian_v4" `
  -CollectionName "genshinbailianv4" `
  -EmbeddingModel "text-embedding-v4"
```

The command above will:

1. Login
2. Ensure knowledge base exists
3. Ensure ingestion pipeline exists
4. Import intent nodes
5. Import query-term mappings

Useful flags for repeated runs:

- `-SkipIntentImport` skip intent import
- `-SkipMappingImport` skip mapping import

## 4. Upload docs (optional, heavy)

To upload markdown docs and optionally trigger chunking:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/genshin/bootstrap_genshin_rag.ps1 `
  -UploadDocs `
  -MaxUploads 300 `
  -ChunkAfterUpload
```

Notes:

- `-MaxUploads 0` means upload all docs.
- Prefer phased batches (`200~500`) first.
- If your machine is small, increase `-UploadBatchSleepMs`.

## 5. Reconcile and repair (recommended)

After repeated imports, run one reconciliation pass to:

- fill missing intents/mappings
- normalize problematic characters
- align existing intent fields back to seed

```powershell
python scripts/genshin/reconcile_genshin_seed.py `
  --api-base-url "http://localhost:9090/api/ragent" `
  --username "admin" `
  --password "admin" `
  --seed-root "resources/genshin_seed" `
  --knowledge-base-name "genshin_kb_bailian_v4"
```

## 6. Validate with trace

1. Ask questions in chat page.
2. Open `链路追踪` in admin.
3. Verify chain: rewrite -> intent -> retrieval -> generation.
4. Use sample questions from `resources/genshin_seed/config/sample_questions.json`.
