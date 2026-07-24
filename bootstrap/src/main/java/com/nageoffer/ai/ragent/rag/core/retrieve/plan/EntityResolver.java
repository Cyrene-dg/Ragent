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

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Deterministic entity recognition based on canonical names and aliases. */
@Service
@RequiredArgsConstructor
public class EntityResolver {

    private final EntityCatalogService entityCatalogService;

    public EntityResolution resolve(String question, Collection<String> allowedCategories) {
        String normalizedQuestion = normalize(question);
        if (normalizedQuestion.isBlank()) {
            return EntityResolution.notFound();
        }
        Set<String> normalizedCategories = allowedCategories == null ? Set.of() : allowedCategories.stream()
                .map(EntityResolver::normalize)
                .filter(category -> !category.isBlank())
                .collect(Collectors.toUnmodifiableSet());

        Map<String, Candidate> bestByEntity = new LinkedHashMap<>();
        for (EntityCatalogService.EntityCatalogEntry entry : entityCatalogService.getCatalog()) {
            if (!normalizedCategories.isEmpty() && !normalizedCategories.contains(normalize(entry.category()))) {
                continue;
            }
            List<String> aliases = entry.aliases() == null ? List.of() : entry.aliases();
            for (String alias : aliases) {
                String normalizedAlias = normalize(alias);
                if (normalizedAlias.length() < 2 || !normalizedQuestion.contains(normalizedAlias)) {
                    continue;
                }
                String entityKey = entry.entityType() + ":" + entry.entityId();
                Candidate candidate = new Candidate(entry, alias, normalizedAlias.length());
                bestByEntity.merge(entityKey, candidate,
                        (left, right) -> left.aliasLength() >= right.aliasLength() ? left : right);
            }
        }
        if (bestByEntity.isEmpty()) {
            return EntityResolution.notFound();
        }

        int longestMatch = bestByEntity.values().stream()
                .mapToInt(Candidate::aliasLength)
                .max()
                .orElse(0);
        List<Candidate> strongestCandidates = bestByEntity.values().stream()
                .filter(candidate -> candidate.aliasLength() == longestMatch)
                .sorted(Comparator.comparing(candidate -> candidate.entry().entityId()))
                .toList();
        if (strongestCandidates.size() > 1) {
            return EntityResolution.ambiguous(strongestCandidates.get(0).matchedAlias(), strongestCandidates.stream()
                    .map(candidate -> candidate.entry().entityType() + ":" + candidate.entry().entityId())
                    .toList());
        }

        Candidate candidate = strongestCandidates.get(0);
        EntityCatalogService.EntityCatalogEntry entry = candidate.entry();
        return EntityResolution.matched(candidate.matchedAlias(), entry.entityType(), entry.entityId(), entry.entityName());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim().toLowerCase(Locale.ROOT);
    }

    private record Candidate(EntityCatalogService.EntityCatalogEntry entry, String matchedAlias, int aliasLength) {
    }
}
