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
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptContext;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import com.nageoffer.ai.ragent.rag.core.prompt.RAGPromptService;
import com.nageoffer.ai.ragent.rag.core.workflow.RequestPlanner;
import com.nageoffer.ai.ragent.rag.core.workflow.ExecutionCoordinator;
import com.nageoffer.ai.ragent.rag.core.workflow.ResponseAssembler;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryRewriteService;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
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

/**
 * RAG 对话服务默认实现
 * <p>
 * 核心流程：
 * 记忆加载 -> 改写拆分 -> 能力规划 -> 执行协调 -> 结果组装 -> 流式输出
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
    private final StreamCallbackFactory callbackFactory;
    private final QueryRewriteService queryRewriteService;
    private final RequestPlanner requestPlanner;
    private final ExecutionCoordinator executionCoordinator;
    private final ResponseAssembler responseAssembler;

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
        var plan = requestPlanner.plan(rewriteResult);
        if (StrUtil.isNotBlank(plan.clarification())) {
            callback.onContent(plan.clarification());
            callback.onComplete();
            return;
        }
        List<ExecutionCoordinator.StepResult> results;
        try {
            results = executionCoordinator.execute(plan);
        } catch (IllegalArgumentException exception) {
            log.warn("Execution plan rejected: {}", exception.getMessage());
            callback.onContent("处理计划已失效或尚不支持该执行顺序，请明确操作后重试。");
            callback.onComplete();
            return;
        }
        var response = responseAssembler.assemble(results);
        boolean directOnly = results.stream().allMatch(result -> result.step().kind()
                == com.nageoffer.ai.ragent.rag.enums.IntentKind.SYSTEM);
        boolean hasDirect = results.stream().anyMatch(result -> result.step().kind()
                == com.nageoffer.ai.ragent.rag.enums.IntentKind.SYSTEM);
        String customPrompt = response.directInstructions().stream().findFirst().orElse(null);
        if (directOnly || response.context().isEmpty() && hasDirect) {
            if (!response.failures().isEmpty()) {
                customPrompt = (customPrompt == null ? promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH) : customPrompt)
                        + "\nSome requested operations failed. State these limitations explicitly: " + response.failures();
            }
            if (response.clarification() != null) {
                customPrompt = (customPrompt == null ? promptTemplateLoader.load(CHAT_SYSTEM_PROMPT_PATH) : customPrompt)
                        + "\nPrivate knowledge needs clarification: " + response.clarification();
            }
            var handle = streamSystemResponse(rewriteResult.rewrittenQuestion(), history, customPrompt, callback);
            taskManager.bindHandle(taskId, handle);
            return;
        }
        if (response.context().isEmpty()) {
            String reply = response.clarification() != null ? response.clarification()
                    : !response.failures().isEmpty() ? String.join("\n", response.failures())
                    : "未检索到与问题相关的文档内容。";
            callback.onContent(reply);
            callback.onComplete();
            return;
        }
        List<String> instructions = new ArrayList<>(response.directInstructions());
        if (!response.failures().isEmpty()) instructions.add("部分步骤失败，请明确说明，不要编造结果：" + response.failures());
        if (response.clarification() != null) instructions.add("部分资料范围待澄清：" + response.clarification());
        StreamCancellationHandle handle = streamLLMResponse(rewriteResult, response.context(), response.intents(),
                history, thinkingEnabled, callback, instructions);
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
                                                       boolean deepThinking, StreamCallback callback, List<String> instructions) {
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
        if (!instructions.isEmpty()) {
            messages = new ArrayList<>(messages);
            messages.add(0, ChatMessage.system(String.join("\n", instructions)));
        }
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
