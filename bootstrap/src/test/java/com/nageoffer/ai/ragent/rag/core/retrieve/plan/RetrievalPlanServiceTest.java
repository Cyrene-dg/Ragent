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

package com.nageoffer.ai.ragent.rag.core.retrieve.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.rag.config.MetadataRetrievalProperties;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RetrievalPlanServiceTest {

    @TempDir
    Path tempDirectory;

    private RetrievalPlanService retrievalPlanService;

    @BeforeEach
    void setUp() throws IOException {
        Path routes = tempDirectory.resolve("intent-routes.json");
        Path catalog = tempDirectory.resolve("entity-catalog.jsonl");
        Files.writeString(routes, """
                [
                  {"intentCode":"genshin_v7_character_skill","metadataFilters":{"category":"character","doc_type":["character_skill","character_constellation"]}},
                  {"intentCode":"genshin_v7_weapon_detail","metadataFilters":{"category":"weapon","doc_type":"weapon_profile"}}
                ]
                """, StandardCharsets.UTF_8);
        Files.writeString(catalog, """
                {"entity_type":"character","entity_id":"10000002","entity_name":"神里绫华","aliases":["神里绫华","绫华","Kamisato Ayaka"],"category":"character","source_document":"characters/ayaka.md","source_version":"v7"}
                {"entity_type":"character","entity_id":"10000007","entity_name":"旅行者（空）","aliases":["旅行者"],"category":"character","source_document":"characters/aether.md","source_version":"v7"}
                {"entity_type":"character","entity_id":"10000005","entity_name":"旅行者（荧）","aliases":["旅行者"],"category":"character","source_document":"characters/lumine.md","source_version":"v7"}
                {"entity_type":"weapon","entity_id":"11501","entity_name":"狼的末路","aliases":["狼末","狼的末路"],"category":"weapon","source_document":"weapons/wgs.md","source_version":"v7"}
                """, StandardCharsets.UTF_8);

        MetadataRetrievalProperties properties = new MetadataRetrievalProperties();
        properties.setIntentRoutesLocation("file:" + routes.toAbsolutePath());
        properties.setEntityCatalogLocation("file:" + catalog.toAbsolutePath());
        ObjectMapper objectMapper = new ObjectMapper();
        DefaultResourceLoader resourceLoader = new DefaultResourceLoader();
        EntityCatalogService entityCatalogService = new EntityCatalogService(objectMapper, resourceLoader, properties);
        EntityResolver entityResolver = new EntityResolver(entityCatalogService);
        IntentRouteRuleService routeRuleService = new IntentRouteRuleService(objectMapper, resourceLoader, properties);
        retrievalPlanService = new RetrievalPlanService(properties, routeRuleService, entityResolver);
    }

    @Test
    void buildsFiveLevelPlanForMatchedCharacterAlias() {
        RetrievalPlan plan = retrievalPlanService.buildPlan("绫华大招怎么用", List.of(score("genshin_v7_character_skill")));

        IntentRetrievalPlan intentPlan = plan.intentPlans().get(0);
        assertEquals(EntityResolutionStatus.MATCHED, intentPlan.entityResolution().status());
        assertEquals("10000002", intentPlan.entityResolution().entityId());
        assertEquals(5, intentPlan.filterLevels().size());
        assertEquals("10000002", intentPlan.filterLevels().get(0).metadataFilters().get("entity_id"));
        assertEquals("character", intentPlan.filterLevels().get(0).metadataFilters().get("category"));
        assertEquals(List.of("character_skill", "character_constellation"),
                intentPlan.filterLevels().get(0).metadataFilters().get("doc_type"));
        assertEquals("v7-global", intentPlan.filterLevels().get(intentPlan.filterLevels().size() - 1).name());
    }

    @Test
    void keepsRouteFiltersButDoesNotInventEntityForGenericQuestion() {
        RetrievalPlan plan = retrievalPlanService.buildPlan("元素战技怎么用", List.of(score("genshin_v7_character_skill")));

        IntentRetrievalPlan intentPlan = plan.intentPlans().get(0);
        assertEquals(EntityResolutionStatus.NOT_FOUND, intentPlan.entityResolution().status());
        assertEquals(3, intentPlan.filterLevels().size());
        assertFalse(intentPlan.filterLevels().stream()
                .anyMatch(level -> level.metadataFilters().containsKey("entity_id")));
    }

    @Test
    void marksAmbiguousAliasWithoutAddingAnEntityFilter() {
        RetrievalPlan plan = retrievalPlanService.buildPlan("旅行者的元素战技", List.of(score("genshin_v7_character_skill")));

        IntentRetrievalPlan intentPlan = plan.intentPlans().get(0);
        assertEquals(EntityResolutionStatus.AMBIGUOUS, intentPlan.entityResolution().status());
        assertEquals(2, intentPlan.entityResolution().candidateEntityIds().size());
        assertFalse(intentPlan.filterLevels().stream()
                .anyMatch(level -> level.metadataFilters().containsKey("entity_id")));
    }

    @Test
    void createsSeparatePlansForSeparateIntentRoutes() {
        RetrievalPlan plan = retrievalPlanService.buildPlan("绫华技能和狼末被动", List.of(
                score("genshin_v7_character_skill"),
                score("genshin_v7_weapon_detail")
        ));

        assertEquals(2, plan.intentPlans().size());
        assertEquals("character", plan.intentPlans().get(0).routeFilters().get("category"));
        assertEquals("weapon", plan.intentPlans().get(1).routeFilters().get("category"));
        assertEquals("10000002", plan.intentPlans().get(0).entityResolution().entityId());
        assertEquals("11501", plan.intentPlans().get(1).entityResolution().entityId());
    }

    private NodeScore score(String intentCode) {
        return NodeScore.builder()
                .node(IntentNode.builder().id(intentCode).name(intentCode).build())
                .score(0.9)
                .build();
    }
}
