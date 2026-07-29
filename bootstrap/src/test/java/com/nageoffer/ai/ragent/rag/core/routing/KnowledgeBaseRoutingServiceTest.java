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

import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.rag.config.KnowledgeBaseRoutingProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.EntityResolution;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.EntityResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeBaseRoutingServiceTest {

    @Test
    void routesProductQuestionByKeywordWithoutCallingLlm() {
        EntityResolver entityResolver = mock(EntityResolver.class);
        when(entityResolver.resolveAny(anyString(), anyString())).thenReturn(EntityResolution.notFound());
        LLMService llmService = mock(LLMService.class);

        KnowledgeBaseRoutingService service = service(entityResolver, llmService);
        KnowledgeBaseRoute route = service.route("小米手机的屏幕参数是什么？");

        assertEquals("digital-store-v1", route.profileId());
        assertEquals("2002", route.kbId());
        assertEquals("keyword", route.strategy());
        verify(llmService, never()).chat(org.mockito.ArgumentMatchers.any(ChatRequest.class));
    }

    @Test
    void routesByEntityEvenWhenQuestionDoesNotContainRouteKeyword() {
        EntityResolver entityResolver = mock(EntityResolver.class);
        when(entityResolver.resolveAny("查一下 Galaxy S25 的参数", "1001"))
                .thenReturn(EntityResolution.notFound());
        when(entityResolver.resolveAny("查一下 Galaxy S25 的参数", "2002"))
                .thenReturn(EntityResolution.matched("Galaxy S25", "product", "sku-25", "Galaxy S25"));
        LLMService llmService = mock(LLMService.class);

        KnowledgeBaseRoutingService service = service(entityResolver, llmService);
        KnowledgeBaseRoute route = service.route("查一下 Galaxy S25 的参数");

        assertEquals("digital-store-v1", route.profileId());
        assertEquals("entity", route.strategy());
        verify(llmService, never()).chat(org.mockito.ArgumentMatchers.any(ChatRequest.class));
    }

    private KnowledgeBaseRoutingService service(EntityResolver entityResolver, LLMService llmService) {
        KnowledgeBaseRoutingProperties properties = new KnowledgeBaseRoutingProperties();
        properties.setProfiles(List.of(profile("genshin-v7", "1001", List.of("原神", "命座")),
                profile("digital-store-v1", "2002", List.of("手机", "商品参数"))));
        return new KnowledgeBaseRoutingService(properties, new KnowledgeBaseProfileRegistry(properties), entityResolver, llmService);
    }

    private KnowledgeBaseRoutingProperties.Profile profile(String id, String kbId, List<String> keywords) {
        KnowledgeBaseRoutingProperties.Profile profile = new KnowledgeBaseRoutingProperties.Profile();
        profile.setId(id);
        profile.setKbId(kbId);
        profile.setDescription(id);
        profile.setRouteKeywords(keywords);
        return profile;
    }
}
