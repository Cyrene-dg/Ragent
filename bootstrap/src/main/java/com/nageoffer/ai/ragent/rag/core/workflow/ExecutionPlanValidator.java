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

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.HashSet;

@Service
public class ExecutionPlanValidator {
    private final int maxSteps;

    public ExecutionPlanValidator(@Value("${rag.workflow.max-steps:16}") int maxSteps) {
        if (maxSteps <= 0) throw new IllegalArgumentException("max-steps must be positive");
        this.maxSteps = maxSteps;
    }

    public void validate(ExecutionPlan plan, CapabilityCatalog.Snapshot catalog) {
        if (plan == null) throw new IllegalArgumentException("Missing execution plan");
        if (plan.clarification() != null && !plan.clarification().isBlank()) {
            if (!plan.steps().isEmpty()) throw new IllegalArgumentException("A clarification plan cannot execute steps");
            return;
        }
        if (plan.steps().isEmpty() || plan.steps().size() > maxSteps) throw new IllegalArgumentException("Invalid step count");
        var ids = new HashSet<String>();
        for (var step : plan.steps()) {
            if (step.id() == null || step.id().isBlank() || !ids.add(step.id()))
                throw new IllegalArgumentException("Invalid or duplicate step ID");
            if (step.kind() == null || step.question() == null || step.question().isBlank())
                throw new IllegalArgumentException("A step requires capability and query");
            if (!step.dependsOn().isEmpty())
                throw new IllegalArgumentException("Dependent execution is not enabled in this migration phase");
            if (step.targetId() != null && !step.targetId().isBlank()) {
                var target = catalog.targets().get(step.targetId());
                if (target == null || target.getKind() != step.kind())
                    throw new IllegalArgumentException("Target is outside this capability catalog");
            } else if (step.kind() == com.nageoffer.ai.ragent.rag.enums.IntentKind.MCP) {
                throw new IllegalArgumentException("MCP step requires a registered target");
            }
        }
    }
}
