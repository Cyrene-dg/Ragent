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

package com.nageoffer.ai.ragent.infra.model;

/**
 * 运行时 Embedding 模型偏好提供器。
 * <p>
 * 返回的模型ID会覆盖配置中的 default-model 作为第一候选。
 */
public interface EmbeddingModelPreferenceProvider {

    /**
     * 返回运行时首选的 embedding 模型ID。
     *
     * @return 模型ID，不存在则返回 null
     */
    String preferredEmbeddingModelId();
}
