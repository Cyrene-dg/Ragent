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

package com.nageoffer.ai.ragent.rag.core.retrieve.plan; // 检索计划子包

import com.nageoffer.ai.ragent.framework.trace.RagTraceNode; // 链路追踪注解
import com.nageoffer.ai.ragent.rag.config.MetadataRetrievalProperties; // 配置参数
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;   // 保持插入顺序
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 检索计划生成服务。
 * 将意图分类结果 + 实体解析结果 + 路由规则 三条线汇聚，构建分层渐进检索计划。
 *
 * 调用链：
 * RetrievalPlanService.buildPlan
 *   -> IntentRouteRuleService.findByIntentCode  (查路由表: 意图 -> metadataFilters)
 *   -> EntityResolver.resolve                    (重解析实体，可能带 category 过滤)
 *   -> ElementFacetResolver.resolve              (解析 element 维度)
 *   -> buildFilterLevels                         (从紧到松拼出逐层 filter)
 */
@Service
@RequiredArgsConstructor
public class RetrievalPlanService {

    private final MetadataRetrievalProperties properties;      // 全局配置（开关、容量等）
    private final IntentRouteRuleService intentRouteRuleService; // 意图编码 -> 路由过滤规则
    private final EntityResolver entityResolver;                // 实体名匹配
    private final ElementFacetResolver elementFacetResolver;    // element 维度解析（角色系统的元素属性）

    // ============ 对外入口（3 个重载，参数从少到多，最终汇聚到主干） ============

    /**
     * 最简入口：没有外部传来的 entityResolution，自己现做一次实体匹配。
     * 通常用于检索前还不知道实体匹配结果的场景。
     */
    @RagTraceNode(name = "retrieval-plan", type = "RETRIEVE")
    public RetrievalPlan buildPlan(String question, List<NodeScore> kbIntents) {
        return buildPlan(question, kbIntents, entityResolver.resolveAny(question));
    }

    /**
     * 带 entityResolution 的入口：适用已提前做过实体匹配的场景。
     * elementFacetEnabled 从配置读。
     */
    @RagTraceNode(name = "retrieval-plan", type = "RETRIEVE")
    public RetrievalPlan buildPlan(String question, List<NodeScore> kbIntents, EntityResolution entityResolution) {
        return buildPlan(question, kbIntents, entityResolution, properties.isElementFacetEnabled());
    }

    // ============ 主干方法 ============

