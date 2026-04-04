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

package com.nageoffer.ai.ragent.mcp.executor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.mcp.core.MCPToolDefinition;
import com.nageoffer.ai.ragent.mcp.core.MCPToolExecutor;
import com.nageoffer.ai.ragent.mcp.core.MCPToolRequest;
import com.nageoffer.ai.ragent.mcp.core.MCPToolResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Component
public class TicketMCPExecutor implements MCPToolExecutor {

    private static final String TOOL_ID = "ticket_query";
    private static final List<String> STATUS_ENUM = List.of("待处理", "处理中", "已解决");
    private static final List<String> PRIORITY_ENUM = List.of("紧急", "高", "中", "低");
    private static final List<String> SOURCE_ENUM = List.of("all", "task", "bug", "story");

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${zentao.api-base-url:http://127.0.0.1/zentao/api.php/v1}")
    private String zentaoApiBaseUrl;
    @Value("${zentao.account:admin}")
    private String zentaoAccount;
    @Value("${zentao.password:Ghr040707}")
    private String zentaoPassword;
    @Value("${zentao.product-id:1}")
    private int zentaoProductId;
    @Value("${zentao.execution-id:3}")
    private int zentaoExecutionId;
    @Value("${zentao.page-limit:200}")
    private int zentaoPageLimit;

    private volatile String cachedToken;
    private volatile long tokenExpireAtMs;

    public TicketMCPExecutor(RestTemplateBuilder restTemplateBuilder, ObjectMapper objectMapper) {
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(8))
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public MCPToolDefinition getToolDefinition() {
        Map<String, MCPToolDefinition.ParameterDef> parameters = new LinkedHashMap<>();

        parameters.put("status", MCPToolDefinition.ParameterDef.builder()
                .description("工单状态筛选：待处理、处理中、已解决，不填则查询全部状态")
                .type("string")
                .required(false)
                .enumValues(STATUS_ENUM)
                .build());

        parameters.put("source", MCPToolDefinition.ParameterDef.builder()
                .description("工单来源筛选：all（全部）、task（任务）、bug（缺陷）、story（需求）")
                .type("string")
                .required(false)
                .defaultValue("all")
                .enumValues(SOURCE_ENUM)
                .build());

        parameters.put("priority", MCPToolDefinition.ParameterDef.builder()
                .description("优先级筛选：紧急、高、中、低，不填则查询全部优先级")
                .type("string")
                .required(false)
                .enumValues(PRIORITY_ENUM)
                .build());

        parameters.put("keyword", MCPToolDefinition.ParameterDef.builder()
                .description("关键字筛选，匹配标题内容")
                .type("string")
                .required(false)
                .build());

        parameters.put("queryType", MCPToolDefinition.ParameterDef.builder()
                .description("查询类型：summary(汇总概览)、list(工单列表)、stats(统计分析)")
                .type("string")
                .required(false)
                .defaultValue("summary")
                .enumValues(List.of("summary", "list", "stats"))
                .build());

        parameters.put("limit", MCPToolDefinition.ParameterDef.builder()
                .description("返回记录数限制，默认10")
                .type("integer")
                .required(false)
                .defaultValue(10)
                .build());

        return MCPToolDefinition.builder()
                .toolId(TOOL_ID)
                .description("查询禅道真实工单数据，支持按状态、来源、优先级和关键字筛选，返回汇总、列表或统计结果")
                .parameters(parameters)
                .requireUserId(true)
                .build();
    }

    @Override
    public MCPToolResponse execute(MCPToolRequest request) {
        try {
            String status = request.getStringParameter("status");
            String source = request.getStringParameter("source");
            String priority = request.getStringParameter("priority");
            String keyword = request.getStringParameter("keyword");
            String queryType = request.getStringParameter("queryType");
            Integer limit = request.getParameter("limit");

            if (queryType == null || queryType.isBlank()) queryType = "summary";
            if (source == null || source.isBlank()) source = "all";
            if (limit == null || limit <= 0) limit = 10;

            List<TicketRecord> allData = loadAllTickets();
            List<TicketRecord> filtered = filterData(allData, status, source, priority, keyword);

            String result = switch (queryType) {
                case "list" -> buildListResult(filtered, limit);
                case "stats" -> buildStatsResult(filtered);
                default -> buildSummaryResult(filtered, status, source, priority, keyword);
            };

            return MCPToolResponse.success(TOOL_ID, result);
        } catch (Exception e) {
            log.error("禅道工单查询失败", e);
            return MCPToolResponse.error(TOOL_ID, "EXECUTION_ERROR", "查询失败: " + e.getMessage());
        }
    }

    private String buildSummaryResult(List<TicketRecord> data, String status, String source,
                                      String priority, String keyword) {
        int total = data.size();
        long pending = data.stream().filter(t -> "待处理".equals(t.status)).count();
        long inProgress = data.stream().filter(t -> "处理中".equals(t.status)).count();
        long resolved = data.stream().filter(t -> "已解决".equals(t.status)).count();
        long urgent = data.stream().filter(t -> "紧急".equals(t.priority)).count();
        long high = data.stream().filter(t -> "高".equals(t.priority)).count();

        StringBuilder sb = new StringBuilder();
        sb.append("【禅道工单汇总】\n\n");

        List<String> filters = new ArrayList<>();
        if (status != null && !status.isBlank()) filters.add("状态: " + normalizeStatusFilter(status));
        if (source != null && !source.isBlank() && !"all".equalsIgnoreCase(source)) filters.add("来源: " + source.toLowerCase(Locale.ROOT));
        if (priority != null && !priority.isBlank()) filters.add("优先级: " + normalizePriorityFilter(priority));
        if (keyword != null && !keyword.isBlank()) filters.add("关键字: " + keyword.trim());
        if (!filters.isEmpty()) sb.append("筛选条件: ").append(String.join("，", filters)).append("\n\n");

        sb.append(String.format("工单总数: %d 个\n\n", total));
        sb.append("【状态分布】\n");
        sb.append(String.format("  待处理: %d 个\n", pending));
        sb.append(String.format("  处理中: %d 个\n", inProgress));
        sb.append(String.format("  已解决: %d 个\n\n", resolved));

        if (total > 0) {
            double resolveRate = resolved * 100.0 / total;
            sb.append(String.format("解决率: %.1f%%\n", resolveRate));
        }

        if (urgent + high > 0) {
            sb.append(String.format("\n紧急/高优先级工单: %d 个（紧急 %d，高 %d）\n", urgent + high, urgent, high));
        }

        Map<String, Long> bySource = data.stream()
                .collect(Collectors.groupingBy(t -> t.sourceType, Collectors.counting()));
        if (!bySource.isEmpty()) {
            sb.append("\n【来源分布】\n");
            bySource.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .forEach(e -> sb.append(String.format("  %s: %d 个\n", e.getKey(), e.getValue())));
        }

        return sb.toString().trim();
    }

    private String buildListResult(List<TicketRecord> data, int limit) {
        List<TicketRecord> sorted = data.stream()
                .sorted((a, b) -> {
                    int pa = priorityWeight(a.priority);
                    int pb = priorityWeight(b.priority);
                    if (pa != pb) return Integer.compare(pa, pb);
                    int sa = statusWeight(a.status);
                    int sb = statusWeight(b.status);
                    if (sa != sb) return Integer.compare(sa, sb);
                    return b.openedDate.compareTo(a.openedDate);
                })
                .limit(limit)
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("【工单列表】共 %d 条，显示 %d 条（按优先级排序）\n\n", data.size(), sorted.size()));

        for (int i = 0; i < sorted.size(); i++) {
            TicketRecord t = sorted.get(i);
            sb.append(String.format("%d. [%s] %s\n", i + 1, t.ticketId, t.title));
            sb.append(String.format("   来源: %s | 优先级: %s | 状态: %s\n", t.sourceType, t.priority, t.status));
            sb.append(String.format("   指派: %s | 原始状态: %s | 创建时间: %s\n\n", t.assignee, t.rawStatus, t.openedDate));
        }

        return sb.toString().trim();
    }

