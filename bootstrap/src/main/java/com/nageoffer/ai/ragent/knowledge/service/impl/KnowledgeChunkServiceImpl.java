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

package com.nageoffer.ai.ragent.knowledge.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkBatchRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkCreateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkMetadataBatchUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkPageRequest;
import com.nageoffer.ai.ragent.knowledge.controller.request.KnowledgeChunkUpdateRequest;
import com.nageoffer.ai.ragent.knowledge.controller.vo.KnowledgeChunkVO;
import com.nageoffer.ai.ragent.core.chunk.VectorChunk;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeChunkDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeBaseDO;
import com.nageoffer.ai.ragent.knowledge.dao.entity.KnowledgeDocumentDO;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeBaseMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeChunkMapper;
import com.nageoffer.ai.ragent.knowledge.dao.mapper.KnowledgeDocumentMapper;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.exception.ClientException;
import com.nageoffer.ai.ragent.framework.exception.ServiceException;
import com.nageoffer.ai.ragent.infra.embedding.EmbeddingService;
import com.nageoffer.ai.ragent.infra.token.TokenCounterService;
import com.nageoffer.ai.ragent.rag.core.vector.VectorStoreService;
import com.nageoffer.ai.ragent.rag.es.KeywordEsGateway;
import com.nageoffer.ai.ragent.knowledge.service.KnowledgeChunkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 知识库 Chunk 服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeChunkServiceImpl implements KnowledgeChunkService {

    /** DashScope text-embedding-v4 accepts at most ten inputs per request. */
    private static final int EMBEDDING_BATCH_LIMIT = 10;

    private final KnowledgeChunkMapper chunkMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final EmbeddingService embeddingService;
    private final TokenCounterService tokenCounterService;
    private final VectorStoreService vectorStoreService;
    private final KeywordEsGateway keywordEsGateway;
    private final ObjectMapper objectMapper;

    @Override
    public Boolean existsByDocId(String docId) {
        List<KnowledgeChunkDO> chunkDOList = chunkMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeChunkDO.class).eq(KnowledgeChunkDO::getDocId, docId)
        );
        return chunkDOList != null && !chunkDOList.isEmpty();
    }

    @Override
    public IPage<KnowledgeChunkVO> pageQuery(String docId, KnowledgeChunkPageRequest requestParam) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        LambdaQueryWrapper<KnowledgeChunkDO> queryWrapper = new LambdaQueryWrapper<KnowledgeChunkDO>()
                .eq(KnowledgeChunkDO::getDocId, docId)
                .eq(requestParam.getEnabled() != null, KnowledgeChunkDO::getEnabled, requestParam.getEnabled())
                .orderByAsc(KnowledgeChunkDO::getChunkIndex);

        Page<KnowledgeChunkDO> page = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        IPage<KnowledgeChunkDO> result = chunkMapper.selectPage(page, queryWrapper);
        fillTokenCountsIfMissing(result.getRecords());
        return result.convert(each -> BeanUtil.toBean(each, KnowledgeChunkVO.class));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeChunkVO create(String docId, KnowledgeChunkCreateRequest requestParam) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        String content = requestParam.getContent();
        Assert.notBlank(content, () -> new ClientException("Chunk 内容不能为空"));

        Integer chunkIndex = requestParam.getIndex();
        if (chunkIndex == null) {
            // 自动取当前最大值 + 1
            int maxIndex = chunkMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeChunkDO>()
                            .eq(KnowledgeChunkDO::getDocId, docId)
                            .orderByDesc(KnowledgeChunkDO::getChunkIndex)
                            .last("LIMIT 1")
            ) != null ? chunkMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeChunkDO>()
                            .eq(KnowledgeChunkDO::getDocId, docId)
                            .orderByDesc(KnowledgeChunkDO::getChunkIndex)
                            .last("LIMIT 1")
            ).getChunkIndex() : -1;
            chunkIndex = maxIndex + 1;
        }

        String contentHash = calculateHash(content);
        int charCount = content.length();
        String embeddingModel = resolveEmbeddingModel(documentDO.getKbId());
        Integer tokenCount = resolveTokenCount(content);

        KnowledgeChunkDO chunkDO = KnowledgeChunkDO.builder()
                .id(Long.parseLong(requestParam.getChunkId()))
                .kbId(documentDO.getKbId())
                .docId(Long.parseLong(docId))
                .chunkIndex(chunkIndex)
                .content(content)
                .contentHash(contentHash)
                .charCount(charCount)
                .tokenCount(tokenCount)
                .metadataJson(serializeMetadata(requestParam.getMetadata()))
                .enabled(1)
                .createdBy(UserContext.getUsername())
                .build();

        chunkMapper.insert(chunkDO);
        log.info("新增 Chunk 成功, kbId={}, docId={}, chunkId={}, chunkIndex={}", documentDO.getKbId(), docId, chunkDO.getId(), chunkIndex);

        // 同步写入 Milvus
        syncChunkToMilvus(String.valueOf(documentDO.getKbId()), docId, chunkDO, embeddingModel);
        syncChunksToKeywordEs(documentDO, List.of(chunkDO));

        return BeanUtil.toBean(chunkDO, KnowledgeChunkVO.class);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchCreate(String docId, List<KnowledgeChunkCreateRequest> requestParams) {
        batchCreate(docId, requestParams, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchCreate(String docId, List<KnowledgeChunkCreateRequest> requestParams, boolean writeVector) {
        if (CollUtil.isEmpty(requestParams)) {
            return;
        }

        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        boolean needAutoIndex = requestParams.stream().anyMatch(request -> request.getIndex() == null);
        int nextIndex = 0;
        if (needAutoIndex) {
            KnowledgeChunkDO latest = chunkMapper.selectOne(
                    new LambdaQueryWrapper<KnowledgeChunkDO>()
                            .eq(KnowledgeChunkDO::getDocId, docId)
                            .orderByDesc(KnowledgeChunkDO::getChunkIndex)
                            .last("LIMIT 1")
            );
            nextIndex = latest != null && latest.getChunkIndex() != null ? latest.getChunkIndex() + 1 : 0;
        }

        Long docIdLong = Long.parseLong(docId);
        Long kbId = documentDO.getKbId();
        String username = UserContext.getUsername();
        String embeddingModel = resolveEmbeddingModel(kbId);
        List<KnowledgeChunkDO> chunkDOList = new ArrayList<>(requestParams.size());

        for (KnowledgeChunkCreateRequest request : requestParams) {
            String content = request.getContent();
            Assert.notBlank(content, () -> new ClientException("Chunk 内容不能为空"));

            Integer chunkIndex = request.getIndex();
            if (chunkIndex == null) {
                chunkIndex = nextIndex++;
            }

            String chunkId = request.getChunkId();
            if (!StringUtils.hasText(chunkId)) {
                chunkId = IdUtil.getSnowflakeNextIdStr();
            }

            KnowledgeChunkDO chunkDO = KnowledgeChunkDO.builder()
                    .id(Long.parseLong(chunkId))
                    .kbId(kbId)
                    .docId(docIdLong)
                    .chunkIndex(chunkIndex)
                    .content(content)
                    .contentHash(calculateHash(content))
                    .charCount(content.length())
                    .tokenCount(resolveTokenCount(content))
                    .metadataJson(serializeMetadata(request.getMetadata()))
                    .enabled(1)
                    .createdBy(username)
                    .build();
            chunkDOList.add(chunkDO);
        }

        // 批量写入数据库，向量索引由上层统一处理以避免重复计算
        chunkMapper.insert(chunkDOList);
        KnowledgeDocumentDO documentUpdate = new KnowledgeDocumentDO();
        documentUpdate.setId(documentDO.getId());
        documentUpdate.setChunkCount(Math.toIntExact(chunkMapper.selectCount(
                Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                        .eq(KnowledgeChunkDO::getDocId, docId)
                        .eq(KnowledgeChunkDO::getDeleted, 0)
        )));
        documentMapper.updateById(documentUpdate);

        if (writeVector) {
            String kbIdStr = String.valueOf(documentDO.getKbId());
            List<VectorChunk> vectorChunks = chunkDOList.stream()
                    .map(each -> VectorChunk.builder()
                            .chunkId(String.valueOf(each.getId()))
                            .content(each.getContent())
                            .index(each.getChunkIndex())
                            .metadata(deserializeMetadata(each.getMetadataJson()))
                            .build())
                    .toList();
            if (CollUtil.isNotEmpty(vectorChunks)) {
                attachEmbeddings(vectorChunks, embeddingModel);
                vectorStoreService.indexDocumentChunks(kbIdStr, docId, vectorChunks);
            }
        }
        syncChunksToKeywordEs(documentDO, chunkDOList);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(String docId, String chunkId, KnowledgeChunkUpdateRequest requestParam) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        KnowledgeChunkDO chunkDO = chunkMapper.selectById(chunkId);
        Assert.notNull(chunkDO, () -> new ClientException("Chunk 不存在"));
        Assert.isTrue(chunkDO.getDocId().equals(Long.parseLong(docId)), () -> new ClientException("Chunk 不属于该文档"));

        String newContent = requestParam.getContent();
        Assert.notBlank(newContent, () -> new ClientException("Chunk 内容不能为空"));
        boolean contentChanged = !newContent.equals(chunkDO.getContent());
        boolean metadataChanged = requestParam.getMetadata() != null;
        if (!contentChanged && !metadataChanged) {
            return;
        }

        if (contentChanged) {
            chunkDO.setContent(newContent);
            chunkDO.setContentHash(calculateHash(newContent));
            chunkDO.setCharCount(newContent.length());
        }
        String embeddingModel = resolveEmbeddingModel(documentDO.getKbId());
        if (contentChanged) {
            chunkDO.setTokenCount(resolveTokenCount(newContent));
        }
        if (metadataChanged) {
            chunkDO.setMetadataJson(serializeMetadata(requestParam.getMetadata()));
        }
        chunkDO.setUpdatedBy(UserContext.getUsername());

        chunkMapper.updateById(chunkDO);

        String kbId = String.valueOf(documentDO.getKbId());
        log.info("更新 Chunk 成功, kbId={}, docId={}, chunkId={}", kbId, docId, chunkId);

        // 同步向量数据库
        vectorStoreService.updateChunk(
                String.valueOf(chunkDO.getKbId()),
                docId,
                VectorChunk.builder()
                        .chunkId(chunkId)
                        .content(chunkDO.getContent())
                        .index(chunkDO.getChunkIndex())
                        .metadata(deserializeMetadata(chunkDO.getMetadataJson()))
                        .embedding(toArray(embedContent(chunkDO.getContent(), embeddingModel)))
                        .build()
        );
        syncChunksToKeywordEs(documentDO, List.of(chunkDO));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdateMetadata(String docId, List<KnowledgeChunkMetadataBatchUpdateRequest> requestParams) {
        if (CollUtil.isEmpty(requestParams)) {
            return;
        }
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        Map<Long, Map<String, Object>> metadataByChunkId = new HashMap<>();
        for (KnowledgeChunkMetadataBatchUpdateRequest request : requestParams) {
            Assert.notNull(request, () -> new ClientException("Chunk metadata 请求不能为空"));
            Assert.isTrue(StringUtils.hasText(request.getChunkId()), () -> new ClientException("Chunk ID 不能为空"));
            Assert.notNull(request.getMetadata(), () -> new ClientException("Chunk metadata 不能为空"));
            Long chunkId = Long.parseLong(request.getChunkId());
            Assert.isTrue(metadataByChunkId.putIfAbsent(chunkId, request.getMetadata()) == null,
                    () -> new ClientException("批量 metadata 请求中存在重复 Chunk ID: " + chunkId));
        }

        List<KnowledgeChunkDO> chunks = chunkMapper.selectByIds(metadataByChunkId.keySet());
        Assert.isTrue(chunks.size() == metadataByChunkId.size(), () -> new ClientException("部分 Chunk 不存在"));
        String username = UserContext.getUsername();
        int changed = 0;
        for (KnowledgeChunkDO chunk : chunks) {
            Assert.isTrue(chunk.getDocId().equals(Long.parseLong(docId)),
                    () -> new ClientException("Chunk " + chunk.getId() + " 不属于文档 " + docId));
            String serializedMetadata = serializeMetadata(metadataByChunkId.get(chunk.getId()));
            if (Objects.equals(serializedMetadata, chunk.getMetadataJson())) {
                continue;
            }
            chunk.setMetadataJson(serializedMetadata);
            chunk.setUpdatedBy(username);
            chunkMapper.updateById(chunk);
            changed++;
        }
        log.info("批量更新 Chunk metadata 成功, kbId={}, docId={}, changed={}; 请随后重建文档索引",
                documentDO.getKbId(), docId, changed);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String docId, String chunkId) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        KnowledgeChunkDO chunkDO = chunkMapper.selectById(chunkId);
        Assert.notNull(chunkDO, () -> new ClientException("Chunk 不存在"));
        Assert.isTrue(chunkDO.getDocId().equals(Long.parseLong(docId)), () -> new ClientException("Chunk 不属于该文档"));

        chunkMapper.deleteById(chunkId);

        String kbId = String.valueOf(documentDO.getKbId());
        log.info("删除 Chunk 成功, kbId={}, docId={}, chunkId={}", kbId, docId, chunkId);

        deleteChunkFromMilvus(kbId, chunkId);
        keywordEsGateway.deleteChunk(chunkId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void enableChunk(String docId, String chunkId, boolean enabled) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        validateDocumentEnabledForChunkEnable(documentDO, enabled);

        KnowledgeChunkDO chunkDO = chunkMapper.selectById(chunkId);
        Assert.notNull(chunkDO, () -> new ClientException("Chunk 不存在"));
        Assert.isTrue(chunkDO.getDocId().equals(Long.parseLong(docId)), () -> new ClientException("Chunk 不属于该文档"));

        // 如果状态没变，直接返回
        int enabledValue = enabled ? 1 : 0;
        if (chunkDO.getEnabled().equals(enabledValue)) {
            return;
        }

        chunkDO.setEnabled(enabledValue);
        chunkDO.setUpdatedBy(UserContext.getUsername());
        chunkMapper.updateById(chunkDO);

        String kbId = String.valueOf(documentDO.getKbId());
        log.info("{}Chunk 成功, kbId={}, docId={}, chunkId={}", enabled ? "启用" : "禁用", kbId, docId, chunkId);

        if (enabled) {
            syncChunkToMilvus(kbId, docId, chunkDO, resolveEmbeddingModel(documentDO.getKbId()));
            syncChunksToKeywordEs(documentDO, List.of(chunkDO));
        } else {
            deleteChunkFromMilvus(kbId, chunkId);
            keywordEsGateway.deleteChunk(chunkId);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchEnable(String docId, KnowledgeChunkBatchRequest requestParam) {
        batchUpdateEnabled(docId, requestParam, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchDisable(String docId, KnowledgeChunkBatchRequest requestParam) {
        batchUpdateEnabled(docId, requestParam, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rebuildByDocId(String docId) {
        doRebuildByDocId(docId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateEnabledByDocId(String docId, boolean enabled) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        int enabledValue = enabled ? 1 : 0;
        chunkMapper.update(
                Wrappers.lambdaUpdate(KnowledgeChunkDO.class)
                        .eq(KnowledgeChunkDO::getDocId, docId)
                        .set(KnowledgeChunkDO::getEnabled, enabledValue)
                        .set(KnowledgeChunkDO::getUpdatedBy, UserContext.getUsername())
        );

        String kbId = String.valueOf(documentDO.getKbId());
        log.info("根据文档ID更新所有Chunk启用状态, kbId={}, docId={}, enabled={}", kbId, docId, enabled);
        if (enabled) {
            replaceDocKeywordIndex(documentDO, docId);
        } else {
            keywordEsGateway.deleteChunksByDocId(docId);
        }
    }

    @Override
    public List<KnowledgeChunkVO> listByDocId(String docId) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        List<KnowledgeChunkDO> chunkDOList = chunkMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                        .eq(KnowledgeChunkDO::getDocId, docId)
                        .orderByAsc(KnowledgeChunkDO::getChunkIndex)
        );

        return chunkDOList.stream()
                .map(each -> BeanUtil.toBean(each, KnowledgeChunkVO.class))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByDocId(String docId) {
        if (docId == null) {
            return;
        }
        keywordEsGateway.deleteChunksByDocId(docId);
        chunkMapper.delete(new LambdaQueryWrapper<KnowledgeChunkDO>().eq(KnowledgeChunkDO::getDocId, docId));
    }

    private void doRebuildByDocId(String docId) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));

        String kbId = String.valueOf(documentDO.getKbId());
        log.info("开始重建文档向量, kbId={}, docId={}", kbId, docId);

        // 1. 读取 MySQL enabled=1 的 chunks
        List<KnowledgeChunkDO> enabledChunks = chunkMapper.selectList(
                new LambdaQueryWrapper<KnowledgeChunkDO>()
                        .eq(KnowledgeChunkDO::getDocId, docId)
                        .eq(KnowledgeChunkDO::getEnabled, 1)
                        .orderByAsc(KnowledgeChunkDO::getChunkIndex)
        );

        if (enabledChunks.isEmpty()) {
            vectorStoreService.deleteDocumentVectors(kbId, docId);
            log.warn("文档下没有启用的 Chunk，跳过向量重建, kbId={}, docId={}", kbId, docId);
            return;
        }

        // 2. 先完成所有 Embedding，再删除旧索引。这样远端模型失败时仍保留旧向量。
        List<VectorChunk> chunks = enabledChunks.stream()
                .map(
                        each -> VectorChunk.builder()
                                .content(each.getContent())
                                .index(each.getChunkIndex())
                                .chunkId(String.valueOf(each.getId()))
                                .metadata(deserializeMetadata(each.getMetadataJson()))
                                .build()
                )
                .collect(Collectors.toList());

        attachEmbeddings(chunks, resolveEmbeddingModel(documentDO.getKbId()));

        // 3. Embedding 完整成功后才替换 Milvus 文档索引。
        vectorStoreService.deleteDocumentVectors(kbId, docId);
        vectorStoreService.indexDocumentChunks(kbId, docId, chunks);

        log.info("重建文档向量成功, kbId={}, docId={}, chunkCount={}", kbId, docId, enabledChunks.size());
        replaceDocKeywordIndex(documentDO, docId);
    }

    // ==================== 私有方法 ====================

    /**
     * 批量更新启用状态
     */
    private void batchUpdateEnabled(String docId, KnowledgeChunkBatchRequest requestParam, boolean enabled) {
        KnowledgeDocumentDO documentDO = documentMapper.selectById(docId);
        Assert.notNull(documentDO, () -> new ClientException("文档不存在"));
        validateDocumentEnabledForChunkEnable(documentDO, enabled);

        List<KnowledgeChunkDO> chunks;
        if (requestParam == null || requestParam.getChunkIds() == null || requestParam.getChunkIds().isEmpty()) {
            // 操作文档下所有 chunk
            chunks = chunkMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeChunkDO>()
                            .eq(KnowledgeChunkDO::getDocId, docId)
            );
        } else {
            // 操作指定 chunk
            chunks = chunkMapper.selectByIds(requestParam.getChunkIds());
            // 校验所有 chunk 都属于该文档
            chunks.forEach(c -> {
                if (!c.getDocId().equals(Long.parseLong(docId))) {
                    throw new ClientException("Chunk " + c.getId() + " 不属于文档 " + docId);
                }
            });
        }

        int enabledValue = enabled ? 1 : 0;
        List<Long> needUpdateIds = new ArrayList<>();

        for (KnowledgeChunkDO chunk : chunks) {
            if (!chunk.getEnabled().equals(enabledValue)) {
                chunk.setEnabled(enabledValue);
                chunk.setUpdatedBy(UserContext.getUsername());
                chunkMapper.updateById(chunk);
                needUpdateIds.add(chunk.getId());
            }
        }

        String kbId = String.valueOf(documentDO.getKbId());
        log.info("批量{}Chunk 成功, kbId={}, docId={}, count={}", enabled ? "启用" : "禁用", kbId, docId, needUpdateIds.size());

        if (enabled) {
            doRebuildByDocId(docId);
        } else {
            for (Long chunkId : needUpdateIds) {
                deleteChunkFromMilvus(kbId, String.valueOf(chunkId));
            }
            replaceDocKeywordIndex(documentDO, docId);
        }
    }

    private void syncChunksToKeywordEs(KnowledgeDocumentDO documentDO, List<KnowledgeChunkDO> chunks) {
        if (documentDO == null || CollUtil.isEmpty(chunks)) {
            return;
        }
        keywordEsGateway.upsertChunks(
                documentDO.getKbId(),
                documentDO.getId(),
                documentDO.getDocName(),
                chunks
        );
    }

    private void replaceDocKeywordIndex(KnowledgeDocumentDO documentDO, String docId) {
        if (documentDO == null || !StringUtils.hasText(docId)) {
            return;
        }
        keywordEsGateway.deleteChunksByDocId(docId);
        List<KnowledgeChunkDO> enabledChunks = chunkMapper.selectList(
                Wrappers.lambdaQuery(KnowledgeChunkDO.class)
                        .eq(KnowledgeChunkDO::getDocId, docId)
                        .eq(KnowledgeChunkDO::getEnabled, 1)
                        .orderByAsc(KnowledgeChunkDO::getChunkIndex)
        );
        if (CollUtil.isEmpty(enabledChunks)) {
            return;
        }
        syncChunksToKeywordEs(documentDO, enabledChunks);
    }

    /**
     * 启用 chunk 前必须保证所属文档为启用状态
     */
    private void validateDocumentEnabledForChunkEnable(KnowledgeDocumentDO documentDO, boolean enableChunk) {
        if (!enableChunk) {
            return;
        }
        if (!Integer.valueOf(1).equals(documentDO.getEnabled())) {
            throw new ClientException("文档未启用，无法启用Chunk，请先启用文档");
        }
    }

    /**
     * 将单个 chunk 同步到 Milvus
     */
    private void syncChunkToMilvus(String kbId, String docId, KnowledgeChunkDO chunkDO, String embeddingModel) {
        List<Float> embedding = embedContent(chunkDO.getContent(), embeddingModel);
        float[] vector = toArray(embedding);

        VectorChunk chunk = VectorChunk.builder()
                .index(chunkDO.getChunkIndex())
                .content(chunkDO.getContent())
                .chunkId(String.valueOf(chunkDO.getId()))
                .metadata(deserializeMetadata(chunkDO.getMetadataJson()))
                .embedding(vector)
                .build();
        vectorStoreService.indexDocumentChunks(kbId, docId, List.of(chunk));

        log.debug("同步 Chunk 到 Milvus 成功, kbId={}, docId={}, chunkId={}", kbId, docId, chunkDO.getId());
    }

    /**
     * 从 Milvus 删除单个 chunk
     */
    private void deleteChunkFromMilvus(String kbId, String chunkId) {
        vectorStoreService.deleteChunkById(kbId, chunkId);
        log.debug("从 Milvus 删除 Chunk, kbId={}, chunkId={}", kbId, chunkId);
    }

    /**
     * 计算内容哈希（SHA-256）
     */
    private String calculateHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }
    }

    /**
     * List<Float> 转 float[]
     */
    private static float[] toArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private void attachEmbeddings(List<VectorChunk> chunks, String embeddingModel) {
        if (CollUtil.isEmpty(chunks)) {
            return;
        }
        List<List<Float>> vectors = new ArrayList<>(chunks.size());
        for (int start = 0; start < chunks.size(); start += EMBEDDING_BATCH_LIMIT) {
            int end = Math.min(start + EMBEDDING_BATCH_LIMIT, chunks.size());
            List<String> texts = chunks.subList(start, end).stream().map(VectorChunk::getContent).toList();
            List<List<Float>> batchVectors = embedBatch(texts, embeddingModel);
            if (batchVectors == null || batchVectors.size() != texts.size()) {
                throw new ServiceException("向量结果数量不匹配");
            }
            vectors.addAll(batchVectors);
        }
        if (vectors == null || vectors.size() != chunks.size()) {
            throw new ServiceException("向量结果数量不匹配");
        }
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setEmbedding(toArray(vectors.get(i)));
        }
    }

    private List<Float> embedContent(String content, String embeddingModel) {
        return StrUtil.isBlank(embeddingModel)
                ? embeddingService.embed(content)
                : embeddingService.embed(content, embeddingModel);
    }

    private List<List<Float>> embedBatch(List<String> texts, String embeddingModel) {
        return StrUtil.isBlank(embeddingModel)
                ? embeddingService.embedBatch(texts)
                : embeddingService.embedBatch(texts, embeddingModel);
    }

    private String resolveEmbeddingModel(Long kbId) {
        if (kbId == null) {
            return null;
        }
        KnowledgeBaseDO kbDO = knowledgeBaseMapper.selectById(kbId);
        return kbDO != null ? kbDO.getEmbeddingModel() : null;
    }

    private Integer resolveTokenCount(String content) {
        if (!StringUtils.hasText(content)) {
            return 0;
        }
        return tokenCounterService.countTokens(content);
    }

    private String serializeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception ex) {
            throw new ClientException("Chunk 元数据序列化失败");
        }
    }

    private Map<String, Object> deserializeMetadata(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() {
            });
        } catch (Exception ex) {
            log.warn("Chunk 元数据解析失败，忽略本次元数据", ex);
            return Collections.emptyMap();
        }
    }

    private void fillTokenCountsIfMissing(List<KnowledgeChunkDO> chunks) {
        if (CollUtil.isEmpty(chunks)) {
            return;
        }
        for (KnowledgeChunkDO chunk : chunks) {
            if (chunk.getTokenCount() != null) {
                continue;
            }
            Integer tokenCount = resolveTokenCount(chunk.getContent());
            if (tokenCount == null) {
                continue;
            }
            chunk.setTokenCount(tokenCount);
            KnowledgeChunkDO update = new KnowledgeChunkDO();
            update.setId(chunk.getId());
            update.setTokenCount(tokenCount);
            chunkMapper.updateById(update);
        }
    }
}
