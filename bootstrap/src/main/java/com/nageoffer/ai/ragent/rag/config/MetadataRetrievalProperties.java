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
 * Metadata routing resources and candidate-pool policy for the v7 knowledge base.
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.metadata-routing")
public class MetadataRetrievalProperties {

    /** Enable v7 metadata-plan generation. */
    private boolean enabled = true;

    /** Apply the generated plan to Milvus; disabled keeps the legacy retrieval path. */
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

    /** Desired unique candidate count before a later retrieval phase stops relaxing filters. */
    private int targetCandidateCount = 20;

    /** Maximum candidate pool inside one metadata retrieval plan. */
    private int maxCandidatePool = 40;

    /**
     * Safety ceiling after cross-channel de-duplication and before rerank.
     * The limiter is dormant while the candidate count is at or below this value.
     */
    private int maxRerankCandidates = 80;

    /** Standard RRF rank constant used only when the rerank safety ceiling is exceeded. */
    private int rrfRankConstant = 60;
}
