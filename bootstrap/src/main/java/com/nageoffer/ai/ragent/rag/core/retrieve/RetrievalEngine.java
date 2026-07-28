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

package com.nageoffer.ai.ragent.rag.core.retrieve;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.dto.KbResult;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPParameterExtractor;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPRequest;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPTool;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPToolExecutor;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPToolRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.RetrievalPlan;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.RetrievalPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.DEFAULT_TOP_K;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.INTENT_MIN_SCORE;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MULTI_CHANNEL_KEY;

/**
 * 检索引擎
 * 负责协调多通道检索（知识库）和 MCP（模型控制协议）工具的调用，并对检索结果进行重排序和格式化，最终生成用于 LLM 的上下文
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalEngine {

    private final ContextFormatter contextFormatter;
    private final MCPParameterExtractor mcpParameterExtractor;
    private final MCPToolRegistry mcpToolRegistry;
    private final MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    private final RetrievalPlanService retrievalPlanService;
    @Qualifier("ragContextThreadPoolExecutor")
    private final Executor ragContextExecutor;
    @Qualifier("mcpBatchThreadPoolExecutor")
    private final Executor mcpBatchExecutor;

    /**
     * 检索方法：根据子问题意图列表执行检索，整合知识库和MCP工具的结果
     *
     * @param subIntents 子问题意图列表，包含每个子问题及其相关的意图节点和评分
     * @param topK       需要返回的最相关结果数量，若 ≤0 则使用默认值
     * @return RetrievalContext 检索上下文，包含知识库上下文、MCP上下文和分组的检索块
     */
    @RagTraceNode(name = "retrieval-engine", type = "RETRIEVE")
    //检索总入口，负责并发处理每个子问题的 KB/MCP 结果，并最终拼成一个总的 RetrievalContext 返回
    public RetrievalContext retrieve(List<SubQuestionIntent> subIntents, int topK) {
        //如果前面意图识别阶段没有给出任何子问题结果，那这里就不做检索了，直接返回一个空的检索上下文。
        if (CollUtil.isEmpty(subIntents)) {
            return RetrievalContext.builder()
                    .mcpContext("")
                    .kbContext("")
                    .intentChunks(Map.of())
                    .build();
        }


        //topk:每个检索问题最多取多少条结果
        int finalTopK = topK > 0 ? topK : DEFAULT_TOP_K;
        //核心方法：对每个子问题异步处理，分别构建出它自己的检索结果上下文。
        List<CompletableFuture<SubQuestionContext>> tasks = subIntents.stream()
                //异步处理，提升多子问题场景下的整体检索速度。
                .map(si -> CompletableFuture.supplyAsync(
                        //把单个子问题的处理委托给buildSubQuestionContext
                        () -> buildSubQuestionContext(
                                si,
                                //这个具体子问题最终该用多大的 topK。
                                resolveSubQuestionTopK(si, finalTopK)
                        ),
                        ragContextExecutor
                ))
                .toList();
        //把前面那些异步任务一个个等完，并拿到真正的子问题上下文结果列表。
        List<SubQuestionContext> contexts = tasks.stream()
                .map(CompletableFuture::join)
                .toList();

        StringBuilder kbBuilder = new StringBuilder();//用来拼接所有子问题的知识库上下文文本。
        StringBuilder mcpBuilder = new StringBuilder();//用来拼接所有子问题的工具调用结果文本。
        Map<String, List<RetrievedChunk>> mergedIntentChunks = new ConcurrentHashMap<>();//用来收集所有子问题的检索 chunk 分组结果。

        //把每个子问题的局部结果汇总进总结果
        for (SubQuestionContext context : contexts) {
            //如果某个子问题确实检索到了 KB 内容，就把它按统一格式追加到总的 KB 文本里。
            if (StrUtil.isNotBlank(context.kbContext())) {
                appendSection(kbBuilder, context.question(), context.kbContext());
            }
            //如果某个子问题确实检索到了 KB 内容，就把它按统一格式追加到总的 KB 文本里。
            if (StrUtil.isNotBlank(context.mcpContext())) {
                appendSection(mcpBuilder, context.question(), context.mcpContext());
            }
            //如果某个子问题有 MCP 工具调用结果，就把它也按统一格式追加到总的 MCP 文本里。
            if (CollUtil.isNotEmpty(context.intentChunks())) {
                mergedIntentChunks.putAll(context.intentChunks());
            }
        }

        //把前面汇总好的 MCP 文本、KB 文本、意图 chunk 明细，统一封装成一个总的 RetrievalContext 返回。
        return RetrievalContext.builder()
                .mcpContext(mcpBuilder.toString().trim())
                .kbContext(kbBuilder.toString().trim())
                .intentChunks(mergedIntentChunks)
                .build();
    }
    //针对单个子问题，分别处理 KB 检索和 MCP 调用，再封装成一个子问题级上下文结果
    private SubQuestionContext buildSubQuestionContext(SubQuestionIntent intent, int topK) {
       //把这个子问题真正处理成可用的 KB/MCP 上下文
        List<NodeScore> kbIntents = filterKbIntents(intent.nodeScores());
        List<NodeScore> mcpIntents = filterMCPIntents(intent.nodeScores());

        // 先把“意图 + 实体 + 路由规则”汇总为只读 Plan；随后原样传入多通道检索引擎。
        // 开启 executionEnabled 时，ES/Milvus 消费 Plan 做分层过滤；关闭时各通道保持旧检索路径。
        RetrievalPlan retrievalPlan = retrievalPlanService.buildPlan(
                intent.subQuestion(), kbIntents, intent.entityResolution());
        if (!retrievalPlan.isEmpty()) {
            log.info("Metadata retrieval plan: question='{}', targetCandidateCount={}, maxCandidatePool={}, plans={}",
                    intent.subQuestion(),
                    retrievalPlan.targetCandidateCount(),
                    retrievalPlan.maxCandidatePool(),
                    retrievalPlan.intentPlans());
        }

        //拿当前子问题和它的 KB 意图，去做知识检索，并得到一个 KB 检索结果对象。
        KbResult kbResult = retrieveAndRerank(intent, kbIntents, topK, retrievalPlan);

        //如果当前子问题存在 MCP 意图，就去执行对应工具并把结果合并成一段 MCP 上下文；如果没有 MCP 意图，就给空字符串。
        String mcpContext = CollUtil.isNotEmpty(mcpIntents)
                ? executeMcpAndMerge(intent.subQuestion(), mcpIntents)
                : "";

        //把当前子问题文本、KB 上下文、MCP 上下文、检索 chunk 明细，一起封装成一个 SubQuestionContext 返回。
        return new SubQuestionContext(intent.subQuestion(), kbResult.groupedContext(), mcpContext, kbResult.intentChunks());
    }

    /**
     * 子问题实际 TopK 计算规则：
     * 1. 命中 KB 意图节点且配置了节点级 topK：取最大值（多意图保守放大）
     * 2. 没有任何可用节点级 topK：回退到全局 topK
     */
    //计算某个子问题实际应该用多大的 topK，优先采用命中意图节点里配置的较大值，否则回退到全局值。
    private int resolveSubQuestionTopK(SubQuestionIntent intent, int fallbackTopK) {
        return filterKbIntents(intent.nodeScores()).stream()
                .map(NodeScore::getNode)
                .filter(Objects::nonNull)
                .map(IntentNode::getTopK)
                .filter(Objects::nonNull)
                .filter(topK -> topK > 0)
                .max(Integer::compareTo)
                .orElse(fallbackTopK);
    }

    //追加文本
    private void appendSection(StringBuilder builder, String question, String context) {
        builder.append("---\n")
                .append("**子问题**：").append(question).append("\n\n")
                .append("**相关文档**：\n")
                .append(context).append("\n\n");
    }

    //从候选意图里筛出分数达标、类型为 MCP 且配置了工具 ID 的意图。
    private List<NodeScore> filterMCPIntents(List<NodeScore> nodeScores) {
        return nodeScores.stream()
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                .filter(ns -> ns.getNode() != null && ns.getNode().getKind() == IntentKind.MCP)
                .filter(ns -> StrUtil.isNotBlank(ns.getNode().getMcpToolId()))
                .toList();
    }

    //从候选意图里筛出分数达标且可视为 KB 路线的意图。
    private List<NodeScore> filterKbIntents(List<NodeScore> nodeScores) {
        return nodeScores.stream()
                //先把分数太低的候选意图去掉。
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                //先把分数太低的候选意图去掉。
                .filter(ns -> {
                    //先拿到这个候选意图真正对应的节点对象
                    IntentNode node = ns.getNode();
                    if (node == null) {
                        return false;
                    }
                    //如果这个节点的类型是 KB，或者类型干脆没写（null），那也把它当成 KB 保留。
                    return node.getKind() == null || node.getKind() == IntentKind.KB;
                })
                .toList();
    }

    //执行当前子问题对应的所有 MCP 工具，并把成功结果格式化成一段 MCP 上下文文本。
    private String executeMcpAndMerge(String question, List<NodeScore> mcpIntents) {
        if (CollUtil.isEmpty(mcpIntents)) {
            return "";
        }

        List<MCPResponse> responses = executeMcpTools(question, mcpIntents);
        if (responses.isEmpty() || responses.stream().noneMatch(MCPResponse::isSuccess)) {
            return "";
        }

        return contextFormatter.formatMcpContext(responses, mcpIntents);
    }


    //针对一个子问题，先去知识库真正检索一批相关 chunk；然后把这些 chunk 按“意图节点”整理成后面大模型能吃的 KB 上下文文本，并顺手保留结构化分组结果。
    private KbResult retrieveAndRerank(SubQuestionIntent intent,
                                       List<NodeScore> kbIntents,
                                       int topK,
                                       RetrievalPlan retrievalPlan) {
        // 使用多通道检索引擎（是否启用全局检索由置信度阈值决定）
        List<SubQuestionIntent> subIntents = List.of(intent);
        List<RetrievedChunk> chunks = multiChannelRetrievalEngine.retrieveKnowledgeChannels(subIntents, topK, retrievalPlan);

        if (CollUtil.isEmpty(chunks)) {
            return KbResult.empty();
        }

        // 按意图节点分组（用于格式化上下文）
        Map<String, List<RetrievedChunk>> intentChunks = new ConcurrentHashMap<>();

        // 如果有意图识别结果，按意图节点 ID 分组
        if (CollUtil.isNotEmpty(kbIntents)) {
            // 将所有 chunks 按意图节点 ID 分配
            // 注意：多通道检索返回的 chunks 无法精确对应到某个意图节点
            // 所以我们将所有 chunks 分配给每个意图节点
            for (NodeScore ns : kbIntents) {
                intentChunks.put(ns.getNode().getId(), chunks);
            }
        } else {
            // 如果没有意图识别结果，使用特殊 key
            intentChunks.put(MULTI_CHANNEL_KEY, chunks);
        }

        String groupedContext = contextFormatter.formatKbContext(kbIntents, intentChunks, topK);
        return new KbResult(groupedContext, intentChunks);
    }

    //对单个子问题执行知识库检索，并把检索结果按意图节点分组、格式化成 KB 上下文。
    private List<MCPResponse> executeMcpTools(String question, List<NodeScore> mcpIntentScores) {
        List<MCPRequest> requests = mcpIntentScores.stream()
                .map(ns -> buildMcpRequest(question, ns.getNode()))
                .filter(Objects::nonNull)
                .toList();

        if (requests.isEmpty()) {
            return List.of();
        }

        // 并行执行所有 MCP 工具调用
        List<CompletableFuture<MCPResponse>> futures = requests.stream()
                .map(request -> CompletableFuture.supplyAsync(() -> executeSingleMcpTool(request), mcpBatchExecutor))
                .toList();

        return futures.stream()
                .map(CompletableFuture::join)
                .toList();
    }

    //把多个 MCP 意图先转成请求，再并发调用所有对应工具。
    private MCPResponse executeSingleMcpTool(MCPRequest request) {
        String toolId = request.getToolId();
        Optional<MCPToolExecutor> executorOpt = mcpToolRegistry.getExecutor(toolId);
        if (executorOpt.isEmpty()) {
            log.warn("MCP 工具执行失败, 工具不存在: {}", toolId);
            return MCPResponse.error(toolId, "TOOL_NOT_FOUND", "工具不存在: " + toolId);
        }

        try {
            return executorOpt.get().execute(request);
        } catch (Exception e) {
            log.error("MCP 工具执行异常, toolId: {}", toolId, e);
            return MCPResponse.error(toolId, "EXECUTION_ERROR", "工具调用异常: " + e.getMessage());
        }
    }

    //执行一次单独的 MCP 工具调用，并对“工具不存在”或“执行异常”做统一兜底。
    private MCPRequest buildMcpRequest(String question, IntentNode intentNode) {
        String toolId = intentNode.getMcpToolId();
        Optional<MCPToolExecutor> executorOpt = mcpToolRegistry.getExecutor(toolId);
        if (executorOpt.isEmpty()) {
            log.warn("MCP 工具不存在: {}", toolId);
            return null;
        }

        MCPTool tool = executorOpt.get().getToolDefinition();

        String customParamPrompt = intentNode.getParamPromptTemplate();
        Map<String, Object> params = mcpParameterExtractor.extractParameters(question, tool, customParamPrompt);

        return MCPRequest.builder()
                .toolId(toolId)
                .userQuestion(question)
                .parameters(params != null ? params : new HashMap<>())
                .build();
    }

    //根据当前问题和意图节点信息，提取参数并构造一个标准的 MCP 工具请求对象。
    private record SubQuestionContext(String question,
                                      String kbContext,
                                      String mcpContext,
                                      Map<String, List<RetrievedChunk>> intentChunks) {
    }
}
