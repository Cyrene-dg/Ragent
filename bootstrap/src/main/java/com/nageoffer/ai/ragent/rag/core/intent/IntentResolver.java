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

package com.nageoffer.ai.ragent.rag.core.intent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.nageoffer.ai.ragent.rag.dto.IntentCandidate;
import com.nageoffer.ai.ragent.rag.dto.IntentGroup;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import com.nageoffer.ai.ragent.rag.enums.IntentKind;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.EntityResolution;
import com.nageoffer.ai.ragent.rag.core.retrieve.plan.EntityResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.INTENT_MIN_SCORE;
import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.MAX_INTENT_COUNT;
import static com.nageoffer.ai.ragent.rag.enums.IntentKind.SYSTEM;

@Service
@RequiredArgsConstructor
public class IntentResolver {

    @Qualifier("defaultIntentClassifier")
    private final IntentClassifier intentClassifier;
    @Qualifier("intentClassifyThreadPoolExecutor")
    private final Executor intentClassifyExecutor;
    private final EntityResolver entityResolver;

    @RagTraceNode(name = "intent-resolve", type = "INTENT")
    //对改写结果里的每个子问题并发做意图分类，并把总意图数裁剪到上限内。
    public List<SubQuestionIntent> resolve(RewriteResult rewriteResult) {
       //校验子问题是否为空，优先使用 RewriteResult 里已经拆好的子问题列表；如果子问题列表为空，就退回到只处理“改写后的主问题”这一条。
        List<String> subQuestions = CollUtil.isNotEmpty(rewriteResult.subQuestions())
                ? rewriteResult.subQuestions()
                : List.of(rewriteResult.rewrittenQuestion());
        //把每个子问题都丢到线程池里异步分类，最后得到一组 Future 任务。
        List<CompletableFuture<SubQuestionIntent>> tasks = subQuestions.stream()
                .map(q -> CompletableFuture.supplyAsync(
                        //对某个子问题 q，先调用 classifyIntents(q) 得到它的候选意图列表，再把“子问题本身 + 该问题对应的意图列表”封装成一个 SubQuestionIntent。
                        () -> classifySubQuestion(q),
                        intentClassifyExecutor
                ))
                .toList();
        //把前面那些 Future 一个个取结果，最终收集成真正的 SubQuestionIntent 列表。
        List<SubQuestionIntent> subIntents = tasks.stream()
                .map(CompletableFuture::join)
                .toList();
        //不要直接把所有子问题的所有候选意图原样返回，而是先做一次全局裁剪，再返回最终结果。
        return capTotalIntents(subIntents);
    }

    //把所有子问题的意图结果按类型重新汇总成两组：MCP 意图和 KB 意图。
    public IntentGroup mergeIntentGroup(List<SubQuestionIntent> subIntents) {
        //先准备一个空列表，用来收集所有属于 MCP 类型的意图。
        List<NodeScore> mcpIntents = new ArrayList<>();
        //再准备一个空列表，用来收集所有属于 KB 类型的意图。
        List<NodeScore> kbIntents = new ArrayList<>();
        //再准备一个空列表，用来收集所有属于 KB 类型的意图。
        for (SubQuestionIntent si : subIntents) {
            //从当前这个子问题的候选意图列表里，把所有 MCP 类型意图筛出来，然后全部加到全局 mcpIntents 列表里。
            mcpIntents.addAll(filterMcpIntents(si.nodeScores()));
            //从当前这个子问题的候选意图列表里，把所有 MCP 类型意图筛出来，然后全部加到全局 mcpIntents 列表里。
            kbIntents.addAll(filterKbIntents(si.nodeScores()));
        }
        //封装成一个 IntentGroup 返回。
        return new IntentGroup(mcpIntents, kbIntents);
    }

    //判断当前意图列表是否只有一个系统意图，用于识别“纯系统响应”场景
    public boolean isSystemOnly(List<NodeScore> nodeScores) {
        //校验条件1.只能有一个候选意图2.只能有一个候选意图3.这个节点类型必须是 SYSTEM,不能是KB和MCP
        return nodeScores.size() == 1
                && nodeScores.get(0).getNode() != null
                && nodeScores.get(0).getNode().getKind() == SYSTEM;
    }

//    对单个问题调用分类器做意图识别，并按最低分阈值和最大数量做初步过滤。
    //输入一个问题字符串，输出这个问题对应的一组候选意图 NodeScore 列表。
    private SubQuestionIntent classifySubQuestion(String question) {
        EntityResolution entityHint = entityResolver.resolveAny(question);
        List<NodeScore> scores = classifyIntents(question, entityHint);
        return new SubQuestionIntent(question, scores, entityHint);
    }

    private List<NodeScore> classifyIntents(String question, EntityResolution entityHint) {
        //把当前问题交给意图分类器，得到原始的候选意图列表。

        //这里直接列出这个方法的流程，会先加载最新意图树，用叶子节点构造分类提示词，再把当前问题交给大模型，让模型返回若干“意图节点 id + 分数”的 JSON 结果；程序随后解析这些结果、校验节点合法性、映射回真实 IntentNode 并封装成 NodeScore 列表，按分数降序返回。
        //整个意图树都在 Redis 里缓存,根节点是值
        List<NodeScore> scores = intentClassifier.classifyTargets(question, entityHint);
        //对分类器返回的原始候选意图列表，再做一轮筛选处理，然后直接返回。
        return scores.stream()
                //把分数低于最低阈值的意图过滤掉。
                .filter(ns -> ns.getScore() >= INTENT_MIN_SCORE)
                //在过滤完低分意图后，最多只保留前 MAX_INTENT_COUNT 个候选意图。
                .limit(MAX_INTENT_COUNT)
                .toList();
    }

