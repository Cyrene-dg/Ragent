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

package com.nageoffer.ai.ragent.rag.core.knowledge;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.config.KnowledgeBaseRoutingProperties;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentClassifier;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.retrieve.MultiChannelRetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.EntityResolver;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.RetrievalPlan;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.RetrievalPlanService;
import com.nageoffer.ai.ragent.rag.core.routing.KnowledgeBaseRoutingService;
import com.nageoffer.ai.ragent.rag.dto.KbResult;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.*;

@Service
@RequiredArgsConstructor
public class DefaultKnowledgeQueryService implements KnowledgeQueryService {
    private final KnowledgeBaseRoutingService router;
    @Qualifier("defaultIntentClassifier")
    private final IntentClassifier classifier;
    private final EntityResolver entityResolver;
    private final RetrievalPlanService planService;
    private final MultiChannelRetrievalEngine channels;
    private final ContextFormatter formatter;
    private final IntentGuidanceService guidance;

    @Override
    @RagTraceNode(name = "knowledge-query", type = "RETRIEVE")
    public KnowledgeResult query(String question, int topK) {
        List<IntentNode> leaves = classifier.leafTargets().stream().filter(IntentNode::isKB).toList();
        Map<String, List<IntentNode>> byKb = leaves.stream()
                .filter(node -> node.getKbId() != null && !node.getKbId().isBlank())
                .collect(Collectors.groupingBy(IntentNode::getKbId));
        List<KnowledgeBaseRoutingProperties.Profile> legacyProfiles = byKb.entrySet().stream().map(entry -> {
            var profile = new KnowledgeBaseRoutingProperties.Profile();
            profile.setKbId(entry.getKey());
            profile.setId("kb:" + entry.getKey());
            profile.setDescription(entry.getValue().stream()
                    .map(node -> node.getFullPath() + " " + node.getDescription()).collect(Collectors.joining("; ")));
            return profile;
        }).toList();
        var routes = router.routeAll(question, legacyProfiles);
        if (routes.isEmpty()) {
            return new KnowledgeResult(List.of(), KbResult.empty(), "请明确需要查询哪个知识库或资料范围。");
        }
        List<SubQuestionIntent> resolutions = new ArrayList<>();
        for (var route : routes) {
            var entity = router.managedKbIds().contains(route.kbId())
                    ? entityResolver.resolveAny(question, route.kbId())
                    : com.nageoffer.ai.ragent.rag.core.retrieve.plan.EntityResolution.notFound();
            var scores = classifier.classifyKnowledgeTargets(question, entity, route.kbId()).stream()
                    .filter(score -> score.getNode() != null && score.getNode().isKB())
                    .filter(score -> route.kbId().equals(score.getNode().getKbId()))
                    .filter(score -> score.getScore() >= INTENT_MIN_SCORE)
                    .limit(MAX_INTENT_COUNT).toList();
            var resolution = new SubQuestionIntent(question, scores, entity, route);
            var decision = guidance.detectAmbiguity(question, List.of(resolution));
            if (decision.isPrompt()) {
                return new KnowledgeResult(List.copyOf(resolutions), KbResult.empty(), decision.getPrompt());
            }
            resolutions.add(resolution);
        }
        StringBuilder context = new StringBuilder();
        Map<String, List<RetrievedChunk>> chunks = new LinkedHashMap<>();
        for (var resolution : resolutions) {
            KbResult result = retrieveResolved(resolution, topK);
            if (!result.groupedContext().isBlank()) {
                context.append("\n### 知识库 ").append(resolution.knowledgeBaseRoute().kbId())
                        .append("\n").append(result.groupedContext()).append("\n");
            }
            result.intentChunks().forEach((id, values) -> chunks.merge(id, values, (left, right) -> {
                var merged = new ArrayList<>(left);
                merged.addAll(right);
                return merged.stream().distinct().toList();
            }));
        }
        return new KnowledgeResult(resolutions, new KbResult(context.toString().trim(), chunks), null);
    }

    @Override
    public KbResult retrieveResolved(SubQuestionIntent intent, int topK) {
        if (intent.nodeScores().stream().anyMatch(score -> score.getNode() == null || !score.getNode().isKB())) {
            throw new IllegalArgumentException("Knowledge retrieval accepts KB targets only");
        }
        String kbId = intent.knowledgeBaseRoute().kbId();
        if (kbId != null && intent.nodeScores().stream().anyMatch(score -> !kbId.equals(score.getNode().getKbId()))) {
            throw new IllegalArgumentException("Knowledge intent is outside the selected corpus");
        }
        List<NodeScore> scores = intent.nodeScores();
        int fallback = topK > 0 ? topK : DEFAULT_TOP_K;
        int limit = scores.stream().map(NodeScore::getNode).map(IntentNode::getTopK)
                .filter(Objects::nonNull).filter(value -> value > 0).max(Integer::compareTo).orElse(fallback);
        RetrievalPlan plan = planService.buildPlan(intent.subQuestion(), scores, intent.entityResolution());
        // A configured entity fallback must not escape the corpus selected for this request.
        if (kbId != null) {
            boolean entityFallback = plan.intentPlans().stream()
                    .anyMatch(value -> "metadata_entity_fallback".equals(value.intentCode()));
            plan = new RetrievalPlan(plan.question(), plan.targetCandidateCount(), plan.maxCandidatePool(),
                    plan.intentPlans().stream().filter(value -> kbId.equals(value.kbId())).toList());
            String collection = router.entityFallbackCollection(kbId);
            var entity = intent.entityResolution();
            if (plan.isEmpty() && entityFallback && entity.isMatched() && entity.category() != null
                    && collection != null && !collection.isBlank()) {
                Map<String, Object> filters = Map.of("entity_type", entity.entityType(), "entity_id", entity.entityId(),
                        "category", entity.category());
                var fallbackPlan = new com.nageoffer.ai.ragent.rag.core.retrieve.plan.IntentRetrievalPlan(
                        "metadata_entity_fallback", "实体兜底检索", .75, kbId, collection,
                        Map.of("category", entity.category()), entity,
                        List.of(new com.nageoffer.ai.ragent.rag.core.retrieve.plan.RetrievalFilterLevel(1,
                                "entity-category-fallback", filters)));
                plan = new RetrievalPlan(plan.question(), plan.targetCandidateCount(), plan.maxCandidatePool(), List.of(fallbackPlan));
            }
        }
        List<RetrievedChunk> values = kbId == null
                ? channels.retrieveKnowledgeChannels(List.of(intent), limit, plan)
                : channels.retrieveKnowledgeChannels(List.of(intent), limit, plan, java.util.Set.of(kbId));
        if (values.isEmpty()) {
            return KbResult.empty();
        }
        Map<String, List<RetrievedChunk>> grouped = new LinkedHashMap<>();
        if (scores.isEmpty()) {
            grouped.put(MULTI_CHANNEL_KEY + ":" + kbId, values);
        } else {
            scores.forEach(score -> grouped.put(score.getNode().getId(), values));
        }
        return new KbResult(formatter.formatKbContext(scores, grouped, limit), grouped);
    }
}
