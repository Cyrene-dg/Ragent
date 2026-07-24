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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.MetadataRetrievalProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A dormant rerank-input safety valve for metadata-routed retrieval. It leaves
 * normal requests unchanged, and applies reciprocal-rank fusion only after
 * cross-channel de-duplication exceeds the configured ceiling.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandidatePoolLimiterPostProcessor implements SearchResultPostProcessor {

    private final MetadataRetrievalProperties metadataRetrievalProperties;

    @Override
    public String getName() {
        return "CandidatePoolLimiter";
    }

    @Override
    public int getOrder() {
        return 5;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return metadataRetrievalProperties.isEnabled()
                && metadataRetrievalProperties.isExecutionEnabled()
                && context != null
                && context.getRetrievalPlan() != null
                && !context.getRetrievalPlan().isEmpty();
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        int limit = metadataRetrievalProperties.getMaxRerankCandidates();
        if (CollUtil.isEmpty(chunks) || limit <= 0 || chunks.size() <= limit) {
            return chunks;
        }

        Map<String, Candidate> candidatesByKey = new LinkedHashMap<>();
        for (int index = 0; index < chunks.size(); index++) {
            RetrievedChunk chunk = chunks.get(index);
            if (chunk != null) {
                candidatesByKey.putIfAbsent(chunkKey(chunk), new Candidate(chunk, index));
            }
        }
        if (candidatesByKey.size() <= limit) {
            return List.copyOf(candidatesByKey.values().stream().map(Candidate::chunk).toList());
        }

        int rankConstant = Math.max(0, metadataRetrievalProperties.getRrfRankConstant());
        if (results != null) {
            for (SearchChannelResult result : results) {
                if (result == null || CollUtil.isEmpty(result.getChunks())) {
                    continue;
                }
                Set<String> seenInChannel = new HashSet<>();
                for (int index = 0; index < result.getChunks().size(); index++) {
                    RetrievedChunk chunk = result.getChunks().get(index);
                    if (chunk == null) {
                        continue;
                    }
                    String key = chunkKey(chunk);
                    if (!seenInChannel.add(key)) {
                        continue;
                    }
                    Candidate candidate = candidatesByKey.get(key);
                    if (candidate != null) {
                        int rank = index + 1;
                        candidate.addRrfContribution(1D / (rankConstant + rank), rank);
                    }
                }
            }
        }

        List<RetrievedChunk> limited = candidatesByKey.values().stream()
                .sorted(Comparator.comparingDouble(Candidate::rrfScore).reversed()
                        .thenComparingInt(Candidate::bestRank)
                        .thenComparingInt(Candidate::originalOrder))
                .limit(limit)
                .map(Candidate::chunk)
                .toList();
        log.info("Candidate pool safety limiter triggered: before={}, after={}, strategy=rrf, rankConstant={}",
                candidatesByKey.size(), limited.size(), rankConstant);
        return limited;
    }

    private String chunkKey(RetrievedChunk chunk) {
        return StrUtil.isNotBlank(chunk.getId())
                ? chunk.getId()
                : "text:" + String.valueOf(chunk.getText()).hashCode();
    }

    private static final class Candidate {

        private final RetrievedChunk chunk;
        private final int originalOrder;
        private double rrfScore;
        private int bestRank = Integer.MAX_VALUE;

        private Candidate(RetrievedChunk chunk, int originalOrder) {
            this.chunk = chunk;
            this.originalOrder = originalOrder;
        }

        private void addRrfContribution(double contribution, int rank) {
            rrfScore += contribution;
            bestRank = Math.min(bestRank, rank);
        }

        private RetrievedChunk chunk() {
            return chunk;
        }

        private int originalOrder() {
            return originalOrder;
        }

        private double rrfScore() {
            return rrfScore;
        }

        private int bestRank() {
            return bestRank;
        }
    }
}
