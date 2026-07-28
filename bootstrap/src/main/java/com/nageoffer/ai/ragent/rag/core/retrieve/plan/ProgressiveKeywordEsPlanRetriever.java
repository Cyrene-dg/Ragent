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
 * ES 关键词通道的分层 Plan 执行器。
 *
 * 它和 {@link ProgressiveMilvusPlanRetriever} 消费完全相同的 {@link RetrievalPlan}：
 * 外层按意图分数处理，内层按 filterLevels 从严格到宽松执行；区别仅在于每一层调用
 * {@link KeywordEsGateway} 做 BM25 关键词召回，而不是调用 Milvus 做向量召回。
 *
 * 本类只编排“查哪一层、何时停止、怎样去重”，ES DSL 构造和 HTTP 通信仍由 Gateway 负责。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProgressiveKeywordEsPlanRetriever {

    private final KeywordEsGateway keywordEsGateway;

    /**
     * 按 Plan 渐进执行 ES 检索。
     * 每个意图最多累计 targetCandidateCount 条；整个 ES 通道最多保留 maxCandidatePool 条。
     * 对 ANY_HIT 层，只要该层有结果便停止放宽；普通层则继续补足候选数。
     */
    public ProgressiveKeywordEsResult retrieve(String question,
                                               RetrievalPlan plan,
                                               List<NodeScore> kbIntents) {
        if (plan == null || plan.isEmpty() || StrUtil.isBlank(question)) {
            return ProgressiveKeywordEsResult.empty();
        }

        int targetPerIntent = Math.max(1, plan.targetCandidateCount()); // 单意图在继续放宽前希望累计的候选数
        int maxCandidatePool = Math.max(targetPerIntent, plan.maxCandidatePool()); // ES 通道内部候选池上限
        Map<String, NodeScore> nodeByIntentCode = strongestNodeByIntentCode(kbIntents); // intentCode -> 最高分节点
        LinkedHashMap<String, RetrievedChunk> candidates = new LinkedHashMap<>(); // 按 chunkId 去重的候选池
        List<LevelExecution> executions = new ArrayList<>(); // 记录每层请求，便于 Trace/日志诊断
        int attemptedSearches = 0;
        int failedSearches = 0;

        // 外层：优先执行分类置信度更高的意图计划
        List<IntentRetrievalPlan> sortedPlans = plan.intentPlans().stream()
                .sorted(Comparator.comparingDouble(IntentRetrievalPlan::intentScore).reversed())
                .toList();
        for (IntentRetrievalPlan intentPlan : sortedPlans) {
            if (candidates.size() >= maxCandidatePool) {
                break;
            }
            // ES 以 kbId 限定知识库分区；优先使用 Plan 中已固定的值
            String kbId = kbIdOf(intentPlan, nodeByIntentCode);
            if (StrUtil.isBlank(kbId)) {
                log.warn("Skip ES metadata plan because its intent has no kbId: intentCode={}", intentPlan.intentCode());
                continue;
            }

            int candidateCountBeforeIntent = candidates.size();
            Set<String> kbIds = Set.of(kbId);
            // 内层：同一意图的 filterLevels 从严格到宽松逐层执行
            for (RetrievalFilterLevel level : intentPlan.filterLevels()) {
                if (candidates.size() >= maxCandidatePool) {
                    break;
                }
                int addedForIntent = candidates.size() - candidateCountBeforeIntent;
                if (addedForIntent >= targetPerIntent) {
                    break;
                }

                // 本层只请求候选池还装得下的数量；Gateway 将 metadataFilters 编译为 ES filter 子句
                int remainingGlobalCapacity = maxCandidatePool - candidates.size();
                int requestTopK = Math.min(remainingGlobalCapacity,
                        Math.max(targetPerIntent, targetPerIntent - addedForIntent));
                attemptedSearches++;
                try {
                    // ★ 真正调 ES 做 BM25 关键词检索，带上 kb 分区、topK 和 metadataFilters
                    List<RetrievedChunk> levelChunks = keywordEsGateway.search(
                            question, requestTopK, kbIds, level.metadataFilters());
                    int uniqueBeforeLevel = candidates.size();
                    mergeByChunkIdentity(candidates, levelChunks, maxCandidatePool); // 同一 chunk 只保留分数更高的版本
                    int addedByLevel = candidates.size() - uniqueBeforeLevel;
                    executions.add(new LevelExecution(intentPlan.intentCode(), level.order(), level.name(),
                            level.metadataFilters(), levelChunks == null ? 0 : levelChunks.size(),
                            addedByLevel, candidates.size()));
                    log.info("Metadata ES level finished: intentCode={}, level={}, filters={}, returned={}, uniqueAdded={}, pooled={}",
                            intentPlan.intentCode(), level.name(), level.metadataFilters(),
                            levelChunks == null ? 0 : levelChunks.size(), addedByLevel, candidates.size());
                    // 与 Milvus 共用停止语义：精确层命中即停，普通层继续补候选
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

    /** 同一个 intentCode 可能有多个评分记录，只保留最高分，用于补全 Plan 未携带的 kbId。 */
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

    /** Plan 已冻结 kbId 时不回查；否则兼容旧调用方式，从 NodeScore 补取。 */
    private String kbIdOf(IntentRetrievalPlan intentPlan, Map<String, NodeScore> nodeByIntentCode) {
        if (StrUtil.isNotBlank(intentPlan.kbId())) {
            return intentPlan.kbId().trim();
        }
        NodeScore nodeScore = nodeByIntentCode.get(intentPlan.intentCode());
        IntentNode node = nodeScore == null ? null : nodeScore.getNode();
        return node == null ? null : StrUtil.trim(node.getKbId());
    }

    /**
     * 合并本层结果到 ES 候选池：优先使用 chunkId，缺失时才以文本 hash 兜底；
     * 同 key 重复时保留 ES 分数更高的块，不因重复而占用候选池容量。
     */
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
