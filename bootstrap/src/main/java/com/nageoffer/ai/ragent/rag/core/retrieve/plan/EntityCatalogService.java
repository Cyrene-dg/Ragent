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

package com.nageoffer.ai.ragent.rag.core.retrieve.plan; // 检索计划子包

import com.fasterxml.jackson.annotation.JsonProperty; // JSON 字段名映射（Java 用驼峰，JSON 用下划线）
import com.fasterxml.jackson.databind.ObjectMapper;    // Jackson JSON 解析器
import com.nageoffer.ai.ragent.rag.config.MetadataRetrievalProperties; // 实体目录配置（路径等）
import com.nageoffer.ai.ragent.rag.config.KnowledgeBaseRoutingProperties;
import com.nageoffer.ai.ragent.rag.core.routing.KnowledgeBaseProfileRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader; // Spring 资源加载器，支持 classpath:/ 等前缀
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets; // UTF-8 编码
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v7 实体目录加载服务。
 *
 * 职责：从配置的目录文件中（JSONL 格式，一行一个实体）加载所有已知实体的列表。
 * 这是一个纯数据加载层，不做匹配逻辑——匹配由 {@link EntityResolver} 负责。
 *
 * 设计要点：懒加载 + double-checked locking，避免可选配文件缺失导致应用启动失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class EntityCatalogService {

    private final ObjectMapper objectMapper;         // Jackson 解析器，反序列化 JSON 行
    private final ResourceLoader resourceLoader;     // Spring 资源加载器
    private final MetadataRetrievalProperties properties; // 配置（目录文件路径）

    /**
     * volatile 保证多线程可见性：
     * 线程 A 写完 catalog 后，线程 B 能立刻看到，不会读到 null 或未初始化状态。
     * 用 List<EntityCatalogEntry> 而不是原始类型，因为 catalog 一旦加载完成内容不变。
     */
    private final KnowledgeBaseProfileRegistry profileRegistry;
    private volatile List<EntityCatalogEntry> catalog;
    private final Map<String, List<EntityCatalogEntry>> catalogByKbId = new ConcurrentHashMap<>();

    /** 保留给旧测试和单知识库调用方的兼容构造器。 */
    public EntityCatalogService(ObjectMapper objectMapper,
                                ResourceLoader resourceLoader,
                                MetadataRetrievalProperties properties) {
        this(objectMapper, resourceLoader, properties,
                new KnowledgeBaseProfileRegistry(new KnowledgeBaseRoutingProperties()));
    }

    /**
     * 获取实体目录列表（懒加载 + double-checked locking）。
     * 多次调用只加载一次，后续直接返回已缓存的列表。
     */
    public List<EntityCatalogEntry> getCatalog() {
        // 快速路径：已加载过，直接返回（volatile 读，极快，无锁）
        List<EntityCatalogEntry> snapshot = catalog;
        if (snapshot != null) {
            return snapshot;
        }
        // 慢路径：首次调用，需要加载（加锁防止多线程同时加载）
        synchronized (this) {
            // double-check：拿到锁后再次检查，防止另一个线程刚加载完
            if (catalog == null) {
                catalog = loadCatalog(); // 真正从文件加载
            }
            return catalog;
        }
    }

    /** Load the catalog of an already routed knowledge base. */
    public List<EntityCatalogEntry> getCatalog(String kbId) {
        return profileRegistry.findByKbId(kbId)
                .map(profile -> catalogByKbId.computeIfAbsent(profile.getKbId(),
                        ignored -> loadCatalog(profile.getEntityCatalogLocation())))
                .orElseGet(this::getCatalog);
    }

    /**
     * 从 JSONL 文件加载实体目录。
     * JSONL 格式：每行一个 JSON 对象，字段如：
     * {"entity_type":"character","entity_id":"10000002","entity_name":"神里绫华","aliases":["神里绫华","绫华"],"category":"character"}
     *
     * 容错：文件不存在或解析失败不抛异常，返回空列表，让上层降级为不按实体过滤。
     */
    private List<EntityCatalogEntry> loadCatalog() {
        return loadCatalog(properties.getEntityCatalogLocation());
    }

    private List<EntityCatalogEntry> loadCatalog(String location) {
        // Spring ResourceLoader 支持 classpath:、file: 等多种前缀
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            log.warn("Metadata entity catalog is unavailable: {}", location);
            return List.of();
        }

        List<EntityCatalogEntry> entries = new ArrayList<>();
        // try-with-resources 自动关闭流
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            // 逐行读取，每行解析一个实体对象
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    // Jackson 反序列化：JSON 行 -> EntityCatalogEntry 对象
                    entries.add(objectMapper.readValue(line, EntityCatalogEntry.class));
                }
            }
            log.info("Loaded metadata entity catalog: entries={}, resource={}", entries.size(), resource.getDescription());
            // 不可变拷贝，防止外部意外修改
            return List.copyOf(entries);
        } catch (IOException exception) {
            log.warn("Failed to load metadata entity catalog: {}", location, exception);
            return List.of(); // 加载失败不阻服务
        }
    }

    /**
     * 实体目录条目：一个实体在目录中的完整描述。
     *
     * @JsonProperty 将 Java 驼峰字段（entityType）映射到 JSON 下划线键（entity_type）。
     * 例如 JSON {"entity_type":"character"} 自动映射到 entityType = "character"。
     */
    public record EntityCatalogEntry(
            @JsonProperty("entity_type")
            String entityType,          // 实体类型：character（角色）/ weapon（武器）/ achievement（成就）等

            @JsonProperty("entity_id")
            String entityId,            // 唯一标识，如角色 ID "10000002"，用于精准过滤

            @JsonProperty("entity_name")
            String entityName,          // 实体标准名，如 "神里绫华"

            List<String> aliases,       // 别名列表；EntityResolver 用标准化后的包含匹配，不是编辑距离式模糊匹配

            String category,            // 所属大类，如 "character"，可用于辅助 route filter

            @JsonProperty("source_document")
            String sourceDocument,      // 来源文档名，审计用途

            @JsonProperty("source_version")
            String sourceVersion        // 来源文档版本号，审计用途
    ) {
    }
}
