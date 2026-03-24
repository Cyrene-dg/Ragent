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

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import com.nageoffer.ai.ragent.rag.aop.ChatRateLimit;
import com.nageoffer.ai.ragent.rag.core.guidance.GuidanceDecision;
import com.nageoffer.ai.ragent.rag.core.guidance.IntentGuidanceService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentResolver;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.retrieve.RetrievalEngine;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.service.RAGChatService;
import com.nageoffer.ai.ragent.rag.service.handler.StreamCallbackFactory;
import com.nageoffer.ai.ragent.rag.service.handler.StreamTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.CHAT_SYSTEM_PROMPT_PATH;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.DEFAULT_TOP_K;

/**
 * RAG 对话服务默认实现
 * <p>
 * 核心流程：
 * 记忆加载 -> 改写拆分 -> 意图解析 -> 歧义引导 -> 检索(MCP+KB) -> Prompt 组装 -> 流式输出
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGChatServiceImpl implements RAGChatService {

    private final LLMService llmService;
    private final RAGPromptService promptBuilder;
    private final PromptTemplateLoader promptTemplateLoader;
    private final ConversationMemoryService memoryService;
    private final StreamTaskManager taskManager;
    private final IntentGuidanceService guidanceService;
    private final StreamCallbackFactory callbackFactory;
    private final QueryRewriteService queryRewriteService;
    private final IntentResolver intentResolver;
    private final RetrievalEngine retrievalEngine;

    @Override
    //自定义注解，聊天接口限流
    @ChatRateLimit
    public void streamChat(String question, String conversationId, Boolean deepThinking, SseEmitter emitter) {
        //没有会话id那就默认新生成一个（用雪花算法）
        String actualConversationId = StrUtil.isBlank(conversationId) ? IdUtil.getSnowflakeNextIdStr() : conversationId;
        //准备任务id
        String taskId = StrUtil.isBlank(RagTraceContext.getTaskId())
                ? IdUtil.getSnowflakeNextIdStr()
                : RagTraceContext.getTaskId();
        log.info("开始流式对话，会话ID：{}，任务ID：{}", actualConversationId, taskId);
        boolean thinkingEnabled = Boolean.TRUE.equals(deepThinking);

        //流式回调对象
        StreamCallback callback = callbackFactory.createChatEventHandler(emitter, actualConversationId, taskId);

        String userId = UserContext.getUserId();
        //加载聊天历史
        List<ChatMessage> history = memoryService.loadAndAppend(actualConversationId, userId, ChatMessage.user(question));

        //对用户问题改写拆分
        RewriteResult rewriteResult = queryRewriteService.rewriteWithSplit(question, history);
        //根据改写拆分好的问题识别意图
        List<SubQuestionIntent> subIntents = intentResolver.resolve(rewriteResult);

        //是否澄清用户问题
        GuidanceDecision guidanceDecision = guidanceService.detectAmbiguity(rewriteResult.rewrittenQuestion(), subIntents);
        if (guidanceDecision.isPrompt()) {
            //需要用户澄清的话把提示语给前端不在继续检索和模型调用
            callback.onContent(guidanceDecision.getPrompt());
            callback.onComplete();
            return;
        }

        //判断系统内置回答行不行
        boolean allSystemOnly = subIntents.stream()
                .allMatch(si -> intentResolver.isSystemOnly(si.nodeScores()));
        //如果可以仅在内置解决，尝试找一个可用的自定义 Prompt 模板。
        if (allSystemOnly) {
            //1.遍历所有子意图2.再遍历它们对应的节点分数3.取出节点上的 promptTemplate4.过滤空的5.找到第一个非空模板6.找不到就返回 null
            String customPrompt = subIntents.stream()
                    .flatMap(si -> si.nodeScores().stream())
                    .map(ns -> ns.getNode().getPromptTemplate())
                    .filter(StrUtil::isNotBlank)
                    .findFirst()
                    .orElse(null);
            //调用streamSystemResponse，传入的参数是改写后的问题，历史对话，刚找到的prompt，流式输出回调
            StreamCancellationHandle handle = streamSystemResponse(rewriteResult.rewrittenQuestion(), history, customPrompt, callback);
            //绑定这次任务于任务id
            taskManager.bindHandle(taskId, handle);
            //结束方法
            return;
        }

        //系统内置回答解决不了，进入标准 RAG 检索。
        RetrievalContext ctx = retrievalEngine.retrieve(subIntents, DEFAULT_TOP_K);
        //检索结果为空直接返回
        if (ctx.isEmpty()) {
            String emptyReply = "未检索到与问题相关的文档内容。";
            callback.onContent(emptyReply);
            callback.onComplete();
            return;
        }

        // 聚合所有意图用于 prompt 规划
        IntentGroup mergedGroup = intentResolver.mergeIntentGroup(subIntents);

        //调用大模型进行流式回答
        StreamCancellationHandle handle = streamLLMResponse(
                rewriteResult,//改写后的问题结果
                ctx,//检索到的知识库内容
                mergedGroup,//合并后的总体意图
                history,//历史对话
                thinkingEnabled,//是否开启深度思考
                callback//流式输出通道
        );
        //把当前任务 ID 和模型流式任务绑定。
        taskManager.bindHandle(taskId, handle);
    }

    @Override
    public void stopTask(String taskId) {
        taskManager.cancel(taskId);
    }

    // ==================== LLM 响应 ====================

    //启动的是一个流式的大模型调用任务，并且返回一个可取消句柄。也就是内置系统回答
    private StreamCancellationHandle streamSystemResponse(String question, List<ChatMessage> history,
                                                          String customPrompt, StreamCallback callback) {
        //确定系统提示词。
        String systemPrompt = StrUtil.isNotBlank(customPrompt)
                ? customPrompt
                : promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH);

        //构建messages 列表
        List<ChatMessage> messages = new ArrayList<>();
        //往消息列表中先加入一条 system 消息。
        messages.add(ChatMessage.system(systemPrompt));
        //判断历史消息列表是否不为空。
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(history.subList(0, history.size() - 1));
        }
//        加入本轮用户问题
        messages.add(ChatMessage.user(question));
//        构造 ChatRequest
        ChatRequest req = ChatRequest.builder()
                .messages(messages)
                .temperature(0.7D)
                .thinking(false)
                .build();
        return llmService.streamChat(req, callback);
    }

    //调用大模型的方法
    private StreamCancellationHandle streamLLMResponse(RewriteResult rewriteResult, RetrievalContext ctx,
                                                       IntentGroup intentGroup, List<ChatMessage> history,
                                                       boolean deepThinking, StreamCallback callback) {
        //建造者模式，构建一个PromptContext 对象，即提示词上下文
        PromptContext promptContext = PromptContext.builder()
                .question(rewriteResult.rewrittenQuestion())//改写后的问题
                .mcpContext(ctx.getMcpContext())//MCP 上下文
                .kbContext(ctx.getKbContext())//知识库上下文
                .mcpIntents(intentGroup.mcpIntents())//MCP 意图
                .kbIntents(intentGroup.kbIntents())//KB 意图
                .intentChunks(ctx.getIntentChunks())//各意图命中的 chunk
                .build();

        //构造最终发给模型的 messages
        List<ChatMessage> messages = promptBuilder.buildStructuredMessages(
                promptContext,
                history,//历史消息
                rewriteResult.rewrittenQuestion(),//改写后的主问题
                rewriteResult.subQuestions()  // 传入子问题列表
        );
        //构造一个 ChatRequest
        ChatRequest chatRequest = ChatRequest.builder()
                .messages(messages)
                .thinking(deepThinking)//深度思考
                .temperature(ctx.hasMcp() ? 0.3D : 0D)  // MCP 场景稍微放宽温度
                .topP(ctx.hasMcp() ? 0.8D : 1D)//topP 越小，模型越收敛 ,topP 越大，模型越开放
                .build();

        //最终执行点，调用大模型，生成内容通过 callback 持续回传
        return llmService.streamChat(chatRequest, callback);
    }
}
