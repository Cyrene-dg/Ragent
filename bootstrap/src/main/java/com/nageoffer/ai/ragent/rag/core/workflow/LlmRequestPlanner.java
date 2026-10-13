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

import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class LlmRequestPlanner implements RequestPlanner {
    private final LLMService llm;
    private final CapabilityCatalog catalog;
    private final ExecutionPlanValidator validator;

    @Override
    @RagTraceNode(name = "request-planner", type = "INTENT")
    public ExecutionPlan plan(RewriteResult request) {
        try {
            var snapshot = catalog.snapshot();
            String prompt = "Select capabilities for the user's request. Do not retrieve documents or call tools. "
                    + "Preserve all questions; split only when separate capabilities are needed. "
                    + "KB steps may need multiple corpora; do not choose a KB targetId. "
                    + "Use SYSTEM for general knowledge/conversation, KB for private/domain-specific documents, "
                    + "and MCP for registered live business operations. Use a matching SYSTEM target for its configured response. "
                    + "An MCP targetId must be from the catalog. Prefer the original intent target over tool: aliases "
                    + "when both match, to retain response and parameter templates. "
                    + "Return JSON {\"steps\":[{\"id\":\"s1\",\"kind\":\"SYSTEM|KB|MCP\",\"question\":\"...\","
                    + "\"targetId\":null,\"dependsOn\":[]}],\"clarification\":null}. "
                    + "Dependent tasks are not enabled yet: if one task needs another task's result, "
                    + "return empty steps and a short clarification asking for the required input; do not flatten dependencies. "
                    + "If the request cannot be safely routed, also return a clarification.\n" + snapshot.description();
            String raw = llm.chat(ChatRequest.builder().messages(List.of(ChatMessage.system(prompt),
                    ChatMessage.user(request.rewrittenQuestion() + "\nSubquestions: " + request.subQuestions())))
                    .thinking(false).temperature(0D).build());
            var object = JsonParser.parseString(LLMResponseCleaner.stripMarkdownCodeFence(raw)).getAsJsonObject();
            var steps = new ArrayList<ExecutionPlan.Step>();
            for (var element : object.getAsJsonArray("steps")) {
                var step = element.getAsJsonObject();
                List<String> dependencies = new ArrayList<>();
                if (step.has("dependsOn")) step.getAsJsonArray("dependsOn").forEach(value -> dependencies.add(value.getAsString()));
                steps.add(new ExecutionPlan.Step(step.get("id").getAsString(), IntentKind.valueOf(step.get("kind").getAsString()),
                        step.get("question").getAsString(), text(step, "targetId"), dependencies));
            }
            var plan = new ExecutionPlan(steps, text(object, "clarification"));
            validator.validate(plan, snapshot);
            return plan;
        } catch (Exception exception) {
            log.warn("Capability planning failed: {}", exception.getMessage());
            // Never silently fall back to global KB search after model/JSON/validation failure.
            return new ExecutionPlan(List.of(), "暂时无法确定可靠的处理步骤，请明确需要查询的资料或工具操作。");
        }
    }

    private String text(com.google.gson.JsonObject object, String field) {
        return !object.has(field) || object.get(field).isJsonNull() ? null : object.get(field).getAsString();
    }
}
