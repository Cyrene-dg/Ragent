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

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk; // 检索到的知识块

import java.util.List;

/**
 * 渐进检索的停止策略（纯工具类，Milvus 和 ES 执行器共用）。
 *
 * 核心逻辑只有一条：如果当前层的 stopCondition == ANY_HIT 并且确实搜出了结果，
 * 就不再向下一层放宽过滤条件，直接停止。
 *
 * 为什么需要这个？因为分层检索从紧到松，如果 entity_id 层已经搜到东西了，
 * 没必再去跑没有 entity_id 的宽松层——那层范围更大、结果更多、但精度更低。
 */
public final class ProgressivePlanStopPolicy {

    // 私有构造器：禁止实例化（工具类，只有静态方法）
    private ProgressivePlanStopPolicy() {
    }

    /**
     * 判断当前层检索完成后是否应该停止，不再向下一层放宽。
     *
     * 停止条件：当前层的 stopCondition 是 ANY_HIT，且确实搜到了结果（非空）。
     *
     * 不停止的情况：
     *   - stopCondition 是 TARGET_REACHED → 需要凑够目标数量，不能一层就停
     *   - levelChunks 为空 → 这层没搜到东西，必须放宽到下一层
     *
     * @param level       当前层的 filter 定义（含 stopCondition）
     * @param levelChunks 本层搜到的结果列表
     * @return true 停止 / false 继续下一层
     */
    public static boolean shouldStopAfterLevel(RetrievalFilterLevel level, List<RetrievedChunk> levelChunks) {
        return level.stopCondition() == RetrievalFilterLevel.StopCondition.ANY_HIT // 精确层：命中即停
                && levelChunks != null                                              // 防止 NPE
                && !levelChunks.isEmpty();                                         // 确实搜到了东西
    }
}