    //对单个问题调用分类器做意图识别，并按最低分阈值和最大数量做初步过滤。
    private List<NodeScore> filterMcpIntents(List<NodeScore> nodeScores) {
        return nodeScores.stream()
                .filter(ns -> ns.getNode() != null && ns.getNode().getKind() == IntentKind.MCP)
                .filter(ns -> StrUtil.isNotBlank(ns.getNode().getMcpToolId()))
                .toList();
    }

    //从意图列表里筛出知识库检索用的 KB 类型意图。
    private List<NodeScore> filterKbIntents(List<NodeScore> nodeScores) {
        return nodeScores.stream()
                .filter(ns -> {
                    IntentNode node = ns.getNode();
                    if (node == null) {
                        return false;
                    }
                    return node.getKind() == null || node.getKind() == IntentKind.KB;
                })
                .toList();
    }

    /**
     * 限制总意图数量不超过 MAX_INTENT_COUNT
     * <p>
     * 策略：
     * 1. 如果总数未超限，直接返回
     * 2. 如果超限，每个子问题至少保留 1 个最高分意图
     * 3. 剩余配额按分数从高到低分配给其他意图
     */
    private List<SubQuestionIntent> capTotalIntents(List<SubQuestionIntent> subIntents) {
        int totalIntents = subIntents.stream()
                .mapToInt(si -> si.nodeScores().size())
                .sum();

        // 未超限，直接返回
        if (totalIntents <= MAX_INTENT_COUNT) {
            return subIntents;
        }

        // 步骤1：收集所有意图，按子问题索引分组
        List<IntentCandidate> allCandidates = collectAllCandidates(subIntents);

        // 步骤2：每个子问题保留最高分意图
        List<IntentCandidate> guaranteedIntents = selectTopIntentPerSubQuestion(allCandidates, subIntents.size());

        // 步骤3：计算剩余配额
        int remaining = MAX_INTENT_COUNT - guaranteedIntents.size();

        // 步骤4：从剩余候选中按分数选择
        List<IntentCandidate> additionalIntents = selectAdditionalIntents(allCandidates, guaranteedIntents, remaining);

        // 步骤5：合并并重建结果
        return rebuildSubIntents(subIntents, guaranteedIntents, additionalIntents);
    }

    /**
     * 收集所有意图候选，标记所属子问题索引
     */
    private List<IntentCandidate> collectAllCandidates(List<SubQuestionIntent> subIntents) {
        List<IntentCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < subIntents.size(); i++) {
            List<NodeScore> nodeScores = subIntents.get(i).nodeScores();
            if (CollUtil.isEmpty(nodeScores)) {
                continue;
            }
            for (NodeScore ns : nodeScores) {
                candidates.add(new IntentCandidate(i, ns));
            }
        }
        // 按分数降序排序
        candidates.sort((a, b) -> Double.compare(b.nodeScore().getScore(), a.nodeScore().getScore()));
        return candidates;
    }

    /**
     * 每个子问题选择最高分意图（保底策略）
     */
    private List<IntentCandidate> selectTopIntentPerSubQuestion(List<IntentCandidate> allCandidates, int subQuestionCount) {
        List<IntentCandidate> topIntents = new ArrayList<>();
        boolean[] selected = new boolean[subQuestionCount];

        for (IntentCandidate candidate : allCandidates) {
            int index = candidate.subQuestionIndex();
            if (!selected[index]) {
                topIntents.add(candidate);
                selected[index] = true;
            }
            // 所有子问题都有了保底意图，提前退出
            if (topIntents.size() == subQuestionCount) {
                break;
            }
        }
        return topIntents;
    }

    /**
     * 从剩余候选中选择额外意图
     */
    private List<IntentCandidate> selectAdditionalIntents(List<IntentCandidate> allCandidates,
                                                          List<IntentCandidate> guaranteedIntents,
                                                          int remaining) {
        if (remaining <= 0) {
            return List.of();
        }

        List<IntentCandidate> additional = new ArrayList<>();
        for (IntentCandidate candidate : allCandidates) {
            // 跳过已经被选为保底的意图
            if (guaranteedIntents.contains(candidate)) {
                continue;
            }
            additional.add(candidate);
            if (additional.size() >= remaining) {
                break;
            }
        }
        return additional;
    }

    /**
     * 根据选中的意图重建 SubQuestionIntent 列表
     */
    private List<SubQuestionIntent> rebuildSubIntents(List<SubQuestionIntent> originalSubIntents,
                                                      List<IntentCandidate> guaranteedIntents,
                                                      List<IntentCandidate> additionalIntents) {
        // 合并所有选中的意图
        List<IntentCandidate> allSelected = new ArrayList<>(guaranteedIntents);
        allSelected.addAll(additionalIntents);

        // 按子问题索引分组
        Map<Integer, List<NodeScore>> groupedByIndex = new ConcurrentHashMap<>();
        for (IntentCandidate candidate : allSelected) {
            groupedByIndex.computeIfAbsent(candidate.subQuestionIndex(), k -> new ArrayList<>())
                    .add(candidate.nodeScore());
        }

        // 重建结果
        List<SubQuestionIntent> result = new ArrayList<>();
        for (int i = 0; i < originalSubIntents.size(); i++) {
            SubQuestionIntent original = originalSubIntents.get(i);
            List<NodeScore> retained = groupedByIndex.getOrDefault(i, List.of());
            result.add(new SubQuestionIntent(original.subQuestion(), retained));
        }
        return result;
    }
}
