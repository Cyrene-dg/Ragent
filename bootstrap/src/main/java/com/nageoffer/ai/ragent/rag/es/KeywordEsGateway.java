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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.rag.config.KeywordEsChannelProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ES 关键词索引网关，负责检索与索引同步
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordEsGateway {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType NDJSON = MediaType.parse("application/x-ndjson; charset=utf-8");
    private static final Pattern ID_PATTERN = Pattern.compile("(?i)\\bID\\s*[:：]\\s*([A-Za-z0-9_-]{2,32})");
    private static final Pattern CN_NAME_PATTERN = Pattern.compile("(?i)\\bCN\\s*Name\\s*[:：]\\s*([^\\r\\n]{1,60})");
    private static final Pattern EN_NAME_PATTERN = Pattern.compile("(?i)\\bEN\\s*Name\\s*[:：]\\s*([^\\r\\n]{1,80})");

    private final ObjectMapper objectMapper;
    private final OkHttpClient baseOkHttpClient;
    private final KeywordEsChannelProperties properties;

    public boolean available() {
        return properties.isEnabled()
                && StringUtils.hasText(properties.getBaseUrl())
                && StringUtils.hasText(properties.getIndexName());
    }

    public List<RetrievedChunk> search(String query, int size, Set<String> kbIds) {
        if (!available() || !StringUtils.hasText(query) || size <= 0) {
            return List.of();
        }

        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("size", size);
            body.put("track_total_hits", false);
            body.put("min_score", properties.getMinScore());

            ObjectNode queryNode = body.putObject("query").putObject("bool");
            ArrayNode should = queryNode.putArray("should");

            String titleField = "title^" + trimTrailingZeros(properties.getTitleBoost());
            String keywordsField = "keywords^" + trimTrailingZeros(properties.getKeywordsBoost());
            String contentField = "content^" + trimTrailingZeros(properties.getContentBoost());

            ObjectNode multiMatch = should.addObject()
                    .putObject("multi_match");
            multiMatch.put("query", query);
            multiMatch.put("type", "best_fields");
            multiMatch.put("operator", "or");
            ArrayNode fields = multiMatch.putArray("fields");
            fields.add(titleField);
            fields.add(keywordsField);
            fields.add(contentField);

            should.addObject()
                    .putObject("match_phrase")
                    .putObject("content")
                    .put("query", query)
                    .put("boost", properties.getPhraseBoost());

            queryNode.put("minimum_should_match", 1);

            ArrayNode filter = queryNode.putArray("filter");
            filter.addObject()
                    .putObject("term")
                    .putObject("enabled")
                    .put("value", 1);
            if (kbIds != null && !kbIds.isEmpty()) {
                ArrayNode values = filter.addObject()
                        .putObject("terms")
                        .putArray("kb_id");
                kbIds.forEach(values::add);
            }

            Request request = withAuthHeader(new Request.Builder()
                    .url(buildUrl("/%s/_search", properties.getIndexName()))
                    .post(RequestBody.create(body.toString(), JSON)))
                    .build();

            String raw = executeAndReadBody(request);
            JsonNode root = objectMapper.readTree(raw);
            JsonNode hits = root.path("hits").path("hits");
            if (!hits.isArray() || hits.isEmpty()) {
                return List.of();
            }

            List<RetrievedChunk> chunks = new ArrayList<>(hits.size());
            for (JsonNode hit : hits) {
                JsonNode source = hit.path("_source");
                String chunkId = textOrNull(source, "chunk_id");
                if (!StringUtils.hasText(chunkId)) {
                    chunkId = textOrNull(hit, "_id");
                }
                if (!StringUtils.hasText(chunkId)) {
                    continue;
                }

                String content = textOrNull(source, "content");
                if (content == null) {
                    content = "";
                }
                float score = (float) hit.path("_score").asDouble(0.0);
                chunks.add(new RetrievedChunk(chunkId, content, score));
            }
            return chunks;
        } catch (Exception ex) {
            log.error("ES 关键词检索失败, query={}", query, ex);
            return List.of();
        }
    }

    public void upsertChunks(Long kbId, Long docId, String title, Collection<KnowledgeChunkDO> chunks) {
        if (!available() || chunks == null || chunks.isEmpty() || kbId == null || docId == null) {
            return;
        }

        StringBuilder ndjson = new StringBuilder(chunks.size() * 256);
        for (KnowledgeChunkDO chunk : chunks) {
            if (chunk == null || chunk.getId() == null || chunk.getEnabled() == null || chunk.getEnabled() != 1) {
                continue;
            }
            String chunkId = String.valueOf(chunk.getId());
            ObjectNode meta = objectMapper.createObjectNode();
            meta.putObject("index")
                    .put("_index", properties.getIndexName())
                    .put("_id", chunkId);

            ObjectNode doc = objectMapper.createObjectNode();
            doc.put("chunk_id", chunkId);
            doc.put("kb_id", String.valueOf(kbId));
            doc.put("doc_id", String.valueOf(docId));
            doc.put("title", title == null ? "" : title);
            doc.put("content", chunk.getContent() == null ? "" : chunk.getContent());
            doc.put("keywords", buildKeywords(title, chunk.getContent()));
            doc.put("enabled", 1);
            if (chunk.getUpdateTime() != null) {
                doc.put("update_time", chunk.getUpdateTime().toInstant().toString());
            }

            ndjson.append(meta).append('\n');
            ndjson.append(doc).append('\n');
        }

        if (ndjson.isEmpty()) {
            return;
        }

        String refreshParam = properties.isRefreshOnWrite() ? "?refresh=wait_for" : "";
        Request request = withAuthHeader(new Request.Builder()
                .url(buildUrl("/_bulk%s", refreshParam))
                .post(RequestBody.create(ndjson.toString(), NDJSON)))
                .build();
        try {
            String raw = executeAndReadBody(request);
            JsonNode root = objectMapper.readTree(raw);
            if (root.path("errors").asBoolean(false)) {
                log.warn("ES bulk upsert 存在失败项, docId={}, body={}", docId, raw);
            }
        } catch (Exception ex) {
            log.error("ES bulk upsert 失败, docId={}", docId, ex);
        }
    }

    public void upsertChunk(Long kbId, Long docId, String title, KnowledgeChunkDO chunk) {
        if (!available() || chunk == null || chunk.getId() == null || kbId == null || docId == null) {
            return;
        }
        if (chunk.getEnabled() != null && chunk.getEnabled() != 1) {
            deleteChunk(String.valueOf(chunk.getId()));
            return;
        }
        upsertChunks(kbId, docId, title, List.of(chunk));
    }

    public void deleteChunk(String chunkId) {
        if (!available() || !StringUtils.hasText(chunkId)) {
            return;
        }
        String refreshParam = properties.isRefreshOnWrite() ? "?refresh=wait_for" : "";
        Request request = withAuthHeader(new Request.Builder()
                .url(buildUrl("/%s/_doc/%s%s", properties.getIndexName(), chunkId, refreshParam))
                .delete())
                .build();
        try (Response response = execute(request)) {
            if (!response.isSuccessful() && response.code() != 404) {
                throw new IOException("delete chunk failed: " + response.code());
            }
        } catch (Exception ex) {
            log.error("ES 删除 chunk 失败, chunkId={}", chunkId, ex);
        }
    }

    public void deleteChunksByDocId(String docId) {
        if (!available() || !StringUtils.hasText(docId)) {
            return;
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.putObject("query")
                    .putObject("term")
                    .putObject("doc_id")
                    .put("value", docId);

            String refreshParam = properties.isRefreshOnWrite() ? "?refresh=true" : "";
            Request request = withAuthHeader(new Request.Builder()
                    .url(buildUrl("/%s/_delete_by_query%s", properties.getIndexName(), refreshParam))
                    .post(RequestBody.create(body.toString(), JSON)))
                    .build();
            executeAndReadBody(request);
        } catch (Exception ex) {
            log.error("ES 按 docId 删除失败, docId={}", docId, ex);
        }
    }

    private Response execute(Request request) throws IOException {
        OkHttpClient client = baseOkHttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .writeTimeout(Duration.ofMillis(properties.getWriteTimeoutMs()))
                .readTimeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .callTimeout(Duration.ofMillis(properties.getCallTimeoutMs()))
                .build();

        IOException last = null;
        int attempts = Math.max(1, properties.getMaxRetries() + 1);
        for (int i = 0; i < attempts; i++) {
            try {
                Response response = client.newCall(request).execute();
                if (response.isSuccessful()) {
                    return response;
                }
                if (response.code() >= 500 && i < attempts - 1) {
                    response.close();
                    sleep(properties.getRetryBackoffMs());
                    continue;
                }
                return response;
            } catch (IOException ex) {
                last = ex;
                if (i < attempts - 1) {
                    sleep(properties.getRetryBackoffMs());
                    continue;
                }
            }
        }
        throw last == null ? new IOException("ES request failed without cause") : last;
    }

    private String executeAndReadBody(Request request) throws IOException {
        try (Response response = execute(request)) {
            String body = readBody(response.body());
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + ", body=" + body);
            }
            return body;
        }
    }

    private Request.Builder withAuthHeader(Request.Builder builder) {
        if (!StringUtils.hasText(properties.getUsername())) {
            return builder;
        }
        String raw = properties.getUsername() + ":" + (properties.getPassword() == null ? "" : properties.getPassword());
        String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        return builder.header("Authorization", "Basic " + encoded);
    }

    private String buildUrl(String template, Object... args) {
        String path = String.format(template, args);
        String base = properties.getBaseUrl().trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return base + path;
    }

    private static String readBody(ResponseBody body) throws IOException {
        if (body == null) {
            return "";
        }
        return body.string();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return StringUtils.hasText(text) ? text : null;
    }

    private static String trimTrailingZeros(double value) {
        if (Math.rint(value) == value) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private static String buildKeywords(String title, String content) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        addTerm(terms, title);

        if (StringUtils.hasText(content)) {
            Matcher idMatcher = ID_PATTERN.matcher(content);
            while (idMatcher.find()) {
                addTerm(terms, idMatcher.group(1));
            }
            Matcher cnMatcher = CN_NAME_PATTERN.matcher(content);
            while (cnMatcher.find()) {
                addTerm(terms, cnMatcher.group(1));
            }
            Matcher enMatcher = EN_NAME_PATTERN.matcher(content);
            while (enMatcher.find()) {
                addTerm(terms, enMatcher.group(1));
            }
        }
        return String.join(",", terms);
    }

    private static void addTerm(Set<String> terms, String raw) {
        if (!StringUtils.hasText(raw)) {
            return;
        }
        String normalized = raw.trim().replaceAll("\\s+", " ");
        if (!StringUtils.hasText(normalized)) {
            return;
        }
        terms.add(normalized);
        terms.add(normalized.toLowerCase());
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}

