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
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.nageoffer.ai.ragent.rag.enums.SSEEventType;
import com.nageoffer.ai.ragent.rag.dto.CompletionPayload;
import com.nageoffer.ai.ragent.framework.web.SseEmitterSender;
import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Slf4j
@Component
//流式任务的注册、取消、清理中心
public class StreamTaskManager {

    private static final String CANCEL_TOPIC = "ragent:stream:cancel";
    private static final String CANCEL_KEY_PREFIX = "ragent:stream:cancel:";
    private static final Duration CANCEL_TTL = Duration.ofMinutes(30);

    //可以理解为一个类似map，现在开始创建一个缓存，键是taskId 值是也就是任务信息对象
    private final Cache<String, StreamTaskInfo> tasks = CacheBuilder.newBuilder()
            .expireAfterWrite(CANCEL_TTL)//过期时间
            //这个缓存最大允许10000个任务存在
            .maximumSize(10000)  // 限制最大数量，基本上不可能超出这个数量。如果觉得不稳妥，可以把值调大并在配置文件声明
            .build();

    private final RedissonClient redissonClient;
    private int listenerId = -1;

    //构造方法：构造任务管理器，并注入 Redisson，供后面做 Redis 标记和发布订阅取消消息使用。
    public StreamTaskManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @PostConstruct
//    在类初始化后订阅 Redis 的取消主题，这样一旦收到某个 taskId 的取消消息，就会执行本地取消逻辑。
    public void subscribe() {
        //先拿到 Redis 里“取消主题”对应的 Topic 对象(RTopic 是 Redisson 对 Redis 发布/订阅频道 的 Java 封装)
        RTopic topic = redissonClient.getTopic(CANCEL_TOPIC);
        //给这个 topic 注册一个监听器，当频道里收到字符串消息时，就执行后面的回调逻辑。
        listenerId = topic.addListener(String.class, (channel, taskId) -> {
            if (StrUtil.isBlank(taskId)) {
                return;
            }
            cancelLocal(taskId);
        });
    }

    @PreDestroy
    //在类销毁前移除 Redis 主题监听器，避免应用关闭时还残留订阅。
    public void unsubscribe() {
        if (listenerId == -1) {
            return;
        }
        redissonClient.getTopic(CANCEL_TOPIC).removeListener(listenerId);
    }

    //给某个任务注册 SSE 发送器和“取消时要返回的内容生成器”，并在注册时顺便检查这个任务是不是早就已经被取消了。
    public void register(String taskId, SseEmitterSender sender, Supplier<CompletionPayload> onCancelSupplier) {
        StreamTaskInfo taskInfo = getOrCreate(taskId);//先根据 taskId 找到这个任务的状态对象；如果没有，就新建一个。
        taskInfo.sender = sender;//把当前前端 SSE 发送器保存到任务对象里。
        taskInfo.onCancelSupplier = onCancelSupplier;//把“取消时生成 CompletionPayload 的回调”也保存到任务对象里。
        //在注册完 sender 和回调之后，立刻去 Redis 查一下，这个任务是不是已经被全局标记为取消了。
        if (isTaskCancelledInRedis(taskId, taskInfo)) {
            //补偿收尾
            //如果发现任务已经取消了，那就立刻调用取消回调，生成当前时刻的返回内容。
            CompletionPayload payload = taskInfo.onCancelSupplier.get();
            //通过当前这个 SSE 发送器，给前端发送取消事件和结束事件。
            sendCancelAndDone(sender, payload);
            //正式关闭这条 SSE 连接。
            sender.complete();
        }
    }

    //把大模型流式调用返回的“可取消句柄”绑定到指定任务上
    public void bindHandle(String taskId, StreamCancellationHandle handle) {
        //先根据 taskId 拿到这个任务对应的任务信息对象；如果没有，就创建一个新的。
        StreamTaskInfo taskInfo = getOrCreate(taskId);
        //把这次流式任务的取消句柄保存到任务信息对象里。
        taskInfo.handle = handle;
        //如果这个任务当前已经被标记成“取消”，并且现在又确实拿到了一个非空的取消句柄，那么就应该立刻取消。
        if (taskInfo.cancelled.get() && handle != null) {
            //调用取消方法
            handle.cancel();
        }
    }

    //检查本地缓存里这个任务当前是否已经处于取消状态。
    public boolean isCancelled(String taskId) {
        StreamTaskInfo info = tasks.getIfPresent(taskId);
        return info != null && info.cancelled.get();
    }

