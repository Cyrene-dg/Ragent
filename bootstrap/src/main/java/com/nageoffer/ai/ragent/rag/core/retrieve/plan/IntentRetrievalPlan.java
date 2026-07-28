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

import java.util.LinkedHashMap; // 用于构造 routeFilters 的防御性拷贝
import java.util.List;           // 分层策略列表
import java.util.Map;            // 路由过滤的 key-value 键值对

/**
 * 单个意图节点（叶子）的检索子计划。
 * 由意图分类结果 + 实体解析结果综合生成，决定该意图下的检索策略。
 */
public record IntentRetrievalPlan( // Java record：不可变数据载体
        String intentCode,                // 意图编码，例如 "genshin_v7_character_skill"
        String intentName,                // 意图中文名称，例如 "角色技能与命座"
        double intentScore,               // 意图分类置信度分数（0.0 ~ 1.0），用于多个意图计划的执行优先级排序
        String kbId,                      // 知识库 ID，指定在哪个知识库中检索
        String collectionName,            // Milvus 集合名称，对应该知识库的向量存储
        Map<String, Object> routeFilters, // 路由过滤条件，如 {category: "character", doc_type: "character_skill"}
        EntityResolution entityResolution,// 实体解析结果：是否命中实体、命中状态、匹配到的实体列表
        List<RetrievalFilterLevel> filterLevels // 分层检索策略：从严格到宽松逐层放宽
) {

    // 紧凑构造器：自动赋值前拦截，做防御性拷贝
    public IntentRetrievalPlan {
        // 防御性不可变拷贝；过滤字段的先后不代表执行优先级，优先级由 filterLevels 列表决定
        routeFilters = Map.copyOf(new LinkedHashMap<>(routeFilters));
        // 不可变拷贝，防止外部修改分层策略
        filterLevels = List.copyOf(filterLevels);
    }

    /**
     * 便捷构造器：当不需要指定 kbId 和 collectionName 时使用（默认为 null）。
     * 适用于执行时由外部上下文动态决定知识库的场景。
     */
    public IntentRetrievalPlan(String intentCode, String intentName, double intentScore,
                                Map<String, Object> routeFilters, EntityResolution entityResolution,
                                List<RetrievalFilterLevel> filterLevels) {
        // 委托全参构造器，kbId 和 collectionName 传 null
        this(intentCode, intentName, intentScore, null, null, routeFilters, entityResolution, filterLevels);
    }
}
