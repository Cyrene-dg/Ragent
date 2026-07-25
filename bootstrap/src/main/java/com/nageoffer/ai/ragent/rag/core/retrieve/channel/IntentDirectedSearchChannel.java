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
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.rag.config.SearchChannelProperties;
import com.nageoffer.ai.ragent.rag.config.MetadataRetrievalProperties;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.strategy.IntentParallelRetriever;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.ProgressiveMilvusPlanRetriever;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.ProgressiveMilvusPlanRetriever.ProgressiveRetrievalResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.IntentRetrievalPlan;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.RetrievalPlan;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * 意图定向检索通道。
 */
@Slf4j
@Component
public class IntentDirectedSearchChannel implements SearchChannel {

    private final SearchChannelProperties properties;
    private final MetadataRetrievalProperties metadataRetrievalProperties;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final IntentParallelRetriever parallelRetriever;
    private final ProgressiveMilvusPlanRetriever progressivePlanRetriever;

    public IntentDirectedSearchChannel(RetrieverService retrieverService,
                                       SearchChannelProperties properties,
                                       MetadataRetrievalProperties metadataRetrievalProperties,
                                       KnowledgeBaseMapper knowledgeBaseMapper,
                                       ProgressiveMilvusPlanRetriever progressivePlanRetriever,
                                       @Qualifier("ragInnerRetrievalThreadPoolExecutor") Executor ragInnerRetrievalExecutor) {
        this.properties = properties;
        this.metadataRetrievalProperties = metadataRetrievalProperties;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.parallelRetriever = new IntentParallelRetriever(retrieverService, ragInnerRetrievalExecutor);
        this.progressivePlanRetriever = progressivePlanRetriever;
    }

    @Override
    public String getName() {
        return "IntentDirectedSearch";
    }

