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

package com.nageoffer.ai.ragent.infra.model;

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.infra.enums.ModelProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 模型选择器
 * 负责根据配置和当前需求（如普通对话、深度思考、Embedding等）选择合适的模型候选列表
 */
@Slf4j
@Component
@RequiredArgsConstructor
//负责根据配置、调用场景和模型健康状态，挑出当前可用的模型候选列表。
public class ModelSelector {

    private final AIModelProperties properties;//模型配置总表。
    private final ModelHealthStore healthStore;//模型健康状态记录器。

    //负责为聊天场景挑选候选模型，并根据是否深度思考决定首选模型。
    public List<ModelTarget> selectChatCandidates(Boolean deepThinking) {
        //从配置表中拿出模型
        AIModelProperties.ModelGroup group = properties.getChat();
        if (group == null) {
            return List.of();
        }

        //拿到首选模型id
        String firstChoiceModelId = resolveFirstChoiceModel(group, deepThinking);
        //返回候选表里的第一个模型
        return selectCandidates(group, firstChoiceModelId, deepThinking);
    }
    //负责为向量化（Embedding）场景挑选候选模型。
    public List<ModelTarget> selectEmbeddingCandidates() {
        return selectCandidates(properties.getEmbedding());
    }
    //负责为重排序（Rerank）场景挑选候选模型。
    public List<ModelTarget> selectRerankCandidates() {
        return selectCandidates(properties.getRerank());
    }
    //负责返回默认的 Embedding 模型，也就是候选列表里的第一个。
    public ModelTarget selectDefaultEmbedding() {
        List<ModelTarget> targets = selectEmbeddingCandidates();
        return targets.isEmpty() ? null : targets.get(0);
    }

    /**
     * 根据模式解析首选模型
     * - 深度思考模式：优先使用 deep-thinking-model
     * - 普通模式：使用 default-model
     */
    //负责根据是否开启深度思考模式，决定聊天场景的首选模型 ID。
    private String resolveFirstChoiceModel(AIModelProperties.ModelGroup group, Boolean deepThinking) {
        //根据是否启动深度思考返回模型id
        if (Boolean.TRUE.equals(deepThinking)) {
            String deepModel = group.getDeepThinkingModel();
            if (StrUtil.isNotBlank(deepModel)) {
                return deepModel;
            }
        }
        return group.getDefaultModel();
    }
    //负责按默认模型规则，为某个模型组生成候选列表。
    private List<ModelTarget> selectCandidates(AIModelProperties.ModelGroup group) {
        if (group == null) {
            return List.of();
        }
        return selectCandidates(group, group.getDefaultModel(), null);
    }
    //负责按首选模型和思考模式过滤并生成最终候选列表(重载)，这个负责更复杂的场景，指定深度思考等等
    private List<ModelTarget> selectCandidates(AIModelProperties.ModelGroup group, String firstChoiceModelId, Boolean deepThinking) {
        //没有候选模型列表返回一个空列表
        if (group == null || group.getCandidates() == null) {
            return List.of();
        }

        //准备排序好的模型列表
        List<AIModelProperties.ModelCandidate> orderedCandidates =
                prepareOrderedCandidates(group.getCandidates(), firstChoiceModelId, deepThinking);

        //将上面的额候选列表构造成真正的可用列表
        return buildAvailableTargets(orderedCandidates);
    }

    /**
     * 准备排序后的候选模型列表
     */
    //负责过滤不可用候选、按优先级排序，并把首选模型提到最前面。
    private List<AIModelProperties.ModelCandidate> prepareOrderedCandidates(
            //原始列表，首选模型，深度思考
            List<AIModelProperties.ModelCandidate> candidates,
            String firstChoiceModelId,
            Boolean deepThinking) {
        List<AIModelProperties.ModelCandidate> enabled = candidates.stream()
                //去掉空对象和被禁用的模型
                .filter(c -> c != null && !Boolean.FALSE.equals(c.getEnabled()))
                //处理深度思考模式
                .filter(c -> !Boolean.TRUE.equals(deepThinking) || Boolean.TRUE.equals(c.getSupportsThinking()))
                //排序，优先级明确的模型优先
                .sorted(Comparator
                        .comparing(AIModelProperties.ModelCandidate::getPriority,
                                Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(AIModelProperties.ModelCandidate::getId,
                                Comparator.nullsLast(String::compareTo)))
                //构造好候选列表
                .collect(Collectors.toCollection(ArrayList::new));

        //如果深度思考模式情况下没有可用模型
        if (Boolean.TRUE.equals(deepThinking) && enabled.isEmpty()) {
            log.warn("深度思考模式没有可用候选模型");
            return enabled;
        }

        //再次人为把首选模型排第一位
        promoteFirstChoiceModel(enabled, firstChoiceModelId);

        //返回处理好的列表
        return enabled;
    }

    //负责把指定的首选模型移动到候选列表第一位。
    private void promoteFirstChoiceModel(
            List<AIModelProperties.ModelCandidate> candidates,
            String firstChoiceModelId) {

        if (StrUtil.isBlank(firstChoiceModelId)) {
            return;
        }

        //找到首选模型
        AIModelProperties.ModelCandidate firstChoice = findCandidate(candidates, firstChoiceModelId);
        //放第一位
        candidates.remove(firstChoice);
        candidates.add(0, firstChoice);
    }

    //负责把候选配置转换成真正可用的 ModelTarget 列表。
    private List<ModelTarget> buildAvailableTargets(
            List<AIModelProperties.ModelCandidate> candidates) {
        //从 properties 里拿到所有 provider 配置，保存到 providers 这个 Map 里。
        Map<String, AIModelProperties.ProviderConfig> providers = properties.getProviders();

        //构建真正的模型目标列表
        return candidates.stream()
                //尝试把一个候选模型配置，构造成一个真正的 ModelTarget,过滤null，收集成一个list返回
                .map(candidate -> buildModelTarget(candidate, providers))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    //负责检查模型是否熔断、provider 是否有配置，并构造一个可用的
    private ModelTarget buildModelTarget(AIModelProperties.ModelCandidate candidate, Map<String, AIModelProperties.ProviderConfig> providers) {
        //解析候选模型的最终model
        String modelId = resolveId(candidate);

        // 检查熔断状态
        if (healthStore.isOpen(modelId)) {
            return null;
        }

        // 验证 provider 配置
        AIModelProperties.ProviderConfig provider = providers.get(candidate.getProvider());
        if (provider == null && !ModelProvider.NOOP.matches(candidate.getProvider())) {
            log.warn("Provider配置缺失: provider={}, modelId={}",
                    candidate.getProvider(), modelId);
            return null;
        }

        //返回这个modeltarget，封装模型唯一标识，原始候选模型位置，对应provider配置
        return new ModelTarget(modelId, candidate, provider);
    }

    //负责按模型 ID 在候选列表里找到对应模型。
    private AIModelProperties.ModelCandidate findCandidate(
            List<AIModelProperties.ModelCandidate> candidates,
            String id) {

        return candidates.stream()
                .filter(c -> id.equals(c.getId()))
                .findFirst()
                .orElse(null);
    }

    //负责给候选模型解析出最终 ID，没有显式 ID 时就用 provider 和 model 拼一个。
    private String resolveId(AIModelProperties.ModelCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        if (StrUtil.isNotBlank(candidate.getId())) {
            return candidate.getId();
        }
        return String.format("%s::%s",
                Objects.toString(candidate.getProvider(), "unknown"),
                Objects.toString(candidate.getModel(), "unknown"));
    }
}
