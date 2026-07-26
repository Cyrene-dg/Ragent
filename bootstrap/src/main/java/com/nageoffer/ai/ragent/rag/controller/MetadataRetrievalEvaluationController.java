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
import com.nageoffer.ai.ragent.rag.evaluation.MetadataRetrievalEvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;

/**
 * Local offline-evaluation endpoint.  It is absent unless the explicit
 * {@code rag.metadata-routing.evaluation-enabled=true} property is supplied.
 */
@RestController
@RequestMapping("/rag/evaluation/metadata-retrieval")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rag.metadata-routing", name = "evaluation-enabled", havingValue = "true")
public class MetadataRetrievalEvaluationController {

    private final MetadataRetrievalEvaluationService evaluationService;

    @PostMapping("/compare")
    public Result<MetadataRetrievalEvaluationService.MetadataRetrievalEvaluationResult> compare(
            @RequestBody EvaluationRequest request,
            HttpServletRequest servletRequest) {
        requireLoopbackRequest(servletRequest);
        return Results.success(evaluationService.compare(request.question(), request.topK()));
    }

    private static void requireLoopbackRequest(HttpServletRequest request) {
        try {
            if (InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) {
                return;
            }
        } catch (Exception ignored) {
            // Deny if the source cannot be verified.
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "评测接口仅允许本机访问");
    }

    public record EvaluationRequest(String question, Integer topK) {
    }
}
