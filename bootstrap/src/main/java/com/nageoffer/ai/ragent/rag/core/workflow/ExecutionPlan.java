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

import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import java.util.List;

/** Business steps, deliberately independent of the KB RetrievalPlan. */
public record ExecutionPlan(List<Step> steps, String clarification) {
    public ExecutionPlan {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public record Step(String id, IntentKind kind, String question, String targetId, List<String> dependsOn) {
        public Step {
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        }
    }
}