    private String buildStatsResult(List<TicketRecord> data) {
        StringBuilder sb = new StringBuilder();
        sb.append("【工单统计分析】\n\n");

        if (data.isEmpty()) {
            sb.append("暂无工单数据");
            return sb.toString();
        }

        Map<String, Long> bySource = data.stream()
                .collect(Collectors.groupingBy(t -> t.sourceType, Collectors.counting()));
        sb.append("【来源统计】\n");
        bySource.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> sb.append(String.format("  %s: %d 个 (%.1f%%)\n",
                        e.getKey(), e.getValue(), e.getValue() * 100.0 / data.size())));

        sb.append("\n【优先级统计】\n");
        Map<String, Long> byPriority = data.stream()
                .collect(Collectors.groupingBy(t -> t.priority, Collectors.counting()));
        PRIORITY_ENUM.forEach(level -> {
            long count = byPriority.getOrDefault(level, 0L);
            if (count > 0) {
                sb.append(String.format("  %s: %d 个\n", level, count));
            }
        });

        sb.append("\n【待处理/处理中负责人负载】\n");
        Map<String, Long> byAssignee = data.stream()
                .filter(t -> !"已解决".equals(t.status))
                .collect(Collectors.groupingBy(t -> t.assignee, Collectors.counting()));
        byAssignee.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .forEach(e -> sb.append(String.format("  %s: %d 个\n", e.getKey(), e.getValue())));

