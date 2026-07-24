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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel;

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.KeywordEsChannelProperties;
import com.nageoffer.ai.ragent.rag.config.MetadataRetrievalProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.ProgressiveKeywordEsPlanRetriever;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.ProgressiveKeywordEsPlanRetriever.ProgressiveKeywordEsResult;
import com.nageoffer.ai.ragent.rag.es.KeywordEsGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ES 关键词检索通道
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordEsSearchChannel implements SearchChannel {

    private final KeywordEsGateway keywordEsGateway;
    private final KeywordEsChannelProperties properties;
    private final MetadataRetrievalProperties metadataRetrievalProperties;
    private final ProgressiveKeywordEsPlanRetriever progressiveKeywordEsPlanRetriever;

    @Override
    public String getName() {
        return "KeywordEsSearch";
    }

    @Override
    public int getPriority() {
        return 5;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        if (!keywordEsGateway.available()) {
            return false;
        }
        if (context == null || !StringUtils.hasText(context.getMainQuestion())) {
            return false;
        }
        if (CollUtil.isEmpty(context.getIntents())) {
            return properties.isFallbackWhenNoIntent();
        }
        double maxIntentScore = context.getIntents().stream()
                .flatMap(each -> each.nodeScores().stream())
                .filter(ns -> ns.getNode() != null && ns.getNode().isKB())
                .mapToDouble(NodeScore::getScore)
                .max()
                .orElse(0.0);
        return maxIntentScore >= properties.getMinIntentScore() || properties.isFallbackWhenNoIntent();
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();
        try {
            String question = context.getMainQuestion();
            if (metadataRetrievalProperties.isEnabled()
                    && metadataRetrievalProperties.isExecutionEnabled()
                    && context.getRetrievalPlan() != null
                    && !context.getRetrievalPlan().isEmpty()) {
                ProgressiveKeywordEsResult planResult = progressiveKeywordEsPlanRetriever.retrieve(
                        question, context.getRetrievalPlan(), flattenKbIntents(context));
                if (!planResult.shouldFallbackToLegacy()) {
                    long latency = System.currentTimeMillis() - startTime;
                    log.info("ES metadata-plan retrieval completed, chunks={}, levels={}, latencyMs={}",
                            planResult.chunks().size(), planResult.executions().size(), latency);
                    return SearchChannelResult.builder()
                            .channelType(SearchChannelType.KEYWORD_ES)
                            .channelName(getName())
                            .chunks(planResult.chunks())
                            .confidence(0.78)
                            .latencyMs(latency)
                            .metadata(java.util.Map.of("strategy", "metadata-plan",
                                    "levelCount", planResult.executions().size(),
                                    "candidateCount", planResult.chunks().size()))
                            .build();
                }
                log.warn("All ES metadata-plan searches failed; fall back to legacy ES retrieval");
            }
            Set<String> kbIds = extractKbIds(context);
            int topKMultiplier = Math.max(1, properties.getTopKMultiplier());
            int recallSize = Math.max(1, context.getTopK() * topKMultiplier);

            List<RetrievedChunk> chunks = keywordEsGateway.search(question, recallSize, kbIds);
            long latency = System.currentTimeMillis() - startTime;

            log.info("ES 关键词检索完成, chunks={}, kbFilters={}, latencyMs={}",
                    chunks.size(), kbIds.size(), latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KEYWORD_ES)
                    .channelName(getName())
                    .chunks(chunks)
                    .confidence(kbIds.isEmpty() ? 0.65 : 0.78)
                    .latencyMs(latency)
                    .metadata(kbIds.isEmpty()
                            ? java.util.Map.of("kbFilter", "global")
                            : java.util.Map.of("kbFilter", "intent", "kbCount", kbIds.size()))
                    .build();
        } catch (Exception ex) {
            log.error("ES 关键词检索异常", ex);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.KEYWORD_ES)
                    .channelName(getName())
                    .chunks(List.of())
                    .confidence(0.0)
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.KEYWORD_ES;
    }

    private Set<String> extractKbIds(SearchContext context) {
        if (context == null || CollUtil.isEmpty(context.getIntents())) {
            return Set.of();
        }
        return context.getIntents().stream()
                .flatMap(each -> each.nodeScores().stream())
                .filter(score -> score.getNode() != null && score.getNode().isKB())
                .filter(score -> score.getScore() >= properties.getMinIntentScore())
                .map(score -> score.getNode().getKbId())
                .filter(StringUtils::hasText)
                .collect(Collectors.toSet());
    }

    private List<NodeScore> flattenKbIntents(SearchContext context) {
        if (context == null || CollUtil.isEmpty(context.getIntents())) {
            return List.of();
        }
        return context.getIntents().stream()
                .flatMap(each -> each.nodeScores().stream())
                .filter(score -> score.getNode() != null && score.getNode().isKB())
                .toList();
    }
}

