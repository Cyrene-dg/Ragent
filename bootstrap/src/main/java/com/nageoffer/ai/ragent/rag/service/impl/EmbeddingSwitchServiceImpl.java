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

package com.nageoffer.ai.ragent.rag.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.model.EmbeddingModelPreferenceProvider;
import com.nageoffer.ai.ragent.rag.controller.vo.EmbeddingSwitchVO;
import com.nageoffer.ai.ragent.rag.dao.entity.RuntimeConfigDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.RuntimeConfigMapper;
import com.nageoffer.ai.ragent.rag.service.EmbeddingSwitchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Embedding 运行时切换服务。
 * <p>
 * 通过持久化 runtime config 保存“当前首选模型ID”，并实时影响模型选择器。
 */
@Service
@RequiredArgsConstructor
public class EmbeddingSwitchServiceImpl implements EmbeddingSwitchService, EmbeddingModelPreferenceProvider {

    private static final String EMBEDDING_PREFERRED_MODEL_KEY = "embedding.preferred-model-id";

    private final AIModelProperties aiModelProperties;
    private final RuntimeConfigMapper runtimeConfigMapper;

    @Override
    public EmbeddingSwitchVO getSwitchInfo() {
        AIModelProperties.ModelCandidate active = resolveActiveCandidate();
        return buildView(active);
    }

    @Override
    public EmbeddingSwitchVO switchProvider(String provider) {
        if (StrUtil.isBlank(provider)) {
            throw new ClientException("provider 不能为空");
        }
        AIModelProperties.ModelCandidate candidate = resolveProviderCandidate(provider.trim());
        upsertPreferredModel(candidate.getId());
        return buildView(candidate);
    }

    @Override
    public String preferredEmbeddingModelId() {
        RuntimeConfigDO config = runtimeConfigMapper.selectOne(
                Wrappers.lambdaQuery(RuntimeConfigDO.class)
                        .eq(RuntimeConfigDO::getConfigKey, EMBEDDING_PREFERRED_MODEL_KEY)
                        .last("LIMIT 1")
        );
        return config == null ? null : StrUtil.trimToNull(config.getConfigValue());
    }

    private EmbeddingSwitchVO buildView(AIModelProperties.ModelCandidate active) {
        List<EmbeddingSwitchVO.SwitchableProvider> providers = buildProviderList();
        return EmbeddingSwitchVO.builder()
                .activeProvider(active == null ? null : active.getProvider())
                .activeModelId(active == null ? null : active.getId())
                .activeModel(active == null ? null : active.getModel())
                .activeDimension(active == null ? null : active.getDimension())
                .providers(providers)
                .build();
    }

    private AIModelProperties.ModelCandidate resolveActiveCandidate() {
        String preferredModelId = preferredEmbeddingModelId();
        if (StrUtil.isNotBlank(preferredModelId)) {
            AIModelProperties.ModelCandidate preferred = findCandidateById(preferredModelId);
            if (preferred != null) {
                return preferred;
            }
        }
        String defaultModel = aiModelProperties.getEmbedding() == null ? null : aiModelProperties.getEmbedding().getDefaultModel();
        AIModelProperties.ModelCandidate defaultCandidate = findCandidateById(defaultModel);
        if (defaultCandidate != null) {
            return defaultCandidate;
        }
        List<AIModelProperties.ModelCandidate> candidates = aiModelProperties.getEmbedding() == null
                ? List.of()
                : aiModelProperties.getEmbedding().getCandidates();
        return sortCandidates(candidates).stream().findFirst().orElse(null);
    }

    private AIModelProperties.ModelCandidate resolveProviderCandidate(String provider) {
        List<AIModelProperties.ModelCandidate> candidates = aiModelProperties.getEmbedding() == null
                ? List.of()
                : aiModelProperties.getEmbedding().getCandidates();
        return sortCandidates(candidates).stream()
                .filter(each -> provider.equalsIgnoreCase(each.getProvider()))
                .findFirst()
                .orElseThrow(() -> new ClientException("未找到可用的 embedding provider: " + provider));
    }

    private List<EmbeddingSwitchVO.SwitchableProvider> buildProviderList() {
        List<AIModelProperties.ModelCandidate> candidates = aiModelProperties.getEmbedding() == null
                ? List.of()
                : aiModelProperties.getEmbedding().getCandidates();
        return sortCandidates(candidates).stream()
                .collect(java.util.stream.Collectors.toMap(
                        c -> c.getProvider().toLowerCase(),
                        c -> c,
                        (left, right) -> left
                ))
                .values()
                .stream()
                .map(candidate -> EmbeddingSwitchVO.SwitchableProvider.builder()
                        .provider(candidate.getProvider())
                        .modelId(candidate.getId())
                        .model(candidate.getModel())
                        .dimension(candidate.getDimension())
                        .build())
                .sorted(Comparator.comparing(EmbeddingSwitchVO.SwitchableProvider::getProvider, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private List<AIModelProperties.ModelCandidate> sortCandidates(List<AIModelProperties.ModelCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return candidates.stream()
                .filter(Objects::nonNull)
                .filter(each -> !Boolean.FALSE.equals(each.getEnabled()))
                .filter(each -> StrUtil.isNotBlank(each.getId()) && StrUtil.isNotBlank(each.getProvider()))
                .sorted(Comparator
                        .comparing(AIModelProperties.ModelCandidate::getPriority, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(AIModelProperties.ModelCandidate::getId, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    private AIModelProperties.ModelCandidate findCandidateById(String modelId) {
        if (StrUtil.isBlank(modelId) || aiModelProperties.getEmbedding() == null) {
            return null;
        }
        return Optional.ofNullable(aiModelProperties.getEmbedding().getCandidates())
                .orElse(List.of())
                .stream()
                .filter(Objects::nonNull)
                .filter(each -> !Boolean.FALSE.equals(each.getEnabled()))
                .filter(each -> modelId.equals(each.getId()))
                .findFirst()
                .orElse(null);
    }

    private void upsertPreferredModel(String modelId) {
        RuntimeConfigDO existing = runtimeConfigMapper.selectOne(
                Wrappers.lambdaQuery(RuntimeConfigDO.class)
                        .eq(RuntimeConfigDO::getConfigKey, EMBEDDING_PREFERRED_MODEL_KEY)
                        .last("LIMIT 1")
        );
        if (existing == null) {
            RuntimeConfigDO create = RuntimeConfigDO.builder()
                    .configKey(EMBEDDING_PREFERRED_MODEL_KEY)
                    .configValue(modelId)
                    .build();
            runtimeConfigMapper.insert(create);
            return;
        }
        existing.setConfigValue(modelId);
        runtimeConfigMapper.updateById(existing);
    }
}
