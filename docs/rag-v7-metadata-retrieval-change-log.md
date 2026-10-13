# RAG v7 元数据检索 Change Log

> 范围：记录与“原神 v7 意图路由、元数据检索、评测闭环”直接相关的提交。
> 粒度：一条提交说明一个可独立理解、验证或回退的改动单元；不逐行复述代码。

## 阅读方法

每一节固定回答五件事：

1. 当时解决什么问题；
2. 改了哪些核心模块；
3. 新行为是什么；
4. 如何验证；
5. 还留下什么边界或后续工作。

---

## `41282a8`｜两阶段意图识别

**问题**：意图叶子多时，不能把全部候选直接放进 LLM 分类 Prompt；Prompt 变长、成本和误判风险都会增大。

**主要改动**：

- 调整 `DefaultIntentClassifier`；
- 为 `IntentPreFilterProperties` 和 `application.yaml` 补预过滤配置；
- 补充 `QueryTermMappingService` 的归一化支持；
- 调整 Embedding 客户端参数。

**行为**：先用规则、BM25、向量召回候选，再由 LLM 在候选中精判意图。

**验证**：后续 1302 条弱标注样本中，Top80 候选 Recall@80 为 100%，海选 P95 为 15ms；这属于候选覆盖测试，不等同于最终意图分类准确率。

**后续演进**：v7 小树不需要强行走 Top80，后来增加“叶子数小则直判、数量大才两阶段”的自适应策略。

---

## `500595c`｜早期语料与意图树快照沉淀

**问题**：需要保留当时原神语料、结构化切块和意图树的探索证据，供后续设计与简历复盘使用。

**主要改动**：

- 小幅调整 `StructureAwareTextChunker`；
- 保存意图树快照、学习文档和早期项目资料。

**行为**：主要是探索期材料沉淀，不是当前 v7 运行链路的核心功能提交。

**验证**：作为后续“旧树粒度与资料边界为什么不够好”的对照材料。

**边界**：提交中混有学习资料和简历文件，因此不应被视为生产化功能提交；后续 v7 改造把运行配置、种子和评测资产独立出来。

---

## `220a56b`｜补充探索期设计文档

**问题**：早期讨论的结构感知切块与意图树设计需要留档。

**主要改动**：补充项目学习/设计文档。

**行为与验证**：无新的运行时行为；用于记录当时方案。

**边界**：这类文档不能替代可复算评测。后续提交把“想法”推进为可执行 Plan 和 A/B 数据。

---

## `6a2ede4`｜建立 v7 数据管线与自适应意图路由

**问题**：旧 v4/v6 原神数据、意图树与运行态混杂；Chunk 缺少统一元数据，无法让检索明确知道“查哪个角色、哪类资料”。

**主要改动**：

- 建立 `resources/genshin_seed_v7`：意图节点、路由规则、实体目录、Chunk 工件和 manifest；
- 让知识块支持 `metadata_json`，并在写入向量库时同步 metadata；
- 新增 `IntentHybridRecallService`，提供规则/BM25/向量候选海选；
- 让 `DefaultIntentClassifier` 根据叶子数量选择直判或两阶段；
- 新增 v7 构建、导入和校验脚本；
- 新增意图候选 A/B 测试。

**行为**：v7 查询开始有独立 collection、独立路由规则和实体目录；意图树较小时直接 LLM 判定，超过阈值再预过滤。

**验证**：种子构建/导入脚本可重复执行；意图候选测试覆盖直判与两阶段路径。

**后续演进**：这一提交解决“能得到意图候选”，但尚未让意图和实体真正约束 Milvus/ES 的 Chunk 检索。

---

## `aba0372`｜实现第一阶段意图实体检索计划

**问题**：意图和实体信息仍停留在识别层，检索器里容易出现零散 `if/else`，难以解释和测试。

**主要改动**：

- 新增 `EntityCatalogService`、`EntityResolver`、`EntityResolution`；
- 新增 `IntentRouteRuleService`；
- 新增 `RetrievalPlan`、`IntentRetrievalPlan`、`RetrievalFilterLevel`；
- 新增 `RetrievalPlanService`；
- 修改 `RetrievalEngine` 传递 Plan；
- 补齐 Plan 单元测试。

**行为**：将“问题 + 意图 + 实体”转成多层 metadata 条件。例如角色技能问题先尝试 `entity_id + category + doc_type`，再按层放宽。

**验证**：`RetrievalPlanServiceTest` 覆盖唯一实体、找不到实体、多意图、过滤层构造等场景。

**后续演进**：此时 Plan 只是“判断正确”，还未真正由 Milvus / ES 执行。

---

## `f519006`｜实现 Milvus 分层元数据检索

**问题**：Plan 已生成，但向量检索仍可能绕过 metadata 边界，查到范围外的角色或资料类型。

**主要改动**：

- 新增 `ProgressiveMilvusPlanRetriever`；
- 改造 `IntentDirectedSearchChannel`、`SearchContext`、`MultiChannelRetrievalEngine`；
- `MilvusRetrieverService` 支持把 metadata Map 转为 Milvus JSON filter；
- 补 Milvus 分层执行测试。

**行为**：同一问题只生成一次 query 向量，再按 Plan 的每层条件调用 `retrieveByVector(...)`；每层按 Chunk ID 去重，达到目标候选池或终止条件后停止。

**验证**：测试覆盖严格层拿够候选、前层不足后续补足、双意图分别执行、全局上限等场景。

**后续演进**：ES 关键词通道仍未同步条件，混合结果仍可能被 ES 污染。

---

## `4e497ea`｜实现 ES 元数据分层检索

