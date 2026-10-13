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

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ResponseAssembler {
    private final ContextFormatter formatter;

    public AssembledResponse assemble(List<ExecutionCoordinator.StepResult> results) {
        StringBuilder kb = new StringBuilder();
        StringBuilder mcp = new StringBuilder();
        var kbTargets = new ArrayList<NodeScore>();
        var toolTargets = new ArrayList<NodeScore>();
        var instructions = new ArrayList<String>();
        var failures = new ArrayList<String>();
        Map<String, List<RetrievedChunk>> chunks = new LinkedHashMap<>();
        String clarification = null;
        for (var result : results) {
            if (result.error() != null) failures.add(result.step().id() + ": " + result.error());
            if (result.step().kind() == com.nageoffer.ai.ragent.rag.enums.IntentKind.SYSTEM) {
                String template = result.target() == null ? null : result.target().getPromptTemplate();
                if (template != null && !template.isBlank()) instructions.add(template);
            }
            if (result.knowledge() != null) {
                var knowledge = result.knowledge();
                if (knowledge.clarification() != null) clarification = knowledge.clarification();
                knowledge.resolutions().forEach(intent -> kbTargets.addAll(intent.nodeScores()));
                if (!knowledge.result().groupedContext().isBlank()) {
                    kb.append("\n### 子问题：").append(result.step().question()).append("\n")
                            .append(knowledge.result().groupedContext()).append("\n");
                }
                knowledge.result().intentChunks().forEach((key, values) -> chunks.merge(key, values, (left, right) -> {
                    var merged = new ArrayList<>(left);
                    merged.addAll(right);
                    return merged.stream().distinct().toList();
                }));
            }
            if (result.tool() != null) {
                var scores = List.of(new NodeScore(result.target(), 1D));
                toolTargets.addAll(scores);
                if (result.tool().isSuccess()) {
                    mcp.append("\n### 子问题：").append(result.step().question()).append("\n")
                            .append(formatter.formatMcpContext(List.of(result.tool()), scores)).append("\n");
                } else {
                    failures.add(result.step().id() + ": " + result.tool().getErrorMessage());
                }
            }
        }
        var context = RetrievalContext.builder().kbContext(kb.toString().trim()).mcpContext(mcp.toString().trim())
                .intentChunks(chunks).build();
        return new AssembledResponse(context, new IntentGroup(toolTargets, kbTargets),
                List.copyOf(instructions), List.copyOf(failures), clarification);
    }

    public record AssembledResponse(RetrievalContext context, IntentGroup intents, List<String> directInstructions,
                                    List<String> failures, String clarification) {}
}
