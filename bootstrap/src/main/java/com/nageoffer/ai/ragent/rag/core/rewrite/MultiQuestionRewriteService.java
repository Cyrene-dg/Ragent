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

package com.nageoffer.ai.ragent.rag.core.rewrite;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.rag.config.RAGConfigProperties;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.framework.trace.RagTraceNode;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.QUERY_REWRITE_AND_SPLIT_PROMPT_PATH;

/**
 * 查询预处理：改写 + 拆分多问句
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiQuestionRewriteService implements QueryRewriteService {

    private final LLMService llmService;
    private final RAGConfigProperties ragConfigProperties;
    private final QueryTermMappingService queryTermMappingService;
    private final PromptTemplateLoader promptTemplateLoader;

    @Override
    @RagTraceNode(name = "query-rewrite", type = "REWRITE")
    //对用户问题做改写处理，但最终只返回“改写后的主问题字符串”。
    public String rewrite(String userQuestion) {
        return rewriteAndSplit(userQuestion).rewrittenQuestion();
    }

    @Override
    //对用户问题做改写和拆分，并返回同时包含“改写结果 + 子问题列表”的 RewriteResult。
    public RewriteResult rewriteWithSplit(String userQuestion) {
        return rewriteAndSplit(userQuestion);
    }

    @Override
    @RagTraceNode(name = "query-rewrite-and-split", type = "REWRITE")
    //结合最近对话历史，对当前问题做改写和拆分，是主流程里更完整的改写入口。
    public RewriteResult rewriteWithSplit(String userQuestion, List<ChatMessage> history) {
        if (!ragConfigProperties.getQueryRewriteEnabled()) {
            String normalized = queryTermMappingService.normalize(userQuestion);
            List<String> subs = ruleBasedSplit(normalized);
            return new RewriteResult(normalized, subs);
        }

        String normalizedQuestion = queryTermMappingService.normalize(userQuestion);

        //这里把历史一块传进去
        return callLLMRewriteAndSplit(normalizedQuestion, userQuestion, history);
    }

    /**
     * 先用默认改写做归一化，再进行多问句拆分。
     */
    //这是内部统一入口，负责根据开关决定走“规则改写/拆分”还是“LLM 改写/拆分”
    private RewriteResult rewriteAndSplit(String userQuestion) {
        // 开关关闭：直接做规则归一化 + 规则拆分
        //如果“查询改写开关”没开，那就不调用大模型，直接走普通规则逻辑。
        //从ragConfigProperties这个配置中查询
        if (!ragConfigProperties.getQueryRewriteEnabled()) {
            //先对原始用户问题做术语归一化处理
            String normalized = queryTermMappingService.normalize(userQuestion);
            //对归一化之后的问题，再用规则方式拆分成多个子问题
            List<String> subs = ruleBasedSplit(normalized);
            //把“归一化后的主问题”和“规则拆出来的子问题列表”封装成一个 RewriteResult 返回。
            return new RewriteResult(normalized, subs);
        }

        //开关开启时，也先做一次术语归一化
        String normalizedQuestion = queryTermMappingService.normalize(userQuestion);

        //把归一化后的问题、原始问题以及空历史记录传给 LLM 改写逻辑，并直接返回结果。
        return callLLMRewriteAndSplit(normalizedQuestion, userQuestion, List.of());

        // 兜底：使用归一化结果 + 规则拆分

    }

    //真正调用大模型完成改写和多问句拆分，并在失败时走兜底逻辑
    private RewriteResult callLLMRewriteAndSplit(String normalizedQuestion,
                                                 String originalQuestion,
                                                 List<ChatMessage> history) {
        //先把“怎么让大模型做改写和拆分”的提示词模板加载出来，再把它和当前问题、历史对话一起组装成一个标准的 LLM 请求。
        String systemPrompt = promptTemplateLoader.load(QUERY_REWRITE_AND_SPLIT_PROMPT_PATH);
        ChatRequest req = buildRewriteRequest(systemPrompt, normalizedQuestion, history);

        try {
            //真正调用大模型，让它返回“改写 + 拆分”的结果。
            String raw = llmService.chat(req);
            //把模型返回的原始文本，解析成程序真正能继续处理的 RewriteResult
            RewriteResult parsed = parseRewriteAndSplit(raw);

            //只要模型输出能成功解析成结构化结果，就记录日志，然后直接把这个结果返回。
            if (parsed != null) {
                log.info("""
                        RAG用户问题查询改写+拆分：
                        原始问题：{}
                        归一化后：{}
                        改写结果：{}
                        子问题：{}
                        """, originalQuestion, normalizedQuestion, parsed.rewrittenQuestion(), parsed.subQuestions());
                return parsed;
            }

            log.warn("查询改写+拆分解析失败，使用归一化问题兜底 - normalizedQuestion={}", normalizedQuestion);
        } catch (Exception e) {
            log.warn("查询改写+拆分 LLM 调用失败，使用归一化问题兜底 - question={}，normalizedQuestion={}", originalQuestion, normalizedQuestion, e);
        }

        // 统一兜底逻辑
        //如果 LLM 调用失败，或者调用成功但解析失败，那就不用模型改写结果了，直接把“归一化后的问题”当作最终问题，同时子问题列表里也只放这一条。
        return new RewriteResult(normalizedQuestion, List.of(normalizedQuestion));
    }

    //组装发给大模型的 ChatRequest，包括系统提示词、最近几轮历史消息和当前问题。
    private ChatRequest buildRewriteRequest(String systemPrompt,
                                            String question,
                                            List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(ChatMessage.system(systemPrompt));
        }

        // 只保留最近 1-2 轮的 User 和 Assistant 消息
        // 过滤掉 System 摘要，避免 Token 浪费
        if (CollUtil.isNotEmpty(history)) {
            List<ChatMessage> recentHistory = history.stream()
                    .filter(msg -> msg.getRole() == ChatMessage.Role.USER
                            || msg.getRole() == ChatMessage.Role.ASSISTANT)
                    .skip(Math.max(0, history.size() - 4))  // 最多保留最近 4 条消息（2 轮对话）
                    .toList();
            messages.addAll(recentHistory);
        }

        messages.add(ChatMessage.user(question));

        return ChatRequest.builder()
                .messages(messages)
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();
    }


    //把大模型返回的原始文本解析成结构化的 RewriteResult
    private RewriteResult parseRewriteAndSplit(String raw) {
        try {
            //先把模型输出做一次清洗
            // 移除可能存在的 Markdown 代码块标记
            String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);

            //从 JSON 里提取 rewrite 字段，并把它整理成一个去掉首尾空格的字符串。
            JsonElement root = JsonParser.parseString(cleaned);
            if (!root.isJsonObject()) {
                return null;
            }
            //从 JSON 里提取 rewrite 字段，并把它整理成一个去掉首尾空格的字符串。
            JsonObject obj = root.getAsJsonObject();
            String rewrite = obj.has("rewrite") ? obj.get("rewrite").getAsString().trim() : "";
            //开始解析 sub_questions
            //如果 JSON 里有 sub_questions，并且它确实是个数组，就把里面合法的字符串元素一个个取出来，去空格、去空项后放进 subs 列表。
            List<String> subs = new ArrayList<>();
            if (obj.has("sub_questions") && obj.get("sub_questions").isJsonArray()) {
                JsonArray arr = obj.getAsJsonArray("sub_questions");
                for (JsonElement el : arr) {
                    if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                        String s = el.getAsString().trim();
                        if (StrUtil.isNotBlank(s)) {
                            subs.add(s);
                        }
                    }
                }
            }
            //校验 rewrite 是否有效
            if (StrUtil.isBlank(rewrite)) {
                return null;
            }
            //如果没有子问题，就自动兜底成 [rewrite]
            if (CollUtil.isEmpty(subs)) {
                subs = List.of(rewrite);
            }
            //构造最终结构化结果
            return new RewriteResult(rewrite, subs);
        } catch (Exception e) {
            log.warn("解析改写+拆分结果失败，raw={}", raw, e);
            return null;
        }
    }

    //ruleBasedSplit(String question)
    //在不用大模型或解析失败时，按常见标点把问题粗略拆成多个子问题作为兜底方案。
    private List<String> ruleBasedSplit(String question) {
        // 兜底：按常见分隔符拆分
        List<String> parts = Arrays.stream(question.split("[?？。；;\\n]+"))//把 question 这个字符串，按问号、句号、分号、换行这些分隔符拆开
                //把每一段字符串都去掉首尾空白字符。
                .map(String::trim)
                //只保留非空、非全空白的字符串。
                .filter(StrUtil::isNotBlank)
                //收集结果
                .collect(Collectors.toList());

        //如果拆分之后什么都没得到，那就直接把原问题本身作为唯一一个子问题返回。
        if (CollUtil.isEmpty(parts)) {
            return List.of(question);
        }
        //把每个子问题都补成“问句形式”：如果末尾已经有问号就保留，没有就补一个中文问号。
        return parts.stream()
                .map(s -> s.endsWith("？") || s.endsWith("?") ? s : s + "？")
                .toList();
    }
}