    @Override
    public int getPriority() {
        return 1;
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        if (!properties.getChannels().getIntentDirected().isEnabled()) {
            return false;
        }
        if (context == null) {
            return false;
        }
        if (hasExecutableMetadataPlan(context)) {
            return true;
        }
        if (CollUtil.isEmpty(context.getIntents())) {
            return false;
        }
        List<NodeScore> kbIntents = extractKbIntents(context);
        return CollUtil.isNotEmpty(kbIntents);
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        long startTime = System.currentTimeMillis();
        try {
            List<NodeScore> kbIntents = extractKbIntents(context);
            if (CollUtil.isEmpty(kbIntents) && !hasExecutableMetadataPlan(context)) {
                return SearchChannelResult.builder()
                        .channelType(SearchChannelType.INTENT_DIRECTED)
                        .channelName(getName())
                        .chunks(List.of())
                        .confidence(0.0)
                        .latencyMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            Map<String, String> embeddingModelByCollection = loadEmbeddingModelMap(kbIntents, context.getRetrievalPlan());
            List<RetrievedChunk> allChunks = retrieveByPlanOrLegacy(context, kbIntents, embeddingModelByCollection);

            double confidence = kbIntents.stream()
                    .mapToDouble(NodeScore::getScore)
                    .max()
                    .orElseGet(() -> context.getRetrievalPlan() == null ? 0.0 : context.getRetrievalPlan().intentPlans().stream()
                            .mapToDouble(IntentRetrievalPlan::intentScore)
                            .max()
                            .orElse(0.0));

            long latency = System.currentTimeMillis() - startTime;
            log.info("意图定向检索完成，检索到 {} 个 Chunk，置信度：{}，耗时 {}ms",
                    allChunks.size(), confidence, latency);

            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.INTENT_DIRECTED)
                    .channelName(getName())
                    .chunks(allChunks)
                    .confidence(confidence)
                    .latencyMs(latency)
                    .metadata(Map.of("intentCount", kbIntents.size()))
                    .build();
        } catch (Exception e) {
            log.error("意图定向检索失败", e);
            return SearchChannelResult.builder()
                    .channelType(SearchChannelType.INTENT_DIRECTED)
                    .channelName(getName())
                    .chunks(List.of())
                    .confidence(0.0)
                    .latencyMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    @Override
    public SearchChannelType getType() {
        return SearchChannelType.INTENT_DIRECTED;
    }

    private List<NodeScore> extractKbIntents(SearchContext context) {
        double minScore = properties.getChannels().getIntentDirected().getMinIntentScore();
        return context.getIntents().stream()
                .flatMap(si -> si.nodeScores().stream())
                .filter(ns -> ns.getNode() != null && ns.getNode().isKB())
                .filter(ns -> ns.getScore() >= minScore)
                .toList();
    }

    private List<RetrievedChunk> retrieveByPlanOrLegacy(SearchContext context,
                                                         List<NodeScore> kbIntents,
                                                         Map<String, String> embeddingModelByCollection) {
        RetrievalPlan retrievalPlan = context.getRetrievalPlan();
        if (metadataRetrievalProperties.isEnabled()
                && metadataRetrievalProperties.isExecutionEnabled()
                && retrievalPlan != null
                && !retrievalPlan.isEmpty()) {
            Set<String> plannedIntentCodes = retrievalPlan.intentPlans().stream()
                    .map(IntentRetrievalPlan::intentCode)
                    .collect(java.util.stream.Collectors.toSet());
            List<NodeScore> plannedIntents = kbIntents.stream()
                    .filter(nodeScore -> plannedIntentCodes.contains(nodeScore.getNode().getId()))
                    .toList();
            List<NodeScore> legacyIntents = kbIntents.stream()
                    .filter(nodeScore -> !plannedIntentCodes.contains(nodeScore.getNode().getId()))
                    .toList();
            try {
                ProgressiveRetrievalResult result = progressivePlanRetriever.retrieve(
                        context.getMainQuestion(),
                        retrievalPlan,
                        plannedIntents,
                        embeddingModelByCollection
                );
                if (!result.shouldFallbackToLegacy()) {
                    log.info("Metadata Milvus plan completed: candidates={}, executedLevels={}, failedSearches={}, reachedPoolLimit={}",
                            result.chunks().size(), result.executions().size(), result.failedSearches(), result.reachedCandidatePoolLimit());
                    if (CollUtil.isEmpty(legacyIntents)) {
                        return result.chunks();
                    }
                    List<RetrievedChunk> mergedChunks = new java.util.ArrayList<>(result.chunks());
                    mergedChunks.addAll(retrieveByLegacy(context, legacyIntents, embeddingModelByCollection));
                    return mergedChunks;
                }
                log.warn("All metadata Milvus searches failed; fall back to legacy intent retrieval");
            } catch (Exception ex) {
                log.warn("Metadata Milvus plan could not run; fall back to legacy intent retrieval", ex);
            }
        }

        return retrieveByLegacy(context, kbIntents, embeddingModelByCollection);
    }

    private List<RetrievedChunk> retrieveByLegacy(SearchContext context,
                                                   List<NodeScore> kbIntents,
                                                   Map<String, String> embeddingModelByCollection) {
        int topKMultiplier = properties.getChannels().getIntentDirected().getTopKMultiplier();
        return parallelRetriever.executeParallelRetrieval(
                context.getMainQuestion(),
                kbIntents,
                context.getTopK(),
                topKMultiplier,
                embeddingModelByCollection::get
        );
    }

    /**
     * 将意图命中的 collection 映射到对应知识库绑定的 embedding 模型。
     */
    private Map<String, String> loadEmbeddingModelMap(List<NodeScore> kbIntents, RetrievalPlan retrievalPlan) {
        Set<String> collections = new LinkedHashSet<>(kbIntents.stream()
                .map(NodeScore::getNode)
                .filter(node -> node != null && node.getCollectionName() != null && !node.getCollectionName().isBlank())
                .map(node -> node.getCollectionName().trim())
                .distinct()
                .toList());
        if (retrievalPlan != null) {
            retrievalPlan.intentPlans().stream()
                    .map(IntentRetrievalPlan::collectionName)
                    .filter(collection -> collection != null && !collection.isBlank())
                    .map(String::trim)
                    .forEach(collections::add);
        }
        if (collections.isEmpty()) {
            return Map.of();
        }

        List<KnowledgeBaseDO> kbList = knowledgeBaseMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeBaseDO.class)
                        .select(KnowledgeBaseDO::getCollectionName, KnowledgeBaseDO::getEmbeddingModel)
                        .in(KnowledgeBaseDO::getCollectionName, collections)
                        .eq(KnowledgeBaseDO::getDeleted, 0)
        );

        Map<String, String> mapping = new HashMap<>();
        for (KnowledgeBaseDO kb : kbList) {
            if (kb.getCollectionName() != null && !kb.getCollectionName().isBlank()) {
                mapping.put(kb.getCollectionName(), kb.getEmbeddingModel());
            }
        }
        return mapping;
    }

    private boolean hasExecutableMetadataPlan(SearchContext context) {
        return metadataRetrievalProperties.isEnabled()
                && metadataRetrievalProperties.isExecutionEnabled()
                && context.getRetrievalPlan() != null
                && !context.getRetrievalPlan().isEmpty();
    }
}
