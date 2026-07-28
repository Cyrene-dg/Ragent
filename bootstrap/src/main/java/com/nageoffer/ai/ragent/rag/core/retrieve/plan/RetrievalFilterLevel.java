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

import java.util.LinkedHashMap; // 用于构造 metadataFilters 的防御性拷贝
import java.util.Map;            // 元数据过滤的键值对

/**
 * 分层渐进检索中，某一层的检索策略定义。
 * 每层有不同的过滤条件，从严格（精确匹配）到宽松（少过滤/不过滤），
 * 执行器从严格到宽松逐层执行：ANY_HIT 层命中即停，TARGET_REACHED 层会继续补足目标候选数。
 */
public record RetrievalFilterLevel(
        int order,                        // 执行顺序号：1, 2, 3... 数字越小越先执行（严格层）
        String name,                      // 本层名称，如 "exact_match", "relaxed", "no_filter"，用于日志
        Map<String, Object> metadataFilters, // 本层的元数据过滤条件，如 {category: "character", entity_id: "10000002"}
        StopCondition stopCondition       // 停止条件：满足什么情况就停不再往下层走
) {

    /**
     * 停止策略枚举
     */
    public enum StopCondition {
        TARGET_REACHED, // 命中足够目标条数就停（默认策略，通用层用这个）
        ANY_HIT         // 只要搜出任意结果就立刻停（带 entity_id 或明确 element 的精确范围用这个）
    }

    // 紧凑构造器：防御性拷贝 + 默认值处理
    public RetrievalFilterLevel {
        // 防御性不可变拷贝；过滤字段的迭代顺序不影响检索语义
        metadataFilters = Map.copyOf(new LinkedHashMap<>(metadataFilters));
        // 如果没显式指定 stopCondition，默认用 TARGET_REACHED
        stopCondition = stopCondition == null ? StopCondition.TARGET_REACHED : stopCondition;
    }

    /**
     * 两参便捷构造器：不传 stopCondition 时，自动判断。
     * 规则：如果 filter 里包含 entity_id（精确匹配到实体），用 ANY_HIT 快速停；
     * 否则用 TARGET_REACHED 等凑够目标数。
     */
    public RetrievalFilterLevel(int order, String name, Map<String, Object> metadataFilters) {
        this(order, name, metadataFilters,
                metadataFilters.containsKey("entity_id") ? StopCondition.ANY_HIT : StopCondition.TARGET_REACHED);
    }
}
