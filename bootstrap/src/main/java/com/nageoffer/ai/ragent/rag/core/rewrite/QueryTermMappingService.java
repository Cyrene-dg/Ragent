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

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nageoffer.ai.ragent.rag.dao.entity.QueryTermMappingDO;
import com.nageoffer.ai.ragent.rag.dao.mapper.QueryTermMappingMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class QueryTermMappingService {

    private final QueryTermMappingMapper mappingMapper;

    // 按优先级、长度缓存好的映射规则
    private volatile List<QueryTermMappingDO> cachedMappings = List.of();

//    第一阶段：应用启动时，先把“映射规则”从数据库加载到内存
//
//    流程是：
//
//    项目启动，Spring 创建这个 QueryTermMappingService
//    @PostConstruct 触发 loadMappings()
//    去数据库查所有 enabled = 1 的术语映射规则
//    按“优先级高的在前、源词更长的在前”排序
//    把这些规则缓存到本地内存的 cachedMappings 里


//    先把“怎么替换词”的规则表准备好，后面处理用户问题时就不用每次都查数据库。
    @PostConstruct
    public void loadMappings() {
        List<QueryTermMappingDO> dbList = mappingMapper.selectList(
                Wrappers.lambdaQuery(QueryTermMappingDO.class)
                        .eq(QueryTermMappingDO::getEnabled, 1)
        );
        // 建议：优先级高的在前，sourceTerm 更长的在前，避免短词先替换把长词打断
        dbList.sort(Comparator
                .comparing(QueryTermMappingDO::getPriority, Comparator.nullsLast(Integer::compareTo)).reversed()
                .thenComparing(m -> m.getSourceTerm() == null ? 0 : m.getSourceTerm().length(), Comparator.reverseOrder())
        );
        cachedMappings = dbList;

        log.info("查询归一化映射规则加载完成, 共加载 {} 条规则", cachedMappings.size());
    }

    /**
     * 对用户问题做术语归一化
     */

//    每次用户来一个问题，就按这批规则做一轮文本替换
//    流程是：
//
//    用户输入一个问题
//    调 normalize(text)
//    先判断输入是不是空、规则缓存是不是空
//    如果正常，就遍历内存里的每条映射规则
//    对每条规则检查：
//              是否启用
//            是否是当前支持的匹配类型
//             source 和 target 是否合法
//    如果规则合法，就把文本里匹配到的 sourceTerm 替换成 targetTerm
//    所有规则跑完后，得到一个“归一化后的问题”
//    如果前后有变化，就打日志
//            返回归一化后的结果
    public String normalize(String text) {
        if (text == null || text.isEmpty() || cachedMappings.isEmpty()) {
            return text;
        }
        String result = text;
        for (QueryTermMappingDO mapping : cachedMappings) {
            if (Boolean.FALSE.equals(mapping.getEnabled())) {
                continue;
            }
            if (mapping.getMatchType() != null && mapping.getMatchType() != 1) {
                // 这里只示例 match_type = 1 的简单子串匹配，其他类型可以自己扩展
                continue;
            }
            String source = mapping.getSourceTerm();
            String target = mapping.getTargetTerm();
            if (source == null || source.isEmpty() || target == null || target.isEmpty()) {
                continue;
            }
            result = QueryTermMappingUtil.applyMapping(result, source, target);
        }

        if (!Objects.equals(text, result)) {
            log.info("查询归一化：original='{}', normalized='{}'", text, result);
        }
        return result;
    }

    /**
     * 返回当前启用映射规则快照（只读）
     */
    public List<QueryTermMappingDO> listEnabledMappingsSnapshot() {
        return List.copyOf(cachedMappings);
    }
}
