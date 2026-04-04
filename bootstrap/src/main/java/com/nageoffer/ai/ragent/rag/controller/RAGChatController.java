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
import com.nageoffer.ai.ragent.framework.idempotent.IdempotentSubmit;
import com.nageoffer.ai.ragent.framework.web.Results;
import com.nageoffer.ai.ragent.rag.service.RAGChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 对话控制器
 * 提供流式问答与任务取消接口
 */
@RestController
//自动生成构造器，用于依赖注入
@RequiredArgsConstructor
public class RAGChatController {

    private final RAGChatService ragChatService;

    /**
     * 发起 SSE 流式对话
     */
    //防止用户重复提交请求的幂等注解
    @IdempotentSubmit(
            key = "T(com.nageoffer.ai.ragent.framework.context.UserContext).getUserId()",
            message = "当前会话处理中，请稍后再发起新的对话"
    )
    //返回的是流式返回类型，不是普通json
    @GetMapping(value = "/rag/v3/chat", produces = "text/event-stream;charset=UTF-8")
    //参数，问题，对话号，是否开启深度思考
    public SseEmitter chat(@RequestParam("question") String question,
                           @RequestParam(value = "conversationId", required = false) String conversationId,
                           @RequestParam(value = "deepThinking", required = false, defaultValue = "false") Boolean deepThinking) {
        //不设置超时时间，即无限等待
        SseEmitter emitter = new SseEmitter(0L);
        //将参数传入service
        ragChatService.streamChat(question, conversationId, deepThinking, emitter);
        return emitter;
    }

    /**
     * 停止指定任务
     */
    //幂等注解
    @IdempotentSubmit
    @PostMapping(value = "/rag/v3/stop")
    public Result<Void> stop(@RequestParam("taskId") String taskId) {
        ragChatService.stopTask(taskId);
        return Results.success();
    }
}
