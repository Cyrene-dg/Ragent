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

package com.nageoffer.ai.ragent.infra.chat;

import cn.hutool.core.collection.CollUtil;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import com.nageoffer.ai.ragent.infra.model.ModelHealthStore;
import com.nageoffer.ai.ragent.infra.model.ModelRoutingExecutor;
import com.nageoffer.ai.ragent.infra.model.ModelSelector;
import com.nageoffer.ai.ragent.infra.model.ModelTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 路由式 LLM 服务实现类
 * <p>
 * 该服务负责智能路由和调度大模型请求，主要功能包括：
 * 1. 根据请求特性选择最佳的大模型提供商
 * 2. 支持多模型候选的自动降级和故障转移
 * 3. 维护模型健康状态，优化路由策略
 * 4. 支持同步和流式两种调用方式
 */
@Slf4j
@Service
@Primary
public class RoutingLLMService implements LLMService {

    private static final int FIRST_PACKET_TIMEOUT_SECONDS = 60;
    private static final String STREAM_INTERRUPTED_MESSAGE = "流式请求被中断";
    private static final String STREAM_NO_PROVIDER_MESSAGE = "无可用大模型提供者";
    private static final String STREAM_START_FAILED_MESSAGE = "流式请求启动失败";
    private static final String STREAM_TIMEOUT_MESSAGE = "流式首包超时";
    private static final String STREAM_NO_CONTENT_MESSAGE = "流式请求未返回内容";
    private static final String STREAM_ALL_FAILED_MESSAGE = "大模型调用失败，请稍后再试...";

    private final ModelSelector selector;//模型选择器
    private final ModelHealthStore healthStore;//模型健康状态存储
    private final ModelRoutingExecutor executor;//模型路由执行器
    private final Map<String, ChatClient> clientsByProvider;//所有可用的 ChatClient 列表

    //构造方法注入
    public RoutingLLMService(
            ModelSelector selector,
            ModelHealthStore healthStore,
            ModelRoutingExecutor executor,
            List<ChatClient> clients) {
        this.selector = selector;
        this.healthStore = healthStore;
        this.executor = executor;
        this.clientsByProvider = clients.stream()
                .collect(Collectors.toMap(ChatClient::provider, Function.identity()));
    }

    @Override
    @RagTraceNode(name = "llm-chat-routing", type = "LLM_ROUTING")//给这一步打链路追踪节点
    public String chat(ChatRequest request) {
        //调用执行器
        return executor.executeWithFallback(
                ModelCapability.CHAT,//能力是聊天
                selector.selectChatCandidates(request.getThinking()),//能力是给定一个候选模型 target，从 clientsByProvider 里找到它对应的 ChatClient
                target -> clientsByProvider.get(target.candidate().getProvider()),//从 clientsByProvider 里找到它对应的 ChatClient
                (client, target) -> client.chat(request, target)//拿到 client 和 target 后，调用对应客户端的同步聊天方法。
        );
    }

