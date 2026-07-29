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

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap; // 按 entityKey 聚合并去重候选实体
import java.util.List;
import java.util.Locale;        // 转小写时指定英文 locale，避免土语问题
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基于规则（非 LLM）的实体识别器。
 *
 * 核心思路：拿用户问题文本，去实体目录中逐条比对每个实体配置的 aliases，
 * 看是否出现在问题里。用最长匹配策略决定是否精确命中。
 *
 * 这是一个确定性算法，不调 LLM，速度快且结果稳定。
 */
@Service
@RequiredArgsConstructor
public class EntityResolver {

    private final EntityCatalogService entityCatalogService; // 实体目录加载服务

    /**
     * 无障碍版：不做 category 过滤，对所有实体进行匹配。
     * 适用于意图分类前阶段，此时还不知道该意图对应哪些 category。
     */
    public EntityResolution resolveAny(String question) {
        return resolve(question, List.of()); // 空列表 = 不做 category 过滤
    }

    public EntityResolution resolveAny(String question, String kbId) {
        return resolve(question, List.of(), kbId);
    }

    /**
     * 核心匹配方法。
     *
     * 流程：
     * 1. 把用户问题和实体别名都做 normalize（去空格、转小写、去空白）
     * 2. 遍历目录中每个实体，看其别名是否在问题文本中出现
     * 3. 同一实体取最长匹配的别名（"绫华" vs "神里绫华" -> 后者胜出）
     * 4. 全局取匹配长度最长的那些候选人
     * 5. 如果只有一个最长匹配 -> MATCHED；多个 -> AMBIGUOUS；无匹配 -> NOT_FOUND
     *
     * @param question          用户问题
     * @param allowedCategories 限定只在这些 category 中搜索，空列表 = 全量匹配
     */
    public EntityResolution resolve(String question, Collection<String> allowedCategories) {
        return resolve(question, allowedCategories, null);
    }

    public EntityResolution resolve(String question, Collection<String> allowedCategories, String kbId) {
        // 预处理：去空格、转小写（"神里 绫华" -> "神里绫华"）
        String normalizedQuestion = normalize(question);
        if (normalizedQuestion.isBlank()) {
            return EntityResolution.notFound();
        }

        // 把 allowedCategories 也 normalize，用于后面 category 过滤
        Set<String> normalizedCategories = allowedCategories == null ? Set.of() : allowedCategories.stream()
                .map(EntityResolver::normalize)
                .filter(category -> !category.isBlank())
                .collect(Collectors.toUnmodifiableSet());

        // 按 entityKey（entityType:entityId）分组，同一实体只保留最长匹配的别名
        Map<String, Candidate> bestByEntity = new LinkedHashMap<>();

        // 遍历实体目录中每一条
        for (EntityCatalogService.EntityCatalogEntry entry : entityCatalogService.getCatalog(kbId)) {
            // 如果指定了 category 过滤，跳过不匹配的实体
            if (!normalizedCategories.isEmpty() && !normalizedCategories.contains(normalize(entry.category()))) {
                continue;
            }

            // 遍历该实体的所有别名（entityName + aliases）
            List<String> aliases = entry.aliases() == null ? List.of() : entry.aliases();
            for (String alias : aliases) {
                String normalizedAlias = normalize(alias);
                // 跳过太短的别名（"泵" 这种单字容易误匹配）+ 问题里没有直接 continue
                if (normalizedAlias.length() < 2 || !normalizedQuestion.contains(normalizedAlias)) {
                    continue;
                }

                // 命中！记录候选结果
                String entityKey = entry.entityType() + ":" + entry.entityId();
                Candidate candidate = new Candidate(entry, alias, normalizedAlias.length());

                // 同一 entityKey：如果已有缓存，只保留别名更长的那个
                // 例如 "绫华" 和 "神里绫华" 都匹配到同一实体，保留后者（信息更具体）
                bestByEntity.merge(entityKey, candidate,
                        (left, right) -> left.aliasLength() >= right.aliasLength() ? left : right);
            }
        }

        // 没有任何别名匹配 -> 未找到
        if (bestByEntity.isEmpty()) {
            return EntityResolution.notFound();
        }

        // 找出全局最长的别名匹配长度
        int longestMatch = bestByEntity.values().stream()
                .mapToInt(Candidate::aliasLength)
                .max()
                .orElse(0);

        // 过滤：只保留别名长度等于全局最长长度的候选人
        List<Candidate> strongestCandidates = bestByEntity.values().stream()
                .filter(candidate -> candidate.aliasLength() == longestMatch)
                .sorted(Comparator.comparing(candidate -> candidate.entry().entityId())) // 按 entityId 排序（稳定输出）
                .toList();

        // 多个候选人的别名一样长且都最长 -> 并列命中，无法确定具体实体
        if (strongestCandidates.size() > 1) {
            return EntityResolution.ambiguous(
                    strongestCandidates.get(0).matchedAlias(),
                    strongestCandidates.stream()
                            .map(candidate -> candidate.entry().entityType() + ":" + candidate.entry().entityId())
                            .toList()
            );
        }

        // 唯一的最长匹配 -> 精确命中
        Candidate candidate = strongestCandidates.get(0);
        EntityCatalogService.EntityCatalogEntry entry = candidate.entry();
        return EntityResolution.matched(
                candidate.matchedAlias(),  // 匹配到的文本
                entry.entityType(),         // 实体类型
                entry.entityId(),           // 实体 ID
                entry.entityName(),         // 实体名
                entry.category()            // 实体分类
        );
    }

    /**
     * 文本标准化：去掉常见空白字符（空格/换行/制表符）+ 统一转小写。
     * 确保用户问 "神里 绫华" 和别名 "神里绫华" 能匹配上。
     *
     * @param value 原始文本
     * @return 标准化后的文本（纯小写、无空白字符）
     */
    private static String normalize(String value) {
        return value == null
                ? ""
                : value.replaceAll("\\s+", "") // 去掉所有空白字符（含全角空格）
                        .trim()
                        .toLowerCase(Locale.ROOT); // Locale.ROOT 避免土耳其语等特殊 locale 下 "I" 转小写问题
    }

    /**
     * 候选匹配记录：在一次别名匹配中命中的实体和匹配信息。
     */
    private record Candidate(
            EntityCatalogService.EntityCatalogEntry entry, // 命中哪个实体
            String matchedAlias,                             // 用哪个别名命中的
            int aliasLength                                  // 该别名标准化后的长度（用于最长匹配策略）
    ) {
    }
}
