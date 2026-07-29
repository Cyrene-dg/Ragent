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

package com.nageoffer.ai.ragent.rag.core.routing;

import com.nageoffer.ai.ragent.rag.config.KnowledgeBaseRoutingProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/** Read-only lookup for corpus-specific catalogs and metadata route tables. */
@Component
@RequiredArgsConstructor
public class KnowledgeBaseProfileRegistry {

    private final KnowledgeBaseRoutingProperties properties;

    public List<KnowledgeBaseRoutingProperties.Profile> enabledProfiles() {
        return properties.getProfiles() == null ? List.of() : properties.getProfiles().stream()
                .filter(profile -> StringUtils.hasText(profile.getKbId()))
                .toList();
    }

    public Optional<KnowledgeBaseRoutingProperties.Profile> findByKbId(String kbId) {
        if (!StringUtils.hasText(kbId)) {
            return Optional.empty();
        }
        return enabledProfiles().stream().filter(profile -> kbId.equals(profile.getKbId())).findFirst();
    }

    public boolean isRoutingEnabled() {
        return properties.isEnabled() && !enabledProfiles().isEmpty();
    }
}
