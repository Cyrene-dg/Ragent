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

package com.nageoffer.ai.ragent.rag.core.retrieve.plan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.config.MetadataRetrievalProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Loads the declarative v7 leaf-intent to metadata-filter mapping. */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentRouteRuleService {

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final MetadataRetrievalProperties properties;

    private volatile Map<String, IntentRouteRule> routeByIntentCode;

    public Optional<IntentRouteRule> findByIntentCode(String intentCode) {
        return Optional.ofNullable(getRouteByIntentCode().get(intentCode));
    }

    private Map<String, IntentRouteRule> getRouteByIntentCode() {
        Map<String, IntentRouteRule> snapshot = routeByIntentCode;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (routeByIntentCode == null) {
                routeByIntentCode = loadRoutes();
            }
            return routeByIntentCode;
        }
    }

    private Map<String, IntentRouteRule> loadRoutes() {
        Resource resource = resourceLoader.getResource(properties.getIntentRoutesLocation());
        if (!resource.exists()) {
            log.warn("Metadata intent route rules are unavailable: {}", properties.getIntentRoutesLocation());
            return Map.of();
        }
        try (var inputStream = resource.getInputStream()) {
            List<IntentRouteRule> rules = objectMapper.readValue(inputStream, new TypeReference<>() {
            });
            Map<String, IntentRouteRule> routes = new LinkedHashMap<>();
            for (IntentRouteRule rule : rules) {
                if (rule.intentCode() != null && rule.metadataFilters() != null) {
                    routes.put(rule.intentCode(), rule.copy());
                }
            }
            log.info("Loaded metadata intent route rules: entries={}, resource={}", routes.size(), resource.getDescription());
            return Map.copyOf(routes);
        } catch (IOException exception) {
            log.warn("Failed to load metadata intent route rules: {}", properties.getIntentRoutesLocation(), exception);
            return Map.of();
        }
    }

    public record IntentRouteRule(String intentCode, Map<String, Object> metadataFilters) {

        private IntentRouteRule copy() {
            return new IntentRouteRule(intentCode, Map.copyOf(new LinkedHashMap<>(metadataFilters)));
        }
    }
}