    @Override
    @RagTraceNode(name = "llm-stream-routing", type = "LLM_ROUTING")
    //真正的流式模型的调用，按照顺序尝试多个模型
    public StreamCancellationHandle streamChat(ChatRequest request, StreamCallback callback) {
        //创建候选模型的列表
        List<ModelTarget> targets = selector.selectChatCandidates(request.getThinking());
       //一个候选模型都没有就抛异常
        if (CollUtil.isEmpty(targets)) {
            throw new RemoteException(STREAM_NO_PROVIDER_MESSAGE);
        }

        //先准备一些公共变量，label，表示当前能力标签，这里是 CHAT 的展示名。
        //lastError 记录“最后一次失败的异常”。
        String label = ModelCapability.CHAT.getDisplayName();
        Throwable lastError = null;

        //尝试每个候选模型
        for (ModelTarget target : targets) {
            //解析具体客户端
            ChatClient client = resolveClient(target, label);
            if (client == null) {
                continue;
            }
            //首包等待器，用来判断这个流式请求有没有成功收到第一段响应
            FirstPacketAwaiter awaiter = new FirstPacketAwaiter();
//            先别立刻把模型返回内容直接发给外层 callback，先包一层做探测和缓冲。
            ProbeBufferingCallback wrapper = new ProbeBufferingCallback(callback, awaiter);
//            真正尝试启动流式请求
            StreamCancellationHandle handle;
            try {
                //调用底层客户端
                handle = client.streamChat(request, wrapper, target);
            } catch (Exception e) {
                //失败的话报异常进入下一次循环
                healthStore.markFailure(target.id());
                lastError = e;
                log.warn("{} 流式请求启动失败，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider(), e);
                continue;
            }
            //启动成功没返回句柄也算失败，返回异常进入下一次循环
            if (handle == null) {
                healthStore.markFailure(target.id());
                lastError = new RemoteException(STREAM_START_FAILED_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 流式请求未返回取消句柄，切换下一个模型。modelId：{}，provider：{}",
                        label, target.id(), target.candidate().getProvider());
                continue;
            }
            //流式请求已经启动了，但还要再等一下，看这个模型能不能真正返回第一段内容。
            //防止看起来启动成功事实上并不返回内容
            FirstPacketAwaiter.Result result = awaitFirstPacket(awaiter, handle, callback);

            // 判断结果
            //如果首包成功，正式提交并返回，那就真的成功了
            if (result.isSuccess()) {
                //把包装回调里之前缓冲的内容正式提交出去。
                wrapper.commit();
                //把这个模型记为成功。
                healthStore.markSuccess(target.id());
                //直接返回这个取消句柄。
                return handle;
            }

            // 失败处理
            //如果首包没成功，判定失败并取消当前流
            healthStore.markFailure(target.id());
            handle.cancel();
            //根据首包失败结果，构建一个更合适的异常对象，同时记录日志
            lastError = buildLastErrorAndLog(result, target, label);
        }

        // 所有模型都失败了，通知客户端错误
        throw notifyAllFailed(callback, lastError);
    }

    //根据定位到的大模型真正找到所需要的大模型
    private ChatClient resolveClient(ModelTarget target, String label) {
        //先从 target 里拿到候选模型信息 candidate()，再拿到这个候选模型对应的 provider，再去 clientsByProvider 这个 Map 里查对应的客户端
        ChatClient client = clientsByProvider.get(target.candidate().getProvider());
        if (client == null) {
            log.warn("{} 提供商客户端缺失: provider：{}，modelId：{}",
                    label, target.candidate().getProvider(), target.id());
        }
        return client;
    }

    //等待首包的方法，传入首包等待器，取消句柄，流式回调，返回结果对象
    private FirstPacketAwaiter.Result awaitFirstPacket(FirstPacketAwaiter awaiter,
                                                       StreamCancellationHandle handle,
                                                       StreamCallback callback) {
        try {
            //指定最多等待的秒数
            return awaiter.await(FIRST_PACKET_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            //发现异常阻塞等待中断线程
            Thread.currentThread().interrupt();
            //取消任务
            handle.cancel();
            //返回异常信息
            RemoteException interruptedException = new RemoteException(STREAM_INTERRUPTED_MESSAGE, e, BaseErrorCode.REMOTE_ERROR);
            callback.onError(interruptedException);
            throw interruptedException;
        }
    }

    //失败异常的定义类
    private Throwable buildLastErrorAndLog(FirstPacketAwaiter.Result result, ModelTarget target, String label) {
        switch (result.getType()) {
            //判定是哪一类失败，返回失败的一些信息
            case ERROR -> {
                Throwable error = result.getError() != null
                        ? result.getError()
                        : new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求失败，切换下一个模型",
                        label, target.id(), target.candidate().getProvider(), error);
                return error;
            }
            case TIMEOUT -> {
                RemoteException timeout = new RemoteException(STREAM_TIMEOUT_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求超时，切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return timeout;
            }
            case NO_CONTENT -> {
                RemoteException noContent = new RemoteException(STREAM_NO_CONTENT_MESSAGE, BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求无内容完成，切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return noContent;
            }
            default -> {
                RemoteException unknown = new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR);
                log.warn("{} 失败模型: modelId={}, provider={}，原因: 流式请求失败（未知类型），切换下一个模型",
                        label, target.id(), target.candidate().getProvider());
                return unknown;
            }
        }
    }

    //创建最终异常对象，表示提示语，最后一次失败的原因，统一错误吗
    private RemoteException notifyAllFailed(StreamCallback callback, Throwable lastError) {
        RemoteException finalException = new RemoteException(
                STREAM_ALL_FAILED_MESSAGE,
                lastError,
                BaseErrorCode.REMOTE_ERROR
        );
        callback.onError(finalException);
        return finalException;
    }

    /**
     * 流式首包探测回调：
     * - 探测阶段先缓存事件，避免失败模型的内容污染下游输出
     * - 首包成功后 commit，按原始顺序回放缓存并转实时转发
     */
    //内部类，本质上是一个：包装版 callback
    private static final class ProbeBufferingCallback implements StreamCallback {
        private final StreamCallback downstream;//真正的下游回调对象
        private final FirstPacketAwaiter awaiter;//首包等待器。
        private final Object lock = new Object();//定义一个锁对象。
        private final List<BufferedEvent> bufferedEvents = new ArrayList<>();//事件缓存列表。在确认首包成功之前，收到的事件先放这里
        private volatile boolean committed;//一个提交标记。

        //构造方法
        private ProbeBufferingCallback(StreamCallback downstream, FirstPacketAwaiter awaiter) {
            this.downstream = downstream;
            this.awaiter = awaiter;
            this.committed = false;
        }

        @Override
        //当模型流式返回“普通正文内容”时，会进这里。
        public void onContent(String content) {
            //告诉首包等待器：已经收到内容了
            awaiter.markContent();
            //把这段正文包装成一个 BufferedEvent，如果还没 commit，就先缓存，如果已经 commit，就直接发给下游
            bufferOrDispatch(BufferedEvent.content(content));
        }

        @Override
        //当模型返回“思考内容”时，进入这里。
        public void onThinking(String content) {
            //同上
            awaiter.markContent();
            bufferOrDispatch(BufferedEvent.thinking(content));
        }

        @Override
        //当流式响应正常结束时，进入这里。
        public void onComplete() {
            awaiter.markComplete();
            bufferOrDispatch(BufferedEvent.complete());
        }

        //当流式请求出错时，进入这里。
        @Override
        public void onError(Throwable t) {
            awaiter.markError(t);
            bufferOrDispatch(BufferedEvent.error(t));
        }

        /**
         * 首包探测成功后提交：
         * 1. 原子切换为 committed
         * 2. 按事件顺序回放缓存，保证时序一致
         */
        private void commit() {
            //先定义一个临时列表，准备存“缓存事件快照”。
            List<BufferedEvent> snapshot;
            synchronized (lock) {

                if (committed) {
                    return;
                }
//                标记：从现在开始，后续事件不用再缓存了，可以直接发出去。
                committed = true;
//                如果缓存区本来就空，直接结束。
                if (bufferedEvents.isEmpty()) {
                    return;
                }
//                把当前缓存事件拷贝一份出来。
                snapshot = new ArrayList<>(bufferedEvents);
//                清空原缓存。
                bufferedEvents.clear();
            }
//            把刚才缓存的事件逐个真正发出去。
            for (BufferedEvent event : snapshot) {
                dispatch(event);
            }
        }

        //如果还没 commit，就先缓存；如果已经 commit，就直接发
        private void bufferOrDispatch(BufferedEvent event) {
            boolean dispatchNow;
            synchronized (lock) {
                dispatchNow = committed;
                if (!dispatchNow) {
                    bufferedEvents.add(event);
                }
            }
            if (dispatchNow) {
                dispatch(event);
            }
        }
        //事件转发器，把一个缓存事件 BufferedEvent 真正分发给下游回调 downstream
        private void dispatch(BufferedEvent event) {
            //根据事件类型做不同处理
            switch (event.type()) {
                //这是普通回答文本，发给真正的回调处理
                case CONTENT -> downstream.onContent(event.content());
                //这是思考过程内容，不是正文，交给 thinking 分支处理
                case THINKING -> downstream.onThinking(event.content());
                //表示这次流式输出正常结束了。
                case COMPLETE -> downstream.onComplete();
//错误事件一定要带个异常对象传给下游，实在没有就补一个默认的
                case ERROR -> downstream.onError(event.error() != null
                        ? event.error()
                        : new RemoteException("流式请求失败", BaseErrorCode.REMOTE_ERROR));
            }
        }

        //简单的数据载体类，专门表示“一个被缓存的事件”
        private record BufferedEvent(EventType type, String content, Throwable error) {

            //快速创建一个“普通内容事件”
            private static BufferedEvent content(String content) {
//返回一个正文事件，没有异常
                return new BufferedEvent(EventType.CONTENT, content, null);
            }
//快速创建一个“思考内容事件”
            private static BufferedEvent thinking(String content) {
                return new BufferedEvent(EventType.THINKING, content, null);
            }
//快速创建一个“完成事件”
            private static BufferedEvent complete() {
                return new BufferedEvent(EventType.COMPLETE, null, null);
            }
//快速创建一个“错误事件”
            private static BufferedEvent error(Throwable error) {
                return new BufferedEvent(EventType.ERROR, null, error);
            }
        }

        //定义了四种固定事件类型，正文，思考，完成，错误
        private enum EventType {
            CONTENT,
            THINKING,
            COMPLETE,
            ERROR
        }
    }
}
