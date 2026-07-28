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

/**
 * 意图路由规则加载器：把叶子意图编码映射为声明式元数据条件。
 *
 * 例如“角色技能”叶子可映射为 {category=character, doc_type=character_skill}。
 * 它只读取 {@code intent_routes.json} 并提供查询，不参与 LLM 分类，也不直接执行检索。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentRouteRuleService {

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final MetadataRetrievalProperties properties;

    private volatile Map<String, IntentRouteRule> routeByIntentCode;

    /** 按 LLM 最终选择的叶子 intentCode 查询对应的元数据路由规则。 */
    public Optional<IntentRouteRule> findByIntentCode(String intentCode) {
        return Optional.ofNullable(getRouteByIntentCode().get(intentCode));
    }

    /** 懒加载 + double-check：规则文件只读一次，后续请求直接使用不可变缓存。 */
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

    /**
     * 读取 JSON 数组，转换为 intentCode -> IntentRouteRule 的查找表。
     * 文件缺失或格式读取失败时返回空表，上层不会构造 v7 Plan，而不是让应用启动失败。
     */
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

        /** 对 filter Map 做防御性不可变拷贝，避免调用方意外污染共享规则缓存。 */
        private IntentRouteRule copy() {
            return new IntentRouteRule(intentCode, Map.copyOf(new LinkedHashMap<>(metadataFilters)));
        }
    }
}
