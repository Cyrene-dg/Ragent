/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.es;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.rag.config.KeywordEsChannelProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 启动时可选执行一次关键词索引回填
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordEsBackfillRunner implements ApplicationRunner {

    private final KeywordEsChannelProperties properties;
    private final KeywordEsGateway keywordEsGateway;
    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeDocumentMapper documentMapper;

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled() || !properties.isBackfillOnStartup() || !keywordEsGateway.available()) {
            return;
        }
        int pageSize = Math.max(100, properties.getBackfillBatchSize());
        Set<Long> kbIds = properties.getBackfillKbIds() == null
                ? Set.of()
                : properties.getBackfillKbIds().stream()
                        .filter(java.util.Objects::nonNull)
                        .collect(Collectors.toSet());
        log.info("start keyword-es backfill, pageSize={}, kbIds={}", pageSize,
                kbIds.isEmpty() ? "all" : kbIds);

        long current = 1;
        long totalChunks = 0;
        while (true) {
            var query = Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                    .eq(KnowledgeChunkDO::getEnabled, 1)
                    .orderByAsc(KnowledgeChunkDO::getId);
            if (!kbIds.isEmpty()) {
                query.in(KnowledgeChunkDO::getKbId, kbIds);
            }
            IPage<KnowledgeChunkDO> page = chunkMapper.selectPage(
                    new Page<>(current, pageSize),
                    query
            );
            List<KnowledgeChunkDO> records = page.getRecords();
            if (records == null || records.isEmpty()) {
                break;
            }

            Set<Long> docIds = records.stream()
                    .map(KnowledgeChunkDO::getDocId)
                    .collect(Collectors.toSet());
            Map<Long, KnowledgeDocumentDO> docMap = documentMapper.selectBatchIds(docIds).stream()
                    .collect(Collectors.toMap(KnowledgeDocumentDO::getId, Function.identity(), (a, b) -> a));

            List<KeywordEsGateway.ChunkIndexEntry> entries = new ArrayList<>(records.size());
            for (KnowledgeChunkDO chunk : records) {
                KnowledgeDocumentDO doc = docMap.get(chunk.getDocId());
                if (doc == null) {
                    continue;
                }
                entries.add(new KeywordEsGateway.ChunkIndexEntry(doc.getKbId(), doc.getId(), doc.getDocName(), chunk));
            }
            keywordEsGateway.upsertChunkEntries(entries);
            totalChunks += entries.size();

            if (current >= page.getPages()) {
                break;
            }
            current++;
        }
        log.info("keyword-es backfill finished, chunks={}", totalChunks);
    }
}

