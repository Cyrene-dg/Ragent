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

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.*;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.knowledge.KnowledgeQueryService;
import com.nageoffer.ai.ragent.rag.core.mcp.*;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.core.prompt.*;
import com.nageoffer.ai.ragent.rag.core.rewrite.*;
import com.nageoffer.ai.ragent.rag.core.workflow.*;
import com.nageoffer.ai.ragent.rag.dto.KbResult;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.rag.service.handler.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RAGChatWorkflowTest {
    private final LLMService llm = mock(LLMService.class);
    private final RAGPromptService prompts = mock(RAGPromptService.class);
    private final PromptTemplateLoader templates = mock(PromptTemplateLoader.class);
    private final ConversationMemoryService memory = mock(ConversationMemoryService.class);
    private final StreamTaskManager tasks = mock(StreamTaskManager.class);
    private final StreamCallbackFactory callbacks = mock(StreamCallbackFactory.class);
    private final QueryRewriteService rewrite = mock(QueryRewriteService.class);
    private final RequestPlanner planner = mock(RequestPlanner.class);
    private final KnowledgeQueryService knowledge = mock(KnowledgeQueryService.class);
    private final ToolExecutionService tools = mock(ToolExecutionService.class);
    private final CapabilityCatalog catalog = mock(CapabilityCatalog.class);
    private final StreamCallback callback = mock(StreamCallback.class);
    private final StreamCancellationHandle handle = mock(StreamCancellationHandle.class);
    private final IntentNode tool = IntentNode.builder().id("tool").kind(IntentKind.MCP).mcpToolId("ticket_query").build();
    private RAGChatServiceImpl service;

    @BeforeEach
    void setup() {
        when(catalog.snapshot()).thenReturn(new CapabilityCatalog.Snapshot(Map.of("tool", tool), ""));
        when(memory.loadAndAppend(anyString(), any(), any())).thenReturn(List.of(ChatMessage.user("previous"),
                ChatMessage.assistant("previous reply"), ChatMessage.user("query")));
        when(callbacks.createChatEventHandler(any(), anyString(), anyString())).thenReturn(callback);
        when(rewrite.rewriteWithSplit(anyString(), anyList())).thenReturn(new RewriteResult("query", List.of("query")));
        when(templates.load(anyString())).thenReturn("system instructions");
        when(prompts.buildStructuredMessages(any(), anyList(), anyString(), anyList()))
                .thenReturn(List.of(ChatMessage.system("rag instructions"), ChatMessage.user("query")));
        when(llm.streamChat(any(ChatRequest.class), eq(callback))).thenReturn(handle);
        when(knowledge.query(anyString(), anyInt())).thenReturn(new KnowledgeQueryService.KnowledgeResult(List.of(),
                new KbResult("kb evidence", Map.of()), null));
        when(tools.execute(anyString(), any())).thenReturn(MCPResponse.success("ticket_query", "tool result"));
        service = new RAGChatServiceImpl(llm, prompts, templates, memory, tasks, callbacks, rewrite, planner,
                new ExecutionCoordinator(knowledge, tools, catalog, new ExecutionPlanValidator(16), Runnable::run),
                new ResponseAssembler(new DefaultContextFormatter()));
    }

    @ParameterizedTest
    @EnumSource(IntentKind.class)
    void chatEntryKeepsCapabilitiesIsolatedAndBindsStreamingHandle(IntentKind kind) {
        when(planner.plan(any())).thenReturn(new ExecutionPlan(List.of(new ExecutionPlan.Step("s1", kind,
                "query", kind == IntentKind.MCP ? "tool" : null, List.of())), null));

        service.streamChat("query", "conversation", true, new SseEmitter());

        verify(memory).loadAndAppend(eq("conversation"), any(), any());
        verify(callbacks).createChatEventHandler(any(), eq("conversation"), anyString());
        verify(tasks).bindHandle(anyString(), eq(handle));
        var captor = ArgumentCaptor.forClass(ChatRequest.class);
        verify(llm).streamChat(captor.capture(), eq(callback));
        if (kind == IntentKind.SYSTEM) {
            verifyNoInteractions(knowledge, tools, prompts);
            assertTrue(captor.getValue().getMessages().stream().anyMatch(message -> "previous reply".equals(message.getContent())));
        } else {
            assertTrue(captor.getValue().getThinking());
            if (kind == IntentKind.KB) verifyNoInteractions(tools);
            else verifyNoInteractions(knowledge);
        }
    }

    @Test
    void clarificationCompletesSseWithoutExecutingAnything() {
        when(planner.plan(any())).thenReturn(new ExecutionPlan(List.of(), "clarify"));
        service.streamChat("query", "conversation", false, new SseEmitter());
        verify(callback).onContent("clarify");
        verify(callback).onComplete();
        verifyNoInteractions(knowledge, tools, llm, catalog);
    }

    @Test
    void toolFailureIsReportedAsToolFailureNotMissingDocuments() {
        when(planner.plan(any())).thenReturn(new ExecutionPlan(List.of(new ExecutionPlan.Step("s1", IntentKind.MCP,
                "query", "tool", List.of())), null));
        when(tools.execute(anyString(), any())).thenReturn(MCPResponse.error("ticket_query", "TIMEOUT", "tool timed out"));
        service.streamChat("query", "conversation", false, new SseEmitter());
        verify(callback).onContent(contains("tool timed out"));
        verify(callback).onComplete();
        verifyNoInteractions(knowledge, llm);
    }

    @Test
    void stopStillUsesExistingTaskManager() {
        service.stopTask("task");
        verify(tasks).cancel("task");
    }

    @Test
    void rejectedPlanCompletesSseAndNeverRunsAnotherCapability() {
        when(planner.plan(any())).thenReturn(new ExecutionPlan(List.of(new ExecutionPlan.Step("s1", IntentKind.MCP,
                "query", "unregistered", List.of())), null));
        service.streamChat("query", "conversation", false, new SseEmitter());
        verify(callback).onContent(contains("处理计划"));
        verify(callback).onComplete();
        verifyNoInteractions(knowledge, tools, llm);
    }
}
