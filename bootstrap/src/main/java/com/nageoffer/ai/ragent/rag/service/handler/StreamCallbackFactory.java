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

package com.nageoffer.ai.ragent.rag.service.handler;

import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * StreamCallback 工厂
 * 负责创建各种类型的 StreamCallback 实例
 */
@Component
@RequiredArgsConstructor
public class StreamCallbackFactory {

    private final AIModelProperties modelProperties;
    private final ConversationMemoryService memoryService;
    private final ConversationGroupService conversationGroupService;
    private final StreamTaskManager taskManager;

    /**
     * 创建聊天事件处理器
     *
     * @param emitter        SSE 发射器
     * @param conversationId 会话ID
     * @param taskId         任务ID
     * @return StreamCallback 实例
     */
    public StreamCallback createChatEventHandler(SseEmitter emitter,
                                                 String conversationId,
                                                 String taskId) {
        //创建参数对象
        StreamChatHandlerParams params = StreamChatHandlerParams.builder()
                .emitter(emitter)//这次 handler 以后如果要往前端推流，就用这个 emitter。
                .conversationId(conversationId)//这个 handler 知道自己服务的是哪一个会话。
                .taskId(taskId)//这个 handler 知道自己对应的是哪一个流式任务。
                .modelProperties(modelProperties)//把当前类里的成员变量 modelProperties 放进去。
                .memoryService(memoryService)//把记忆服务传进去。
                .conversationGroupService(conversationGroupService)//把会话分组服务传进去。
                .taskManager(taskManager)//把任务管理器也传进去。
                .build();

        return new StreamChatEventHandler(params);
    }
}
