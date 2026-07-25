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

import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.config.MetadataRetrievalProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Converts selected intent leaves and entity matches into progressive metadata scopes. */
@Service
@RequiredArgsConstructor
public class RetrievalPlanService {

    private final MetadataRetrievalProperties properties;
    private final IntentRouteRuleService intentRouteRuleService;
    private final EntityResolver entityResolver;

    @RagTraceNode(name = "retrieval-plan", type = "RETRIEVE")
    public RetrievalPlan buildPlan(String question, List<NodeScore> kbIntents) {
        return buildPlan(question, kbIntents, entityResolver.resolveAny(question));
    }

    @RagTraceNode(name = "retrieval-plan", type = "RETRIEVE")
    public RetrievalPlan buildPlan(String question, List<NodeScore> kbIntents, EntityResolution entityResolution) {
        if (!properties.isEnabled()) {
            return emptyPlan(question);
        }

        Map<String, NodeScore> strongestIntentByCode = new LinkedHashMap<>();
        for (NodeScore nodeScore : kbIntents == null ? List.<NodeScore>of() : kbIntents) {
            IntentNode node = nodeScore == null ? null : nodeScore.getNode();
            if (node == null || node.getId() == null) {
                continue;
            }
            strongestIntentByCode.merge(node.getId(), nodeScore,
                    (left, right) -> left.getScore() >= right.getScore() ? left : right);
        }

        List<IntentRetrievalPlan> intentPlans = new ArrayList<>();
        for (NodeScore nodeScore : strongestIntentByCode.values()) {
            IntentNode node = nodeScore.getNode();
            intentRouteRuleService.findByIntentCode(node.getId()).ifPresent(rule -> {
                Map<String, Object> routeFilters = rule.metadataFilters();
                EntityResolution compatibleEntity = isCompatible(entityResolution, routeFilters)
                        ? entityResolution
                        : entityResolver.resolve(question, categoriesOf(routeFilters));
                intentPlans.add(new IntentRetrievalPlan(
                        node.getId(),
                        node.getName(),
                        nodeScore.getScore(),
                        node.getKbId(),
                        node.getCollectionName(),
                        routeFilters,
                        compatibleEntity,
                        buildFilterLevels(routeFilters, compatibleEntity)
                ));
            });
        }
        if (intentPlans.isEmpty() && entityResolution != null && entityResolution.isMatched()
                && entityResolution.category() != null && !entityResolution.category().isBlank()
                && !properties.getEntityFallbackKbId().isBlank()
                && !properties.getEntityFallbackCollectionName().isBlank()) {
            Map<String, Object> routeFilters = Map.of("category", entityResolution.category());
            intentPlans.add(new IntentRetrievalPlan(
                    "metadata_entity_fallback",
                    "实体兜底检索",
                    0.75D,
                    properties.getEntityFallbackKbId(),
                    properties.getEntityFallbackCollectionName(),
                    routeFilters,
                    entityResolution,
                    buildEntityFallbackLevels(routeFilters, entityResolution)
            ));
        }
        return new RetrievalPlan(question, properties.getTargetCandidateCount(), properties.getMaxCandidatePool(), intentPlans);
    }

    private RetrievalPlan emptyPlan(String question) {
        return new RetrievalPlan(question, properties.getTargetCandidateCount(), properties.getMaxCandidatePool(), List.of());
    }

    private List<RetrievalFilterLevel> buildFilterLevels(Map<String, Object> routeFilters,
                                                         EntityResolution entityResolution) {
        Map<String, Object> route = new LinkedHashMap<>(routeFilters);
        Map<String, Object> categoryOnly = new LinkedHashMap<>();
        if (route.containsKey("category")) {
            categoryOnly.put("category", route.get("category"));
        }

        List<Map<String, Object>> scopes = new ArrayList<>();
        if (entityResolution.isMatched()) {
            Map<String, Object> strict = entityFilters(entityResolution);
            strict.putAll(route);
            addUnique(scopes, strict);

            Map<String, Object> entityCategory = entityFilters(entityResolution);
            entityCategory.putAll(categoryOnly);
            addUnique(scopes, entityCategory);
        }
        addUnique(scopes, route);
        addUnique(scopes, categoryOnly);
        addUnique(scopes, Map.of());

        List<RetrievalFilterLevel> levels = new ArrayList<>();
        for (int index = 0; index < scopes.size(); index++) {
            Map<String, Object> filters = scopes.get(index);
            levels.add(new RetrievalFilterLevel(index + 1, levelName(index, filters, entityResolution), filters));
        }
        return levels;
    }

    private List<RetrievalFilterLevel> buildEntityFallbackLevels(Map<String, Object> routeFilters,
                                                                  EntityResolution entityResolution) {
        Map<String, Object> filters = entityFilters(entityResolution);
        filters.putAll(routeFilters);
        return List.of(new RetrievalFilterLevel(1, "entity-category-fallback", filters));
    }

    private static Map<String, Object> entityFilters(EntityResolution entityResolution) {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("entity_type", entityResolution.entityType());
        filters.put("entity_id", entityResolution.entityId());
        return filters;
    }

    private static void addUnique(List<Map<String, Object>> scopes, Map<String, Object> candidate) {
        Map<String, Object> copy = new LinkedHashMap<>(candidate);
        if (scopes.stream().noneMatch(existing -> Objects.equals(existing, copy))) {
            scopes.add(copy);
        }
    }

    private static String levelName(int index, Map<String, Object> filters, EntityResolution entityResolution) {
        if (filters.isEmpty()) {
            return "v7-global";
        }
        if (entityResolution.isMatched() && filters.containsKey("entity_id") && filters.containsKey("doc_type")) {
            return "entity-category-doc-type";
        }
        if (entityResolution.isMatched() && filters.containsKey("entity_id")) {
            return "entity-category";
        }
        if (filters.containsKey("doc_type")) {
            return "category-doc-type";
        }
        return index == 0 ? "intent-route" : "category";
    }

    private static Set<String> categoriesOf(Map<String, Object> routeFilters) {
        Object category = routeFilters.get("category");
        if (category instanceof Collection<?> collection) {
            Set<String> categories = new LinkedHashSet<>();
            for (Object value : collection) {
                if (value != null) {
                    categories.add(String.valueOf(value));
                }
            }
            return categories;
        }
        return category == null ? Set.of() : Set.of(String.valueOf(category));
    }

    private static boolean isCompatible(EntityResolution entityResolution, Map<String, Object> routeFilters) {
        if (entityResolution == null || !entityResolution.isMatched()) {
            return false;
        }
        String entityCategory = entityResolution.category();
        return entityCategory != null && categoriesOf(routeFilters).contains(entityCategory);
    }
}
