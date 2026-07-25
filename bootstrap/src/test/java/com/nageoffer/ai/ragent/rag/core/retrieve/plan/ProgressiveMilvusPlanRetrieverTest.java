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
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.ProgressiveMilvusPlanRetriever.ProgressiveRetrievalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProgressiveMilvusPlanRetrieverTest {

    private RetrieverService retrieverService;
    private EmbeddingService embeddingService;
    private ProgressiveMilvusPlanRetriever progressiveRetriever;

    @BeforeEach
    void setUp() {
        retrieverService = mock(RetrieverService.class);
        embeddingService = mock(EmbeddingService.class);
        progressiveRetriever = new ProgressiveMilvusPlanRetriever(retrieverService, embeddingService);
        when(embeddingService.embed(anyString(), anyString())).thenReturn(List.of(3F, 4F));
    }

    @Test
    void shouldStopAfterStrictLevelWhenTwentyUniqueChunksAreReturned() {
        List<RetrieveRequest> requests = new ArrayList<>();
        when(retrieverService.retrieveByVector(any(float[].class), any(RetrieveRequest.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(1));
            return chunks("strict-", 1, 20);
        });

        ProgressiveRetrievalResult result = progressiveRetriever.retrieve(
                "神里绫华元素战技",
                singlePlan("character_skill", strictFilters(), categoryFilters()),
                List.of(nodeScore("character_skill")),
                Map.of("genshinroutingv7", "embedding-v7")
        );

        assertEquals(20, result.chunks().size());
        assertEquals(1, requests.size());
        assertEquals(strictFilters(), requests.get(0).getMetadataFilters());
        assertEquals(20, requests.get(0).getTopK());
        assertFalse(result.shouldFallbackToLegacy());
        verify(embeddingService, times(1)).embed("神里绫华元素战技", "embedding-v7");
    }

    @Test
    void shouldStopAfterAnyEntityScopedHitInsteadOfRelaxingAcrossEntities() {
        List<RetrieveRequest> requests = new ArrayList<>();
        when(retrieverService.retrieveByVector(any(float[].class), any(RetrieveRequest.class))).thenAnswer(invocation -> {
            RetrieveRequest request = invocation.getArgument(1);
            requests.add(request);
            if (request.getMetadataFilters().containsKey("entity_id")) {
                return chunks("chunk-", 1, 5);
            }
            return chunks("chunk-", 6, 15);
        });

        ProgressiveRetrievalResult result = progressiveRetriever.retrieve(
                "神里绫华元素战技",
                singlePlan("character_skill", strictFilters(), categoryFilters()),
                List.of(nodeScore("character_skill")),
                Map.of("genshinroutingv7", "embedding-v7")
        );

        assertEquals(5, result.chunks().size());
        assertEquals(1, requests.size());
        assertEquals(strictFilters(), requests.get(0).getMetadataFilters());
        assertEquals(1, result.executions().size());
    }

    @Test
    void shouldRelaxWithinTheSameEntityWhenStrictScopeReturnsNoResult() {
        List<RetrieveRequest> requests = new ArrayList<>();
        when(retrieverService.retrieveByVector(any(float[].class), any(RetrieveRequest.class))).thenAnswer(invocation -> {
            RetrieveRequest request = invocation.getArgument(1);
            requests.add(request);
            return request.getMetadataFilters().containsKey("doc_type") ? List.of() : chunks("entity-", 1, 2);
        });

        ProgressiveRetrievalResult result = progressiveRetriever.retrieve(
                "神里绫华元素战技",
                singlePlan("character_skill", strictFilters(), categoryFilters()),
                List.of(nodeScore("character_skill")),
                Map.of("genshinroutingv7", "embedding-v7")
        );

        assertEquals(2, result.chunks().size());
        assertEquals(2, requests.size());
        assertEquals(strictFilters(), requests.get(0).getMetadataFilters());
        assertEquals(entityCategoryFilters(), requests.get(1).getMetadataFilters());
    }

    @Test
    void shouldCapMultipleIntentPlansAtFortyUniqueCandidates() {
        List<RetrieveRequest> requests = new ArrayList<>();
        when(retrieverService.retrieveByVector(any(float[].class), any(RetrieveRequest.class))).thenAnswer(invocation -> {
            RetrieveRequest request = invocation.getArgument(1);
            requests.add(request);
            String prefix = request.getMetadataFilters().containsKey("entity_id")
                    ? String.valueOf(request.getMetadataFilters().get("entity_id")) + "-"
                    : "fallback-";
            return chunks(prefix, 1, 20);
        });

        RetrievalPlan plan = new RetrievalPlan(
                "神里绫华技能和狼末被动",
                20,
                40,
                List.of(
                        intentPlan("character_skill", Map.of("entity_id", "10000002", "category", "character")),
                        intentPlan("weapon_detail", Map.of("entity_id", "11501", "category", "weapon"))
                )
        );

        ProgressiveRetrievalResult result = progressiveRetriever.retrieve(
                plan.question(),
                plan,
                List.of(nodeScore("character_skill"), nodeScore("weapon_detail")),
                Map.of("genshinroutingv7", "embedding-v7")
        );

        assertEquals(40, result.chunks().size());
        assertEquals(2, requests.size());
        assertTrue(result.reachedCandidatePoolLimit());
        verify(embeddingService, times(1)).embed(plan.question(), "embedding-v7");
    }

    @Test
    void shouldExecuteEntityFallbackPlanWithoutAnIntentNode() {
        when(retrieverService.retrieveByVector(any(float[].class), any(RetrieveRequest.class)))
                .thenReturn(chunks("weapon-", 1, 2));
        RetrievalPlan plan = new RetrievalPlan("狼的末路被动", 20, 40, List.of(new IntentRetrievalPlan(
                "metadata_entity_fallback", "实体兜底检索", 0.75D,
                "2080272025011851264", "genshinroutingv7",
                Map.of("category", "weapon"),
                EntityResolution.matched("狼的末路", "weapon", "12502", "狼的末路", "weapon"),
                List.of(new RetrievalFilterLevel(1, "entity-category-fallback",
                        Map.of("entity_type", "weapon", "entity_id", "12502", "category", "weapon")))
        )));

        ProgressiveRetrievalResult result = progressiveRetriever.retrieve(
                plan.question(), plan, List.of(), Map.of("genshinroutingv7", "embedding-v7"));

        assertEquals(2, result.chunks().size());
        verify(retrieverService).retrieveByVector(any(float[].class), org.mockito.ArgumentMatchers.argThat(request ->
                "genshinroutingv7".equals(request.getCollectionName())
                        && "12502".equals(request.getMetadataFilters().get("entity_id"))));
    }

    private RetrievalPlan singlePlan(String intentCode,
                                     Map<String, Object> strictFilters,
                                     Map<String, Object> categoryOnlyFilters) {
        return new RetrievalPlan(
                "神里绫华元素战技",
                20,
                40,
                List.of(new IntentRetrievalPlan(
                        intentCode,
                        "技能与命座",
                        0.95D,
                        categoryOnlyFilters,
                        EntityResolution.matched("神里绫华", "character", "10000002", "神里绫华"),
                        List.of(
                                new RetrievalFilterLevel(1, "entity-category-doc-type", strictFilters),
                                new RetrievalFilterLevel(2, "entity-category", entityCategoryFilters()),
                                new RetrievalFilterLevel(3, "category", categoryOnlyFilters),
                                new RetrievalFilterLevel(4, "v7-global", Map.of())
                        )
                ))
        );
    }

    private IntentRetrievalPlan intentPlan(String intentCode, Map<String, Object> strictFilters) {
        return new IntentRetrievalPlan(
                intentCode,
                intentCode,
                0.9D,
                strictFilters,
                EntityResolution.notFound(),
                List.of(new RetrievalFilterLevel(1, "strict", strictFilters))
        );
    }

    private NodeScore nodeScore(String intentCode) {
        return NodeScore.builder()
                .score(0.9D)
                .node(IntentNode.builder()
                        .id(intentCode)
                        .collectionName("genshinroutingv7")
                        .build())
                .build();
    }

    private Map<String, Object> strictFilters() {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("category", "character");
        filters.put("doc_type", List.of("character_skill", "character_constellation"));
        filters.put("entity_type", "character");
        filters.put("entity_id", "10000002");
        return filters;
    }

    private Map<String, Object> categoryFilters() {
        return Map.of("category", "character");
    }

    private Map<String, Object> entityCategoryFilters() {
        return Map.of("entity_type", "character", "entity_id", "10000002", "category", "character");
    }

    private List<RetrievedChunk> chunks(String prefix, int start, int count) {
        return java.util.stream.IntStream.range(start, start + count)
                .mapToObj(index -> new RetrievedChunk(prefix + index, "text-" + index, 0.9F))
                .toList();
    }
}
