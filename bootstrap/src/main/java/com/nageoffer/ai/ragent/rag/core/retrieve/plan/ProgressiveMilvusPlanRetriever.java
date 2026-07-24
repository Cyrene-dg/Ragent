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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieveRequest;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes the ordered Milvus metadata-filter levels emitted by
 * {@link RetrievalPlanService}.  It owns policy only: one search is still
 * executed by the existing {@link RetrieverService} implementation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProgressiveMilvusPlanRetriever {

    private final RetrieverService retrieverService;
    private final EmbeddingService embeddingService;

    public ProgressiveRetrievalResult retrieve(String question,
                                               RetrievalPlan plan,
                                               List<NodeScore> kbIntents,
                                               Map<String, String> embeddingModelByCollection) {
        if (plan == null || plan.isEmpty() || CollUtil.isEmpty(kbIntents) || StrUtil.isBlank(question)) {
            return ProgressiveRetrievalResult.empty();
        }

        int targetPerIntent = Math.max(1, plan.targetCandidateCount());
        int maxCandidatePool = Math.max(targetPerIntent, plan.maxCandidatePool());
        Map<String, NodeScore> nodeByIntentCode = strongestNodeByIntentCode(kbIntents);
        Map<String, float[]> embeddingCache = new HashMap<>();
        LinkedHashMap<String, RetrievedChunk> candidates = new LinkedHashMap<>();
        List<LevelExecution> executions = new ArrayList<>();
        int failedSearches = 0;
        int attemptedSearches = 0;

        List<IntentRetrievalPlan> sortedPlans = plan.intentPlans().stream()
                .sorted(Comparator.comparingDouble(IntentRetrievalPlan::intentScore).reversed())
                .toList();
        for (IntentRetrievalPlan intentPlan : sortedPlans) {
            if (candidates.size() >= maxCandidatePool) {
                break;
            }
            NodeScore nodeScore = nodeByIntentCode.get(intentPlan.intentCode());
            IntentNode node = nodeScore == null ? null : nodeScore.getNode();
            if (node == null || StrUtil.isBlank(node.getCollectionName())) {
                log.warn("Skip metadata plan because its intent has no collection: intentCode={}", intentPlan.intentCode());
                continue;
            }

            String collectionName = node.getCollectionName().trim();
            String embeddingModel = embeddingModelByCollection == null
                    ? null
                    : embeddingModelByCollection.get(collectionName);
            float[] queryVector = embeddingCache.computeIfAbsent(
                    embeddingCacheKey(embeddingModel),
                    ignored -> embedAndNormalize(question, embeddingModel)
            );
            if (queryVector.length == 0) {
                throw new IllegalStateException("Cannot build query vector for metadata retrieval plan: " + intentPlan.intentCode());
            }

            int candidateCountBeforeIntent = candidates.size();
            for (RetrievalFilterLevel level : intentPlan.filterLevels()) {
                if (candidates.size() >= maxCandidatePool) {
                    break;
                }
                int addedForIntent = candidates.size() - candidateCountBeforeIntent;
                if (addedForIntent >= targetPerIntent) {
                    break;
                }

                int remainingGlobalCapacity = maxCandidatePool - candidates.size();
                int requestTopK = Math.min(remainingGlobalCapacity, Math.max(targetPerIntent, targetPerIntent - addedForIntent));
                RetrieveRequest request = RetrieveRequest.builder()
                        .query(question)
                        .collectionName(collectionName)
                        .embeddingModel(embeddingModel)
                        .topK(requestTopK)
                        .metadataFilters(level.metadataFilters())
                        .build();
                attemptedSearches++;

                try {
                    List<RetrievedChunk> levelChunks = retrieverService.retrieveByVector(queryVector, request);
                    int uniqueBeforeLevel = candidates.size();
                    mergeByChunkIdentity(candidates, levelChunks, maxCandidatePool);
                    int addedByLevel = candidates.size() - uniqueBeforeLevel;
                    executions.add(new LevelExecution(
                            intentPlan.intentCode(),
                            level.order(),
                            level.name(),
                            level.metadataFilters(),
                            levelChunks == null ? 0 : levelChunks.size(),
                            addedByLevel,
                            candidates.size()
                    ));
                    log.info("Metadata Milvus level finished: intentCode={}, level={}, filters={}, returned={}, uniqueAdded={}, pooled={}",
                            intentPlan.intentCode(),
                            level.name(),
                            level.metadataFilters(),
                            levelChunks == null ? 0 : levelChunks.size(),
                            addedByLevel,
                            candidates.size());
                } catch (Exception ex) {
                    failedSearches++;
                    executions.add(new LevelExecution(
                            intentPlan.intentCode(),
                            level.order(),
                            level.name(),
                            level.metadataFilters(),
                            0,
                            0,
                            candidates.size()
                    ));
                    log.warn("Metadata Milvus level failed, continue relaxing: intentCode={}, level={}, filters={}",
                            intentPlan.intentCode(), level.name(), level.metadataFilters(), ex);
                }
            }
        }

        return new ProgressiveRetrievalResult(
                List.copyOf(candidates.values()),
                List.copyOf(executions),
                attemptedSearches,
                failedSearches,
                candidates.size() >= maxCandidatePool
        );
    }

    private Map<String, NodeScore> strongestNodeByIntentCode(List<NodeScore> kbIntents) {
        Map<String, NodeScore> result = new HashMap<>();
        for (NodeScore nodeScore : kbIntents) {
            if (nodeScore == null || nodeScore.getNode() == null || StrUtil.isBlank(nodeScore.getNode().getId())) {
                continue;
            }
            result.merge(nodeScore.getNode().getId(), nodeScore,
                    (existing, candidate) -> candidate.getScore() > existing.getScore() ? candidate : existing);
        }
        return result;
    }

    private float[] embedAndNormalize(String question, String embeddingModel) {
        List<Float> embedding = StrUtil.isBlank(embeddingModel)
                ? embeddingService.embed(question)
                : embeddingService.embed(question, embeddingModel);
        if (CollUtil.isEmpty(embedding)) {
            return new float[0];
        }

        float[] vector = new float[embedding.size()];
        double squaredLength = 0D;
        for (int index = 0; index < embedding.size(); index++) {
            Float value = embedding.get(index);
            vector[index] = value == null ? 0F : value;
            squaredLength += vector[index] * vector[index];
        }
        double length = Math.sqrt(squaredLength);
        if (length == 0D) {
            return new float[0];
        }
        for (int index = 0; index < vector.length; index++) {
            vector[index] = (float) (vector[index] / length);
        }
        return vector;
    }

    private void mergeByChunkIdentity(Map<String, RetrievedChunk> candidates,
                                      List<RetrievedChunk> chunks,
                                      int maxCandidatePool) {
        if (CollUtil.isEmpty(chunks)) {
            return;
        }
        for (RetrievedChunk chunk : chunks) {
            if (chunk == null) {
                continue;
            }
            String key = chunkIdentity(chunk);
            RetrievedChunk existing = candidates.get(key);
            if (existing == null) {
                if (candidates.size() >= maxCandidatePool) {
                    return;
                }
                candidates.put(key, chunk);
            } else if (chunk.getScore() > existing.getScore()) {
                candidates.put(key, chunk);
            }
        }
    }

    private String chunkIdentity(RetrievedChunk chunk) {
        if (StrUtil.isNotBlank(chunk.getId())) {
            return chunk.getId();
        }
        return "text:" + Objects.hashCode(chunk.getText());
    }

    private String embeddingCacheKey(String embeddingModel) {
        return StrUtil.blankToDefault(embeddingModel, "__default__");
    }

    public record LevelExecution(
            String intentCode,
            int levelOrder,
            String levelName,
            Map<String, Object> metadataFilters,
            int returnedCount,
            int uniqueAddedCount,
            int pooledUniqueCount
    ) {
    }

    public record ProgressiveRetrievalResult(
            List<RetrievedChunk> chunks,
            List<LevelExecution> executions,
            int attemptedSearches,
            int failedSearches,
            boolean reachedCandidatePoolLimit
    ) {

        public static ProgressiveRetrievalResult empty() {
            return new ProgressiveRetrievalResult(List.of(), List.of(), 0, 0, false);
        }

        public boolean shouldFallbackToLegacy() {
            return attemptedSearches > 0 && attemptedSearches == failedSearches;
        }
    }
}
