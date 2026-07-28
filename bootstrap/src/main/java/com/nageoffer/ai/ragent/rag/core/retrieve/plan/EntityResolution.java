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

import java.util.List; // 候选实体 ID 列表

/**
 * 实体解析的安全结果包装。
 * 只有状态为 MATCHED 的结果才能把实体元数据（entity_id 等）放进严格检索过滤器里。
 *
 * 设计思路：实体解析有三种可能——
 * 1. 没找到（NOT_FOUND）→ 退化为纯文本检索
 * 2. 并列最长别名命中多个实体（AMBIGUOUS）→ 知道候选，但不能确定用哪个
 * 3. 精确命中（MATCHED）→ 锁定 entity_id，第一层直接 ANY_HIT 精确召回
 */
public record EntityResolution(
        EntityResolutionStatus status,     // 解析状态：NOT_FOUND / AMBIGUOUS / MATCHED
        String matchedText,                // 用户问题中命中的别名，如 "绫华"
        String entityType,                 // 实体类型，如 "character"、"weapon"
        String entityId,                   // 精确命中的实体 ID，如 "10000002"
        String entityName,                 // 实体标准名，如 "神里绫华"
        String category,                   // 实体所属大类，如 "character"
        List<String> candidateEntityIds    // 候选实体标识：歧义时是 "type:id"，精确命中时只含 entityId
) {

    /**
     * 快捷工厂方法：没有解析到任何实体。
     * 后续检索降级为不按实体过滤的通用检索。
     */
    public static EntityResolution notFound() {
        return new EntityResolution(EntityResolutionStatus.NOT_FOUND, null, null, null, null, null, List.of());
    }

    /**
     * 快捷工厂方法：匹配到多个候选实体，无法确定具体是哪个。
     * 当前用于日志和诊断；多实体并行检索尚未实现，不能据此构造严格过滤条件。
     */
    public static EntityResolution ambiguous(String matchedText, List<String> candidateEntityIds) {
        return new EntityResolution(EntityResolutionStatus.AMBIGUOUS, matchedText, null, null, null, null,
                List.copyOf(candidateEntityIds)); // 不可变拷贝
    }

    /**
     * 快捷工厂方法：精确匹配到一个实体（不含分类信息）。
     */
    public static EntityResolution matched(String matchedText, String entityType, String entityId, String entityName) {
        return matched(matchedText, entityType, entityId, entityName, null); // category 为 null
    }

    /**
     * 快捷工厂方法：精确匹配到一个实体（含分类信息）。
     * 这是最完整的匹配结果，category 可作为除 entity_id 外的辅助过滤字段。
     */
    public static EntityResolution matched(String matchedText, String entityType, String entityId, String entityName,
                                           String category) {
        return new EntityResolution(EntityResolutionStatus.MATCHED, matchedText, entityType, entityId, entityName, category,
                List.of(entityId)); // 精确匹配时候选列表只含自己
    }

    /**
     * 快速判断是否已精确命中实体。
     * RetrievalPlanService 和检索执行器都依赖这个来决策走精确层还是宽松层。
     */
    public boolean isMatched() {
        return status == EntityResolutionStatus.MATCHED;
    }
}
