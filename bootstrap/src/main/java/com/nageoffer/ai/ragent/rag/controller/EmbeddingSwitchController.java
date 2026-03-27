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

package com.nageoffer.ai.ragent.rag.controller;

import com.nageoffer.ai.ragent.framework.convention.Result;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.controller.request.EmbeddingSwitchRequest;
import com.nageoffer.ai.ragent.rag.controller.vo.EmbeddingSwitchVO;
import com.nageoffer.ai.ragent.rag.service.EmbeddingSwitchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Embedding 运行时切换接口。
 */
@RestController
@RequiredArgsConstructor
public class EmbeddingSwitchController {

    private final EmbeddingSwitchService embeddingSwitchService;

    @GetMapping("/rag/settings/embedding-switch")
    public Result<EmbeddingSwitchVO> getSwitchInfo() {
        return Results.success(embeddingSwitchService.getSwitchInfo());
    }

    @PostMapping("/rag/settings/embedding-switch")
    public Result<EmbeddingSwitchVO> switchProvider(@RequestBody EmbeddingSwitchRequest requestParam) {
        return Results.success(embeddingSwitchService.switchProvider(requestParam.getProvider()));
    }
}
