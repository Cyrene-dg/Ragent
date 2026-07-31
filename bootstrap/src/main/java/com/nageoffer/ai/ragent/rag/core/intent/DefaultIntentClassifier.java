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

package com.nageoffer.ai.ragent.rag.core.intent;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.rag.config.IntentPreFilterProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.EntityResolution;
import com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.INTENT_CLASSIFIER_PROMPT_PATH;

/**
 * LLM 树形意图分类器（串行实现）
 * <p>
 * 将所有意图节点一次性发送给 LLM 进行识别打分，适用于意图数量较少的场景
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultIntentClassifier implements IntentClassifier, IntentNodeRegistry {

    private final LLMService llmService;
    private final IntentNodeMapper intentNodeMapper;
    private final PromptTemplateLoader promptTemplateLoader;
    private final IntentTreeCacheManager intentTreeCacheManager;
    private final IntentHybridRecallService intentHybridRecallService;
    private final IntentPreFilterProperties intentPreFilterProperties;

    /**
     * 从 Redis 加载意图树并构建内存结构。
     * 缓存策略：Redis 有就用，没有则从 DB 加载并回写缓存。
     * 输出：叶子节点列表（给 LLM 打分用）+ 全局 id-node 映射（解析 LLM 返回值用）。
     */
    private IntentTreeData loadIntentTreeData() {
        // 1. 优先从 Redis 缓存拿意图树（IntentTreeCacheManager 内部有 Cache-Aside 逻辑）
        List<IntentNode> roots = intentTreeCacheManager.getIntentTreeFromCache();

        // 2. Redis 缓存未命中，退化为从数据库加载并回写缓存
        if (CollUtil.isEmpty(roots)) {
            roots = loadIntentTreeFromDB(); // 从 MySQL 拉平数据并组装成树
            if (!roots.isEmpty()) {
                intentTreeCacheManager.saveIntentTreeToCache(roots); // 写回 Redis 供下次命中
            }
        }

        // 3. 最终也没数据，返回空结构（系统首次启动且 DB 无数据时）
        if (CollUtil.isEmpty(roots)) {
            return new IntentTreeData(List.of(), List.of(), Map.of());
        }

        // 树 -> 展平为所有节点的列表
        List<IntentNode> allNodes = flatten(roots); // DFS 栈遍历展平
        // 所有节点 -> 筛出叶子节点（只有叶子节点才有分类意义）
        List<IntentNode> leafNodes = allNodes.stream()
                .filter(IntentNode::isLeaf)
                .collect(Collectors.toList());
        // 构建 id -> IntentNode 的快速查找表（LLM 返回 id 后映射回实际节点）
        Map<String, IntentNode> id2Node = allNodes.stream()
                .collect(Collectors.toMap(IntentNode::getId, n -> n));

        log.debug("意图树数据加载完成, 总节点数: {}, 叶子节点数: {}", allNodes.size(), leafNodes.size());

        return new IntentTreeData(allNodes, leafNodes, id2Node);
    }

    @Override
    public IntentNode getNodeById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        IntentTreeData data = loadIntentTreeData();
        return data.id2Node.get(id);
    }

    /**
     * 意图树数据结构（临时对象，不持久化）
     */
    private record IntentTreeData(
            List<IntentNode> allNodes,
            List<IntentNode> leafNodes,
            Map<String, IntentNode> id2Node
    ) {
    }

    // 用栈做 DFS 展平树，把所有节点（含非叶子）拉成一个扁平的列表
    private List<IntentNode> flatten(List<IntentNode> roots) {
        List<IntentNode> result = new ArrayList<>();
        Deque<IntentNode> stack = new ArrayDeque<>(roots);
        while (!stack.isEmpty()) {
            IntentNode n = stack.pop();
            result.add(n);
            if (n.getChildren() != null) {
                for (IntentNode child : n.getChildren()) {
                    stack.push(child);
                }
            }
        }
        return result;
    }

    /**
     * 核心意图分类方法（两参数快捷入口，entityHint 为 NOT_FOUND）。
     * 把意图树 + 当前问题交给大模型，让大模型返回候选意图及分数，
     * 再由程序做结构化解析和校验。
     * - 返回结果已按 score 从高到低排序
     */
    @Override
    public List<NodeScore> classifyTargets(String question) {
        return classifyTargets(question, EntityResolution.notFound()); // 无实体命中，用纯文本分类
    }

    /**
     * 核心意图分类方法（带实体提示版本）。
     *
     * 流程：
     * 1. 加载意图树叶子节点
     * 2. 如果叶子太多 -> 用 HybridRecall 粗筛再精判（减少 LLM token 消耗）
     * 3. 构建 prompt（含 entity hint 如实体已命中）-> 调 LLM -> 返回 [{"id":"...","score":0.9}]
     * 4. 解析 JSON -> 校验 id 合法性 -> 封装 NodeScore，按分数降序返回
     */
    @Override
    public List<NodeScore> classifyTargets(String question, EntityResolution entityHint) {
        return classifyTargets(question, entityHint, null);
    }

    @Override
    public List<NodeScore> classifyTargets(String question, EntityResolution entityHint, String kbId) {
        return classifyTargets(question, entityHint, kbId, Set.of());
    }

    @Override
    public List<NodeScore> classifyTargets(String question, EntityResolution entityHint, String kbId,
                                           Set<String> excludedKbIds) {
        // 每次优先从 Redis 缓存读取意图树；节点更新何时生效取决于缓存清理或过期策略
        IntentTreeData data = loadIntentTreeData();
        if (kbId != null && !kbId.isBlank()) {
            List<IntentNode> scopedLeafNodes = data.leafNodes.stream()
                    .filter(node -> kbId.equals(node.getKbId()))
                    .toList();
            if (scopedLeafNodes.isEmpty()) {
                log.warn("No leaf intents found for routed knowledge base: {}", kbId);
                return List.of();
            }
            data = new IntentTreeData(data.allNodes, scopedLeafNodes, data.id2Node);
        } else if (excludedKbIds != null && !excludedKbIds.isEmpty()) {
            List<IntentNode> remainingLeafNodes = data.leafNodes.stream()
                    // Legacy intent leaves may not be bound to a knowledge base. Keep them in the
                    // fallback pool and only exclude leaves that explicitly belong to a managed KB.
                    // Immutable sets reject contains(null), so the null check is also required.
                    .filter(node -> node.getKbId() == null || !excludedKbIds.contains(node.getKbId()))
                    .toList();
            if (remainingLeafNodes.isEmpty()) {
                log.info("No intent leaves remain after excluding out-of-domain managed knowledge bases: {}",
                        excludedKbIds);
                return List.of();
            }
            data = new IntentTreeData(data.allNodes, remainingLeafNodes, data.id2Node);
        }

        // 两阶段策略：叶子太多时先用 HybridRecall 粗筛再精判
        int totalLeafCount = data.leafNodes.size();
        boolean twoStage = intentPreFilterProperties.shouldUseTwoStage(totalLeafCount);
        List<IntentNode> candidateLeafNodes = twoStage
                ? intentHybridRecallService.shortlist(question, data.leafNodes) // 关键词+向量粗筛
                : data.leafNodes; // 直接全量送 LLM
        log.info("意图精判候选规模 - question='{}', totalLeaf={}, shortlisted={}",
                question, data.leafNodes.size(), candidateLeafNodes.size());
        log.info("Intent classification strategy: strategy={}, question='{}', totalLeaf={}, candidateCount={}, candidateLimit={}",
                twoStage ? "two-stage" : "direct",
                question,
                totalLeafCount,
                candidateLeafNodes.size(),
                intentPreFilterProperties.getCandidateLimit());

        // 构建 LLM 请求：system prompt = 意图列表 + 分类指令，user prompt = 用户问题
        String systemPrompt = buildPrompt(candidateLeafNodes, entityHint);
        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.system(systemPrompt),
                        ChatMessage.user(question)
                ))
                .temperature(0.1D) // 低温度保证分类稳定，减少随机性
                .topP(0.3D)        // 限制采样范围
                .thinking(false)   // 分类任务不需要深度思考，直接输出即可
                .build();

        // 调大模型，返回的是 JSON 文本（可能带 markdown 代码块包装）
        String raw = llmService.chat(request);

        try {
            // 去掉可能的 ```json ... ``` 包装
            String cleanedRaw = LLMResponseCleaner.stripMarkdownCodeFence(raw);

            JsonElement root = JsonParser.parseString(cleanedRaw);

            // 兼容两种返回格式：纯数组 [...], 或 { "results": [...] }
            JsonArray arr;
            if (root.isJsonArray()) {
                arr = root.getAsJsonArray();
            } else if (root.isJsonObject() && root.getAsJsonObject().has("results")) {
                arr = root.getAsJsonObject().getAsJsonArray("results");
            } else {
                log.warn("LLM 返回了非预期的 JSON 格式, 原始响应: {}", raw);
                return List.of();
            }

            // 逐条解析 LLM 输出的意图打分
            List<NodeScore> scores = new ArrayList<>();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject obj = el.getAsJsonObject();

                if (!obj.has("id") || !obj.has("score")) continue;

                String id = obj.get("id").getAsString();
                double score = obj.get("score").getAsDouble();

                // 从 id2Node 映射表找回真实 IntentNode（防 LLM 幻觉生成不存在的 id）
                IntentNode node = data.id2Node.get(id);
                if (node == null) {
                    log.warn("LLM 返回了未知的意图节点 ID: {}, 已跳过", id);
                    continue;
                }

                scores.add(new NodeScore(node, score));
            }

            // 按分数降序：置信度最高的意图排最前
            scores.sort(Comparator.comparingDouble(NodeScore::getScore).reversed());

            String selectedIntentScores = scores.stream()
                    .map(each -> "%s=%.3f".formatted(each.getNode().getId(), each.getScore()))
                    .collect(Collectors.joining(", "));
            log.info("Intent classification result: question='{}', selectedIntentScores=[{}]",
                    question, selectedIntentScores);
            return scores;
        } catch (Exception e) {
            // 解析失败不阻断主流程，返回空列表
            log.warn("解析 LLM 响应失败, 原始内容: {}", raw, e);
            return List.of();
        }
    }

    /**
     * 方便使用版本：取前 topN 个意图，并过滤掉 score < minScore 的低置信度结果
     */
    @Override
    public List<NodeScore> topKAboveThreshold(String question, int topN, double minScore) {
        return classifyTargets(question).stream()
                .filter(ns -> ns.getScore() >= minScore)
                .limit(topN)
                .toList();
    }

    /**
     * 构造给 LLM 的 Prompt：
     * - 列出所有叶子节点的 id / 路径 / 描述 / 示例问题
     * - 标注每个节点的类型（KB / MCP / SYSTEM）
     * - 如果有实体命中，在 prompt 末尾附加 entity hint 帮助 LLM 锁定意图
     * - 要求 LLM 只在这些 id 中选择，输出 JSON 数组：[{"id": "...", "score": 0.9, "reason": "..."}]
     */
    private String buildPrompt(List<IntentNode> leafNodes, EntityResolution entityHint) {
        // 拼装叶子节点列表，每个节点一行一行地描述
        StringBuilder sb = new StringBuilder();

        for (IntentNode node : leafNodes) {
            sb.append("- id=").append(node.getId()).append("\n");
            sb.append("  path=").append(node.getFullPath()).append("\n");        // 层级路径，如 "生产 > 设备管理 > 启动"
            sb.append("  description=").append(node.getDescription()).append("\n"); // 节点描述说明

            // 标注节点类型，LLM 可根据类型判断这个意图是知识检索、工具调用还是系统响应
            if (node.isMCP()) {
                sb.append("  type=MCP\n");
                if (node.getMcpToolId() != null) {
                    sb.append("  toolId=").append(node.getMcpToolId()).append("\n");
                }
            } else if (node.isSystem()) {
                sb.append("  type=SYSTEM\n");
            } else {
                sb.append("  type=KB\n");
            }

            // 附带示例问题，帮助 LLM 理解该意图的使用场景
            if (node.getExamples() != null && !node.getExamples().isEmpty()) {
                sb.append("  examples=");
                sb.append(String.join(" / ", node.getExamples()));
                sb.append("\n");
            }
            sb.append("\n");
        }

        // 用模板引擎把叶子节点列表注入分类 prompt 模板
        String prompt = promptTemplateLoader.render(
                INTENT_CLASSIFIER_PROMPT_PATH,
                Map.of("intent_list", sb.toString())
        );

        // 如果实体未命中，直接用原始 prompt；否则在末尾追加 entity hint
        if (!entityHint.isMatched()) {
            return prompt;
        }
        // 实体命中时：把 entity_name/type/category 作为提示追加到 prompt 末尾
        // LLM 会看到"本地目录已确认某个具体实体"，从而更精准地分类到相关意图
        return prompt + "\n\n# Catalog entity hint\n"
                + "A deterministic local catalog matched one concrete entity. Treat its type as a strong hint, "
                + "but still classify the user intent from the question semantics. The entity name is not required "
                + "to appear in a leaf example.\n"
                + "- entity_name=" + entityHint.entityName() + "\n"
                + "- entity_type=" + entityHint.entityType() + "\n"
                + "- entity_category=" + entityHint.category() + "\n";
    }

    /**
     * 从数据库加载完整意图树并组装成多根树结构。
     *
     * 流程：
     * 1. 查出所有未删除的意图节点（扁平记录）
     * 2. DO -> IntentNode，以 (kbId, intentCode) 为 key 放入 map
     * 3. 第二遍遍历，根据 parentId 组装父子关系
     * 4. 递归填充每层节点的 fullPath（用于 LLM prompt 中的路径展示）
     */
    private List<IntentNode> loadIntentTreeFromDB() {
        // 1. 查出所有未删除节点（表中存的是扁平结构，无父子嵌套）
        List<IntentNodeDO> intentNodeDOList = intentNodeMapper.selectList(
                Wrappers.lambdaQuery(IntentNodeDO.class)
                        .eq(IntentNodeDO::getDeleted, 0)
        );

        if (intentNodeDOList.isEmpty()) {
            return List.of();
        }

        // 2. DO -> IntentNode（第一遍：把所有节点建出来，按 (kbId, code) 放入 map）
        Map<ScopedIntentKey, IntentNode> id2Node = new HashMap<>();
        for (IntentNodeDO each : intentNodeDOList) {
            IntentNode node = BeanUtil.toBean(each, IntentNode.class);
            // 数据库中的 intentCode/parentCode 映射到 IntentNode 的 id/parentId
            node.setId(each.getIntentCode());
            node.setParentId(each.getParentCode());
            node.setMcpToolId(each.getMcpToolId());
            node.setParamPromptTemplate(each.getParamPromptTemplate());
            // 确保 children 不为 null，避免后续 add 时 NPE
            if (node.getChildren() == null) {
                node.setChildren(new ArrayList<>());
            }
            id2Node.put(scopedKey(node.getKbId(), node.getId()), node);
        }

        // 3. 第二遍：根据 parentId 组装 parent -> children 的树形关系
        List<IntentNode> roots = new ArrayList<>();
        for (IntentNode node : id2Node.values()) {
            String parentId = node.getParentId();
            if (parentId == null || parentId.isBlank()) {
                // 没有 parentId -> 根节点
                roots.add(node);
                continue;
            }

            IntentNode parent = id2Node.get(scopedKey(node.getKbId(), parentId));
            if (parent == null) {
                // 找不到父节点 -> 兜底当作根节点，避免数据丢失
                roots.add(node);
                continue;
            }

            // 追加到父节点的 children 列表
            if (parent.getChildren() == null) {
                parent.setChildren(new ArrayList<>());
            }
            parent.getChildren().add(node);
        }

        // 4. 递归填充 fullPath 字段（如 "集团信息化 > 人事 > 考勤"）
        fillFullPath(roots, null);

        return roots;
    }

    // 构造 (kbId, intentCode) 复合键，用于多知识库场景下区分不同库的同名意图
    private static ScopedIntentKey scopedKey(String kbId, String intentCode) {
        return new ScopedIntentKey(kbId, intentCode);
    }

    private record ScopedIntentKey(String kbId, String intentCode) {
    }

    /**
     * 递归填充 fullPath 字段，效果类似：
     * - 集团信息化
     * - 集团信息化 > 人事
     * - 业务系统 > OA系统 > 系统介绍
     */
    private void fillFullPath(List<IntentNode> nodes, IntentNode parent) {
        if (nodes == null) return;

        for (IntentNode node : nodes) {
            // 根节点：fullPath = 自己名字；非根节点：父路径 + " > " + 自己名字
            if (parent == null) {
                node.setFullPath(node.getName());
            } else {
                node.setFullPath(parent.getFullPath() + " > " + node.getName());
            }

            // 递归处理子节点
            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                fillFullPath(node.getChildren(), node);
            }
        }
    }
}
