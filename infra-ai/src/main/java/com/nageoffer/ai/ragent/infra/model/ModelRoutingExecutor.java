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

import com.nageoffer.ai.ragent.framework.errorcode.BaseErrorCode;
import com.nageoffer.ai.ragent.framework.exception.RemoteException;
import com.nageoffer.ai.ragent.infra.enums.ModelCapability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Function;

/**
 * 模型路由执行器
 * 负责在多个模型候选者之间进行调度执行，并提供故障转移（Fallback）和健康检查机制
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRoutingExecutor {

    //健康状态存储器。记录某个模型最近是否调用成功、失败、是否还能继续调用。
    private final ModelHealthStore healthStore;

    //自动调用，一个模型失败了，就自动换下一个。
    //这个是挑选好的模型列表来真正调用
    public <C, T> T executeWithFallback(
            ModelCapability capability,//当前要执行的模型能力类型
            List<ModelTarget> targets,//候选模型列表。
            Function<ModelTarget, C> clientResolver,//给我一个模型目标，我来帮你找到真正的调用客户端。
            //调用器函数。
            ModelCaller<C, T> caller) {
        //拿到这个能力的展示名
        String label = capability.getDisplayName();
        //没有候选模型直接报错
        if (targets == null || targets.isEmpty()) {
            throw new RemoteException("No " + label + " model candidates available");
        }

        Throwable last = null;
//        依次尝试每个模型
        for (ModelTarget target : targets) {
//          调用刚才传进来的 clientResolver 函数，根据当前 target 解析出对应的 client。
            C client = clientResolver.apply(target);
            //没有跳过
            if (client == null) {
                log.warn("{} provider client missing: provider={}, modelId={}", label, target.candidate().getProvider(), target.id());
                continue;
            }
            //不允许健康检查跳过
            if (!healthStore.allowCall(target.id())) {
                continue;
            }

            try {
                //开始真正调用模型。
                //这里不是执行器自己写死调用逻辑，而是通过外部传进来的 caller 来调。
                T response = caller.call(client, target);
                healthStore.markSuccess(target.id());
                return response;
            } catch (Exception e) {
                last = e;
//                告诉健康状态存储器：这个模型这次调用失败了
                healthStore.markFailure(target.id());
                //记录异常后继续循环找模型
                log.warn("{} model failed, fallback to next. modelId={}, provider={}", label, target.id(), target.candidate().getProvider(), e);
            }
        }

        //所有模型失败后统一抛异常
        throw new RemoteException(
                "All " + label + " model candidates failed: " + (last == null ? "unknown" : last.getMessage()),
                last,
                BaseErrorCode.REMOTE_ERROR
        );
    }
}
