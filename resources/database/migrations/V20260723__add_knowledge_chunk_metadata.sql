-- Non-destructive v7 RAG migration.
-- Existing chunks remain valid; new v7 chunks persist their retrieval metadata
-- here and replicate it to Milvus JSON metadata during indexing.
ALTER TABLE `t_knowledge_chunk`
    ADD COLUMN `metadata_json` JSON NULL COMMENT 'Chunk级检索元数据JSON' AFTER `token_count`;
