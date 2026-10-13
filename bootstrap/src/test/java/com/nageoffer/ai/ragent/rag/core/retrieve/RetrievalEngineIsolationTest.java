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

import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.knowledge.KnowledgeQueryService;
import com.nageoffer.ai.ragent.rag.core.mcp.ToolExecutionService;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPParameterExtractor;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPToolRegistry;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.RetrievalPlan;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.RetrievalPlanService;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class RetrievalEngineIsolationTest {
    @ParameterizedTest
    @EnumSource(value = IntentKind.class, names = {"SYSTEM", "MCP"})
    void nonKnowledgeIntentNeverStartsKnowledgeRetrieval(IntentKind kind) {
        var knowledge = mock(KnowledgeQueryService.class);
        var engine = new RetrievalEngine(knowledge, mock(ToolExecutionService.class),
                mock(ContextFormatter.class), Runnable::run, Runnable::run);
        var node = IntentNode.builder().id("test").kind(kind).mcpToolId("test-tool").build();

        engine.retrieve(List.of(new SubQuestionIntent("question", List.of(new NodeScore(node, 0.99)))), 8);

        verifyNoInteractions(knowledge);
    }
}
