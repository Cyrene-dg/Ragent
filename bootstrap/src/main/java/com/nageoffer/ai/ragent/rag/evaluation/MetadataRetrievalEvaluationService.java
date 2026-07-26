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

package com.nageoffer.ai.ragent.rag.evaluation;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.MultiChannelRetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.EntityResolution;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.RetrievalPlan;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.RetrievalPlanService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs the two metadata policies against one immutable intent-resolution result.
 *
 * <p>This deliberately tests retrieval only: it skips conversation memory, query rewriting,
 * SSE and answer generation.  The expensive/variable intent classification is executed once,
 * then both branches receive the exact same selected intents and entity resolution.</p>
 */
@Service
@RequiredArgsConstructor
public class MetadataRetrievalEvaluationService {

    private static final int DEFAULT_TOP_K = 20;
    private static final int MAX_TOP_K = 50;

    private final IntentResolver intentResolver;
    private final RetrievalPlanService retrievalPlanService;
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    private final KnowledgeChunkMapper knowledgeChunkMapper;

    public MetadataRetrievalEvaluationResult compare(String question, Integer requestedTopK) {
        if (!StringUtils.hasText(question)) {
            throw new IllegalArgumentException("question 不能为空");
        }

        int topK = normalizeTopK(requestedTopK);
        long intentStartedAt = System.nanoTime();
        List<SubQuestionIntent> subIntents = intentResolver.resolve(
                new RewriteResult(question.trim(), List.of(question.trim()))
        );
        long intentResolutionMs = elapsedMs(intentStartedAt);

        // The evaluation input creates exactly one sub-question.  Keep this guard so a future
        // caller cannot silently compare a plan built for one question against another one.
        if (subIntents.size() != 1) {
            throw new IllegalStateException("评测入口只支持单个子问题，实际数量=" + subIntents.size());
        }

        SubQuestionIntent subIntent = subIntents.get(0);
        List<NodeScore> kbIntents = subIntent.nodeScores().stream()
                .filter(score -> score.getNode() != null)
                .filter(score -> score.getNode().getKind() == null || score.getNode().getKind() == IntentKind.KB)
                .toList();

        BranchResult baseline = runBranch(question.trim(), subIntents, subIntent, kbIntents, topK, false);
        BranchResult candidate = runBranch(question.trim(), subIntents, subIntent, kbIntents, topK, true);

        return new MetadataRetrievalEvaluationResult(
                question.trim(),
                topK,
                intentResolutionMs,
                subIntent.entityResolution(),
                kbIntents,
                baseline,
                candidate
        );
    }

    private BranchResult runBranch(String question,
                                   List<SubQuestionIntent> subIntents,
                                   SubQuestionIntent subIntent,
                                   List<NodeScore> kbIntents,
                                   int topK,
                                   boolean elementFacetEnabled) {
        RetrievalPlan plan = retrievalPlanService.buildPlan(
                question, kbIntents, subIntent.entityResolution(), elementFacetEnabled);
        long retrievalStartedAt = System.nanoTime();
        List<RetrievedChunk> chunks = multiChannelRetrievalEngine.retrieveKnowledgeChannels(subIntents, topK, plan);
        return new BranchResult(elementFacetEnabled, elapsedMs(retrievalStartedAt), plan, enrichChunks(chunks));
    }

    /**
     * Evaluation-only enrichment. Retrieval intentionally exposes only id/text/score in normal
     * paths, but stable offline judgments also need the persisted metadata when a legacy row has
     * damaged text encoding and cannot be mapped by content hash alone.
     */
    private List<EvaluatedChunk> enrichChunks(List<RetrievedChunk> chunks) {
        List<Long> chunkIds = chunks.stream()
                .map(RetrievedChunk::getId)
                .map(this::parseLong)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, KnowledgeChunkDO> chunksById = chunkIds.isEmpty()
                ? Map.of()
                : knowledgeChunkMapper.selectByIds(chunkIds).stream()
                        .collect(Collectors.toMap(KnowledgeChunkDO::getId, Function.identity()));
        return chunks.stream().map(chunk -> {
            KnowledgeChunkDO persisted = chunksById.get(parseLong(chunk.getId()));
            return new EvaluatedChunk(
                    chunk.getId(),
                    chunk.getText(),
                    chunk.getScore(),
                    persisted == null ? null : persisted.getChunkIndex(),
                    persisted == null ? null : persisted.getMetadataJson()
            );
        }).toList();
    }

    private Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int normalizeTopK(Integer requestedTopK) {
        if (requestedTopK == null || requestedTopK <= 0) {
            return DEFAULT_TOP_K;
        }
        return Math.min(requestedTopK, MAX_TOP_K);
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    public record BranchResult(
            boolean elementFacetEnabled,
            long retrievalLatencyMs,
            RetrievalPlan retrievalPlan,
            List<EvaluatedChunk> chunks
    ) {
        public BranchResult {
            chunks = List.copyOf(chunks);
        }
    }

    public record EvaluatedChunk(
            String id,
            String text,
            Float score,
            Integer chunkIndex,
            String metadataJson
    ) {
    }

    public record MetadataRetrievalEvaluationResult(
            String question,
            int topK,
            long intentResolutionLatencyMs,
            EntityResolution entityResolution,
            List<NodeScore> kbIntents,
            BranchResult baseline,
            BranchResult candidate
    ) {
        public MetadataRetrievalEvaluationResult {
            kbIntents = List.copyOf(kbIntents);
        }
    }
}
