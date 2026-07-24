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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.config.MetadataRetrievalProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Loads the v7 entity catalog lazily so a missing optional resource cannot block application startup. */
@Slf4j
@Service
@RequiredArgsConstructor
public class EntityCatalogService {

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final MetadataRetrievalProperties properties;

    private volatile List<EntityCatalogEntry> catalog;

    public List<EntityCatalogEntry> getCatalog() {
        List<EntityCatalogEntry> snapshot = catalog;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (catalog == null) {
                catalog = loadCatalog();
            }
            return catalog;
        }
    }

    private List<EntityCatalogEntry> loadCatalog() {
        Resource resource = resourceLoader.getResource(properties.getEntityCatalogLocation());
        if (!resource.exists()) {
            log.warn("Metadata entity catalog is unavailable: {}", properties.getEntityCatalogLocation());
            return List.of();
        }
        List<EntityCatalogEntry> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    entries.add(objectMapper.readValue(line, EntityCatalogEntry.class));
                }
            }
            log.info("Loaded metadata entity catalog: entries={}, resource={}", entries.size(), resource.getDescription());
            return List.copyOf(entries);
        } catch (IOException exception) {
            log.warn("Failed to load metadata entity catalog: {}", properties.getEntityCatalogLocation(), exception);
            return List.of();
        }
    }

    public record EntityCatalogEntry(
            @JsonProperty("entity_type")
            String entityType,
            @JsonProperty("entity_id")
            String entityId,
            @JsonProperty("entity_name")
            String entityName,
            List<String> aliases,
            String category,
            @JsonProperty("source_document")
            String sourceDocument,
            @JsonProperty("source_version")
            String sourceVersion
    ) {
    }
}
