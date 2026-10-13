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

package com.nageoffer.ai.ragent.rag.core.knowledge;

import com.nageoffer.ai.ragent.rag.dto.KbResult;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import java.util.List;

/** All corpus navigation, intent/entity matching and retrieval stay behind this boundary. */
public interface KnowledgeQueryService {
    KnowledgeResult query(String question, int topK);

    /** Compatibility entry for callers that already resolved KB-only targets. */
    KbResult retrieveResolved(SubQuestionIntent intent, int topK);

    record KnowledgeResult(List<SubQuestionIntent> resolutions, KbResult result, String clarification) {
        public KnowledgeResult {
            resolutions = List.copyOf(resolutions);
        }
    }
}
