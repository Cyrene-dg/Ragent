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

package com.nageoffer.ai.ragent.rag.core.workflow;

import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.knowledge.KnowledgeQueryService;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.core.mcp.ToolExecutionService;
import com.nageoffer.ai.ragent.rag.dto.KbResult;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExecutionCoordinatorTest {
    private final KnowledgeQueryService knowledge = mock(KnowledgeQueryService.class);
    private final ToolExecutionService tools = mock(ToolExecutionService.class);
    private final CapabilityCatalog catalog = mock(CapabilityCatalog.class);
    private final IntentNode tool = IntentNode.builder().id("tool").kind(IntentKind.MCP).mcpToolId("ticket_query").build();

    private ExecutionCoordinator coordinator(java.util.concurrent.Executor executor) {
        when(catalog.snapshot()).thenReturn(new CapabilityCatalog.Snapshot(Map.of("tool", tool), ""));
        return new ExecutionCoordinator(knowledge, tools, catalog, new ExecutionPlanValidator(16), executor);
    }

    @ParameterizedTest
    @EnumSource(IntentKind.class)
    void capabilityIsolation(IntentKind kind) {
        when(knowledge.query(anyString(), anyInt())).thenReturn(new KnowledgeQueryService.KnowledgeResult(List.of(), KbResult.empty(), null));
        when(tools.execute(anyString(), any())).thenReturn(MCPResponse.success("ticket_query", "result"));
        var coordinator = coordinator(Runnable::run);
        var step = new ExecutionPlan.Step("s1", kind, "query", kind == IntentKind.MCP ? "tool" : null, List.of());
        var result = coordinator.execute(new ExecutionPlan(List.of(step), null));
        assertEquals(1, result.size());
        assertNull(result.get(0).error());
        if (kind == IntentKind.KB) verify(knowledge).query(eq("query"), anyInt());
        else verifyNoInteractions(knowledge);
        if (kind == IntentKind.MCP) verify(tools).execute("query", tool);
        else verifyNoInteractions(tools);
    }

    @Test
    void independentKnowledgeAndToolCanStartConcurrently() throws Exception {
        var started = new CountDownLatch(2);
        when(knowledge.query(anyString(), anyInt())).thenAnswer(invocation -> {
            started.countDown();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            return new KnowledgeQueryService.KnowledgeResult(List.of(), KbResult.empty(), null);
        });
        when(tools.execute(anyString(), any())).thenAnswer(invocation -> {
            started.countDown();
            assertTrue(started.await(2, TimeUnit.SECONDS));
            return MCPResponse.success("ticket_query", "result");
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var results = coordinator(pool).execute(new ExecutionPlan(List.of(
                    new ExecutionPlan.Step("kb", IntentKind.KB, "query", null, List.of()),
                    new ExecutionPlan.Step("tool", IntentKind.MCP, "query", "tool", List.of())), null));
            assertTrue(results.stream().allMatch(result -> result.error() == null));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void dependentStepsAreRejectedBeforeAnyExecution() {
        var coordinator = coordinator(Runnable::run);
        var plan = new ExecutionPlan(List.of(
                new ExecutionPlan.Step("s1", IntentKind.MCP, "query", "tool", List.of()),
                new ExecutionPlan.Step("s2", IntentKind.KB, "query", null, List.of("s1"))), null);
        assertThrows(IllegalArgumentException.class, () -> coordinator.execute(plan));
        verifyNoInteractions(knowledge, tools);
    }

    @Test
    void unknownTargetIsRejectedBeforeAnyExecution() {
        var coordinator = coordinator(Runnable::run);
        assertThrows(IllegalArgumentException.class, () -> coordinator.execute(new ExecutionPlan(List.of(
                new ExecutionPlan.Step("s1", IntentKind.MCP, "query", "unknown", List.of())), null)));
        verifyNoInteractions(knowledge, tools);
    }

    @Test
    void failureDoesNotTriggerAnotherCapabilityAsFallback() {
        when(knowledge.query(anyString(), anyInt())).thenThrow(new IllegalStateException("KB unavailable"));
        var result = coordinator(Runnable::run).execute(new ExecutionPlan(List.of(
                new ExecutionPlan.Step("s1", IntentKind.KB, "query", null, List.of())), null));
        assertNotNull(result.get(0).error());
        verifyNoInteractions(tools);
    }
}
