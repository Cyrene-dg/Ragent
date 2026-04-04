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

package com.nageoffer.ai.ragent.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Elasticsearch 关键词检索通道配置
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.search.channels.keyword-es")
public class KeywordEsChannelProperties {

    /**
     * 是否启用关键词检索通道
     */
    private boolean enabled = false;

    /**
     * ES 地址，例如：http://localhost:9200
     */
    private String baseUrl = "http://localhost:9200";

    /**
     * ES 索引名
     */
    private String indexName = "ragent_chunk_v1";

    /**
     * 账号（可选）
     */
    private String username;

    /**
     * 密码（可选）
     */
    private String password;

    /**
     * 检索召回放大倍数
     */
    private int topKMultiplier = 2;

    /**
     * 最低意图分数，低于该分数的意图不参与 KB 过滤
     */
    private double minIntentScore = 0.4;

    /**
     * 没有意图时是否仍允许关键词全局兜底
     */
    private boolean fallbackWhenNoIntent = true;

    /**
     * 标题字段权重
     */
    private double titleBoost = 3.0;

    /**
     * 关键词字段权重
     */
    private double keywordsBoost = 2.0;

    /**
     * 正文字段权重
     */
    private double contentBoost = 1.0;

    /**
     * 短语匹配附加权重
     */
    private double phraseBoost = 1.2;

    /**
     * 最小分数阈值
     */
    private double minScore = 0.0;

    /**
     * 连接超时毫秒
     */
    private long connectTimeoutMs = 1000;

    /**
     * 写超时毫秒
     */
    private long writeTimeoutMs = 3000;

    /**
     * 读超时毫秒
     */
    private long readTimeoutMs = 3000;

    /**
     * 单次请求总超时毫秒
     */
    private long callTimeoutMs = 4000;

    /**
     * 请求失败重试次数
     */
    private int maxRetries = 1;

    /**
     * 重试回退毫秒
     */
    private long retryBackoffMs = 120;

    /**
     * 写入后刷新策略，true 时使用 refresh=wait_for
     */
    private boolean refreshOnWrite = true;

    /**
     * 启动时是否执行一次全量回填
     */
    private boolean backfillOnStartup = false;

    /**
     * 全量回填批量大小
     */
    private int backfillBatchSize = 500;
}