    /**
     * 【主干】根据意图分类 + 实体匹配 + 路由规则，构建完整的分层检索计划。
     *
     * 流程：
     * 1. 开关关了 -> 直接返回空 plan（走旧检索链路）
     * 2. 去重：同 intentCode 只保留最高分的那个 NodeScore
     * 3. 每个意图查路由表，拿 metadataFilters，合并 entity + element
     * 4. entity 和 route filter 的 category 不兼容时，用 route filter 的 category 重新做一次实体匹配
     * 5. 生成 IntentRetrievalPlan → 构造分层 filterLevels
     * 6. 如果没有任何 intentPlan 但 entity 命中了，走实体兜底
     *
     * @param question            用户子问题
     * @param kbIntents           意图分类结果（LLM 返回 + 阈值过滤后的 KB 意图列表）
     * @param entityResolution    实体匹配结果
     * @param elementFacetEnabled 是否启用 element 维度解析
     * @return 完整检索计划
     */
    public RetrievalPlan buildPlan(String question,
                                   List<NodeScore> kbIntents,
                                   EntityResolution entityResolution,
                                   boolean elementFacetEnabled) {
        // 功能开关关了：退化为空 plan（上层会走旧检索链路）
        if (!properties.isEnabled()) {
            return emptyPlan(question);
        }

        // --- 步骤 1：去重，同 intentCode 只保留分数最高的 ---
        Map<String, NodeScore> strongestIntentByCode = new LinkedHashMap<>();
        for (NodeScore nodeScore : kbIntents == null ? List.<NodeScore>of() : kbIntents) {
            IntentNode node = nodeScore == null ? null : nodeScore.getNode();
            if (node == null || node.getId() == null) {
                continue;
            }
            // merge: key=意图编码, 值=分数最高的那个 NodeScore
            strongestIntentByCode.merge(node.getId(), nodeScore,
                    (left, right) -> left.getScore() >= right.getScore() ? left : right);
        }

        // --- 步骤 2：每个意图 -> 查路由表 -> 构建 IntentRetrievalPlan ---
        List<IntentRetrievalPlan> intentPlans = new ArrayList<>();
        for (NodeScore nodeScore : strongestIntentByCode.values()) {
            IntentNode node = nodeScore.getNode();
            // 查路由表：该意图编码对应的 metadataFilters 是什么
            intentRouteRuleService.findByIntentCode(node.getKbId(), node.getId()).ifPresent(rule -> {
                // 2a. 从路由规则取基础 filter，如果 category 是 character 则解析 element
                Map<String, Object> routeFilters = enrichWithElementFacet(
                        question, rule.metadataFilters(), elementFacetEnabled);

                // 2b. 检查实体匹配结果和路由 filter 是否兼容——
                //     比如实体是 character:10000002，路由 category 也得包含 character
                //     不兼容的话用路由的 category 重新做一次实体匹配
                EntityResolution compatibleEntity = isCompatible(entityResolution, routeFilters)
                        ? entityResolution
                        : entityResolver.resolve(question, categoriesOf(routeFilters), node.getKbId());

                // 2c. 组装 IntentRetrievalPlan + 根据 routeFilters+entity 构建分层 filter
                intentPlans.add(new IntentRetrievalPlan(
                        node.getId(),                                    // intentCode
                        node.getName(),                                  // intentName
                        nodeScore.getScore(),                            // 置信度分数
                        node.getKbId(),                                  // 知识库 ID
                        node.getCollectionName(),                        // Milvus collection
                        routeFilters,                                    // 路由过滤条件
                        compatibleEntity,                                // 实体匹配结果（可能重新匹配过）
                        buildFilterLevels(routeFilters, compatibleEntity) // 分层 filter
                ));
            });
        }

        // --- 步骤 3：实体兜底 ——
        // 如果没有任何意图匹配到路由规则（intentPlans 为空），但实体命中了，
        // 且配置了兜底知识库，则生成一个只按 entity_id + entity_category 搜的兜底 plan
        if (intentPlans.isEmpty()
                && entityResolution != null && entityResolution.isMatched()
                && entityResolution.category() != null && !entityResolution.category().isBlank()
                && !properties.getEntityFallbackKbId().isBlank()
                && !properties.getEntityFallbackCollectionName().isBlank()) {
            Map<String, Object> routeFilters = Map.of("category", entityResolution.category());
            intentPlans.add(new IntentRetrievalPlan(
                    "metadata_entity_fallback",
                    "实体兜底检索",
                    0.75D,                                               // 固定分数
                    properties.getEntityFallbackKbId(),                   // 兜底知识库
                    properties.getEntityFallbackCollectionName(),         // 兜底 collection
                    routeFilters,
                    entityResolution,
                    buildEntityFallbackLevels(routeFilters, entityResolution) // 只有 entity 一层
            ));
        }

        // 组装顶层 RetrievalPlan
        return new RetrievalPlan(question,
                properties.getTargetCandidateCount(), // 目标候选数
                properties.getMaxCandidatePool(),      // 候选池上限
                intentPlans);
    }

    // ============ 零件方法 ============

    /** 开关关掉时返回一个空 plan */
    private RetrievalPlan emptyPlan(String question) {
        return new RetrievalPlan(question, properties.getTargetCandidateCount(), properties.getMaxCandidatePool(), List.of());
    }

    /**
     * 【核心零件】根据 routeFilters 和 entityResolution 构建分层 filterLevels。
     * 原则：从最严格（entity_id + 所有 route 字段）到最宽松（无过滤），逐层排列。
     * 有 entity 命中时前三层是实体精确过滤，后面是通用路由过滤。
     */
    private List<RetrievalFilterLevel> buildFilterLevels(Map<String, Object> routeFilters,
                                                          EntityResolution entityResolution) {
        // 从完整的 routeFilters 拆分出不同粒度的版本
        Map<String, Object> route = new LinkedHashMap<>(routeFilters);        // 全量：category+doc_type+element
        Map<String, Object> routeWithoutElement = new LinkedHashMap<>(route);
        routeWithoutElement.remove("element");                                 // 去掉 element
        Map<String, Object> categoryOnly = new LinkedHashMap<>();              // 只有 category
        if (route.containsKey("category")) {
            categoryOnly.put("category", route.get("category"));
        }
        Map<String, Object> categoryElement = new LinkedHashMap<>(categoryOnly); // category + element
        if (route.containsKey("element")) {
            categoryElement.put("element", route.get("element"));
        }

        // 构建 scope 列表：从紧到松逐层添加（addUnique 自动去重）
        List<Map<String, Object>> scopes = new ArrayList<>();

        // --- entity 精确过滤区（优先级最高） ---
        if (entityResolution.isMatched()) {
            Map<String, Object> strict = entityFilters(entityResolution);
            strict.putAll(route);                           // entity + 全量 route
            addUnique(scopes, strict);

            Map<String, Object> entityCategoryElement = entityFilters(entityResolution);
            entityCategoryElement.putAll(categoryElement);   // entity + category + element
            addUnique(scopes, entityCategoryElement);

            Map<String, Object> entityCategory = entityFilters(entityResolution);
            entityCategory.putAll(categoryOnly);             // entity + category（最简 entity 层）
            addUnique(scopes, entityCategory);
        }

        // --- 通用路由过滤区（entity 已丢弃）---
        addUnique(scopes, route);               // 全量 route
        addUnique(scopes, categoryElement);     // category + element
        addUnique(scopes, routeWithoutElement); // category + doc_type（不要 element）
        addUnique(scopes, categoryOnly);        // 只有 category
        addUnique(scopes, Map.of());            // 无过滤，纯向量检索

        // 包装成 RetrievalFilterLevel 列表
        List<RetrievalFilterLevel> levels = new ArrayList<>();
        for (int index = 0; index < scopes.size(); index++) {
            Map<String, Object> filters = scopes.get(index);
            levels.add(new RetrievalFilterLevel(
                    index + 1,                                     // order: 1, 2, 3...
                    levelName(index, filters, entityResolution),   // 用途描述名称
                    filters,
                    stopConditionFor(filters)                      // ANY_HIT / TARGET_REACHED
            ));
        }
        return levels;
    }

