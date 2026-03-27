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

import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.dao.entity.ConversationDO;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.rag.dto.MessageDelta;
import com.nageoffer.ai.ragent.rag.dto.MetaPayload;
import com.nageoffer.ai.ragent.rag.enums.SSEEventType;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.infra.chat.StreamCallback;
import com.nageoffer.ai.ragent.infra.config.AIModelProperties;
import com.nageoffer.ai.ragent.rag.core.memory.ConversationMemoryService;
import com.nageoffer.ai.ragent.rag.service.ConversationGroupService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Optional;

public class StreamChatEventHandler implements StreamCallback {

    private static final String TYPE_THINK = "think";
    private static final String TYPE_RESPONSE = "response";

    private final int messageChunkSize;
    private final SseEmitterSender sender;
    private final String conversationId;
    private final ConversationMemoryService memoryService;
    private final ConversationGroupService conversationGroupService;
    private final String taskId;
    private final String userId;
    private final StreamTaskManager taskManager;
    private final boolean sendTitleOnComplete;
    private final StringBuilder answer = new StringBuilder();

    /**
     * 使用参数对象构造（推荐）
     *
     * @param params 构建参数
     */
    //用传进来的参数初始化当前这次流式聊天处理器，并在构造时顺手完成初始事件发送和任务注册。
    public StreamChatEventHandler(StreamChatHandlerParams params) {
        this.sender = new SseEmitterSender(params.getEmitter());
        this.conversationId = params.getConversationId();
        this.taskId = params.getTaskId();
        this.memoryService = params.getMemoryService();
        this.conversationGroupService = params.getConversationGroupService();
        this.taskManager = params.getTaskManager();
        this.userId = UserContext.getUserId();

        // 计算配置
        //计算每次往前端推送内容时，一小块发多大
        this.messageChunkSize = resolveMessageChunkSize(params.getModelProperties());
        //先判断这次流式回答结束时，需不需要顺便给前端发送会话标题，然后把结果保存到 sendTitleOnComplete。
        this.sendTitleOnComplete = shouldSendTitle();

        // 初始化（发送初始事件、注册任务）
        initialize();
    }

    /**
     * 初始化：发送元数据事件并注册任务
     */
    //向前端先发送本次会话和任务的元数据事件，并把当前任务登记到 taskManager 里，方便后续取消和收尾。
    private void initialize() {
        //先通过 sender 给前端发一个 META 类型的 SSE 事件，内容里带上当前的 conversationId 和 taskId。
        sender.sendEvent(SSEEventType.META.value(), new MetaPayload(conversationId, taskId));
        //把当前这次流式任务注册到任务管理器里，让任务管理器以后可以根据 taskId 找到它，并在取消时知道该怎么处理。
        taskManager.register(taskId, sender, this::buildCompletionPayloadOnCancel);
    }

    /**
     * 解析消息块大小
     */
    //从模型配置里取出“每次推送给前端的消息块大小”，如果没配就给一个默认值。
    private int resolveMessageChunkSize(AIModelProperties modelProperties) {
        //下限是1，先拿到 modelProperties 里的 stream 配置，如果它是 null 也别直接报空指针，如果前面的 stream 配置存在，就继续取它里面的 messageChunkSize
        //没拿到值就默认5
        return Math.max(1, Optional.ofNullable(modelProperties.getStream())
                .map(AIModelProperties.Stream::getMessageChunkSize)
                .orElse(5));
    }

    /**
     * 判断是否需要发送标题
     */
//    判断当前会话原来有没有标题，从而决定这次流式结束时要不要顺便把标题也发给前端。
    private boolean shouldSendTitle() {
        //拿到标题检验一下，返回的是true或者false
        ConversationDO existingConversation = conversationGroupService.findConversation(
                conversationId,
                userId
        );
        return existingConversation == null || StrUtil.isBlank(existingConversation.getTitle());
    }

    /**
     * 构造取消时的完成载荷（如果有内容则先落库）
     */
    //当任务被取消时，先把已经生成的内容尽量落库，再构造一个“取消时也能返回给前端的完成结果”。
    private CompletionPayload buildCompletionPayloadOnCancel() {
        //把当前已经累积生成的回答内容，转成一个普通字符串。
        String content = answer.toString();
        Long messageId = null;
        //如果当前已经生成出来的内容不是空的，就把这部分内容当作 assistant 消息追加到会话记忆里，并拿到保存后的消息 id。
        if (StrUtil.isNotBlank(content)) {
            messageId = memoryService.append(conversationId, userId, ChatMessage.assistant(content));
        }
        //取一下这次完成事件里应该带什么标题。
        String title = resolveTitleForEvent();
        return new CompletionPayload(String.valueOf(messageId), title);
    }

