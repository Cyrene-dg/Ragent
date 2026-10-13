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

import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LlmRequestPlannerTest {
    @Test
    void catalogFailureReturnsClarificationWithoutCallingModel() {
        var llm = mock(LLMService.class);
        var catalog = mock(CapabilityCatalog.class);
        when(catalog.snapshot()).thenThrow(new IllegalStateException("catalog unavailable"));
        var plan = new LlmRequestPlanner(llm, catalog, new ExecutionPlanValidator(16))
                .plan(new RewriteResult("query", List.of("query")));
        assertNotNull(plan.clarification());
        verifyNoInteractions(llm);
    }
    @ParameterizedTest
    @EnumSource(IntentKind.class)
    void parsesAndValidatesCapabilityWithoutExecutingServices(IntentKind kind) {
        var llm = mock(LLMService.class);
        var catalog = mock(CapabilityCatalog.class);
        when(catalog.snapshot()).thenReturn(new CapabilityCatalog.Snapshot(Map.of("tool",
                IntentNode.builder().id("tool").kind(IntentKind.MCP).build()), "capability descriptions"));
        String target = kind == IntentKind.MCP ? "\"tool\"" : "null";
        when(llm.chat(any(ChatRequest.class))).thenReturn("{\"steps\":[{\"id\":\"s1\",\"kind\":\"" + kind
                + "\",\"question\":\"query\",\"targetId\":" + target + "}],\"clarification\":null}");
        var plan = new LlmRequestPlanner(llm, catalog, new ExecutionPlanValidator(16))
                .plan(new RewriteResult("query", List.of("query")));
        assertNull(plan.clarification());
        assertEquals(kind, plan.steps().get(0).kind());
        verify(llm, times(1)).chat(any(ChatRequest.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "{\"steps\":[]}",
            "{\"steps\":[{\"id\":\"s1\",\"kind\":\"MCP\",\"question\":\"q\",\"targetId\":\"unknown\"}]}",
            "{\"steps\":[{\"id\":\"s1\",\"kind\":\"KB\",\"question\":\"q\",\"dependsOn\":[\"prior\"]}]}"})
    void invalidPlansReturnClarificationNotGlobalRetrieval(String raw) {
        var llm = mock(LLMService.class);
        var catalog = mock(CapabilityCatalog.class);
        when(catalog.snapshot()).thenReturn(new CapabilityCatalog.Snapshot(Map.of(), ""));
        when(llm.chat(any(ChatRequest.class))).thenReturn(raw);
        var plan = new LlmRequestPlanner(llm, catalog, new ExecutionPlanValidator(16))
                .plan(new RewriteResult("query", List.of("query")));
        assertTrue(plan.steps().isEmpty());
        assertNotNull(plan.clarification());
    }
}
