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

    /**
     * 【主方法】按检索计划分层渐进搜索 Milvus 向量库。
     *
     * 结构：外层遍历意图（按分数降序），内层遍历该意图的 filterLevels（从紧到松）。
     * 整体逻辑：高分的意图先搜 → 严格的 filter 先试 → 不够就放宽 → 候选池满了就停。
     *
     * @param question                   用户子问题
     * @param plan                       检索计划（含意图列表、容量参数）
     * @param kbIntents                  意图分类结果（用于查 collectionName）
     * @param embeddingModelByCollection 每个 collection 对应的 embedding 模型名
     * @return 检索结果（chunk 列表 + 执行统计）
     */
    public ProgressiveRetrievalResult retrieve(String question,
                                               RetrievalPlan plan,
                                               List<NodeScore> kbIntents,
                                               Map<String, String> embeddingModelByCollection) {
        // 空 plan 或空问题直接返回
        if (plan == null || plan.isEmpty() || StrUtil.isBlank(question)) {
            return ProgressiveRetrievalResult.empty();
        }

        // 从 plan 中取出容量参数
        int targetPerIntent = Math.max(1, plan.targetCandidateCount()); // 每个意图期望搜到几条
        int maxCandidatePool = Math.max(targetPerIntent, plan.maxCandidatePool()); // 候选池总上限

        Map<String, NodeScore> nodeByIntentCode = strongestNodeByIntentCode(kbIntents); // 意图编码→最高分 NodeScore
        Map<String, float[]> embeddingCache = new HashMap<>(); // embedding 缓存：同模型不重复编码
        LinkedHashMap<String, RetrievedChunk> candidates = new LinkedHashMap<>(); // 去重候选池（key=chunkId）
        List<LevelExecution> executions = new ArrayList<>(); // 每层执行记录（日志用）
        int failedSearches = 0;
        int attemptedSearches = 0;

        // ─── 外层循环：按 intentScore 从高到低处理每个意图 ───
        List<IntentRetrievalPlan> sortedPlans = plan.intentPlans().stream()
                .sorted(Comparator.comparingDouble(IntentRetrievalPlan::intentScore).reversed())
                .toList();
        for (IntentRetrievalPlan intentPlan : sortedPlans) {
            if (candidates.size() >= maxCandidatePool) {
                break; // 池满了，跳过剩余低分意图
            }

            // 确定该意图对应的 Milvus collection 名称
            String collectionName = collectionNameOf(intentPlan, nodeByIntentCode);
            if (StrUtil.isBlank(collectionName)) {
                log.warn("Skip metadata plan because its intent has no collection: intentCode={}", intentPlan.intentCode());
                continue;
            }

            // 获取对应的 embedding 模型名，同一模型的问题向量可以复用
            String embeddingModel = embeddingModelByCollection == null
                    ? null
                    : embeddingModelByCollection.get(collectionName);
            float[] queryVector = embeddingCache.computeIfAbsent(
                    embeddingCacheKey(embeddingModel),
                    ignored -> embedAndNormalize(question, embeddingModel) // 问题 → 向量 → L2归一化
            );
            if (queryVector.length == 0) {
                throw new IllegalStateException("Cannot build query vector for metadata retrieval plan: " + intentPlan.intentCode());
            }

            // ─── 内层循环：按 filterLevels 从紧到松逐层检索 ───
            int candidateCountBeforeIntent = candidates.size(); // 记录这轮意图开始前的池大小
            for (RetrievalFilterLevel level : intentPlan.filterLevels()) {
                if (candidates.size() >= maxCandidatePool) {
                    break; // 候选池满了
                }
                int addedForIntent = candidates.size() - candidateCountBeforeIntent;
                if (addedForIntent >= targetPerIntent) {
                    break; // 这个意图已经搜够了，不需要放更宽的层
                }

                // 计算这层该要多少条：不能超过池剩余容量，且要考虑这层预期能加的量
                int remainingGlobalCapacity = maxCandidatePool - candidates.size();
                int requestTopK = Math.min(remainingGlobalCapacity,
                        Math.max(targetPerIntent, targetPerIntent - addedForIntent));

                // 构建检索请求：问题文本 + collection + 这层的 metadata filter + topK
                RetrieveRequest request = RetrieveRequest.builder()
                        .query(question)
                        .collectionName(collectionName)
                        .embeddingModel(embeddingModel)
                        .topK(requestTopK)
                        .metadataFilters(level.metadataFilters()) // ← 关键：这层的过滤条件
                        .build();
                attemptedSearches++;

                try {
                    // ★ 真正调 Milvus 做向量检索的一行 ↓
                    List<RetrievedChunk> levelChunks = retrieverService.retrieveByVector(queryVector, request);

                    int uniqueBeforeLevel = candidates.size();
                    mergeByChunkIdentity(candidates, levelChunks, maxCandidatePool); // 去重合并到候选池
                    int addedByLevel = candidates.size() - uniqueBeforeLevel;

                    // 记录本层执行统计
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

                    // 应用共享停止策略：ANY_HIT 层非空即停；通用层仍会继续补足目标候选数
                    if (ProgressivePlanStopPolicy.shouldStopAfterLevel(level, levelChunks)) {
                        break;
                    }
                } catch (Exception ex) {
                    // 某层检索失败不阻断整体：记录失败，继续放宽到下一层
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

        // 返回最终结果：候选 chunk 列表 + 执行统计
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

    private String collectionNameOf(IntentRetrievalPlan intentPlan, Map<String, NodeScore> nodeByIntentCode) {
        if (StrUtil.isNotBlank(intentPlan.collectionName())) {
            return intentPlan.collectionName().trim();
        }
        NodeScore nodeScore = nodeByIntentCode.get(intentPlan.intentCode());
        IntentNode node = nodeScore == null ? null : nodeScore.getNode();
        return node == null ? null : StrUtil.trim(node.getCollectionName());
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
