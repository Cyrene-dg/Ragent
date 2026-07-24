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

package com.nageoffer.ai.ragent.rag.core.retrieve.plan;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.es.KeywordEsGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProgressiveKeywordEsPlanRetrieverTest {

    private KeywordEsGateway gateway;
    private ProgressiveKeywordEsPlanRetriever progressiveRetriever;

    @BeforeEach
    void setUp() {
        gateway = mock(KeywordEsGateway.class);
        progressiveRetriever = new ProgressiveKeywordEsPlanRetriever(gateway);
    }

    @Test
    void shouldStopAfterStrictLevelWhenTwentyUniqueChunksAreReturned() {
        when(gateway.search(anyString(), anyInt(), anySet(), anyMap())).thenReturn(chunks("strict-", 1, 20));

        var result = progressiveRetriever.retrieve("神里绫华元素战技", plan(), List.of(nodeScore()));

        assertEquals(20, result.chunks().size());
        assertEquals(1, result.executions().size());
        verify(gateway, times(1)).search("神里绫华元素战技", 20, Set.of("2080272025011851264"), strictFilters());
        assertFalse(result.shouldFallbackToLegacy());
    }

    @Test
    void shouldKeepStrictResultsAndRelaxUntilTargetIsFilled() {
        when(gateway.search(anyString(), anyInt(), anySet(), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> filters = invocation.getArgument(3);
            return filters.containsKey("entity_id") ? chunks("chunk-", 1, 5) : chunks("chunk-", 6, 15);
        });

        var result = progressiveRetriever.retrieve("神里绫华元素战技", plan(), List.of(nodeScore()));

        assertEquals(20, result.chunks().size());
        assertEquals(2, result.executions().size());
        assertEquals(strictFilters(), result.executions().get(0).metadataFilters());
        assertEquals(Map.of("category", "character"), result.executions().get(1).metadataFilters());
    }

    @Test
    void shouldKeepTheKnowledgeBaseBoundaryForV7GlobalFallback() {
        ArgumentCaptor<Set<String>> kbIdsCaptor = ArgumentCaptor.forClass(Set.class);
        when(gateway.search(anyString(), anyInt(), anySet(), anyMap())).thenReturn(List.of());

        progressiveRetriever.retrieve("神里绫华元素战技", plan(), List.of(nodeScore()));

        verify(gateway, times(3)).search(anyString(), anyInt(), kbIdsCaptor.capture(), anyMap());
        assertTrue(kbIdsCaptor.getAllValues().stream().allMatch(Set.of("2080272025011851264")::equals));
    }

    private RetrievalPlan plan() {
        return new RetrievalPlan("神里绫华元素战技", 20, 40, List.of(new IntentRetrievalPlan(
                "character_skill", "技能与命座", 0.95D,
                Map.of("category", "character"), EntityResolution.matched("神里绫华", "character", "10000002", "神里绫华"),
                List.of(
                        new RetrievalFilterLevel(1, "entity-category-doc-type", strictFilters()),
                        new RetrievalFilterLevel(2, "category", Map.of("category", "character")),
                        new RetrievalFilterLevel(3, "v7-global", Map.of())
                )
        )));
    }

    private NodeScore nodeScore() {
        return NodeScore.builder().score(0.95D).node(IntentNode.builder()
                .id("character_skill").kbId("2080272025011851264").build()).build();
    }

    private Map<String, Object> strictFilters() {
        return Map.of("category", "character", "doc_type", List.of("character_skill", "character_constellation"),
                "entity_type", "character", "entity_id", "10000002");
    }

    private List<RetrievedChunk> chunks(String prefix, int start, int count) {
        return java.util.stream.IntStream.range(start, start + count)
                .mapToObj(index -> new RetrievedChunk(prefix + index, "text-" + index, 0.9F))
                .toList();
    }
}