    //接收模型返回的正文增量内容，把它追加到完整答案里，并按块拆分后通过 SSE 发给前端。
    @Override
    public void onContent(String chunk) {
        //只要任务已经取消，后面再来的内容一律丢弃。,防止用户点了取消还输出一点点内容
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        //如果这次传进来的内容是空的、空字符串，或者全是空白字符，那也不处理，直接返回。
        if (StrUtil.isBlank(chunk)) {
            return;
        }
        //把当前这次收到的正文片段，追加到完整答案缓冲区里。
        answer.append(chunk);
        sendChunked(TYPE_RESPONSE, chunk);
    }

    //接收模型返回的“思考过程”增量内容，并按块拆分后实时发给前端展示。
    @Override
    public void onThinking(String chunk) {
        //先检查当前任务有没有被取消，如果已经取消，就不再处理这段思考内容。
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        //如果这次传进来的思考内容为空、空字符串或者全是空白字符，那就直接忽略。
        if (StrUtil.isBlank(chunk)) {
            return;
        }
        //把这段思考内容按配置的分块大小拆开，然后以 thinking 类型发送给前端。
        sendChunked(TYPE_THINK, chunk);
    }

    //当模型正常生成完毕时，把完整回答存入会话记忆，向前端发送结束事件，注销任务并关闭 SSE 连接。
    @Override
    public void onComplete() {
//        先判断当前任务是否已经被取消，如果已经取消了，就不要再走正常完成逻辑。
        if (taskManager.isCancelled(taskId)) {
            return;
        }
//        把当前已经完整生成好的回答，作为一条 assistant 消息追加到当前会话里，并拿到保存后的消息 id。
        Long messageId = memoryService.append(conversationId, UserContext.getUserId(),
                ChatMessage.assistant(answer.toString()));
        //确定这次结束事件里应该返回给前端的会话标题。
        String title = resolveTitleForEvent();
        //把 Long 类型的 messageId 转成字符串，如果 messageId 本身是空，就保持 null。
        String messageIdText = messageId == null ? null : String.valueOf(messageId);
        //通过 SSE 给前端发送一个 FINISH 事件，告诉前端这次回答已经完成，并把消息 id 和标题一起带过去。
        sender.sendEvent(SSEEventType.FINISH.value(), new CompletionPayload(messageIdText, title));
        //再发送一个 DONE 事件，明确告诉前端：流式传输已经彻底结束了。
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
        //把当前任务从任务管理器里注销掉。
        taskManager.unregister(taskId);
        //关闭当前 SSE 响应，正式结束这次和前端的流式连接。
        sender.complete();
    }

    //当流式过程中发生异常时，注销当前任务并把错误通过 SSE 通知前端。
    @Override
    public void onError(Throwable t) {
        //如果当前任务其实已经被取消了，那这个异常就不再按“正常错误”处理，直接返回。
        if (taskManager.isCancelled(taskId)) {
            return;
        }
        //把当前任务从任务管理器里移除掉。
        taskManager.unregister(taskId);
        //把这次异常通过 sender 通知给前端。
        sender.fail(t);
    }

    //把一大段内容按配置的块大小拆成多小段，再一段一段发给前端，避免一次推太长。
    private void sendChunked(String type, String content) {
//        先取出字符串 content 的长度。
        int length = content.length();
//        定义一个游标，表示当前遍历到字符串的哪个位置。
        int idx = 0;
//        定义一个计数器，表示当前缓冲区里已经装了多少个“字符单位（准确说是码点）”。
        int count = 0;
//        创建一个缓冲区，用来临时存当前这一小块准备发送的内容
        StringBuilder buffer = new StringBuilder();
//        只要还没遍历完整个字符串，就一直循环处理。
        while (idx < length) {
//            从当前下标 idx 的位置，取出一个 Unicode 码点。
            int codePoint = content.codePointAt(idx);
//            把刚刚取到的这个完整字符追加到缓冲区里。
            buffer.appendCodePoint(codePoint);
//            把游标往后移动。
            idx += Character.charCount(codePoint);
            count++;
//            判断是否达到块大小
            if (count >= messageChunkSize) {
//              通过 sender 给前端发一个 MESSAGE 类型的 SSE 事件。即发送当前块
                sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(type, buffer.toString()));
//                清空缓冲区
                buffer.setLength(0);
//                重置计数器
                count = 0;
            }
        }
//        循环结束后，检查缓冲区里是不是还剩下一点没发出去的内容。
        if (!buffer.isEmpty()) {
            sender.sendEvent(SSEEventType.MESSAGE.value(), new MessageDelta(type, buffer.toString()));
        }
    }

    //决定本次结束事件里要不要带会话标题，如果需要就优先取数据库里的标题，否则给一个默认标题“新对话”。
    private String resolveTitleForEvent() {
        if (!sendTitleOnComplete) {
            return null;
        }
        ConversationDO conversation = conversationGroupService.findConversation(conversationId, userId);
        if (conversation != null && StrUtil.isNotBlank(conversation.getTitle())) {
            return conversation.getTitle();
        }
        return "新对话";
    }
}
