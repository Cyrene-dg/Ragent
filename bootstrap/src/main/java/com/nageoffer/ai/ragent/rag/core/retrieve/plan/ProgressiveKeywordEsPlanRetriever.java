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
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.es.KeywordEsGateway;
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
import java.util.Set;

/**
 * Executes the same ordered metadata scopes as Milvus, but uses ES BM25
 * retrieval inside each scope. It deliberately owns policy only; the gateway
 * still owns ES request construction and transport.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProgressiveKeywordEsPlanRetriever {

    private final KeywordEsGateway keywordEsGateway;

    public ProgressiveKeywordEsResult retrieve(String question,
                                               RetrievalPlan plan,
                                               List<NodeScore> kbIntents) {
        if (plan == null || plan.isEmpty() || StrUtil.isBlank(question)) {
            return ProgressiveKeywordEsResult.empty();
        }

        int targetPerIntent = Math.max(1, plan.targetCandidateCount());
        int maxCandidatePool = Math.max(targetPerIntent, plan.maxCandidatePool());
        Map<String, NodeScore> nodeByIntentCode = strongestNodeByIntentCode(kbIntents);
        LinkedHashMap<String, RetrievedChunk> candidates = new LinkedHashMap<>();
        List<LevelExecution> executions = new ArrayList<>();
        int attemptedSearches = 0;
        int failedSearches = 0;

        List<IntentRetrievalPlan> sortedPlans = plan.intentPlans().stream()
                .sorted(Comparator.comparingDouble(IntentRetrievalPlan::intentScore).reversed())
                .toList();
        for (IntentRetrievalPlan intentPlan : sortedPlans) {
            if (candidates.size() >= maxCandidatePool) {
                break;
            }
            String kbId = kbIdOf(intentPlan, nodeByIntentCode);
            if (StrUtil.isBlank(kbId)) {
                log.warn("Skip ES metadata plan because its intent has no kbId: intentCode={}", intentPlan.intentCode());
                continue;
            }

            int candidateCountBeforeIntent = candidates.size();
            Set<String> kbIds = Set.of(kbId);
            for (RetrievalFilterLevel level : intentPlan.filterLevels()) {
                if (candidates.size() >= maxCandidatePool) {
                    break;
                }
                int addedForIntent = candidates.size() - candidateCountBeforeIntent;
                if (addedForIntent >= targetPerIntent) {
                    break;
                }

                int remainingGlobalCapacity = maxCandidatePool - candidates.size();
                int requestTopK = Math.min(remainingGlobalCapacity,
                        Math.max(targetPerIntent, targetPerIntent - addedForIntent));
                attemptedSearches++;
                try {
                    List<RetrievedChunk> levelChunks = keywordEsGateway.search(
                            question, requestTopK, kbIds, level.metadataFilters());
                    int uniqueBeforeLevel = candidates.size();
                    mergeByChunkIdentity(candidates, levelChunks, maxCandidatePool);
                    int addedByLevel = candidates.size() - uniqueBeforeLevel;
                    executions.add(new LevelExecution(intentPlan.intentCode(), level.order(), level.name(),
                            level.metadataFilters(), levelChunks == null ? 0 : levelChunks.size(),
                            addedByLevel, candidates.size()));
                    log.info("Metadata ES level finished: intentCode={}, level={}, filters={}, returned={}, uniqueAdded={}, pooled={}",
                            intentPlan.intentCode(), level.name(), level.metadataFilters(),
                            levelChunks == null ? 0 : levelChunks.size(), addedByLevel, candidates.size());
                    if (ProgressivePlanStopPolicy.shouldStopAfterLevel(level, levelChunks)) {
                        break;
                    }
                } catch (Exception ex) {
                    failedSearches++;
                    executions.add(new LevelExecution(intentPlan.intentCode(), level.order(), level.name(),
                            level.metadataFilters(), 0, 0, candidates.size()));
                    log.warn("Metadata ES level failed, continue relaxing: intentCode={}, level={}, filters={}",
                            intentPlan.intentCode(), level.name(), level.metadataFilters(), ex);
                }
            }
        }

        return new ProgressiveKeywordEsResult(List.copyOf(candidates.values()), List.copyOf(executions),
                attemptedSearches, failedSearches, candidates.size() >= maxCandidatePool);
    }

    private Map<String, NodeScore> strongestNodeByIntentCode(List<NodeScore> kbIntents) {
        Map<String, NodeScore> result = new HashMap<>();
        if (CollUtil.isEmpty(kbIntents)) {
            return result;
        }
        for (NodeScore nodeScore : kbIntents) {
            if (nodeScore == null || nodeScore.getNode() == null || StrUtil.isBlank(nodeScore.getNode().getId())) {
                continue;
            }
            result.merge(nodeScore.getNode().getId(), nodeScore,
                    (existing, candidate) -> candidate.getScore() > existing.getScore() ? candidate : existing);
        }
        return result;
    }

    private String kbIdOf(IntentRetrievalPlan intentPlan, Map<String, NodeScore> nodeByIntentCode) {
        if (StrUtil.isNotBlank(intentPlan.kbId())) {
            return intentPlan.kbId().trim();
        }
        NodeScore nodeScore = nodeByIntentCode.get(intentPlan.intentCode());
        IntentNode node = nodeScore == null ? null : nodeScore.getNode();
        return node == null ? null : StrUtil.trim(node.getKbId());
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
            String key = StrUtil.isNotBlank(chunk.getId()) ? chunk.getId() : "text:" + Objects.hashCode(chunk.getText());
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

    public record LevelExecution(String intentCode, int levelOrder, String levelName,
                                 Map<String, Object> metadataFilters, int returnedCount,
                                 int uniqueAddedCount, int pooledUniqueCount) {
    }

    public record ProgressiveKeywordEsResult(List<RetrievedChunk> chunks, List<LevelExecution> executions,
                                             int attemptedSearches, int failedSearches,
                                             boolean reachedCandidatePoolLimit) {

        public static ProgressiveKeywordEsResult empty() {
            return new ProgressiveKeywordEsResult(List.of(), List.of(), 0, 0, false);
        }

        public boolean shouldFallbackToLegacy() {
            return attemptedSearches > 0 && attemptedSearches == failedSearches;
        }
    }
}
