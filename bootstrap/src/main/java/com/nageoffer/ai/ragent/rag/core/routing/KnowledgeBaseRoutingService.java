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

package com.nageoffer.ai.ragent.rag.core.routing;

import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.config.KnowledgeBaseRoutingProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.EntityResolution;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.EntityResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fast corpus router.  Keyword/entity matches handle normal traffic; a tiny
 * LLM prompt is used only when those deterministic signals are absent or tied.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseRoutingService {

    /**
     * These words describe a field or operation but not a corpus.  Treating one
     * of them as a deterministic route signal would send questions such as
     * "这个接口为什么返回 500" to the product corpus before the LLM can reject it.
     */
    private static final Set<String> GENERIC_ROUTE_KEYWORDS = Set.of(
            "参数", "型号", "接口", "技能", "流程", "状态"
    );

    private final KnowledgeBaseRoutingProperties properties;
    private final KnowledgeBaseProfileRegistry profileRegistry;
    private final EntityResolver entityResolver;
    private final LLMService llmService;

    /** KB-local multi-corpus routing. Extra profiles describe legacy intent-bound corpora. */
    public List<KnowledgeBaseRoute> routeAll(String question,
                                            List<KnowledgeBaseRoutingProperties.Profile> extraProfiles) {
        Map<String, KnowledgeBaseRoutingProperties.Profile> profiles = new java.util.LinkedHashMap<>();
        profileRegistry.enabledProfiles().forEach(profile -> profiles.put(profile.getKbId(), profile));
        extraProfiles.forEach(profile -> profiles.putIfAbsent(profile.getKbId(), profile));
        if (!StringUtils.hasText(question) || profiles.isEmpty()) {
            return List.of();
        }
        Set<String> managed = profileRegistry.enabledProfiles().stream()
                .map(KnowledgeBaseRoutingProperties.Profile::getKbId).collect(Collectors.toSet());
        // Legacy corpora have no private entity catalog; never reuse another corpus's default catalog to score them.
        List<Candidate> candidates = profiles.values().stream()
                .map(profile -> managed.contains(profile.getKbId()) ? score(question, profile) : new Candidate(profile, 0, false))
                .toList();
        List<KnowledgeBaseRoute> hits = candidates.stream().filter(candidate -> candidate.score() > 0)
                .map(candidate -> resolved(candidate, candidate.entityMatched() ? "entity" : "keyword", 0.9D))
                .toList();
        if (!hits.isEmpty()) {
            return hits;
        }
        if (!properties.isLlmFallbackEnabled()) {
            return List.of();
        }
        String choices = candidates.stream().map(candidate -> candidate.profile().getId() + ": "
                + candidate.profile().getDescription()).collect(Collectors.joining("\n"));
        String raw = llmService.chat(ChatRequest.builder()
                .messages(List.of(ChatMessage.system("Select the knowledge bases explicitly needed by the question. "
                        + "Return a JSON array of profile IDs from the list; use [] if none or unclear. "
                        + "Multiple profiles are allowed only when each is relevant.\n" + choices), ChatMessage.user(question)))
                .thinking(false).temperature(0D).build());
        var array = com.google.gson.JsonParser.parseString(
                com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner.stripMarkdownCodeFence(raw)).getAsJsonArray();
        Set<String> selected = new java.util.LinkedHashSet<>();
        array.forEach(value -> selected.add(value.getAsString()));
        Set<String> allowed = candidates.stream().map(candidate -> candidate.profile().getId()).collect(Collectors.toSet());
        if (!allowed.containsAll(selected)) {
            throw new IllegalArgumentException("Knowledge router returned an unregistered profile");
        }
        return candidates.stream().filter(candidate -> selected.contains(candidate.profile().getId()))
                .map(candidate -> resolved(candidate, "llm", 0.6D)).toList();
    }

    public KnowledgeBaseRoute route(String question) {
        if (!profileRegistry.isRoutingEnabled() || !StringUtils.hasText(question)) {
            return KnowledgeBaseRoute.unresolved();
        }
        List<Candidate> candidates = profileRegistry.enabledProfiles().stream()
                .map(profile -> score(question, profile))
                .sorted(Comparator.comparingDouble(Candidate::score).reversed())
                .toList();
        if (candidates.isEmpty()) {
            return KnowledgeBaseRoute.unresolved();
        }
        Candidate first = candidates.get(0);
        double second = candidates.size() > 1 ? candidates.get(1).score() : 0.0D;
        if (first.score() > 0.0D && first.score() > second) {
            return logDecision(resolved(first, first.entityMatched() ? "entity" : "keyword",
                    Math.min(0.99D, 0.70D + first.score() * 0.05D)));
        }
        return logDecision(properties.isLlmFallbackEnabled()
                ? resolveByLlm(question, candidates)
                : KnowledgeBaseRoute.unresolved());
    }

    private Candidate score(String question, KnowledgeBaseRoutingProperties.Profile profile) {
        String normalizedQuestion = normalize(question);
        int keywordHits = 0;
        for (String keyword : profile.getRouteKeywords() == null ? List.<String>of() : profile.getRouteKeywords()) {
            String normalizedKeyword = normalize(keyword);
            if (normalizedKeyword.length() >= 2
                    && !GENERIC_ROUTE_KEYWORDS.contains(normalizedKeyword)
                    && normalizedQuestion.contains(normalizedKeyword)) {
                keywordHits++;
            }
        }
        EntityResolution entity = entityResolver.resolveAny(question, profile.getKbId());
        return new Candidate(profile, keywordHits + (entity.isMatched() ? 8.0D : 0.0D), entity.isMatched());
    }

    private KnowledgeBaseRoute resolveByLlm(String question, List<Candidate> candidates) {
        String choices = candidates.stream().map(candidate -> "- id=" + candidate.profile().getId()
                        + ", description=" + candidate.profile().getDescription()
                        + ", identifyingKeywords=" + candidate.profile().getRouteKeywords())
                .reduce("", (left, right) -> left + "\n" + right);
        String prompt = "You are a conservative router for managed knowledge bases. "
                + "Deterministic entity and keyword routing could not decide this question, so UNKNOWN is the default. "
                + "Choose a profile only when the question contains clear, corpus-specific evidence. "
                + "Do not guess the nearest profile from generic words such as character, skill, parameter, process, or status. "
                + "A character, product, or concept from another or unspecified domain is UNKNOWN, even when it asks for a data type stored by one profile. "
                + "For example, a character from a different game must not be routed to a Genshin profile merely because the question asks about skills. "
                + "Return exactly one profile id from the choices, or UNKNOWN when evidence is absent or uncertain."
                + "\nChoices:" + choices + "\nQuestion: " + question;
        try {
            String answer = llmService.chat(ChatRequest.builder()
                    .messages(List.of(ChatMessage.system(prompt), ChatMessage.user(question)))
                    .temperature(0.0D).topP(0.1D).thinking(false).build());
            String normalized = normalizeRouteAnswer(answer);
            return candidates.stream()
                    .filter(candidate -> normalized.equals(normalize(candidate.profile().getId())))
                    .findFirst()
                    .map(candidate -> resolved(candidate, "llm", 0.60D))
                    .orElseGet(KnowledgeBaseRoute::outOfDomain);
        } catch (Exception exception) {
            log.warn("Knowledge-base LLM routing failed; preserve legacy global intent flow", exception);
            return KnowledgeBaseRoute.unresolved();
        }
    }

    private static KnowledgeBaseRoute resolved(Candidate candidate, String strategy, double confidence) {
        return new KnowledgeBaseRoute(candidate.profile().getKbId(), candidate.profile().getId(), strategy, confidence);
    }

    private KnowledgeBaseRoute logDecision(KnowledgeBaseRoute route) {
        log.info("knowledge-base route strategy={}, profile={}, kbId={}, confidence={}",
                route.strategy(), route.profileId(), route.kbId(), route.confidence());
        return route;
    }

    /** Managed corpora are excluded only after an explicit UNKNOWN decision, never after an LLM failure. */
    public Set<String> managedKbIds() {
        return profileRegistry.enabledProfiles().stream()
                .map(KnowledgeBaseRoutingProperties.Profile::getKbId)
                .filter(StringUtils::hasText)
                .collect(Collectors.toUnmodifiableSet());
    }

    public String entityFallbackCollection(String kbId) {
        return profileRegistry.findByKbId(kbId)
                .map(KnowledgeBaseRoutingProperties.Profile::getEntityFallbackCollectionName).orElse(null);
    }

    private static String normalizeRouteAnswer(String value) {
        if (value == null) {
            return "";
        }
        String firstLine = value.strip().lines().findFirst().orElse("").trim();
        return normalize(firstLine.replace("`", "").replace("\"", "").replace("'", ""));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim().toLowerCase(Locale.ROOT);
    }

    private record Candidate(KnowledgeBaseRoutingProperties.Profile profile, double score, boolean entityMatched) {
    }
}
