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

package com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor; // 检索后处理器子包

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.config.MetadataRetrievalProperties; // 配置：容量、RRF 参数
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchChannelResult; // 每个检索通道的结果
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;         // 检索上下文（含 plan）
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap; // 保持插入顺序的 map
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 候选池安全限流处理器。
 *
 * 在 Milvus 和 ES 两路检索完成后、发送给 rerank 之前执行。
 * 如果候选池超过了配置的上限，用 RRF（Reciprocal Rank Fusion）算法融合去重，
 * 并按 RRF 分数排序后截断到上限。
 *
 * RRF 原理：每个 chunk 在各通道中的排名越低（越靠前），得分越高。
 *   得分 = Σ(1 / (rank_constant + rank_in_channel))
 *   例如 rank_constant=60 时，排第 1 的 chunk 得 1/61，排第 10 的得 1/70。
 *   一个 chunk 在向量通道排第 2、在 ES 通道排第 5：
 *     总分 = 1/62 + 1/65 ≈ 0.0161 + 0.0154 ≈ 0.0315
 *   这种算法让"两边都排前面"的 chunk 胜出。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandidatePoolLimiterPostProcessor implements SearchResultPostProcessor {

    private final MetadataRetrievalProperties metadataRetrievalProperties;

    /** 处理器名称（日志标识） */
    @Override
    public String getName() {
        return "CandidatePoolLimiter";
    }

    /** 执行顺序：数字越小越先执行；当前位于去重（1）之后、Rerank（10）之前。 */
    @Override
    public int getOrder() {
        return 5;
    }

    /**
     * 仅在 v7 metadata 检索启用且有 plan 时才生效。
     * 其他场景（没有 plan 的旧流程）跳过本处理器。
     */
    @Override
    public boolean isEnabled(SearchContext context) {
        return metadataRetrievalProperties.isEnabled()
                && metadataRetrievalProperties.isExecutionEnabled()
                && context != null
                && context.getRetrievalPlan() != null
                && !context.getRetrievalPlan().isEmpty();
    }

    /**
     * 主逻辑：以去重后的 chunk 数量为准，超过上限时做 RRF 融合 + 排序 + 截断。
     *
     * @param chunks  当前累积的候选 chunk（已去重合并的融合结果）
     * @param results 各通道的原始搜索结果（保留每个 chunk 在各自通道中的排名信息）
     * @param context 检索上下文（含 plan）
     * @return 截断后的候选列表
     */
    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks,
                                        List<SearchChannelResult> results,
                                        SearchContext context) {
        int limit = metadataRetrievalProperties.getMaxRerankCandidates();
        // 数量没超上限，不需要截断，直接原样返回
        if (CollUtil.isEmpty(chunks) || limit <= 0 || chunks.size() <= limit) {
            return chunks;
        }

        // ─── 步骤 1：去重，构建 chunkKey → Candidate 的映射 ───
        Map<String, Candidate> candidatesByKey = new LinkedHashMap<>();
        for (int index = 0; index < chunks.size(); index++) {
            RetrievedChunk chunk = chunks.get(index);
            if (chunk != null) {
                // putIfAbsent：同一个 chunkId 只在第一次出现时记录
                candidatesByKey.putIfAbsent(chunkKey(chunk), new Candidate(chunk, index));
            }
        }
        // 去重后如果没超上限，直接返回（不需要 RRF 排序）
        if (candidatesByKey.size() <= limit) {
            return List.copyOf(candidatesByKey.values().stream().map(Candidate::chunk).toList());
        }

        // ─── 步骤 2：RRF 评分 ——为每个 chunk 累加它在各通道中的排名分 ───
        int rankConstant = Math.max(0, metadataRetrievalProperties.getRrfRankConstant());
        if (results != null) {
            // 遍历每个检索通道（向量通道、ES 通道等）
            for (SearchChannelResult result : results) {
                if (result == null || CollUtil.isEmpty(result.getChunks())) {
                    continue;
                }
                Set<String> seenInChannel = new HashSet<>(); // 同一通道内同一 chunk 只计一次
                for (int index = 0; index < result.getChunks().size(); index++) {
                    RetrievedChunk chunk = result.getChunks().get(index);
                    if (chunk == null) {
                        continue;
                    }
                    String key = chunkKey(chunk);
                    if (!seenInChannel.add(key)) {
                        continue; // 同一通道内重复出现，跳过
                    }
                    Candidate candidate = candidatesByKey.get(key);
                    if (candidate != null) {
                        int rank = index + 1; // 排名从 1 开始
                        // RRF 得分公式：1 / (rankConstant + rank)
                        candidate.addRrfContribution(1D / (rankConstant + rank), rank);
                    }
                }
            }
        }

        // ─── 步骤 3：按 RRF 分数降序排序，截断到 limit ───
        // 主排序：rrfScore 从高到低
        // 次排序：bestRank（排名最好的一次，从低到高）
        // 三排序：originalOrder（在原始列表中的顺序，从低到高，保证稳定）
        List<RetrievedChunk> limited = candidatesByKey.values().stream()
                .sorted(Comparator.comparingDouble(Candidate::rrfScore).reversed()
                        .thenComparingInt(Candidate::bestRank)
                        .thenComparingInt(Candidate::originalOrder))
                .limit(limit) // 截断到上限
                .map(Candidate::chunk)
                .toList();
        log.info("Candidate pool safety limiter triggered: before={}, after={}, strategy=rrf, rankConstant={}",
                candidatesByKey.size(), limited.size(), rankConstant);
        return limited;
    }

    /**
     * 提取本次候选池去重用的标识 key。
     * 优先用 chunk.getId()；没有 id 时用 text 的 hashCode 兜底，理论上存在哈希碰撞可能。
     */
    private String chunkKey(RetrievedChunk chunk) {
        return StrUtil.isNotBlank(chunk.getId())
                ? chunk.getId()
                : "text:" + String.valueOf(chunk.getText()).hashCode();
    }

    /**
     * 候选评分包装类。
     * 记录一个 chunk 的原始顺序、在各通道中被赋予的 RRF 分数、以及最佳排名。
     */
    private static final class Candidate {

        private final RetrievedChunk chunk;          // 原始 chunk 对象
        private final int originalOrder;             // 在 chunks 列表中的原始位置
        private double rrfScore;                      // RRF 累加总分
        private int bestRank = Integer.MAX_VALUE;     // 所有通道中最好的排名（数字越小越好）

        private Candidate(RetrievedChunk chunk, int originalOrder) {
            this.chunk = chunk;
            this.originalOrder = originalOrder;
        }

        /**
         * 累加一个通道的 RRF 贡献值。
         * 如果一个 chunk 在向量通道排第 2、在 ES 通道排第 5：
         *   rrfScore = 1/(60+2) + 1/(60+5) ≈ 0.0161 + 0.0154 = 0.0315
         *   bestRank = min(2, 5) = 2
         */
        private void addRrfContribution(double contribution, int rank) {
            rrfScore += contribution;           // 累加分数
            bestRank = Math.min(bestRank, rank); // 保持最好的排名
        }

        private RetrievedChunk chunk() { return chunk; }
        private int originalOrder() { return originalOrder; }
        private double rrfScore() { return rrfScore; }
        private int bestRank() { return bestRank; }
    }
}
