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

package com.nageoffer.ai.ragent.rag.core.retrieve.plan; // 属于检索计划（plan）子包，存放分层检索相关的数据结构和逻辑

import java.util.List; // 用于存储多个意图子计划

/**
 * 只读的检索决策对象，在真正执行检索之前就已构造完成。
 * 各个检索通道（Milvus/ES）只需消费这个 plan，按计划执行，
 * 不需要自己再做路由判断。
 */
public record RetrievalPlan( // Java record：不可变数据载体，编译器自动生成构造器/getter/equals/hashCode/toString
        String question,           // 用户原始问题文本
        int targetCandidateCount,  // 单个意图计划在继续放宽过滤条件前，希望累计获得的候选数
        int maxCandidatePool,      // 单个检索通道执行该 Plan 时的候选池上限；跨通道 RRF 上限由另一配置控制
        List<IntentRetrievalPlan> intentPlans // 每个意图节点各自的检索子计划（按意图拆分）
) {

    // 紧凑构造器：record 自动赋值之前拦截，做防御性处理
    public RetrievalPlan {
        // 不可变拷贝，防止外部后续修改传入的 List 污染 plan 内部状态
        intentPlans = List.copyOf(intentPlans);
    }

    /**
     * 判断整个检索计划是否为空（没有匹配到任何意图，无需执行检索）
     */
    public boolean isEmpty() {
        return intentPlans.isEmpty(); // 无意图子计划 = 空计划，调用方可跳过后续检索链路
    }
}
