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

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 运行时配置表初始化器。
 * <p>
 * 仅负责兜底建表，避免首次启动因缺表导致切换接口不可用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RuntimeConfigTableInitializer {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        String ddl = """
                CREATE TABLE IF NOT EXISTS t_runtime_config (
                  id BIGINT NOT NULL,
                  config_key VARCHAR(128) NOT NULL,
                  config_value VARCHAR(1024) DEFAULT NULL,
                  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  PRIMARY KEY (id),
                  UNIQUE KEY uk_runtime_config_key (config_key)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行时配置表'
                """;
        try {
            jdbcTemplate.execute(ddl);
        } catch (Exception ex) {
            // 失败不阻断主流程，但会影响运行时切换能力。
            log.error("初始化 t_runtime_config 失败", ex);
        }
    }
}