**问题**：Milvus 已受 Plan 约束，但 ES/BM25 仍按旧方式查，可能把范围外关键词命中重新混进候选池。

**主要改动**：

- 新增 `ProgressiveKeywordEsPlanRetriever`；
- 改造 `KeywordEsSearchChannel` 与 `KeywordEsGateway`；
- 改进 ES metadata 回填与 mapping；
- 增加 ES metadata 单元测试。

**行为**：ES 与 Milvus 循环执行同一份 filter level；两条通道先各自召回，再进入后续合并与重排。

**验证**：测试验证 ES 条件构造、逐层放宽、唯一实体分层和回退行为。

**后续演进**：两个通道的原始分数不能直接比较，候选过多时还需要安全融合策略。

---

## `05b7c97`｜增加候选池 RRF 安全限流

**问题**：ES/BM25 与 Milvus 相似度分数不在同一尺度，且文档量增长时直接把大量候选交给 Rerank 会增加成本和不稳定性。

**主要改动**：

- 新增 `CandidatePoolLimiterPostProcessor`；
- 在 `MetadataRetrievalProperties` 中增加候选池阈值与 RRF 配置；
- 增加对应单元测试。

**行为**：候选池超过 80 时，先按 RRF 的“跨通道排名”融合和裁剪，再进入 Rerank；低于阈值不改变原有链路。

**验证**：测试覆盖阈值不触发、触发后候选数量受限、跨通道排名融合与去重。

**边界**：RRF 是安全网，不应被包装成“每次查询都会显著提升”的功能。

---

## `fab7468`｜补充意图分类诊断日志

**问题**：自适应直判与两阶段策略实际是否按预期触发，缺少可观察证据。

**主要改动**：简化并补充 `DefaultIntentClassifier` 日志，记录策略、总叶子数和候选数。

**行为**：运行日志能看到 `direct` 或 `two-stage`，便于定位配置和阈值问题。

**验证**：通过分类测试和实际运行日志核对小树走直判、大树才触发候选海选。

**边界**：日志用于诊断，不改变分类结果。

---

## `07ddb39`｜实现实体优先检索与命中停止策略

**问题**：原始“候选不足就继续放宽”会让唯一实体问题为了凑满 20 条而混入其他实体资料。

**主要改动**：

- 增加实体解析状态与候选实体支持；
- 新增 `ProgressivePlanStopPolicy`；
- 调整 `DefaultIntentClassifier`、`IntentResolver`、`RetrievalEngine`、Plan 服务及双通道执行器；
- 更新 Prompt、配置和测试。

**行为**：唯一实体在严格层命中任意结果时停止跨实体放宽；严格层为 0 才继续。无实体或多实体歧义仍可走普通分层回退。

**验证**：Milvus / ES 测试覆盖“命中 1 条即停”“0 条继续”“实体 fallback”等场景。

**后续演进**：实体命中解决“谁”，但集合问题仍缺“按元素”缩小范围的能力。

---

## `71c9748`｜补充角色元素元数据检索与安全重建

**问题**：冰、火、风等集合问题没有唯一实体，仅按角色资料类型搜索时，候选池会混入其他元素角色。

**主要改动**：

- 新增 `ElementFacetResolver`；
- `RetrievalPlanService` 可向角色计划补 `element`；
- 批量补齐 v7 角色 Chunk 的元素 metadata；
- 支持 metadata 批量更新与安全重建；
- ES 同步 metadata；
- 更新 v7 校验脚本、测试报告和 Plan 测试。

**行为**：例如“冰元素角色有哪些”形成 `category=character + doc_type=character_profile + element=cryo`，Milvus 与 ES 都遵守这个边界。

**验证**：先做 10 题试运行，再由 `edb64b8` 的评测闭环完成 56 题正式 A/B。

**边界**：元素是稳定、明确的维度；不意味着所有自然语言条件都应无限添加为 metadata 字段。

---

## `edb64b8`｜完善 v7 元数据检索评测与技术文档

**问题**：功能已实现，但缺少可重复的对照开关、Gold 集、结果导出和正式说明；不能仅凭聊天体验说“效果更好”。

**主要改动**：

- 增加 `elementFacetEnabled` 与本地评测开关；
- 新增本地受限的评测 Controller / Service；
- 新增 Gold 草稿生成器、评测运行器和 56 题确认数据；
- 输出 Baseline/Candidate Top20、指标 JSON 和报告；
- 补评测章程、Gold 审阅、技术演进档案；
- 为历史正文乱码行加入仅评测使用的稳定映射兜底；
- 更新 SaToken 白名单，使评测端点只在开关开启时可由本机调用。

**行为**：同一请求只解析一次意图与实体，再分别用 `element=false/true` 构造两条 Plan，避免把 LLM 分类波动混进 A/B。

**验证**：相关 19 项单元测试通过；56 题正式集结果见 [正式评测报告](genshin-v7-metadata-retrieval-evaluation-report-2026-07-26-formal.md)。

**结果**：元素集合覆盖率@20 为 24.34% → 100%，Top5 错误召回率为 33.90% → 14.41%，单实体 Recall@20 保持 100%。同时真实发现“草神”别名缺失和领域外拒答缺失。

---

## 当前未提交后的下一步

后续每一项建议单独提交，不与本次大评测混在一起：

1. `fix(rag): 补充草神等实体别名并增加回归测试`；
2. `feat(rag): 增加领域外证据门控与拒答策略`；
3. `fix(ingestion): 重建历史乱码知识块`；
4. `test(rag): 扩展元数据检索 Gold 集并增加回归阈值`。
