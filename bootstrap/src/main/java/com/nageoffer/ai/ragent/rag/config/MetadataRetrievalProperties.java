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
 * v7 元数据检索的开关、资源位置和候选池策略。
 * 这里定义“Plan 是否生成/执行、20/40/80 三个容量口径、路由表和实体目录在哪里”，
 * 不承载任何实际检索逻辑。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.metadata-routing")
public class MetadataRetrievalProperties {

    /** 是否生成 v7 RetrievalPlan；关闭后完全不进入元数据路由流程。 */
    private boolean enabled = true;

    /** 是否让 ES/Milvus 真正执行生成的 Plan；关闭后仍可记录 Plan，但检索走旧链路。 */
    private boolean executionEnabled = true;

    /**
     * Adds an explicit character element (for example {@code cryo}) to a v7 retrieval plan.
     * Kept configurable so offline evaluation can compare the same corpus with and without
     * this one retrieval constraint.
     */
    private boolean elementFacetEnabled = true;

    /**
     * Enables the local-only metadata evaluation endpoint.  It is false by default so
     * normal deployments do not expose raw retrieval candidates.
     */
    private boolean evaluationEnabled = false;

    /** Declarative leaf-intent to metadata route mapping. */
    private String intentRoutesLocation = "file:resources/genshin_seed_v7/config/intent_routes.json";

    /** Canonical entities and aliases derived from the v7 chunk artifacts. */
    private String entityCatalogLocation = "file:resources/genshin_seed_v7/config/entity_catalog.jsonl";

    /** Search scope used only when a v7 catalog entity is matched but no leaf intent is returned. */
    private String entityFallbackKbId = "2080272025011851264";

    /** Milvus collection paired with {@link #entityFallbackKbId}. */
    private String entityFallbackCollectionName = "genshinroutingv7";

    /** 单个意图计划在继续放宽普通过滤层前，希望累计拿到的去重候选数。 */
    private int targetCandidateCount = 20;

    /** 单个检索通道（Milvus 或 ES）执行一份 Plan 时的候选池上限。 */
    private int maxCandidatePool = 40;

    /**
     * 跨通道合并、去重后进入 Rerank 前的安全上限；超过才启用 RRF 融合并截断。
     * 它不同于 maxCandidatePool：前者管“ES + Milvus 合并后”，后者管“单通道 Plan 内”。
     */
    private int maxRerankCandidates = 80;

    /** Standard RRF rank constant used only when the rerank safety ceiling is exceeded. */
    private int rrfRankConstant = 60;
}
