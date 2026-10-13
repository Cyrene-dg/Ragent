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

package com.nageoffer.ai.ragent.rag.aop;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.framework.context.UserContext;
import com.nageoffer.ai.ragent.framework.trace.RagTraceContext;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.framework.trace.RagTraceRoot;
import com.nageoffer.ai.ragent.rag.config.RagTraceProperties;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceNodeDO;
import com.nageoffer.ai.ragent.rag.dao.entity.RagTraceRunDO;
import com.nageoffer.ai.ragent.rag.service.RagTraceRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Date;

/**
 * 注解式 RAG Trace 采集切面。
 *
 * <p>职责：拦截标有 {@link RagTraceRoot} 或 {@link RagTraceNode} 的方法，
 * 在不修改业务方法的前提下，完成开始落库、计时、成功/异常收尾和节点栈维护。</p>
 */
// Lombok：为 log 字段生成日志对象。
@Slf4j
// 声明这是一个 Spring AOP 切面。
@Aspect
// 让 Spring 将本类注册为 Bean，AOP 才能在运行时创建代理并调用它。
@Component
// 设置切面优先级较高，使 Trace 尽量包住后续业务切面。
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
// Lombok：为 final 依赖生成构造器，供 Spring 注入。
@RequiredArgsConstructor
public class RagTraceAspect {

    // 节点和整条链路刚创建、尚未结束时使用的状态。
    private static final String STATUS_RUNNING = "RUNNING";
    // 方法正常返回时使用的状态。
    private static final String STATUS_SUCCESS = "SUCCESS";
    // 方法抛出异常时使用的状态。
    private static final String STATUS_ERROR = "ERROR";

    // 负责将 Run / Node 的开始和结束状态写入 MySQL。
    private final RagTraceRecordService traceRecordService;
    // Trace 总开关、错误消息最大长度等配置。
    private final RagTraceProperties traceProperties;

    /**
     * 拦截带有 {@link RagTraceRoot} 的方法，为一次完整业务请求创建根 Trace。
     *
     * @param joinPoint 本次原方法调用的“遥控器”：可读取方法、参数，并通过 proceed() 执行原方法
     * @param traceRoot 当前原方法上贴着的完整 @RagTraceRoot 注解对象
     */
    // @annotation(traceRoot) 表示：只匹配带 @RagTraceRoot 的方法，并将注解实例绑定到 traceRoot 参数。
    @Around("@annotation(traceRoot)")
    public Object aroundRoot(ProceedingJoinPoint joinPoint, RagTraceRoot traceRoot) throws Throwable {
        // 追踪功能关闭时完全不干预业务，直接执行原方法。
        if (!traceProperties.isEnabled()) {
            return joinPoint.proceed();
        }

        // 从当前线程的 Trace 上下文读取已有 traceId。
        String existingTraceId = RagTraceContext.getTraceId();
        // 若外层已经创建过 Root，当前方法只是链路中的嵌套调用，不能再次创建一条新的 Run。
        if (StrUtil.isNotBlank(existingTraceId)) {
            return joinPoint.proceed();
        }

        // 取得被拦截方法的签名信息，后面用来读取方法名和参数名。
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        // 取得实际被调用的 Java Method，用于记录入口方法和默认链路名称。
        Method method = signature.getMethod();
        // 为本次完整请求生成全局唯一 traceId，作为 Run 与所有 Node 的关联键。
        String traceId = IdUtil.getSnowflakeNextIdStr();
        // 根据注解配置的参数名，从原方法入参中取出会话 ID。
        String conversationId = resolveStringArg(signature, joinPoint.getArgs(), traceRoot.conversationIdArg());
        // 根据注解配置的参数名，从原方法入参中取出任务 ID。
        String taskId = resolveStringArg(signature, joinPoint.getArgs(), traceRoot.taskIdArg());
        // 注解未配置 name 时，使用方法名作为管理端展示的链路名称。
        String traceName = StrUtil.blankToDefault(traceRoot.name(), method.getName());
        // 保存根链路的业务开始时间。
        Date startTime = new Date();
        // 保存毫秒时间戳，结束时用于计算 durationMs。
        long startMillis = System.currentTimeMillis();

        // 先插入一条 RUNNING 的 Run 记录；即使进程中途崩溃，也能保留“曾执行到这里”的证据。
        traceRecordService.startRun(RagTraceRunDO.builder()
                // 写入本次链路的全局关联 ID。
                .traceId(traceId)
                // 写入给人看的链路名称。
                .traceName(traceName)
                // 记录“类全名#方法名”，便于从管理端回到源码入口。
                .entryMethod(method.getDeclaringClass().getName() + "#" + method.getName())
                // 关联聊天会话。
                .conversationId(conversationId)
                // 关联流式任务。
                .taskId(taskId)
                // 记录当前登录用户。
                .userId(UserContext.getUserId())
                // 初次写入时状态必然是执行中。
                .status(STATUS_RUNNING)
                // 写入根链路开始时间。
                .startTime(startTime)
                // 完成本次 Run 的首次落库。
                .build());

        // 将 traceId 放入当前线程上下文；后续 @RagTraceNode 才知道应归属到哪条 Run。
        RagTraceContext.setTraceId(traceId);
        try {
            // 真正执行被 @RagTraceRoot 标记的原业务方法。
            Object result = joinPoint.proceed();
            // 原方法正常返回后，将整条 Run 更新为成功，并补全结束时间和总耗时。
            traceRecordService.finishRun(
                    // 更新哪一条 Run。
                    traceId,
                    // 正常返回，因此状态为 SUCCESS。
                    STATUS_SUCCESS,
                    // 成功时没有错误信息。
                    null,
                    // 记录结束绝对时间。
                    new Date(),
                    // 用当前时间减开始时间，得到总耗时毫秒数。
                    System.currentTimeMillis() - startMillis
            );
            // 保持原方法的返回值，不改变业务语义。
            return result;
        } catch (Throwable ex) {
            // 原方法抛异常时，将整条 Run 更新为 ERROR，并保留截断后的异常摘要。
            traceRecordService.finishRun(
                    // 更新哪一条 Run。
                    traceId,
                    // 异常结束，因此状态为 ERROR。
                    STATUS_ERROR,
                    // 避免过长异常信息撑爆字段，也避免直接保存完整堆栈。
                    truncateError(ex),
                    // 记录异常发生时的结束时间。
                    new Date(),
                    // 计算本次失败前已经消耗的总时间。
                    System.currentTimeMillis() - startMillis
            );
            // Trace 只记录异常，不吞掉异常；让原业务的异常处理链继续生效。
            throw ex;
        } finally {
            // 无论成功或失败，都清理当前线程上下文，防止线程复用时串到下一次请求。
            RagTraceContext.clear();
        }
    }

