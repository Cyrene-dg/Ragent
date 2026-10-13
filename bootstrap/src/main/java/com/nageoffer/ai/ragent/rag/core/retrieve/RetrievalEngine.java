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

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.knowledge.KnowledgeQueryService;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPResponse;
import com.nageoffer.ai.ragent.rag.core.mcp.ToolExecutionService;
import com.nageoffer.ai.ragent.rag.core.prompt.ContextFormatter;
import com.nageoffer.ai.ragent.rag.dto.RetrievalContext;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.INTENT_MIN_SCORE;

/** Compatibility adapter. New requests use the capability execution coordinator. */
@Service
@RequiredArgsConstructor
public class RetrievalEngine {
    private final KnowledgeQueryService knowledgeService;
    private final ToolExecutionService toolService;
    private final ContextFormatter formatter;
    @Qualifier("ragContextThreadPoolExecutor")
    private final Executor contextExecutor;
    @Qualifier("mcpBatchThreadPoolExecutor")
    private final Executor toolExecutor;

    @RagTraceNode(name = "retrieval-engine", type = "RETRIEVE")
    public RetrievalContext retrieve(List<SubQuestionIntent> intents, int topK) {
        if (intents == null || intents.isEmpty()) return empty();
        var tasks = intents.stream().map(intent -> CompletableFuture.supplyAsync(
                () -> retrieveOne(intent, topK), contextExecutor)).toList();
        StringBuilder kb = new StringBuilder();
        StringBuilder mcp = new StringBuilder();
        Map<String, List<RetrievedChunk>> chunks = new LinkedHashMap<>();
        for (var task : tasks) {
            var result = task.join();
            kb.append(result.getKbContext()).append("\n");
            mcp.append(result.getMcpContext()).append("\n");
            result.getIntentChunks().forEach((key, value) -> chunks.merge(key, value, (left, right) -> {
                var merged = new ArrayList<>(left);
                merged.addAll(right);
                return merged.stream().distinct().toList();
            }));
        }
        return RetrievalContext.builder().kbContext(kb.toString().trim()).mcpContext(mcp.toString().trim())
                .intentChunks(chunks).build();
    }

    private RetrievalContext retrieveOne(SubQuestionIntent intent, int topK) {
        var kbTargets = targets(intent).stream().filter(score -> score.getNode().isKB()).toList();
        var tools = targets(intent).stream().filter(score -> score.getNode().isMCP()).toList();
        var result = empty();
        if (!kbTargets.isEmpty()) {
            var kbIntent = new SubQuestionIntent(intent.subQuestion(), kbTargets,
                    intent.entityResolution(), intent.knowledgeBaseRoute());
            var kb = knowledgeService.retrieveResolved(kbIntent, topK);
            result.setKbContext(kb.groupedContext());
            result.setIntentChunks(kb.intentChunks());
        }
        List<CompletableFuture<MCPResponse>> calls = tools.stream().map(score -> CompletableFuture.supplyAsync(
                () -> toolService.execute(intent.subQuestion(), score.getNode()), toolExecutor)).toList();
        if (!calls.isEmpty()) {
            result.setMcpContext(formatter.formatMcpContext(calls.stream().map(CompletableFuture::join).toList(), tools));
        }
        return result;
    }

    private List<NodeScore> targets(SubQuestionIntent intent) {
        return intent.nodeScores().stream().filter(score -> score.getNode() != null)
                .filter(score -> score.getScore() >= INTENT_MIN_SCORE).toList();
    }

    private RetrievalContext empty() {
        return RetrievalContext.builder().kbContext("").mcpContext("").intentChunks(Map.of()).build();
    }
}
