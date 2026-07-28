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
 * 从问题中提取“明确写出”的原神元素维度，作为可选元数据过滤条件。
 *
 * 只接受“冰系”“冰元素”这类无歧义短语；单独的“冰”会出现在名称和普通文本中，
 * 因此故意不把它当作硬过滤条件。当前支持冰、火、水、雷、风、岩、草七种元素。
 */
@Component
public class ElementFacetResolver {

    private static final Map<String, String> EXPLICIT_ELEMENT_PHRASES = elementPhrases();

    /** 返回规范化元素值（如 cryo）；未出现明确元素词时为空，绝不强行猜测。 */
    public Optional<String> resolve(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        return EXPLICIT_ELEMENT_PHRASES.entrySet().stream()
                .filter(entry -> question.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    /** 构建“用户写法 -> 索引元数据值”的静态词典；Map 只读，避免运行时被修改。 */
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
