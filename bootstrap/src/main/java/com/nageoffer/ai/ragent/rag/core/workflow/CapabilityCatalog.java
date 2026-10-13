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

import com.nageoffer.ai.ragent.rag.core.intent.IntentClassifier;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.mcp.MCPToolRegistry;
import com.nageoffer.ai.ragent.rag.core.routing.KnowledgeBaseProfileRegistry;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Reads capability descriptions only; never runs entity matching, retrieval or tools. */
@Service
@RequiredArgsConstructor
public class CapabilityCatalog {
    @Qualifier("defaultIntentClassifier")
    private final IntentClassifier classifier;
    private final MCPToolRegistry tools;
    private final KnowledgeBaseProfileRegistry profiles;

    public Snapshot snapshot() {
        Map<String, IntentNode> targets = new LinkedHashMap<>();
        Map<String, java.util.Set<String>> knowledgeDescriptions = new LinkedHashMap<>();
        StringBuilder description = new StringBuilder("SYSTEM: ordinary conversation/general reasoning without private data.\n"
                + "KB: retrieve private documents; select corpora only inside the KB service.\n"
                + "MCP: call one of the registered business tools below.\n");
        for (var node : classifier.leafTargets()) {
            if (node.isKB()) {
                String path = node.getFullPath() == null ? node.getName() : node.getFullPath();
                if (path != null) knowledgeDescriptions.computeIfAbsent(String.valueOf(node.getKbId()),
                        ignored -> new java.util.LinkedHashSet<>()).add(path);
            }
            if (node.isSystem() || node.isMCP() && tools.contains(node.getMcpToolId())) {
                targets.put(node.getId(), node);
            }
        }
        for (var tool : tools.listAllTools()) {
            String id = "tool:" + tool.getToolId();
            targets.put(id, IntentNode.builder().id(id).kind(IntentKind.MCP).mcpToolId(tool.getToolId())
                    .description(tool.getDescription()).build());
        }
        targets.forEach((id, node) -> description.append(node.getKind()).append(" targetId=").append(id)
                .append(": ").append(node.getName()).append(" ").append(node.getDescription()).append("\n"));
        profiles.enabledProfiles().forEach(profile -> description.append("KB domain: ")
                .append(profile.getDescription()).append("\n"));
        knowledgeDescriptions.forEach((id, names) -> description.append("Other KB domain: ")
                .append(String.join("; ", names)).append("\n"));
        return new Snapshot(Map.copyOf(targets), description.toString());
    }

    public record Snapshot(Map<String, IntentNode> targets, String description) {}
}