    //对外发起一次任务取消：先在 Redis 里写“已取消”标记，再通过 Redis topic 广播给所有节点。
    public void cancel(String taskId) {
        // 先设置 Redis 标记，再发布消息
        //redis里面就是个标记key：cancelKey(taskId)，value：true
        //RBucket 是 Redisson 对 Redis String 类型的一层 Java 封装
        RBucket<Boolean> bucket = redissonClient.getBucket(cancelKey(taskId));
        //写进值和过期时间
        bucket.set(Boolean.TRUE, CANCEL_TTL);

        // 发布消息通知所有节点（包括本地）
        // 本地节点也通过监听器统一处理，避免重复调用 cancelLocal
//        往 Redis 的某个 topic 里广播一条消息，内容就是 taskId
        redissonClient.getTopic(CANCEL_TOPIC).publish(taskId);
    }

    /**
     * 检查任务是否在 Redis 中被标记为已取消
     * 如果是，会同步状态到本地缓存
     */
    //去 Redis 查这个任务有没有被标记为取消，如果有，就把这个取消状态同步到本地任务对象里。
    private boolean isTaskCancelledInRedis(String taskId, StreamTaskInfo taskInfo) {
        if (taskInfo.cancelled.get()) {
            return true;
        }

        RBucket<Boolean> bucket = redissonClient.getBucket(cancelKey(taskId));
        Boolean cancelled = bucket.get();
        if (Boolean.TRUE.equals(cancelled)) {
            taskInfo.cancelled.set(true);
            return true;
        }
        return false;
    }

    //真正执行本机上的取消动作：标记任务已取消、停止模型流输出、给前端发取消和结束事件。
    //redis回调的逻辑
    private void cancelLocal(String taskId) {
        //先去本地缓存 tasks 里找这个 taskId 对应的任务信息对象。
        StreamTaskInfo taskInfo = tasks.getIfPresent(taskId);
        if (taskInfo == null) {
            return;
        }

        // 使用 CAS 确保只执行一次
        //尝试把任务的取消状态从 false 改成 true；如果改失败，说明别人已经改过了，那当前线程就直接退出。
        if (!taskInfo.cancelled.compareAndSet(false, true)) {
            return;
        }

        if (taskInfo.handle != null) {
            taskInfo.handle.cancel();
        }

        // 在取消时执行回调，保存已累积的内容
        //如果这个任务绑定过 SSE 发送器，那就继续做前端通知。
        if (taskInfo.sender != null) {
            //调用取消回调，生成一个当前取消时刻的返回载荷 payload。
            CompletionPayload payload = taskInfo.onCancelSupplier.get();
            //通过 SSE 给前端发送“取消事件”和“结束事件”。
            sendCancelAndDone(taskInfo.sender, payload);
            taskInfo.sender.complete();
        }
    }

    //在任务结束后把本地缓存和 Redis 里的取消标记都清掉，防止任务信息一直残留。
    public void unregister(String taskId) {
        // 清理本地缓存
        tasks.invalidate(taskId);

        // 清理Redis
        redissonClient.getBucket(cancelKey(taskId)).deleteAsync();
    }

    //在任务结束后把本地缓存和 Redis 里的取消标记都清掉，防止任务信息一直残留
    private String cancelKey(String taskId) {
        return CANCEL_KEY_PREFIX + taskId;
    }

    //根据 taskId 生成这个任务在 Redis 里对应的取消标记 key。
    private void sendCancelAndDone(SseEmitterSender sender, CompletionPayload payload) {
        //如果传进来的 payload 是 null，就构造一个空的 CompletionPayload；否则就直接用原来的 payload。
        CompletionPayload actualPayload = payload == null ? new CompletionPayload(null, null) : payload;
        //向前端发送一个 SSE 事件，事件类型是 CANCEL，事件内容是 actualPayload。
        sender.sendEvent(SSEEventType.CANCEL.value(), actualPayload);
        //再向前端发送一个 DONE 事件，表示这条流正式结束。
        sender.sendEvent(SSEEventType.DONE.value(), "[DONE]");
    }

    @SneakyThrows
    //向前端 SSE 通道发送“任务已取消”和“流结束”的两个事件，保证前端能正常收尾。
    private StreamTaskInfo getOrCreate(String taskId) {
        return tasks.get(taskId, StreamTaskInfo::new);
    }

    //它是单个流式任务的运行时状态对象，里面保存了这个任务的取消状态、取消句柄、SSE 发送器和取消回调。
    private static final class StreamTaskInfo {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile StreamCancellationHandle handle;
        private volatile SseEmitterSender sender;
        private volatile Supplier<CompletionPayload> onCancelSupplier;
    }
}
