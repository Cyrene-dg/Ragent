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

package com.nageoffer.ai.ragent.rag.core.knowledge;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.*;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.retrieve.MultiChannelRetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.*;
import com.nageoffer.ai.ragent.rag.core.routing.*;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeQueryServiceTest {
    private final KnowledgeBaseRoutingService router = mock(KnowledgeBaseRoutingService.class);
    private final IntentClassifier classifier = mock(IntentClassifier.class);
    private final EntityResolver entities = mock(EntityResolver.class);
    private final RetrievalPlanService plans = mock(RetrievalPlanService.class);
    private final MultiChannelRetrievalEngine channels = mock(MultiChannelRetrievalEngine.class);
    private final ContextFormatter formatter = mock(ContextFormatter.class);
    private final IntentGuidanceService guidance = mock(IntentGuidanceService.class);
    private final DefaultKnowledgeQueryService service = new DefaultKnowledgeQueryService(router, classifier,
            entities, plans, channels, formatter, guidance);
    private final IntentNode first = node("one", "a", IntentKind.KB);
    private final IntentNode second = node("two", "b", IntentKind.KB);

    @BeforeEach
    void setup() {
        when(classifier.leafTargets()).thenReturn(List.of(first, second));
        when(entities.resolveAny(anyString(), anyString())).thenReturn(EntityResolution.notFound());
        when(classifier.classifyKnowledgeTargets(anyString(), any(), eq("a"))).thenReturn(List.of(new NodeScore(first, .99)));
        when(classifier.classifyKnowledgeTargets(anyString(), any(), eq("b"))).thenReturn(List.of(new NodeScore(second, .99)));
        when(guidance.detectAmbiguity(anyString(), anyList())).thenReturn(GuidanceDecision.none());
        when(plans.buildPlan(anyString(), anyList(), any())).thenReturn(new RetrievalPlan("q", 20, 40, List.of()));
        when(channels.retrieveKnowledgeChannels(anyList(), anyInt(), any(), anySet())).thenAnswer(invocation -> {
            Set<String> scope = invocation.getArgument(3);
            return List.of(new RetrievedChunk(scope.iterator().next(), "chunk-" + scope.iterator().next(), .9F));
        });
        when(formatter.formatKbContext(anyList(), anyMap(), anyInt())).thenAnswer(invocation -> {
            Map<String, List<RetrievedChunk>> values = invocation.getArgument(1);
            return values.values().iterator().next().get(0).getText();
        });
    }

    @Test
    void pureKnowledgeStaysWithinItsCorpus() {
        when(router.routeAll(anyString(), anyList())).thenReturn(List.of(route("a")));
        var result = service.query("q", 8);
        assertTrue(result.result().groupedContext().contains("chunk-a"));
        verify(channels).retrieveKnowledgeChannels(anyList(), eq(8), any(), eq(Set.of("a")));
        verify(channels, never()).retrieveKnowledgeChannels(anyList(), anyInt(), any());
        verify(classifier, never()).classifyTargets(anyString());
    }

    @Test
    void multipleCorporaAreRetrievedSeparatelyAndBothPreserved() {
        when(router.routeAll(anyString(), anyList())).thenReturn(List.of(route("a"), route("b")));
        var result = service.query("q", 8);
        assertEquals(2, result.resolutions().size());
        assertTrue(result.result().groupedContext().contains("chunk-a"));
        assertTrue(result.result().groupedContext().contains("chunk-b"));
        assertEquals(Set.of("one", "two"), result.result().intentChunks().keySet());
        verify(channels).retrieveKnowledgeChannels(anyList(), anyInt(), any(), eq(Set.of("a")));
        verify(channels).retrieveKnowledgeChannels(anyList(), anyInt(), any(), eq(Set.of("b")));
    }

    @Test
    void unknownCorpusDoesNotSearchEverywhere() {
        when(router.routeAll(anyString(), anyList())).thenReturn(List.of());
        var result = service.query("q", 8);
        assertNotNull(result.clarification());
        verifyNoInteractions(channels, plans, entities);
    }

    @Test
    void resolvedToolIntentIsRejectedAtKnowledgeBoundary() {
        var tool = node("tool", "a", IntentKind.MCP);
        assertThrows(IllegalArgumentException.class, () -> service.retrieveResolved(new SubQuestionIntent("q",
                List.of(new NodeScore(tool, .99)), EntityResolution.notFound(), route("a")), 8));
        verifyNoInteractions(plans, channels);
    }

    @Test
    void wrongCorpusIntentIsRejectedBeforeRetrieval() {
        assertThrows(IllegalArgumentException.class, () -> service.retrieveResolved(new SubQuestionIntent("q",
                List.of(new NodeScore(second, .99)), EntityResolution.notFound(), route("a")), 8));
        verifyNoInteractions(plans, channels);
    }

    @Test
    void legacyCorpusNeverReadsAnotherCorpusDefaultEntityCatalog() {
        when(router.routeAll(anyString(), anyList())).thenReturn(List.of(route("a")));
        when(router.managedKbIds()).thenReturn(Set.of("b"));
        service.query("q", 8);
        verifyNoInteractions(entities);
    }

    @Test
    void entityFallbackPlanCannotEscapeSelectedCorpus() {
        var other = new IntentRetrievalPlan("fallback", "other", .9, "other", "other_collection", Map.of(),
                EntityResolution.notFound(), List.of());
        when(plans.buildPlan(anyString(), anyList(), any())).thenReturn(new RetrievalPlan("q", 20, 40, List.of(other)));
        service.retrieveResolved(new SubQuestionIntent("q", List.of(new NodeScore(first, .99)),
                EntityResolution.notFound(), route("a")), 8);
        verify(channels).retrieveKnowledgeChannels(anyList(), anyInt(), argThat(RetrievalPlan::isEmpty), eq(Set.of("a")));
    }

    @Test
    void configuredEntityFallbackUsesSelectedCorpusAndRetainsAnyHitPolicy() {
        var entity = EntityResolution.matched("product", "product", "P1", "product", "product");
        var oldFallback = new IntentRetrievalPlan("metadata_entity_fallback", "old fallback", .75, "other", "other_collection",
                Map.of("category", "product"), entity, List.of());
        when(plans.buildPlan(anyString(), anyList(), any())).thenReturn(new RetrievalPlan("q", 20, 40, List.of(oldFallback)));
        when(router.entityFallbackCollection("a")).thenReturn("a_collection");
        service.retrieveResolved(new SubQuestionIntent("q", List.of(), entity, route("a")), 8);
        verify(channels).retrieveKnowledgeChannels(anyList(), anyInt(), argThat(plan -> {
            var fallback = plan.intentPlans().get(0);
            return "a".equals(fallback.kbId()) && "a_collection".equals(fallback.collectionName())
                    && fallback.filterLevels().get(0).stopCondition() == RetrievalFilterLevel.StopCondition.ANY_HIT;
        }), eq(Set.of("a")));
    }

    private IntentNode node(String id, String kb, IntentKind kind) {
        return IntentNode.builder().id(id).kbId(kb).kind(kind).build();
    }
    private KnowledgeBaseRoute route(String kb) { return new KnowledgeBaseRoute(kb, kb, "test", .99); }
}
