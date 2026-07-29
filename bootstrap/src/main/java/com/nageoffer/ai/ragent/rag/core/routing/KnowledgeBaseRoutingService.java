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

/**
 * Fast corpus router.  Keyword/entity matches handle normal traffic; a tiny
 * LLM prompt is used only when those deterministic signals are absent or tied.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseRoutingService {

    private final KnowledgeBaseRoutingProperties properties;
    private final KnowledgeBaseProfileRegistry profileRegistry;
    private final EntityResolver entityResolver;
    private final LLMService llmService;

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
            if (normalizedKeyword.length() >= 2 && normalizedQuestion.contains(normalizedKeyword)) {
                keywordHits++;
            }
        }
        EntityResolution entity = entityResolver.resolveAny(question, profile.getKbId());
        return new Candidate(profile, keywordHits + (entity.isMatched() ? 8.0D : 0.0D), entity.isMatched());
    }

    private KnowledgeBaseRoute resolveByLlm(String question, List<Candidate> candidates) {
        String choices = candidates.stream().map(candidate -> "- id=" + candidate.profile().getId()
                        + ", description=" + candidate.profile().getDescription())
                .reduce("", (left, right) -> left + "\n" + right);
        String prompt = "You route a user question to one knowledge base. Return only one profile id from the choices, or UNKNOWN."
                + "\nChoices:" + choices + "\nQuestion: " + question;
        try {
            String answer = llmService.chat(ChatRequest.builder()
                    .messages(List.of(ChatMessage.system(prompt), ChatMessage.user(question)))
                    .temperature(0.0D).topP(0.1D).thinking(false).build());
            String normalized = normalize(answer);
            return candidates.stream()
                    .filter(candidate -> normalized.contains(normalize(candidate.profile().getId())))
                    .findFirst()
                    .map(candidate -> resolved(candidate, "llm", 0.60D))
                    .orElseGet(KnowledgeBaseRoute::unresolved);
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

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim().toLowerCase(Locale.ROOT);
    }

    private record Candidate(KnowledgeBaseRoutingProperties.Profile profile, double score, boolean entityMatched) {
    }
}
