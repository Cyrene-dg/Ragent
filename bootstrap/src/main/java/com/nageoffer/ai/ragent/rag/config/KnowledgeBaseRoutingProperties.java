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

import java.util.ArrayList;
import java.util.List;

/**
 * Knowledge-base routing is deliberately separate from intent classification.
 * It answers "which corpus?" before the existing intent/entity/retrieval flow
 * answers "which material inside that corpus?".
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.knowledge-base-routing")
public class KnowledgeBaseRoutingProperties {

    private boolean enabled = true;
    private boolean llmFallbackEnabled = true;
    private List<Profile> profiles = new ArrayList<>();

    @Data
    public static class Profile {
        private String id;
        private String kbId;
        private String description;
        private List<String> routeKeywords = new ArrayList<>();

        /** Per-corpus metadata configuration.  Empty values fall back to the legacy v7 values. */
        private String intentRoutesLocation;
        private String entityCatalogLocation;
        private String entityFallbackCollectionName;
        private boolean elementFacetEnabled;
    }
}
