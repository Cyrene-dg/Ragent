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

public class QueryTermMappingUtil {

    /**
     * 安全归一化替换：
     * - 只替换 sourceTerm
     * - 如果当前位置本身已经是 targetTerm 起始（例如文本中已经是“平安保司”），则不重复替换
     */
    public static String applyMapping(String text, String sourceTerm, String targetTerm) {
        if (text == null || text.isEmpty()
                || sourceTerm == null || sourceTerm.isEmpty()
                || targetTerm == null || targetTerm.isEmpty()) {
            return text;
        }

        StringBuilder sb = new StringBuilder();
        int idx = 0;
        int len = text.length();
        int sourceLen = sourceTerm.length();

        while (idx < len) {
            int hit = text.indexOf(sourceTerm, idx);
            if (hit < 0) {
                // 后面没有命中，整体拷贝
                sb.append(text, idx, len);
                break;
            }

            // 先把命中之前的文本拷贝过去
            sb.append(text, idx, hit);

            // sourceTerm 可能位于 targetTerm 中间，例如“战技”位于“元素战技”末尾。
            // 只检查 hit 是否为 targetTerm 开头，会把“元素战技”错误改成“元素元素战技”。
            int existingTargetEnd = findContainingTargetEnd(text, hit, sourceTerm, targetTerm);

            if (existingTargetEnd >= 0) {
                // 命中词已被现有目标词包住。前缀已在上面写入，只补写剩余部分。
                sb.append(text, hit, existingTargetEnd);
                idx = existingTargetEnd;
            } else {
                // 不是目标词开头，正常做归一化替换
                sb.append(targetTerm);
                idx = hit + sourceLen;
            }
        }

        return sb.toString();
    }

    /**
     * 返回覆盖当前 source 命中的既有 target 的结束位置；不存在则返回 -1。
     */
    private static int findContainingTargetEnd(String text, int sourceHit, String sourceTerm, String targetTerm) {
        int sourceOffset = targetTerm.indexOf(sourceTerm);
        while (sourceOffset >= 0) {
            int targetStart = sourceHit - sourceOffset;
            int targetEnd = targetStart + targetTerm.length();
            if (targetStart >= 0 && targetEnd <= text.length() && text.startsWith(targetTerm, targetStart)) {
                return targetEnd;
            }
            sourceOffset = targetTerm.indexOf(sourceTerm, sourceOffset + 1);
        }
        return -1;
    }
}