    /** 实体兜底：只有一层 {entity_id, entity_type, category}，选了直接 ANY_HIT 返回 */
    private List<RetrievalFilterLevel> buildEntityFallbackLevels(Map<String, Object> routeFilters,
                                                                  EntityResolution entityResolution) {
        Map<String, Object> filters = entityFilters(entityResolution);
        filters.putAll(routeFilters);
        return List.of(new RetrievalFilterLevel(1, "entity-category-fallback", filters));
    }

    /** 从 EntityResolution 中提取 entity_type + entity_id 两个精确过滤字段 */
    private static Map<String, Object> entityFilters(EntityResolution entityResolution) {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("entity_type", entityResolution.entityType());
        filters.put("entity_id", entityResolution.entityId());
        return filters;
    }

    /** 去重添加：相同的 filter 组合不重复加入 scopes 列表 */
    private static void addUnique(List<Map<String, Object>> scopes, Map<String, Object> candidate) {
        Map<String, Object> copy = new LinkedHashMap<>(candidate);
        if (scopes.stream().noneMatch(existing -> Objects.equals(existing, copy))) {
            scopes.add(copy);
        }
    }

    /**
     * 如果 category 是 "character" 且 element 开关开启，
     * 从用户问题中解析明确的 element（冰、火、水、雷、风、岩、草），追加到 routeFilters 里。
     */
    private Map<String, Object> enrichWithElementFacet(String question,
                                                        Map<String, Object> routeFilters,
                                                        boolean elementFacetEnabled) {
        Map<String, Object> enriched = new LinkedHashMap<>(routeFilters);
        if (elementFacetEnabled && categoriesOf(routeFilters).contains("character")) {
            elementFacetResolver.resolve(question).ifPresent(element -> enriched.put("element", element));
        }
        return enriched;
    }

    /** 判断该层的停止条件：有 entity_id 或 element → 只要搜到就停（ANY_HIT），否则等凑够数 */
    private static RetrievalFilterLevel.StopCondition stopConditionFor(Map<String, Object> filters) {
        return filters.containsKey("entity_id") || filters.containsKey("element")
                ? RetrievalFilterLevel.StopCondition.ANY_HIT
                : RetrievalFilterLevel.StopCondition.TARGET_REACHED;
    }

    /** 给每层 filter 起一个可读的名称，方便日志诊断 */
    private static String levelName(int index, Map<String, Object> filters, EntityResolution entityResolution) {
        if (filters.isEmpty()) return "v7-global";
        if (filters.containsKey("element") && filters.containsKey("doc_type")) return "element-category-doc-type";
        if (filters.containsKey("element")) return "element-category";
        if (entityResolution.isMatched() && filters.containsKey("entity_id") && filters.containsKey("doc_type"))
            return "entity-category-doc-type";
        if (entityResolution.isMatched() && filters.containsKey("entity_id")) return "entity-category";
        if (filters.containsKey("doc_type")) return "category-doc-type";
        return index == 0 ? "intent-route" : "category";
    }

    /**
     * 从 routeFilters 中提取 category 值，兼容两种格式：
     * - 单值："category": "character"  → Set.of("character")
     * - 多值："category": ["character", "weapon"]  → 转 Set
     */
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

    /**
     * 检查实体匹配结果和路由过滤器的 category 是否兼容。
     * 例如 entity 的 category 是 "character"，路由 filter 的 category 也得包含 "character"。
     * 不兼容时后续会以路由 filter 的 category 为限定重新做实体匹配。
     */
    private static boolean isCompatible(EntityResolution entityResolution, Map<String, Object> routeFilters) {
        if (entityResolution == null || !entityResolution.isMatched()) {
            return false;
        }
        String entityCategory = entityResolution.category();
        return entityCategory != null && categoriesOf(routeFilters).contains(entityCategory);
    }
}