        return sb.toString().trim();
    }

    private List<TicketRecord> filterData(List<TicketRecord> data, String status, String source,
                                          String priority, String keyword) {
        String normalizedStatus = normalizeStatusFilter(status);
        String normalizedSource = source == null ? "all" : source.trim().toLowerCase(Locale.ROOT);
        String normalizedPriority = normalizePriorityFilter(priority);
        String normalizedKeyword = keyword == null ? null : keyword.trim().toLowerCase(Locale.ROOT);

        return data.stream()
                .filter(t -> normalizedStatus == null || normalizedStatus.equals(t.status))
                .filter(t -> "all".equals(normalizedSource) || normalizedSource.equals(t.sourceType))
                .filter(t -> normalizedPriority == null || normalizedPriority.equals(t.priority))
                .filter(t -> normalizedKeyword == null || t.title.toLowerCase(Locale.ROOT).contains(normalizedKeyword))
                .toList();
    }

    private List<TicketRecord> loadAllTickets() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("Token", getValidToken());

        List<TicketRecord> records = new ArrayList<>();
        records.addAll(fetchTasks(headers));
        records.addAll(fetchBugs(headers));
        records.addAll(fetchStories(headers));
        return records;
    }

    private List<TicketRecord> fetchTasks(HttpHeaders headers) {
        JsonNode root = getWithRetry("/executions/" + zentaoExecutionId + "/tasks?status=all&limit=" + zentaoPageLimit, headers);
        JsonNode tasks = root.path("tasks");
        if (!tasks.isArray()) return List.of();

        List<TicketRecord> records = new ArrayList<>();
        for (JsonNode task : tasks) {
            TicketRecord record = new TicketRecord();
            record.sourceType = "task";
            record.ticketId = "TASK-" + safeText(task, "id", "-");
            record.title = safeText(task, "name", "(无标题任务)");
            record.rawStatus = safeText(task, "status", "unknown");
            record.status = mapTaskStatus(record.rawStatus);
            record.priority = mapPriority(safeText(task, "pri", "3"));
            record.assignee = extractUser(task.get("assignedTo"));
            record.openedDate = normalizeDate(safeText(task, "openedDate", ""));
            records.add(record);
        }
        return records;
    }

    private List<TicketRecord> fetchBugs(HttpHeaders headers) {
        JsonNode root = getWithRetry("/products/" + zentaoProductId + "/bugs?status=all&limit=" + zentaoPageLimit, headers);
        JsonNode bugs = root.path("bugs");
        if (!bugs.isArray()) return List.of();

        List<TicketRecord> records = new ArrayList<>();
        for (JsonNode bug : bugs) {
            TicketRecord record = new TicketRecord();
            record.sourceType = "bug";
            record.ticketId = "BUG-" + safeText(bug, "id", "-");
            record.title = safeText(bug, "title", "(无标题缺陷)");
            record.rawStatus = safeText(bug, "status", "unknown");
            record.status = mapBugStatus(record.rawStatus);
            record.priority = mapPriority(safeText(bug, "pri", "3"));
            record.assignee = extractUser(bug.get("assignedTo"));
            record.openedDate = normalizeDate(safeText(bug, "openedDate", ""));
            records.add(record);
        }
        return records;
    }

    private List<TicketRecord> fetchStories(HttpHeaders headers) {
        JsonNode root = getWithRetry("/products/" + zentaoProductId + "/stories?status=all&limit=" + zentaoPageLimit, headers);
        JsonNode stories = root.path("stories");
        if (!stories.isArray()) return List.of();

        List<TicketRecord> records = new ArrayList<>();
        for (JsonNode story : stories) {
            TicketRecord record = new TicketRecord();
            record.sourceType = "story";
            record.ticketId = "STORY-" + safeText(story, "id", "-");
            record.title = safeText(story, "title", "(无标题需求)");
            record.rawStatus = safeText(story, "status", "unknown");
            String stage = safeText(story, "stage", "");
            record.status = mapStoryStatus(record.rawStatus, stage);
            record.priority = mapPriority(safeText(story, "pri", "3"));
            record.assignee = extractUser(story.get("assignedTo"));
            record.openedDate = normalizeDate(safeText(story, "openedDate", ""));
            records.add(record);
        }
        return records;
    }

    private JsonNode getWithRetry(String path, HttpHeaders headers) {
        try {
            return doGet(path, headers);
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().value() == 401 || ex.getStatusCode().value() == 403) {
                headers.set("Token", refreshToken());
                return doGet(path, headers);
            }
            throw ex;
        }
    }

    private JsonNode doGet(String path, HttpHeaders headers) {
        String url = normalizeBaseUrl() + path;
        ResponseEntity<String> response = restTemplate.exchange(
                url,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class
        );
        String body = Optional.ofNullable(response.getBody()).orElse("{}");
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("解析禅道响应失败: " + e.getMessage(), e);
        }
    }

    private String getValidToken() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < tokenExpireAtMs) {
            return cachedToken;
        }
        return refreshToken();
    }

    private synchronized String refreshToken() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < tokenExpireAtMs) {
            return cachedToken;
        }
        try {
            String url = normalizeBaseUrl() + "/tokens";
            Map<String, String> body = Map.of("account", zentaoAccount, "password", zentaoPassword);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    String.class
            );
            JsonNode jsonNode = objectMapper.readTree(Optional.ofNullable(response.getBody()).orElse("{}"));
            String token = jsonNode.path("token").asText("");
            if (token.isBlank()) {
                throw new IllegalStateException("token 为空");
            }

            String expiredDate = jsonNode.path("expiredDate").asText("");
            long expireAt = parseExpireAt(expiredDate).orElseGet(() -> now + Duration.ofMinutes(20).toMillis());
            cachedToken = token;
            tokenExpireAtMs = expireAt - Duration.ofSeconds(30).toMillis();
            return cachedToken;
        } catch (Exception e) {
            throw new IllegalStateException("获取禅道 token 失败: " + e.getMessage(), e);
        }
    }

    private Optional<Long> parseExpireAt(String expiredDate) {
        if (expiredDate == null || expiredDate.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(OffsetDateTime.parse(expiredDate).toInstant().toEpochMilli());
        } catch (Exception ignored) {
            try {
                return Optional.of(Instant.parse(expiredDate).toEpochMilli());
            } catch (Exception ignored2) {
                return Optional.empty();
            }
        }
    }

    private String normalizeBaseUrl() {
        String trimmed = zentaoApiBaseUrl == null ? "" : zentaoApiBaseUrl.trim();
        if (trimmed.endsWith("/")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String safeText(JsonNode node, String fieldName, String defaultValue) {
        if (node == null) return defaultValue;
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) return defaultValue;
        String text = value.asText("");
        return text == null || text.isBlank() ? defaultValue : text;
    }

    private String extractUser(JsonNode userNode) {
        if (userNode == null || userNode.isNull()) return "未指派";
        if (userNode.isObject()) {
            String realname = safeText(userNode, "realname", "");
            if (!realname.isBlank()) return realname;
            String account = safeText(userNode, "account", "");
            if (!account.isBlank()) return account;
            return "未指派";
        }
        String text = userNode.asText("");
        return text.isBlank() ? "未指派" : text;
    }

    private String normalizeDate(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) return "-";
        return rawDate.length() >= 10 ? rawDate.substring(0, 10) : rawDate;
    }

    private String mapPriority(String priRaw) {
        int pri = 3;
        try {
            pri = Integer.parseInt(Objects.requireNonNullElse(priRaw, "3"));
        } catch (Exception ignored) {
        }
        return switch (pri) {
            case 1 -> "紧急";
            case 2 -> "高";
            case 4 -> "低";
            default -> "中";
        };
    }

    private String mapTaskStatus(String rawStatus) {
        return switch (safeLower(rawStatus)) {
            case "wait" -> "待处理";
            case "doing", "pause" -> "处理中";
            case "done", "closed", "cancel" -> "已解决";
            default -> "处理中";
        };
    }

    private String mapBugStatus(String rawStatus) {
        return switch (safeLower(rawStatus)) {
            case "resolved", "closed" -> "已解决";
            case "active" -> "处理中";
            default -> "处理中";
        };
    }

    private String mapStoryStatus(String rawStatus, String stage) {
        String status = safeLower(rawStatus);
        if ("closed".equals(status)) return "已解决";
        if ("active".equals(status) || "changing".equals(status)) return "处理中";
        if ("reviewing".equals(status) || "draft".equals(status)) return "待处理";

        String stageLower = safeLower(stage);
        return switch (stageLower) {
            case "wait", "planned", "projected" -> "待处理";
            case "designing", "developing", "testing", "delivering" -> "处理中";
            default -> "已解决";
        };
    }

    private String normalizeStatusFilter(String status) {
        if (status == null || status.isBlank()) return null;
        String normalized = safeLower(status);
        return switch (normalized) {
            case "待处理", "pending", "todo", "wait", "reviewing", "draft" -> "待处理";
            case "处理中", "processing", "in-progress", "doing", "active" -> "处理中";
            case "已解决", "resolved", "done", "closed", "finish", "finished" -> "已解决";
            default -> status.trim();
        };
    }

    private String normalizePriorityFilter(String priority) {
        if (priority == null || priority.isBlank()) return null;
        String normalized = safeLower(priority);
        return switch (normalized) {
            case "紧急", "urgent", "p1", "1" -> "紧急";
            case "高", "high", "p2", "2" -> "高";
            case "中", "medium", "p3", "3" -> "中";
            case "低", "low", "p4", "4" -> "低";
            default -> priority.trim();
        };
    }

    private int priorityWeight(String priority) {
        return switch (priority) {
            case "紧急" -> 1;
            case "高" -> 2;
            case "中" -> 3;
            default -> 4;
        };
    }

    private int statusWeight(String status) {
        return switch (status) {
            case "处理中" -> 1;
            case "待处理" -> 2;
            default -> 3;
        };
    }

    private String safeLower(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static class TicketRecord {
        String sourceType;
        String ticketId;
        String title;
        String status;
        String rawStatus;
        String priority;
        String assignee;
        String openedDate;
    }
}
