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

package com.nageoffer.ai.ragent.rag.core.intent;

import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.rag.config.IntentPreFilterProperties;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultIntentClassifierStrategyTest {

    @Test
    void knowledgeClassificationRejectsToolEvenWhenBoundToSameCorpus() {
        Fixture fixture = fixture(2);
        fixture.leaves().get(0).setKbId("kb");
        fixture.leaves().get(1).setKbId("kb");
        fixture.leaves().get(1).setKind(com.nageoffer.ai.ragent.rag.enums.IntentKind.MCP);
        when(fixture.llmService().chat(any(ChatRequest.class)))
                .thenReturn("[{\"id\":\"leaf-1\",\"score\":0.99}]");
        assertEquals(0, fixture.classifier().classifyKnowledgeTargets("tool leak",
                com.nageoffer.ai.ragent.rag.core.retrieve.plan.EntityResolution.notFound(), "kb").size());
    }

    @Test
    void rejectsExistingNodeOutsideHybridShortlist() {
        Fixture fixture = fixture(81);
        when(fixture.recallService().shortlist(anyString(), anyList()))
                .thenReturn(List.of(fixture.leaves().get(0)));
        when(fixture.llmService().chat(any(ChatRequest.class)))
                .thenReturn("[{\"id\":\"leaf-1\",\"score\":0.99}]");

        assertEquals(0, fixture.classifier().classifyTargets("越界节点").size());
    }

    @Test
    void rejectsExistingNodeOutsideRoutedKnowledgeBase() {
        Fixture fixture = fixture(2);
        fixture.leaves().get(0).setKbId("allowed");
        fixture.leaves().get(1).setKbId("other");
        when(fixture.llmService().chat(any(ChatRequest.class)))
                .thenReturn("[{\"id\":\"leaf-1\",\"score\":0.99}]");

        assertEquals(0, fixture.classifier().classifyTargets("越库节点",
                com.nageoffer.ai.ragent.rag.core.retrieve.plan.EntityResolution.notFound(), "allowed").size());
    }

    @Test
    void shouldClassifyDirectlyWhenAllLeavesFitWithinCandidateLimit() {
        Fixture fixture = fixture(12);

        List<NodeScore> scores = fixture.classifier().classifyTargets("神里绫华的元素战技是什么");

        assertEquals(1, scores.size());
        assertEquals("leaf-0", scores.get(0).getNode().getId());
        verify(fixture.recallService(), never()).shortlist(anyString(), anyList());
    }

    @Test
    void shouldUseHybridShortlistWhenLeafCountExceedsCandidateLimit() {
        Fixture fixture = fixture(81);
        when(fixture.recallService().shortlist(anyString(), anyList()))
                .thenReturn(List.of(fixture.leaves().get(0)));

        List<NodeScore> scores = fixture.classifier().classifyTargets("一个大型意图树中的问题");

        assertEquals(1, scores.size());
        assertEquals("leaf-0", scores.get(0).getNode().getId());
        verify(fixture.recallService()).shortlist(anyString(), anyList());
    }

    @Test
    void shouldExcludeManagedKnowledgeBaseAfterExplicitOutOfDomainRoute() {
        Fixture fixture = fixture(2);
        fixture.leaves().get(0).setKbId("managed-kb");
        fixture.leaves().get(1).setKbId("legacy-kb");
        when(fixture.llmService().chat(any(ChatRequest.class)))
                .thenReturn("[{\"id\":\"leaf-1\",\"score\":0.91}]");

        List<NodeScore> scores = fixture.classifier().classifyTargets(
                "领域外问题",
                com.nageoffer.ai.ragent.rag.core.retrieve.plan.EntityResolution.notFound(),
                null,
                Set.of("managed-kb")
        );

        assertEquals(1, scores.size());
        assertEquals("leaf-1", scores.get(0).getNode().getId());
    }

    @Test
    void shouldKeepLegacyLeafWithoutKnowledgeBaseWhenExcludingManagedKnowledgeBases() {
        Fixture fixture = fixture(2);
        fixture.leaves().get(0).setKbId("managed-kb");
        // Legacy intent leaves are allowed to have no explicit knowledge-base binding.
        fixture.leaves().get(1).setKbId(null);
        when(fixture.llmService().chat(any(ChatRequest.class)))
                .thenReturn("[{\"id\":\"leaf-1\",\"score\":0.91}]");

        List<NodeScore> scores = fixture.classifier().classifyTargets(
                "legacy fallback question",
                com.nageoffer.ai.ragent.rag.core.retrieve.plan.EntityResolution.notFound(),
                null,
                Set.of("managed-kb")
        );

        assertEquals(1, scores.size());
        assertEquals("leaf-1", scores.get(0).getNode().getId());
    }

    private Fixture fixture(int leafCount) {
        List<IntentNode> leaves = new ArrayList<>();
        for (int i = 0; i < leafCount; i++) {
            leaves.add(IntentNode.builder()
                    .id("leaf-" + i)
                    .name("Leaf " + i)
                    .description("test leaf " + i)
                    .fullPath("Test > Leaf " + i)
                    .build());
        }

        LLMService llmService = mock(LLMService.class);
        when(llmService.chat(any(ChatRequest.class))).thenReturn("[{\"id\":\"leaf-0\",\"score\":0.91}]");
        PromptTemplateLoader promptTemplateLoader = mock(PromptTemplateLoader.class);
        when(promptTemplateLoader.render(anyString(), any())).thenReturn("intent prompt");
        IntentTreeCacheManager cacheManager = mock(IntentTreeCacheManager.class);
        when(cacheManager.getIntentTreeFromCache()).thenReturn(leaves);
        IntentHybridRecallService recallService = mock(IntentHybridRecallService.class);
        IntentPreFilterProperties properties = new IntentPreFilterProperties();
        properties.setEnabled(true);
        properties.setCandidateLimit(80);

        DefaultIntentClassifier classifier = new DefaultIntentClassifier(
                llmService,
                mock(IntentNodeMapper.class),
                promptTemplateLoader,
                cacheManager,
                recallService,
                properties
        );
        return new Fixture(classifier, recallService, llmService, leaves);
    }

    private record Fixture(DefaultIntentClassifier classifier,
                           IntentHybridRecallService recallService,
                           LLMService llmService,
                           List<IntentNode> leaves) {
    }
}
