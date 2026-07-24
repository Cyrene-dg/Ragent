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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 意图海选预过滤配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.intent.prefilter")
public class IntentPreFilterProperties {

    /**
     * 是否启用两阶段意图识别（海选 + LLM 精判）
     */
    private boolean enabled = true;

    /**
     * 海选输出候选上限，建议区间 [50, 100]
     */
    private int candidateLimit = 80;

    /**
     * 只有叶子节点数超过 LLM 已验证可承载的候选上限时，才执行海选。
     * 未超过上限时，海选无法缩短 Prompt，只会增加额外延迟。
     */
    public boolean shouldUseTwoStage(int leafNodeCount) {
        return enabled && leafNodeCount > candidateLimit;
    }

    private int minCandidateLimit = 50;

    private int maxCandidateLimit = 100;

    private boolean ruleEnabled = true;

    private boolean keywordEnabled = true;

    private boolean vectorEnabled = true;

    /**
     * 是否异步构建意图向量索引（推荐开启，避免首问阻塞）
     */
    private boolean vectorAsyncWarmup = true;

    /**
     * 各路召回 TopK
     */
    private int ruleTopK = 80;
    private int keywordTopK = 80;
    private int vectorTopK = 80;

    /**
     * 各路保底配额
     */
    private int ruleQuota = 20;
    private int keywordQuota = 20;
    private int vectorQuota = 20;

    /**
     * 各路融合权重
     */
    private double ruleWeight = 1.0;
    private double keywordWeight = 0.9;
    private double vectorWeight = 1.1;

    /**
     * 别名映射词最多追加到每个意图的数量
     */
    private int maxAliasTermsPerIntent = 12;

    /**
     * 规则触发词最小长度
     */
    private int minRuleTriggerLength = 2;
}
