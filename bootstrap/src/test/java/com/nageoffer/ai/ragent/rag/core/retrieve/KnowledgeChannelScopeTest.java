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

package com.nageoffer.ai.ragent.rag.core.retrieve;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.*;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.RetrievalPlan;
import com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor.SearchResultPostProcessor;
import com.nageoffer.ai.ragent.rag.core.routing.KnowledgeBaseRoutingService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeChannelScopeTest {
    @Test
    void allChannelsAndPostProcessorsReceiveSameExplicitCorpusScope() {
        var vector = mock(SearchChannel.class);
        var keyword = mock(SearchChannel.class);
        var rerank = mock(SearchResultPostProcessor.class);
        var router = mock(KnowledgeBaseRoutingService.class);
        var chunk = new RetrievedChunk("id", "text", .8F);
        for (var channel : List.of(vector, keyword)) {
            when(channel.isEnabled(any())).thenReturn(true);
            when(channel.search(any())).thenAnswer(invocation -> {
                SearchContext context = invocation.getArgument(0);
                assertEquals(Set.of("allowed"), context.getRoutedKbIds());
                return SearchChannelResult.builder().chunks(List.of(chunk)).build();
            });
        }
        when(rerank.isEnabled(any())).thenReturn(true);
        when(rerank.process(anyList(), anyList(), any())).thenAnswer(invocation -> {
            SearchContext context = invocation.getArgument(2);
            assertEquals(Set.of("allowed"), context.getRoutedKbIds());
            return List.of(chunk);
        });
        var engine = new MultiChannelRetrievalEngine(List.of(vector, keyword), List.of(rerank), router, Runnable::run);
        var result = engine.retrieveKnowledgeChannels(List.of(), 8, new RetrievalPlan("q", 20, 40, List.of()), Set.of("allowed"));
        assertEquals(List.of(chunk), result);
        verifyNoInteractions(router);
        verify(vector).search(any());
        verify(keyword).search(any());
        verify(rerank).process(anyList(), anyList(), any());
    }

    @Test
    void emptyScopeIsRejectedBeforeAnyChannelRuns() {
        var channel = mock(SearchChannel.class);
        var router = mock(KnowledgeBaseRoutingService.class);
        var engine = new MultiChannelRetrievalEngine(List.of(channel), List.of(), router, Runnable::run);
        assertThrows(IllegalArgumentException.class, () -> engine.retrieveKnowledgeChannels(List.of(), 8, null, Set.of()));
        verifyNoInteractions(channel, router);
    }
}