    /**
     * 拦截带有 {@link RagTraceNode} 的普通方法，为其创建一条节点记录。
     *
     * @param joinPoint 本次原方法调用的“遥控器”
     * @param traceNode 当前原方法上贴着的完整 @RagTraceNode 注解对象
     */
    // @annotation(traceNode) 表示：只匹配带 @RagTraceNode 的方法，并将注解实例绑定到 traceNode 参数。
    @Around("@annotation(traceNode)")
    public Object aroundNode(ProceedingJoinPoint joinPoint, RagTraceNode traceNode) throws Throwable {
        // 追踪总开关关闭时，节点不做任何记录。
        if (!traceProperties.isEnabled()) {
            return joinPoint.proceed();
        }
        // 从当前线程上下文取得所属根链路的 traceId。
        String traceId = RagTraceContext.getTraceId();
        // 没有根 Trace 时，节点无法归属到任何 Run；仍执行原业务，但不落库。
        if (StrUtil.isBlank(traceId)) {
            return joinPoint.proceed();
        }

        // 读取当前被拦截方法的签名。
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        // 取得实际 Java Method，供记录类名、方法名及默认节点名。
        Method method = signature.getMethod();
        // 为这一次具体执行生成唯一 nodeId；同一个方法被多次调用也会有不同 nodeId。
        String nodeId = IdUtil.getSnowflakeNextIdStr();
        // 读取节点栈顶；它就是当前节点的父节点，没有父节点时为 null。
        String parentNodeId = RagTraceContext.currentNodeId();
        // 当前栈深度决定本节点应在管理端缩进展示几层。
        int depth = RagTraceContext.depth();
        // 记录节点开始的绝对时间。
        Date startTime = new Date();
        // 保存毫秒时间戳，结束时计算当前节点独立耗时。
        long startMillis = System.currentTimeMillis();

        // 先插入一条 RUNNING 的 Node 记录。
        traceRecordService.startNode(RagTraceNodeDO.builder()
                // 关联到当前根 Run。
                .traceId(traceId)
                // 写入本次节点执行的唯一 ID。
                .nodeId(nodeId)
                // 写入运行时自动推导出的父节点 ID。
                .parentNodeId(parentNodeId)
                // 写入当前节点层级。
                .depth(depth)
                // 注解未设置 type 时，使用 METHOD 作为默认分组类型。
                .nodeType(StrUtil.blankToDefault(traceNode.type(), "METHOD"))
                // 注解未设置 name 时，使用 Java 方法名作为展示名称。
                .nodeName(StrUtil.blankToDefault(traceNode.name(), method.getName()))
                // 保留实现类全名，便于排错时定位源码。
                .className(method.getDeclaringClass().getName())
                // 保留实现方法名，便于排错时定位源码。
                .methodName(method.getName())
                // 节点刚开始执行，因此状态为 RUNNING。
                .status(STATUS_RUNNING)
                // 写入节点开始时间。
                .startTime(startTime)
                // 完成节点首次落库。
                .build());

        // 将当前 nodeId 压入节点栈；后续嵌套的带注解方法会自动把它视作父节点。
        RagTraceContext.pushNode(nodeId);
        try {
            // 真正执行带 @RagTraceNode 的业务方法。
            Object result = joinPoint.proceed();
            // 原方法正常返回后，更新本节点为成功，并补全结束时间和耗时。
            traceRecordService.finishNode(
                    // 指明节点属于哪条根 Trace。
                    traceId,
                    // 指明要更新哪一个具体节点。
                    nodeId,
                    // 正常返回，因此状态为 SUCCESS。
                    STATUS_SUCCESS,
                    // 成功时没有错误信息。
                    null,
                    // 记录正常结束时间。
                    new Date(),
                    // 计算该节点自身持续时间。
                    System.currentTimeMillis() - startMillis
            );
            // 保持原方法返回值，不改变业务语义。
            return result;
        } catch (Throwable ex) {
            // 原方法抛异常时，更新节点为 ERROR，并保存截断后的异常摘要。
            traceRecordService.finishNode(
                    // 指明节点属于哪条根 Trace。
                    traceId,
                    // 指明要更新哪一个具体节点。
                    nodeId,
                    // 异常结束，因此状态为 ERROR。
                    STATUS_ERROR,
                    // 截断异常文本，避免字段过长。
                    truncateError(ex),
                    // 记录异常结束时间。
                    new Date(),
                    // 计算异常发生前该节点已经执行的时间。
                    System.currentTimeMillis() - startMillis
            );
            // 继续抛出异常，让父节点或上层业务决定是否降级、重试或终止。
            throw ex;
        } finally {
            // 无论成功或失败都弹出本节点，恢复父节点为当前栈顶，避免后续节点挂错父级。
            RagTraceContext.popNode();
        }
    }

