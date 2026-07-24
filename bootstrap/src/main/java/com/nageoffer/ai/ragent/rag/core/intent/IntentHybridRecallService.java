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

package com.nageoffer.ai.ragent.rag.core.intent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.rag.config.IntentPreFilterProperties;
import com.nageoffer.ai.ragent.rag.core.rewrite.QueryTermMappingService;
import com.nageoffer.ai.ragent.rag.dao.entity.QueryTermMappingDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 两阶段意图识别中的第一阶段高召回海选：
 * 规则命中 + BM25 关键词 + 向量检索，并行召回后融合打分，输出 TopN 候选给 LLM 精判。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntentHybridRecallService {

    private final EmbeddingService embeddingService;
    private final QueryTermMappingService queryTermMappingService;
    private final IntentPreFilterProperties properties;
    @Qualifier("intentClassifyThreadPoolExecutor")
    private final Executor intentClassifyExecutor;

    private final AtomicReference<IntentRecallIndex> indexCache = new AtomicReference<>();
    private final AtomicReference<String> vectorWarmupInProgress = new AtomicReference<>();

    public List<IntentNode> shortlist(String question, List<IntentNode> leafNodes) {
        if (!properties.isEnabled()
                || StrUtil.isBlank(question)
                || CollUtil.isEmpty(leafNodes)
                || leafNodes.size() <= properties.getCandidateLimit()) {
            return leafNodes;
        }

        long start = System.currentTimeMillis();
        IntentRecallIndex index = buildOrGetIndex(leafNodes);
        if (index.entries().isEmpty()) {
            return leafNodes;
        }

        CompletableFuture<RouteResult> ruleFuture = CompletableFuture.supplyAsync(
                () -> recallByRule(question, index),
                intentClassifyExecutor
        );
        CompletableFuture<RouteResult> keywordFuture = CompletableFuture.supplyAsync(
                () -> recallByKeyword(question, index),
                intentClassifyExecutor
        );
        CompletableFuture<RouteResult> vectorFuture = CompletableFuture.supplyAsync(
                () -> recallByVector(question, index),
                intentClassifyExecutor
        );

        List<RouteResult> routeResults = List.of(ruleFuture.join(), keywordFuture.join(), vectorFuture.join());
        List<IntentNode> finalCandidates = fuseAndSelect(index, routeResults);
        if (finalCandidates.isEmpty()) {
            return leafNodes;
        }

        long cost = System.currentTimeMillis() - start;
        log.info(
                "意图海选完成 - question='{}', leafCount={}, shortlistCount={}, routeStats=[{}], costMs={}",
                question,
                leafNodes.size(),
                finalCandidates.size(),
                routeResults.stream()
                        .map(r -> r.route().name() + "(hit=" + r.scores().size() + ",latencyMs=" + r.latencyMs() + ")")
                        .collect(Collectors.joining(", ")),
                cost
        );
        return finalCandidates;
    }

    private IntentRecallIndex buildOrGetIndex(List<IntentNode> leafNodes) {
        List<QueryTermMappingDO> mappings = queryTermMappingService.listEnabledMappingsSnapshot();
        String fingerprint = computeFingerprint(leafNodes, mappings);

        IntentRecallIndex cached = indexCache.get();
        if (cached != null && Objects.equals(cached.fingerprint(), fingerprint)) {
            return cached;
        }

        synchronized (this) {
            cached = indexCache.get();
            if (cached != null && Objects.equals(cached.fingerprint(), fingerprint)) {
                return cached;
            }
            IntentRecallIndex built = buildIndex(leafNodes, mappings, fingerprint);
            indexCache.set(built);
            if (properties.isVectorEnabled() && properties.isVectorAsyncWarmup() && !built.vectorReady()) {
                triggerAsyncVectorWarmup(built);
            }
            return built;
        }
    }

    private IntentRecallIndex buildIndex(List<IntentNode> leafNodes, List<QueryTermMappingDO> mappings, String fingerprint) {
        if (CollUtil.isEmpty(leafNodes)) {
            return new IntentRecallIndex(fingerprint, List.of(), Map.of(), Map.of(), 0D, false);
        }
        long start = System.currentTimeMillis();

        List<IntentNode> sortedNodes = leafNodes.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(IntentNode::getId, Comparator.nullsLast(String::compareTo)))
                .toList();

        List<IntentEntry> entries = new ArrayList<>(sortedNodes.size());
        Map<String, IntentEntry> idToEntry = new HashMap<>(sortedNodes.size() * 2);

        Map<String, Integer> docFreq = new HashMap<>();
        long totalDocLength = 0;
        for (IntentNode node : sortedNodes) {
            String rawText = buildIntentRetrievalText(node, mappings);
            String normalizedText = normalizeForContains(rawText);
            String compactText = compactForMatch(rawText);
            List<String> triggers = buildRuleTriggers(node, rawText);
            List<String> docTokens = tokenize(rawText);
            Map<String, Integer> tfMap = buildTfMap(docTokens);
            totalDocLength += docTokens.size();
            tfMap.keySet().forEach(token -> docFreq.merge(token, 1, Integer::sum));

            IntentEntry entry = new IntentEntry(
                    node,
                    rawText,
                    normalizedText,
                    compactText,
                    triggers,
                    tfMap,
                    docTokens.size(),
                    null
            );
            entries.add(entry);
            idToEntry.put(node.getId(), entry);
        }

        double avgDocLen = entries.isEmpty() ? 0D : (double) totalDocLength / entries.size();
        Map<String, Double> idfMap = buildIdfMap(docFreq, entries.size());

        boolean vectorReady = false;
        if (properties.isVectorEnabled() && !properties.isVectorAsyncWarmup()) {
            vectorReady = prepareVectors(entries);
        }

        long cost = System.currentTimeMillis() - start;
        log.info("意图海选索引构建完成 - intents={}, vectorReady={}, costMs={}", entries.size(), vectorReady, cost);

        return new IntentRecallIndex(
                fingerprint,
                List.copyOf(entries),
                Map.copyOf(idToEntry),
                Map.copyOf(idfMap),
                avgDocLen,
                vectorReady
        );
    }

    private void triggerAsyncVectorWarmup(IntentRecallIndex index) {
        if (index == null || CollUtil.isEmpty(index.entries())) {
            return;
        }
        String fingerprint = index.fingerprint();
        if (!vectorWarmupInProgress.compareAndSet(null, fingerprint)) {
            String inProgress = vectorWarmupInProgress.get();
            if (!Objects.equals(inProgress, fingerprint)) {
                log.debug("意图向量索引预热已在进行中，跳过本次触发，inProgress={}, current={}", inProgress, fingerprint);
            }
            return;
        }

        CompletableFuture.runAsync(() -> {
            long start = System.currentTimeMillis();
            boolean ready = prepareVectors(index.entries());
            long cost = System.currentTimeMillis() - start;
            if (ready) {
                markVectorReady(fingerprint);
                log.info("意图向量索引异步预热完成 - intents={}, costMs={}", index.entries().size(), cost);
            } else {
                log.warn("意图向量索引异步预热失败，将继续使用规则+关键词召回");
            }
        }, intentClassifyExecutor).whenComplete((unused, ex) -> {
            if (ex != null) {
                log.warn("意图向量索引异步预热异常，将继续使用规则+关键词召回", ex);
            }
            vectorWarmupInProgress.compareAndSet(fingerprint, null);
        });
    }

    private void markVectorReady(String fingerprint) {
        while (true) {
            IntentRecallIndex current = indexCache.get();
            if (current == null
                    || !Objects.equals(current.fingerprint(), fingerprint)
                    || current.vectorReady()) {
                return;
            }
            IntentRecallIndex updated = new IntentRecallIndex(
                    current.fingerprint(),
                    current.entries(),
                    current.idToEntry(),
                    current.idfMap(),
                    current.avgDocLength(),
                    true
            );
            if (indexCache.compareAndSet(current, updated)) {
                return;
            }
        }
    }

    private boolean prepareVectors(List<IntentEntry> entries) {
        if (CollUtil.isEmpty(entries)) {
            return false;
        }
        try {
            List<String> docs = entries.stream().map(IntentEntry::retrievalText).toList();
            List<List<Float>> vectors = embeddingService.embedBatch(docs);
            if (vectors == null || vectors.size() != entries.size()) {
                log.warn("意图向量海选索引构建失败：embedBatch 返回数量不匹配，expect={}, actual={}",
                        entries.size(), vectors == null ? 0 : vectors.size());
                return false;
            }
            for (int i = 0; i < entries.size(); i++) {
                List<Float> vector = vectors.get(i);
                if (CollUtil.isEmpty(vector)) {
                    continue;
                }
                entries.get(i).embedding = toFloatArray(vector);
            }
            return entries.stream().anyMatch(e -> e.embedding != null && e.embedding.length > 0);
        } catch (Exception ex) {
            log.warn("意图向量海选索引构建异常，将降级为规则+关键词召回", ex);
            return false;
        }
    }

    private RouteResult recallByRule(String question, IntentRecallIndex index) {
        if (!properties.isRuleEnabled()) {
            return RouteResult.empty(Route.RULE);
        }

        long start = System.currentTimeMillis();
        String normalizedQuestion = normalizeForContains(question);
        String compactQuestion = compactForMatch(question);
        if (StrUtil.isBlank(compactQuestion)) {
            return RouteResult.empty(Route.RULE);
        }

        List<RouteScore> scores = new ArrayList<>();
        int minLen = Math.max(1, properties.getMinRuleTriggerLength());
        for (IntentEntry entry : index.entries()) {
            double maxScore = 0D;
            for (String trigger : entry.triggers()) {
                if (StrUtil.isBlank(trigger)) {
                    continue;
                }
                if (compactQuestion.equals(trigger)) {
                    maxScore = 1D;
                    break;
                }
                if (trigger.length() >= minLen && compactQuestion.contains(trigger)) {
                    double ratio = (double) trigger.length() / Math.max(1, compactQuestion.length());
                    maxScore = Math.max(maxScore, 0.78D + Math.min(0.22D, ratio * 0.22D));
                } else if (compactQuestion.length() >= minLen && trigger.contains(compactQuestion)) {
                    maxScore = Math.max(maxScore, 0.68D);
                }
            }

            if (maxScore == 0D && StrUtil.isNotBlank(normalizedQuestion) && entry.normalizedText().contains(normalizedQuestion)) {
                maxScore = 0.72D;
            }
            if (maxScore > 0D) {
                scores.add(new RouteScore(entry.node().getId(), maxScore));
            }
        }

        List<RouteScore> topScores = topK(scores, Math.max(1, properties.getRuleTopK()));
        return new RouteResult(Route.RULE, topScores, System.currentTimeMillis() - start);
    }

    private RouteResult recallByKeyword(String question, IntentRecallIndex index) {
        if (!properties.isKeywordEnabled()) {
            return RouteResult.empty(Route.KEYWORD);
        }

        long start = System.currentTimeMillis();
        List<String> queryTokens = tokenize(question);
        if (CollUtil.isEmpty(queryTokens)) {
            return RouteResult.empty(Route.KEYWORD);
        }

        Set<String> uniqueQueryTokens = new HashSet<>(queryTokens);
        String compactQuestion = compactForMatch(question);
        int minLen = Math.max(1, properties.getMinRuleTriggerLength());
        double k1 = 1.2D;
        double b = 0.75D;

        List<RouteScore> scores = new ArrayList<>(index.entries().size());
        for (IntentEntry entry : index.entries()) {
            double score = 0D;
            for (String token : uniqueQueryTokens) {
                Integer tf = entry.tfMap().get(token);
                if (tf == null || tf <= 0) {
                    continue;
                }
                Double idf = index.idfMap().get(token);
                if (idf == null || idf <= 0D) {
                    continue;
                }
                double norm = tf + k1 * (1 - b + b * entry.docLength() / Math.max(1D, index.avgDocLength()));
                score += idf * tf * (k1 + 1) / norm;
            }

            if (score > 0D && compactQuestion.length() >= minLen && entry.compactText().contains(compactQuestion)) {
                score += 1.2D;
            }
            if (score > 0D) {
                scores.add(new RouteScore(entry.node().getId(), score));
            }
        }

        List<RouteScore> topScores = topK(scores, Math.max(1, properties.getKeywordTopK()));
        return new RouteResult(Route.KEYWORD, topScores, System.currentTimeMillis() - start);
    }

    private RouteResult recallByVector(String question, IntentRecallIndex index) {
        if (!properties.isVectorEnabled() || !index.vectorReady()) {
            return RouteResult.empty(Route.VECTOR);
        }

        long start = System.currentTimeMillis();
        float[] queryVector;
        try {
            queryVector = toFloatArray(embeddingService.embed(question));
        } catch (Exception ex) {
            log.warn("意图向量召回失败，将降级为规则+关键词召回", ex);
            return RouteResult.empty(Route.VECTOR);
        }
        if (queryVector.length == 0) {
            return RouteResult.empty(Route.VECTOR);
        }

        List<RouteScore> scores = new ArrayList<>();
        for (IntentEntry entry : index.entries()) {
            if (entry.embedding == null || entry.embedding.length == 0) {
                continue;
            }
            if (entry.embedding.length != queryVector.length) {
                continue;
            }
            double cosine = cosine(queryVector, entry.embedding);
            double normalized = (cosine + 1D) / 2D;
            if (normalized > 0D) {
                scores.add(new RouteScore(entry.node().getId(), normalized));
            }
        }

        List<RouteScore> topScores = topK(scores, Math.max(1, properties.getVectorTopK()));
        return new RouteResult(Route.VECTOR, topScores, System.currentTimeMillis() - start);
    }

    private List<IntentNode> fuseAndSelect(IntentRecallIndex index, List<RouteResult> routeResults) {
        if (CollUtil.isEmpty(routeResults)) {
            return List.of();
        }

        Map<Route, Double> weights = Map.of(
                Route.RULE, Math.max(0D, properties.getRuleWeight()),
                Route.KEYWORD, Math.max(0D, properties.getKeywordWeight()),
                Route.VECTOR, Math.max(0D, properties.getVectorWeight())
        );
        Map<Route, Integer> quotas = Map.of(
                Route.RULE, Math.max(0, properties.getRuleQuota()),
                Route.KEYWORD, Math.max(0, properties.getKeywordQuota()),
                Route.VECTOR, Math.max(0, properties.getVectorQuota())
        );

        Map<String, Double> fusedScores = new HashMap<>();
        Map<String, EnumSet<Route>> routeHits = new HashMap<>();
        Set<String> guaranteedIds = new LinkedHashSet<>();

        for (RouteResult routeResult : routeResults) {
            List<RouteScore> routeScores = routeResult.scores();
            if (CollUtil.isEmpty(routeScores)) {
                continue;
            }
            double maxRaw = routeScores.stream().mapToDouble(RouteScore::score).max().orElse(0D);
            double minRaw = routeScores.stream().mapToDouble(RouteScore::score).min().orElse(0D);
            int total = routeScores.size();
            int quota = quotas.getOrDefault(routeResult.route(), 0);
            for (int i = 0; i < total; i++) {
                RouteScore each = routeScores.get(i);
                double rankScore = total == 1 ? 1D : 1D - (double) i / (total - 1);
                double rawScore;
                if (Math.abs(maxRaw - minRaw) < 1e-9) {
                    rawScore = 1D;
                } else {
                    rawScore = (each.score() - minRaw) / (maxRaw - minRaw);
                }
                double contribution = weights.getOrDefault(routeResult.route(), 0D) * (0.65D * rankScore + 0.35D * rawScore);
                fusedScores.merge(each.intentId(), contribution, Double::sum);
                routeHits.computeIfAbsent(each.intentId(), ignore -> EnumSet.noneOf(Route.class)).add(routeResult.route());
                if (i < quota) {
                    guaranteedIds.add(each.intentId());
                }
            }
        }

        if (fusedScores.isEmpty()) {
            return List.of();
        }

        List<String> sortedAll = fusedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();

        List<String> guaranteedSorted = guaranteedIds.stream()
                .filter(fusedScores::containsKey)
                .sorted(Comparator.comparingDouble((String id) -> fusedScores.getOrDefault(id, 0D)).reversed())
                .toList();

        LinkedHashSet<String> merged = new LinkedHashSet<>();
        guaranteedSorted.forEach(merged::add);
        sortedAll.forEach(merged::add);

        int candidateLimit = effectiveCandidateLimit(index.entries().size());
        List<IntentNode> result = new ArrayList<>(candidateLimit);
        for (String intentId : merged) {
            IntentEntry entry = index.idToEntry().get(intentId);
            if (entry == null) {
                continue;
            }
            result.add(entry.node());
            if (result.size() >= candidateLimit) {
                break;
            }
        }
        return result;
    }

    private int effectiveCandidateLimit(int totalIntents) {
        int candidateLimit = properties.getCandidateLimit();
        candidateLimit = Math.max(candidateLimit, properties.getMinCandidateLimit());
        candidateLimit = Math.min(candidateLimit, properties.getMaxCandidateLimit());
        candidateLimit = Math.max(1, candidateLimit);
        return Math.min(candidateLimit, Math.max(1, totalIntents));
    }

    private String buildIntentRetrievalText(IntentNode node, List<QueryTermMappingDO> mappings) {
        String examples = CollUtil.isEmpty(node.getExamples()) ? "" : String.join(" / ", node.getExamples());
        String base = String.join("\n",
                "name=" + safe(node.getName()),
                "path=" + safe(node.getFullPath()),
                "description=" + safe(node.getDescription()),
                "examples=" + safe(examples)
        );

        List<String> aliases = resolveAliasTerms(base, mappings);
        if (aliases.isEmpty()) {
            return base;
        }
        return base + "\naliases=" + String.join(" / ", aliases);
    }

    private List<String> resolveAliasTerms(String retrievalTextBase, List<QueryTermMappingDO> mappings) {
        if (CollUtil.isEmpty(mappings) || StrUtil.isBlank(retrievalTextBase)) {
            return List.of();
        }
        String normalized = normalizeForContains(retrievalTextBase);
        if (StrUtil.isBlank(normalized)) {
            return List.of();
        }

        int maxAlias = Math.max(0, properties.getMaxAliasTermsPerIntent());
        if (maxAlias == 0) {
            return List.of();
        }

        List<String> aliases = new ArrayList<>();
        for (QueryTermMappingDO mapping : mappings) {
            if (mapping == null
                    || Boolean.FALSE.equals(mapping.getEnabled())
                    || mapping.getMatchType() != null && mapping.getMatchType() != 1
                    || StrUtil.isBlank(mapping.getSourceTerm())
                    || StrUtil.isBlank(mapping.getTargetTerm())) {
                continue;
            }
            String target = normalizeForContains(mapping.getTargetTerm());
            if (StrUtil.isBlank(target) || !normalized.contains(target)) {
                continue;
            }
            String source = mapping.getSourceTerm().trim();
            if (StrUtil.isBlank(source)) {
                continue;
            }
            aliases.add(source);
            if (aliases.size() >= maxAlias) {
                break;
            }
        }

        if (aliases.isEmpty()) {
            return List.of();
        }
        return aliases.stream()
                .distinct()
                .toList();
    }

    private List<String> buildRuleTriggers(IntentNode node, String retrievalText) {
        LinkedHashSet<String> triggers = new LinkedHashSet<>();
        int minLen = Math.max(1, properties.getMinRuleTriggerLength());

        addTrigger(triggers, node.getName(), minLen);
        addTrigger(triggers, node.getFullPath(), minLen);
        if (CollUtil.isNotEmpty(node.getExamples())) {
            node.getExamples().forEach(example -> addTrigger(triggers, example, minLen));
        }
        Arrays.stream(retrievalText.split("\n"))
                .filter(line -> line.startsWith("aliases="))
                .map(line -> line.substring("aliases=".length()))
                .flatMap(aliasLine -> Arrays.stream(aliasLine.split("/")))
                .forEach(alias -> addTrigger(triggers, alias, minLen));

        if (StrUtil.isNotBlank(node.getFullPath())) {
            Arrays.stream(node.getFullPath().split(">"))
                    .forEach(pathPart -> addTrigger(triggers, pathPart, minLen));
        }
        return List.copyOf(triggers);
    }

    private void addTrigger(Set<String> triggers, String raw, int minLen) {
        String compact = compactForMatch(raw);
        if (StrUtil.isBlank(compact) || compact.length() < minLen) {
            return;
        }
        triggers.add(compact);
    }

    private Map<String, Integer> buildTfMap(List<String> tokens) {
        Map<String, Integer> tf = new HashMap<>();
        for (String token : tokens) {
            if (StrUtil.isBlank(token)) {
                continue;
            }
            tf.merge(token, 1, Integer::sum);
        }
        return tf;
    }

    private Map<String, Double> buildIdfMap(Map<String, Integer> docFreq, int docCount) {
        if (docCount <= 0 || docFreq.isEmpty()) {
            return Map.of();
        }
        Map<String, Double> idfMap = new HashMap<>(docFreq.size() * 2);
        for (Map.Entry<String, Integer> each : docFreq.entrySet()) {
            int df = each.getValue() == null ? 0 : each.getValue();
            if (df <= 0) {
                continue;
            }
            double idf = Math.log1p((docCount - df + 0.5D) / (df + 0.5D));
            idfMap.put(each.getKey(), Math.max(0D, idf));
        }
        return idfMap;
    }

    private List<RouteScore> topK(List<RouteScore> scores, int topK) {
        if (CollUtil.isEmpty(scores) || topK <= 0) {
            return List.of();
        }
        return scores.stream()
                .filter(each -> each.score() > 0D)
                .sorted(Comparator.comparingDouble(RouteScore::score).reversed())
                .limit(topK)
                .toList();
    }

    private List<String> tokenize(String text) {
        if (StrUtil.isBlank(text)) {
            return List.of();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        StringBuilder han = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isLatinOrDigit(c)) {
                flushHanTokens(han, tokens);
                latin.append(c);
                continue;
            }
            flushLatinToken(latin, tokens);
            if (isHan(c)) {
                han.append(c);
                tokens.add(String.valueOf(c));
            } else {
                flushHanTokens(han, tokens);
            }
        }
        flushLatinToken(latin, tokens);
        flushHanTokens(han, tokens);
        return tokens;
    }

    private void flushLatinToken(StringBuilder latin, List<String> tokens) {
        if (latin.length() == 0) {
            return;
        }
        tokens.add(latin.toString());
        latin.setLength(0);
    }

    private void flushHanTokens(StringBuilder han, List<String> tokens) {
        if (han.length() == 0) {
            return;
        }
        String seq = han.toString();
        if (seq.length() > 1) {
            for (int i = 0; i < seq.length() - 1; i++) {
                tokens.add(seq.substring(i, i + 2));
            }
        }
        if (seq.length() <= 8) {
            tokens.add(seq);
        }
        han.setLength(0);
    }

    private boolean isLatinOrDigit(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '_'
                || c == '-';
    }

    private boolean isHan(char c) {
        Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN;
    }

    private String normalizeForContains(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT)
                .replace('\u3000', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String compactForMatch(String raw) {
        if (StrUtil.isBlank(raw)) {
            return "";
        }
        String normalized = normalizeForContains(raw);
        return normalized.replaceAll("[\\p{Punct}\\s]+", "");
    }

    private String computeFingerprint(List<IntentNode> leafNodes, List<QueryTermMappingDO> mappings) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            leafNodes.stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(IntentNode::getId, Comparator.nullsLast(String::compareTo)))
                    .forEach(node -> {
                        updateDigest(digest, node.getId());
                        updateDigest(digest, node.getName());
                        updateDigest(digest, node.getFullPath());
                        updateDigest(digest, node.getDescription());
                        if (CollUtil.isNotEmpty(node.getExamples())) {
                            node.getExamples().forEach(each -> updateDigest(digest, each));
                        }
                    });

            mappings.stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing((QueryTermMappingDO each) -> safe(each.getSourceTerm()))
                            .thenComparing(each -> safe(each.getTargetTerm())))
                    .forEach(mapping -> {
                        updateDigest(digest, mapping.getSourceTerm());
                        updateDigest(digest, mapping.getTargetTerm());
                        updateDigest(digest, mapping.getMatchType() == null ? "" : String.valueOf(mapping.getMatchType()));
                        updateDigest(digest, mapping.getEnabled() == null ? "" : String.valueOf(mapping.getEnabled()));
                    });
            return bytesToHex(digest.digest());
        } catch (Exception ex) {
            log.warn("计算意图海选索引指纹失败，降级使用弱指纹", ex);
            return String.valueOf(Objects.hash(leafNodes.size(), mappings.size()));
        }
    }

    private void updateDigest(MessageDigest digest, String text) {
        if (text == null) {
            digest.update((byte) 0);
            return;
        }
        digest.update(text.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte each : bytes) {
            sb.append(String.format("%02x", each));
        }
        return sb.toString();
    }

    private float[] toFloatArray(List<Float> values) {
        if (CollUtil.isEmpty(values)) {
            return new float[0];
        }
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    private double cosine(float[] q, float[] d) {
        if (q == null || d == null || q.length == 0 || d.length == 0 || q.length != d.length) {
            return 0D;
        }
        double dot = 0D;
        double nq = 0D;
        double nd = 0D;
        for (int i = 0; i < q.length; i++) {
            dot += q[i] * d[i];
            nq += q[i] * q[i];
            nd += d[i] * d[i];
        }
        if (nq == 0D || nd == 0D) {
            return 0D;
        }
        return dot / (Math.sqrt(nq) * Math.sqrt(nd));
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private enum Route {
        RULE,
        KEYWORD,
        VECTOR
    }

    private record RouteScore(String intentId, double score) {
    }

    private record RouteResult(Route route, List<RouteScore> scores, long latencyMs) {

        private static RouteResult empty(Route route) {
            return new RouteResult(route, List.of(), 0L);
        }
    }

    private static class IntentEntry {

        private final IntentNode node;
        private final String retrievalText;
        private final String normalizedText;
        private final String compactText;
        private final List<String> triggers;
        private final Map<String, Integer> tfMap;
        private final int docLength;
        private float[] embedding;

        private IntentEntry(IntentNode node,
                            String retrievalText,
                            String normalizedText,
                            String compactText,
                            List<String> triggers,
                            Map<String, Integer> tfMap,
                            int docLength,
                            float[] embedding) {
            this.node = node;
            this.retrievalText = retrievalText;
            this.normalizedText = normalizedText;
            this.compactText = compactText;
            this.triggers = List.copyOf(triggers);
            this.tfMap = Map.copyOf(tfMap);
            this.docLength = docLength;
            this.embedding = embedding;
        }

        private IntentNode node() {
            return node;
        }

        private String retrievalText() {
            return retrievalText;
        }

        private String normalizedText() {
            return normalizedText;
        }

        private String compactText() {
            return compactText;
        }

        private List<String> triggers() {
            return triggers;
        }

        private Map<String, Integer> tfMap() {
            return tfMap;
        }

        private int docLength() {
            return docLength;
        }
    }

    private record IntentRecallIndex(
            String fingerprint,
            List<IntentEntry> entries,
            Map<String, IntentEntry> idToEntry,
            Map<String, Double> idfMap,
            double avgDocLength,
            boolean vectorReady
    ) {
    }
}
