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

package com.nageoffer.ai.ragent.rag.controller.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class EmbeddingSwitchVO {

    /**
     * 当前生效的 provider。
     */
    private String activeProvider;

    /**
     * 当前生效的模型ID。
     */
    private String activeModelId;

    /**
     * 当前生效的模型名（传给 provider 的 model 字段）。
     */
    private String activeModel;

    /**
     * 当前模型维度。
     */
    private Integer activeDimension;

    /**
     * 可切换 provider 列表（每个 provider 给出一个推荐模型）。
     */
    private List<SwitchableProvider> providers;

    @Data
    @Builder
    public static class SwitchableProvider {
        private String provider;
        private String modelId;
        private String model;
        private Integer dimension;
    }
}