    /**
     * 按参数名从被拦截方法的实参中读取字符串值。
     * RagTraceRoot 可通过 conversationIdArg / taskIdArg 指定要取的参数名。
     */
    private String resolveStringArg(MethodSignature signature, Object[] args, String argName) {
        // 没有配置参数名、没有实参或方法没有参数时，无法解析，直接返回 null。
        if (StrUtil.isBlank(argName) || args == null || args.length == 0) {
            return null;
        }
        // 读取编译后可获取的方法参数名数组。
        String[] parameterNames = signature.getParameterNames();
        // 参数名不可用或数量和实际参数不一致时，不能安全按名称取值。
        if (parameterNames == null || parameterNames.length != args.length) {
            return null;
        }
        // 逐个比对声明参数名，寻找注解要求的那个参数。
        for (int i = 0; i < parameterNames.length; i++) {
            // 当前参数名不是目标参数名，就继续检查下一个。
            if (!argName.equals(parameterNames[i])) {
                continue;
            }
            // 找到目标参数后，读取它的实际传入值。
            Object arg = args[i];
            // 实参为 null 时保持 null，避免错误转换为字符串 "null"。
            if (arg == null) {
                return null;
            }
            // 会话 ID / 任务 ID 最终统一按字符串存储。
            return String.valueOf(arg);
        }
        // 遍历完仍未找到指定参数名，返回 null。
        return null;
    }

    /**
     * 将异常压缩为可落库的摘要文本，防止超出数据库字段长度。
     */
    private String truncateError(Throwable throwable) {
        // 理论上异常路径会传入非空对象；此处仍做空值保护。
        if (throwable == null) {
            return null;
        }
        // 用“异常类名: 异常消息”组成易读摘要，而不是直接保存完整堆栈。
        String message = throwable.getClass().getSimpleName() + ": " + StrUtil.blankToDefault(throwable.getMessage(), "");
        // 若摘要长度在配置上限内，原样返回。
        if (message.length() <= traceProperties.getMaxErrorLength()) {
            return message;
        }
        // 超过上限时截断，保证数据库更新不会因字段过长失败。
        return message.substring(0, traceProperties.getMaxErrorLength());
    }
}
