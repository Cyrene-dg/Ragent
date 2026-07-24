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

package com.nageoffer.ai.ragent.rag.es;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.rag.config.KeywordEsChannelProperties;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordEsGatewayMetadataTest {

    @Test
    void shouldCopyRoutingMetadataToExactFilterFieldsAndPreserveRawMetadata() {
        KeywordEsGateway gateway = new KeywordEsGateway(new ObjectMapper(), new OkHttpClient(), new KeywordEsChannelProperties());
        KnowledgeChunkDO chunk = KnowledgeChunkDO.builder()
                .id(101L)
                .content("神里绫华的元素战技")
                .metadataJson("""
                        {"category":"character","doc_type":"character_skill","entity_type":"character",
                         "entity_id":"10000002","section_path":"角色/神里绫华/技能","extra":"kept in source"}
                        """)
                .build();

        ObjectNode document = gateway.buildIndexDocument(10L, 20L, "神里绫华", chunk);

        assertEquals("character", document.path("category").asText());
        assertEquals("character_skill", document.path("doc_type").asText());
        assertEquals("10000002", document.path("entity_id").asText());
        assertEquals("kept in source", document.path("metadata").path("extra").asText());
        assertTrue(document.has("kb_id"));
    }
}
