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

package com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.MetadataRetrievalProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelType;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.RetrievalPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CandidatePoolLimiterPostProcessorTest {

    private CandidatePoolLimiterPostProcessor processor;
    private SearchContext context;

    @BeforeEach
    void setUp() {
        MetadataRetrievalProperties properties = new MetadataRetrievalProperties();
        properties.setMaxRerankCandidates(2);
        properties.setRrfRankConstant(60);
        processor = new CandidatePoolLimiterPostProcessor(properties);
        context = SearchContext.builder()
                .retrievalPlan(new RetrievalPlan("question", 20, 40, List.of()))
                .build();
    }

    @Test
    void shouldLeaveCandidatesUntouchedAtOrBelowConfiguredLimit() {
        List<RetrievedChunk> candidates = List.of(chunk("a", 0.1F), chunk("b", 999F));

        List<RetrievedChunk> result = processor.process(candidates, List.of(), context);

        assertSame(candidates, result);
    }

    @Test
    void shouldUseRrfRankInsteadOfIncomparableRawChannelScoresWhenLimitTriggers() {
        List<RetrievedChunk> milvus = List.of(chunk("milvus-top", 999F), chunk("shared", 0.01F), chunk("tail-a", 0.9F));
        List<RetrievedChunk> es = List.of(chunk("es-top", 0.1F), chunk("shared", 0.01F), chunk("tail-b", 100F));
        List<SearchChannelResult> channelResults = List.of(
                channel(SearchChannelType.INTENT_DIRECTED, milvus),
                channel(SearchChannelType.KEYWORD_ES, es)
        );
        List<RetrievedChunk> deduplicated = List.of(milvus.get(0), milvus.get(1), milvus.get(2), es.get(0), es.get(2));

        List<RetrievedChunk> result = processor.process(deduplicated, channelResults, context);

        assertEquals(List.of("shared", "milvus-top"), result.stream().map(RetrievedChunk::getId).toList());
    }

    private SearchChannelResult channel(SearchChannelType type, List<RetrievedChunk> chunks) {
        return SearchChannelResult.builder().channelType(type).channelName(type.name()).chunks(chunks).build();
    }

    private RetrievedChunk chunk(String id, float score) {
        return new RetrievedChunk(id, "text-" + id, score);
    }
}
