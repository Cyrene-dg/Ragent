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

package com.nageoffer.ai.ragent.rag.core.mcp;

import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ToolExecutionServiceTest {
    private final MCPToolRegistry registry = mock(MCPToolRegistry.class);
    private final MCPParameterExtractor extractor = mock(MCPParameterExtractor.class);
    private final MCPToolExecutor executor = mock(MCPToolExecutor.class);
    private final DefaultToolExecutionService service = new DefaultToolExecutionService(registry, extractor);
    private final IntentNode target = IntentNode.builder().id("ticket").kind(IntentKind.MCP).mcpToolId("ticket_query").build();

    private void configure(Map<String, Object> values) {
        var definition = MCPTool.builder().toolId("ticket_query").requireUserId(false).parameters(Map.of("ticketId",
                MCPTool.ParameterDef.builder().type("string").required(true).build())).build();
        when(registry.getExecutor("ticket_query")).thenReturn(Optional.of(executor));
        when(executor.getToolDefinition()).thenReturn(definition);
        when(extractor.extractParameters(anyString(), eq(definition), any())).thenReturn(values);
        when(executor.execute(any())).thenReturn(MCPResponse.success("ticket_query", "open"));
    }

    @Test
    void executesOnlyRegisteredToolWithDeclaredParameters() {
        configure(Map.of("ticketId", "T1", "invented", "ignored"));
        assertTrue(service.execute("q", target).isSuccess());
        verify(executor).execute(argThat(request -> request.getParameters().equals(Map.of("ticketId", "T1"))));
    }

    @Test
    void missingRequiredParameterDoesNotExecute() {
        configure(Map.of());
        assertEquals("MISSING_PARAMETER", service.execute("q", target).getErrorCode());
        verify(executor, never()).execute(any());
    }

    @Test
    void invalidTypeDoesNotExecute() {
        configure(Map.of("ticketId", 42));
        assertEquals("INVALID_PARAMETER", service.execute("q", target).getErrorCode());
        verify(executor, never()).execute(any());
    }

    @Test
    void unavailableToolDoesNotExtractParameters() {
        when(registry.getExecutor("ticket_query")).thenReturn(Optional.empty());
        assertEquals("TOOL_NOT_FOUND", service.execute("q", target).getErrorCode());
        verifyNoInteractions(extractor, executor);
    }
}
