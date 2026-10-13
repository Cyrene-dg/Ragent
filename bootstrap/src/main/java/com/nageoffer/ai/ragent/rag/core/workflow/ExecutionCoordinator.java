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
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.DEFAULT_TOP_K;

@Service
@RequiredArgsConstructor
public class ExecutionCoordinator {
    private final KnowledgeQueryService knowledge;
    private final ToolExecutionService tools;
    private final CapabilityCatalog catalog;
    private final ExecutionPlanValidator validator;
    @Qualifier("ragContextThreadPoolExecutor")
    private final Executor executor;

    @RagTraceNode(name = "execution-coordinator", type = "WORKFLOW")
    public List<StepResult> execute(ExecutionPlan plan) {
        var snapshot = catalog.snapshot();
        validator.validate(plan, snapshot);
        var futures = plan.steps().stream().map(step -> CompletableFuture.supplyAsync(
                () -> executeOne(step, snapshot), executor)).toList();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private StepResult executeOne(ExecutionPlan.Step step, CapabilityCatalog.Snapshot snapshot) {
        IntentNode target = step.targetId() == null ? null : snapshot.targets().get(step.targetId());
        try {
            return switch (step.kind()) {
                case SYSTEM -> new StepResult(step, target, null, null, null);
                case KB -> new StepResult(step, null, knowledge.query(step.question(), DEFAULT_TOP_K), null, null);
                case MCP -> new StepResult(step, target, null, tools.execute(step.question(), target), null);
            };
        } catch (Exception exception) {
            return new StepResult(step, target, null, null, "步骤执行失败: " + exception.getMessage());
        }
    }

    public record StepResult(ExecutionPlan.Step step, IntentNode target,
                             KnowledgeQueryService.KnowledgeResult knowledge, MCPResponse tool, String error) {}
}
