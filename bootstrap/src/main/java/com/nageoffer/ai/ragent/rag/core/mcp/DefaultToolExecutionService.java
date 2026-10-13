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
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DefaultToolExecutionService implements ToolExecutionService {
    private final MCPToolRegistry registry;
    private final MCPParameterExtractor extractor;

    @Override
    @RagTraceNode(name = "tool-execution", type = "MCP")
    public MCPResponse execute(String question, IntentNode intent) {
        if (intent == null || !intent.isMCP() || intent.getMcpToolId() == null) {
            throw new IllegalArgumentException("Tool execution requires an MCP target");
        }
        String toolId = intent.getMcpToolId();
        var executor = registry.getExecutor(toolId);
        if (executor.isEmpty()) {
            return MCPResponse.error(toolId, "TOOL_NOT_FOUND", "工具不存在: " + toolId);
        }
        try {
            MCPTool tool = executor.get().getToolDefinition();
            Map<String, Object> extracted = extractor.extractParameters(question, tool, intent.getParamPromptTemplate());
            Map<String, Object> parameters = new HashMap<>();
            for (var entry : (tool.getParameters() == null ? Map.<String, MCPTool.ParameterDef>of() : tool.getParameters()).entrySet()) {
                String name = entry.getKey();
                var definition = entry.getValue();
                Object value = tool.isRequireUserId() && "userId".equals(name)
                        ? com.nageoffer.ai.ragent.framework.context.UserContext.getUserId()
                        : extracted == null ? null : extracted.get(name);
                if (value == null) value = definition.getDefaultValue();
                if (value == null || value instanceof String text && text.isBlank()) {
                    if (definition.isRequired()) {
                        return MCPResponse.error(toolId, "MISSING_PARAMETER", "缺少必要参数: " + name);
                    }
                    continue;
                }
                boolean validType = switch (definition.getType()) {
                    case "number", "integer" -> value instanceof Number;
                    case "boolean" -> value instanceof Boolean;
                    case "array" -> value instanceof List;
                    case "object" -> value instanceof Map;
                    default -> value instanceof String;
                };
                if (!validType || definition.getEnumValues() != null && !definition.getEnumValues().isEmpty()
                        && !definition.getEnumValues().contains(String.valueOf(value))) {
                    return MCPResponse.error(toolId, "INVALID_PARAMETER", "参数不符合工具定义: " + name);
                }
                parameters.put(name, value);
            }
            return executor.get().execute(MCPRequest.builder().toolId(toolId).userQuestion(question).parameters(parameters).build());
        } catch (Exception exception) {
            return MCPResponse.error(toolId, "EXECUTION_ERROR", "工具调用失败: " + exception.getMessage());
        }
    }
}
