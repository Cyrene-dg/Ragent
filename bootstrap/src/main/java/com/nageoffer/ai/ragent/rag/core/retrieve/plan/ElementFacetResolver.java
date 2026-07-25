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

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Extracts an explicit Genshin element facet from a query.
 *
 * <p>Only unambiguous phrases such as "冰系" and "冰元素" are accepted. A bare
 * character like "冰" is deliberately ignored: it occurs in titles and normal
 * prose too often to be a safe hard retrieval filter.</p>
 */
@Component
public class ElementFacetResolver {

    private static final Map<String, String> EXPLICIT_ELEMENT_PHRASES = elementPhrases();

    public Optional<String> resolve(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        return EXPLICIT_ELEMENT_PHRASES.entrySet().stream()
                .filter(entry -> question.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private static Map<String, String> elementPhrases() {
        Map<String, String> phrases = new LinkedHashMap<>();
        add(phrases, "cryo", "冰元素", "冰系", "冰属性");
        add(phrases, "pyro", "火元素", "火系", "火属性");
        add(phrases, "hydro", "水元素", "水系", "水属性");
        add(phrases, "electro", "雷元素", "雷系", "雷属性");
        add(phrases, "anemo", "风元素", "风系", "风属性");
        add(phrases, "geo", "岩元素", "岩系", "岩属性");
        add(phrases, "dendro", "草元素", "草系", "草属性");
        return Map.copyOf(phrases);
    }

    private static void add(Map<String, String> phrases, String element, String... aliases) {
        for (String alias : aliases) {
            phrases.put(alias, element);
        }
    }
}
