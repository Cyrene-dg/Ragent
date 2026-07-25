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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    /** Fields shared with Milvus that are safe and useful as ES exact filters. */
    private static final List<String> ROUTING_METADATA_FIELDS = List.of(
            "schema_version", "source_version", "source_document", "document_title",
            "category", "doc_type", "entity_type", "entity_id", "section_path", "language", "element"
    );

    private final ObjectMapper objectMapper;
    private final OkHttpClient baseOkHttpClient;
    private final KeywordEsChannelProperties properties;

    private volatile boolean routingMetadataMappingEnsured;

    public boolean available() {
        return properties.isEnabled()
                && StringUtils.hasText(properties.getBaseUrl())
                && StringUtils.hasText(properties.getIndexName());
    }

    public List<RetrievedChunk> search(String query, int size, Set<String> kbIds) {
        return search(query, size, kbIds, Map.of());
    }

    /**
     * Keyword retrieval within a knowledge-base scope and an optional metadata scope.
     * Metadata values are always emitted as filter clauses, never as full-text terms.
     */
    public List<RetrievedChunk> search(String query,
                                       int size,
                                       Set<String> kbIds,
                                       Map<String, Object> metadataFilters) {
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
            appendMetadataFilters(filter, metadataFilters);

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

        upsertChunkEntries(chunks.stream()
                .map(chunk -> new ChunkIndexEntry(kbId, docId, title, chunk))
                .toList());
    }

    /**
     * Bulk upsert entries from multiple documents in one ES request. The
     * startup backfill uses this method so a large corpus does not turn into
     * thousands of tiny refresh=wait_for requests.
     */
    public void upsertChunkEntries(Collection<ChunkIndexEntry> entries) {
        if (!available() || entries == null || entries.isEmpty()) {
            return;
        }

        if (!ensureRoutingMetadataMappings()) {
            log.error("ES routing metadata mapping is unavailable; skip index write to avoid an inconsistent schema");
            return;
        }

        StringBuilder ndjson = new StringBuilder(entries.size() * 384);
        for (ChunkIndexEntry entry : entries) {
            KnowledgeChunkDO chunk = entry == null ? null : entry.chunk();
            if (chunk == null || chunk.getId() == null || chunk.getEnabled() == null || chunk.getEnabled() != 1
                    || entry.kbId() == null || entry.docId() == null) {
                continue;
            }
            String chunkId = String.valueOf(chunk.getId());
            ObjectNode meta = objectMapper.createObjectNode();
            meta.putObject("index")
                    .put("_index", properties.getIndexName())
                    .put("_id", chunkId);

            ObjectNode doc = buildIndexDocument(entry.kbId(), entry.docId(), entry.title(), chunk);

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
                log.warn("ES bulk upsert contains failed items, body={}", raw);
            }
        } catch (Exception ex) {
            log.error("ES bulk upsert failed", ex);
        }
    }

    public record ChunkIndexEntry(Long kbId, Long docId, String title, KnowledgeChunkDO chunk) {
    }

    /** Package-visible for focused serialization tests. */
    ObjectNode buildIndexDocument(Long kbId, Long docId, String title, KnowledgeChunkDO chunk) {
        ObjectNode doc = objectMapper.createObjectNode();
        doc.put("chunk_id", String.valueOf(chunk.getId()));
        doc.put("kb_id", String.valueOf(kbId));
        doc.put("doc_id", String.valueOf(docId));
        doc.put("title", title == null ? "" : title);
        doc.put("content", chunk.getContent() == null ? "" : chunk.getContent());
        doc.put("keywords", buildKeywords(title, chunk.getContent()));
        doc.put("enabled", 1);
        if (chunk.getUpdateTime() != null) {
            doc.put("update_time", chunk.getUpdateTime().toInstant().toString());
        }
        appendRoutingMetadata(doc, chunk.getMetadataJson());
        return doc;
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

    private void appendMetadataFilters(ArrayNode filter, Map<String, Object> metadataFilters) {
        if (metadataFilters == null || metadataFilters.isEmpty()) {
            return;
        }
        metadataFilters.forEach((field, rawValue) -> {
            if (!ROUTING_METADATA_FIELDS.contains(field) || rawValue == null) {
                return;
            }
            if (rawValue instanceof Collection<?> values) {
                List<?> nonNullValues = values.stream().filter(java.util.Objects::nonNull).toList();
                if (nonNullValues.isEmpty()) {
                    return;
                }
                ArrayNode terms = filter.addObject().putObject("terms").putArray(field);
                nonNullValues.forEach(value -> terms.add(String.valueOf(value)));
                return;
            }
            filter.addObject().putObject("term").putObject(field).put("value", String.valueOf(rawValue));
        });
    }

    private void appendRoutingMetadata(ObjectNode doc, String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return;
        }
        try {
            JsonNode metadata = objectMapper.readTree(metadataJson);
            if (metadata == null || !metadata.isObject()) {
                return;
            }
            // Preserve complete source metadata for inspection without dynamically indexing arbitrary keys.
            doc.set("metadata", metadata);
            for (String field : ROUTING_METADATA_FIELDS) {
                JsonNode value = metadata.get(field);
                if (value != null && !value.isNull() && value.isValueNode()) {
                    doc.put(field, value.asText());
                }
            }
        } catch (Exception ex) {
            log.warn("Skip malformed chunk metadata while indexing ES, chunkId={}", doc.path("chunk_id").asText(), ex);
        }
    }

    private boolean ensureRoutingMetadataMappings() {
        if (routingMetadataMappingEnsured) {
            return true;
        }
        synchronized (this) {
            if (routingMetadataMappingEnsured) {
                return true;
            }
            try {
                ObjectNode body = objectMapper.createObjectNode();
                ObjectNode mappingProperties = body.putObject("properties");
                for (String field : ROUTING_METADATA_FIELDS) {
                    mappingProperties.putObject(field).put("type", "keyword");
                }
                mappingProperties.putObject("metadata")
                        .put("type", "object")
                        .put("enabled", false);

                Request request = withAuthHeader(new Request.Builder()
                        .url(buildUrl("/%s/_mapping", properties.getIndexName()))
                        .put(RequestBody.create(body.toString(), JSON)))
                        .build();
                executeAndReadBody(request);
                routingMetadataMappingEnsured = true;
                log.info("ES routing metadata mapping ensured, fields={}", ROUTING_METADATA_FIELDS);
                return true;
            } catch (Exception ex) {
                log.error("Failed to ensure ES routing metadata mapping", ex);
                return false;
            }
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

