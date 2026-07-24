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

package com.nageoffer.ai.ragent.rag.Intent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.core.intent.IntentHybridRecallService;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.dao.entity.IntentNodeDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.IntentNodeMapper;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.rag.enums.IntentLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@SpringBootTest(properties = {
        "rag.intent.prefilter.vector-enabled=false"
})
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class IntentPrefilterAbEvalTests {

    private final IntentHybridRecallService intentHybridRecallService;
    private final IntentNodeMapper intentNodeMapper;
    private final ObjectMapper objectMapper;

    @Test
    public void evalPrefilterABAndExportArtifacts() throws Exception {
        List<IntentNode> leafNodes = loadLeafNodesFromDb();
        Assertions.assertFalse(leafNodes.isEmpty(), "leafNodes is empty, cannot run intent prefilter evaluation");

        List<EvalSample> samples = buildEvalSamples(leafNodes);
        Assertions.assertFalse(samples.isEmpty(), "eval samples is empty, cannot run intent prefilter evaluation");

        int baselineCandidateSize = leafNodes.size();
        int hitCount = 0;
        int fallbackToFullCount = 0;
        long totalShortlistSize = 0L;
        List<Long> latencyMs = new ArrayList<>(samples.size());
        List<Map<String, Object>> perQueryRows = new ArrayList<>(samples.size());

        for (EvalSample sample : samples) {
            long start = System.nanoTime();
            List<IntentNode> shortlist = intentHybridRecallService.shortlist(sample.query(), leafNodes);
            long costMs = (System.nanoTime() - start) / 1_000_000L;

            boolean hit = shortlist.stream().anyMatch(each -> Objects.equals(each.getId(), sample.goldIntentId()));
            if (hit) {
                hitCount++;
            }
            if (shortlist.size() >= baselineCandidateSize) {
                fallbackToFullCount++;
            }
            totalShortlistSize += shortlist.size();
            latencyMs.add(costMs);

            perQueryRows.add(Map.of(
                    "query", sample.query(),
                    "gold_intent_id", sample.goldIntentId(),
                    "shortlist_size", shortlist.size(),
                    "hit", hit,
                    "latency_ms", costMs,
                    "top5_intents", shortlist.stream().map(IntentNode::getId).limit(5).collect(Collectors.joining("|"))
            ));
        }

        double recallAt80 = hitCount * 1.0D / samples.size();
        double avgShortlistSize = totalShortlistSize * 1.0D / samples.size();
        double candidateReduction = baselineCandidateSize <= 0
                ? 0D
                : (baselineCandidateSize - avgShortlistSize) / baselineCandidateSize;

        List<Long> sortedLatency = latencyMs.stream().sorted().toList();
        long p50 = percentile(sortedLatency, 0.50D);
        long p95 = percentile(sortedLatency, 0.95D);
        long p99 = percentile(sortedLatency, 0.99D);

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("sample_count", samples.size());
        metrics.put("leaf_intent_count", baselineCandidateSize);
        metrics.put("baseline_candidate_size", baselineCandidateSize);
        metrics.put("prefilter_avg_candidate_size", round(avgShortlistSize));
        metrics.put("prefilter_candidate_reduction_ratio", round(candidateReduction));
        metrics.put("prefilter_recall_at_80", round(recallAt80));
        metrics.put("prefilter_hit_count", hitCount);
        metrics.put("prefilter_fallback_to_full_count", fallbackToFullCount);
        metrics.put("prefilter_fallback_to_full_ratio", round(fallbackToFullCount * 1.0D / samples.size()));
        metrics.put("prefilter_latency_ms_p50", p50);
        metrics.put("prefilter_latency_ms_p95", p95);
        metrics.put("prefilter_latency_ms_p99", p99);

        String runTag = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path outputDir = Path.of("tests", "eval_rag", "artifacts", "intent_prefilter_ab", runTag);
        Files.createDirectories(outputDir);

        writeJson(outputDir.resolve("metrics.json"), metrics);
        writeCsv(outputDir.resolve("per_query.csv"), perQueryRows);
        writeSummary(outputDir.resolve("summary.md"), metrics);

        log.info("Intent prefilter A/B evaluation completed, sampleCount={}, outputDir={}", samples.size(), outputDir.toAbsolutePath());
    }

    private List<IntentNode> loadLeafNodesFromDb() {
        List<IntentNodeDO> rows = intentNodeMapper.selectList(
                Wrappers.lambdaQuery(IntentNodeDO.class)
                        .eq(IntentNodeDO::getDeleted, 0)
                        .eq(IntentNodeDO::getEnabled, 1)
        );
        if (CollUtil.isEmpty(rows)) {
            return List.of();
        }

        Map<String, IntentNode> id2Node = new HashMap<>(rows.size() * 2);
        for (IntentNodeDO each : rows) {
            IntentNode node = IntentNode.builder()
                    .id(each.getIntentCode())
                    .kbId(each.getKbId() == null ? null : String.valueOf(each.getKbId()))
                    .name(each.getName())
                    .description(each.getDescription())
                    .level(IntentLevel.fromCode(each.getLevel()))
                    .parentId(each.getParentCode())
                    .examples(parseExamples(each.getExamples()))
                    .children(new ArrayList<>())
                    .fullPath("")
                    .kind(IntentKind.fromCode(each.getKind()))
                    .collectionName(each.getCollectionName())
                    .mcpToolId(each.getMcpToolId())
                    .topK(each.getTopK())
                    .promptSnippet(each.getPromptSnippet())
                    .promptTemplate(each.getPromptTemplate())
                    .paramPromptTemplate(each.getParamPromptTemplate())
                    .build();
            id2Node.put(node.getId(), node);
        }

        List<IntentNode> roots = new ArrayList<>();
        for (IntentNode node : id2Node.values()) {
            String parentId = node.getParentId();
            if (StrUtil.isBlank(parentId)) {
                roots.add(node);
                continue;
            }
            IntentNode parent = id2Node.get(parentId);
            if (parent == null) {
                roots.add(node);
                continue;
            }
            parent.getChildren().add(node);
        }

        fillFullPath(roots, null);
        return flatten(roots).stream().filter(IntentNode::isLeaf).toList();
    }

    private List<EvalSample> buildEvalSamples(List<IntentNode> leafNodes) {
        Map<String, Set<String>> query2IntentIds = new HashMap<>();
        for (IntentNode node : leafNodes) {
            if (CollUtil.isEmpty(node.getExamples())) {
                continue;
            }
            for (String query : node.getExamples()) {
                String trimmed = query == null ? "" : query.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                query2IntentIds.computeIfAbsent(trimmed, ignore -> new HashSet<>()).add(node.getId());
            }
        }

        List<EvalSample> samples = new ArrayList<>();
        for (Map.Entry<String, Set<String>> each : query2IntentIds.entrySet()) {
            if (each.getValue().size() != 1) {
                continue;
            }
            String goldId = each.getValue().iterator().next();
            samples.add(new EvalSample(each.getKey(), goldId));
        }
        samples.sort(Comparator.comparing(EvalSample::query));
        return samples;
    }

    private List<String> parseExamples(String raw) {
        if (StrUtil.isBlank(raw)) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(raw, new TypeReference<>() {
            });
            return parsed == null ? List.of() : parsed.stream().filter(StrUtil::isNotBlank).toList();
        } catch (Exception ignore) {
            return List.of(raw);
        }
    }

    private void fillFullPath(List<IntentNode> nodes, IntentNode parent) {
        if (CollUtil.isEmpty(nodes)) {
            return;
        }
        for (IntentNode node : nodes) {
            if (parent == null) {
                node.setFullPath(node.getName());
            } else {
                node.setFullPath(parent.getFullPath() + " > " + node.getName());
            }
            fillFullPath(node.getChildren(), node);
        }
    }

    private List<IntentNode> flatten(List<IntentNode> roots) {
        List<IntentNode> result = new ArrayList<>();
        List<IntentNode> stack = new ArrayList<>(roots);
        while (!stack.isEmpty()) {
            IntentNode node = stack.remove(stack.size() - 1);
            result.add(node);
            if (CollUtil.isNotEmpty(node.getChildren())) {
                stack.addAll(node.getChildren());
            }
        }
        return result;
    }

    private long percentile(List<Long> sorted, double p) {
        if (CollUtil.isEmpty(sorted)) {
            return 0L;
        }
        int idx = (int) Math.ceil(sorted.size() * p) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }

    private double round(double value) {
        return Math.round(value * 10000D) / 10000D;
    }

    private void writeJson(Path path, Map<String, Object> metrics) throws Exception {
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(metrics);
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    private void writeCsv(Path path, List<Map<String, Object>> rows) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("query,gold_intent_id,shortlist_size,hit,latency_ms,top5_intents\n");
        for (Map<String, Object> row : rows) {
            sb.append(csv(row.get("query"))).append(',')
                    .append(csv(row.get("gold_intent_id"))).append(',')
                    .append(row.get("shortlist_size")).append(',')
                    .append(row.get("hit")).append(',')
                    .append(row.get("latency_ms")).append(',')
                    .append(csv(row.get("top5_intents")))
                    .append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private void writeSummary(Path path, Map<String, Object> metrics) throws Exception {
        String summary = """
                # Intent Prefilter A/B Summary

                - Sample count: %s
                - Leaf intent count (baseline candidate size): %s
                - Prefilter avg candidate size: %s
                - Candidate reduction ratio: %s
                - Prefilter Recall@80: %s
                - Prefilter fallback-to-full ratio: %s
                - Prefilter latency P50/P95/P99 (ms): %s / %s / %s
                """.formatted(
                metrics.get("sample_count"),
                metrics.get("leaf_intent_count"),
                metrics.get("prefilter_avg_candidate_size"),
                metrics.get("prefilter_candidate_reduction_ratio"),
                metrics.get("prefilter_recall_at_80"),
                metrics.get("prefilter_fallback_to_full_ratio"),
                metrics.get("prefilter_latency_ms_p50"),
                metrics.get("prefilter_latency_ms_p95"),
                metrics.get("prefilter_latency_ms_p99")
        );
        Files.writeString(path, summary, StandardCharsets.UTF_8);
    }

    private String csv(Object raw) {
        String text = raw == null ? "" : String.valueOf(raw);
        String escaped = text.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private record EvalSample(String query, String goldIntentId) {
    }
}
