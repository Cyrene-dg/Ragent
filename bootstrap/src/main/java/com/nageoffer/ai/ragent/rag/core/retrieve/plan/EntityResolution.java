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

import java.util.List;

/**
 * A safe entity-resolution result.  Only {@link EntityResolutionStatus#MATCHED}
 * may contribute entity metadata to a strict retrieval filter.
 */
public record EntityResolution(
        EntityResolutionStatus status,
        String matchedText,
        String entityType,
        String entityId,
        String entityName,
        String category,
        List<String> candidateEntityIds
) {

    public static EntityResolution notFound() {
        return new EntityResolution(EntityResolutionStatus.NOT_FOUND, null, null, null, null, null, List.of());
    }

    public static EntityResolution ambiguous(String matchedText, List<String> candidateEntityIds) {
        return new EntityResolution(EntityResolutionStatus.AMBIGUOUS, matchedText, null, null, null, null,
                List.copyOf(candidateEntityIds));
    }

    public static EntityResolution matched(String matchedText, String entityType, String entityId, String entityName) {
        return matched(matchedText, entityType, entityId, entityName, null);
    }

    public static EntityResolution matched(String matchedText, String entityType, String entityId, String entityName,
                                           String category) {
        return new EntityResolution(EntityResolutionStatus.MATCHED, matchedText, entityType, entityId, entityName, category,
                List.of(entityId));
    }

    public boolean isMatched() {
        return status == EntityResolutionStatus.MATCHED;
    }
}
