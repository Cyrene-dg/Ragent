# 路由改造 WP0～WP3：第一批实施记录

日期：2026-10-12。范围：能力选择、KB 内部路由、工具执行与执行协调解耦；实现普通问答、纯 KB、纯 MCP、多 KB 及无依赖混合步骤。没有引入 Spring AI Alibaba Graph。

## 当前主调用链

```text
RAGChatServiceImpl
  → 原 QueryRewriteService（保留改写、历史上下文）
  → RequestPlanner / ExecutionPlanValidator（只读能力描述，输出与校验有限步骤）
  → ExecutionCoordinator（独立步骤并行）
      SYSTEM → 交给最终普通回答分支，不进入任何检索或工具服务
      KB     → KnowledgeQueryService
                 → routeAll：一个或多个库
                 → 库内实体与 KB-only 意图分类
                 → 原 RetrievalPlan + Milvus/ES + 后处理器/Rerank
      MCP    → ToolExecutionService → 原 Registry/Executor/Client
  → ResponseAssembler → 原 Prompt、LLMService、SSE、会话与任务句柄
```

## 分阶段实施与验证

### WP0：复现与边界修复

- 先新增四个失败用例：全树存在但不在 shortlist 的节点、其他库的节点，以及 SYSTEM/MCP 进入 KB 检索。
- 修改前运行 8 项相关测试，明确得到 4 个断言失败，其他原有测试通过。
- 分类结果改为仅接受本次允许候选，拒绝非有限/越界分数并去除重复节点。
- 在旧检索入口先添加非 KB 意图隔离，随后进入服务抽取。
- 修复后连同原有 KB 路由、Plan、Milvus/ES 渐进检索与候选限池测试运行，共 35 项通过。

### WP1：独立服务边界

- 新增 `KnowledgeQueryService` 与 `ToolExecutionService` 接口和实现。
- `RetrievalEngine` 变为旧调用者的兼容适配器，原 KB 与 MCP 执行逻辑委托独立服务；没有通过删掉检索通道来解决隔离问题。
- KB 内部分类在进入 LLM/混合召回前限定 KB 类型及 kbId。
- 新增显式作用域检索入口，所有通道与后处理器读同一份允许库集合；新入口不再重新解释全局路由。
- 旧 `IntentResolver` 及旧检索重载继续供兼容/评测调用者使用；主聊天入口不再依赖它们。
- 抽取后的兼容及既有回归 35 项通过；新增服务边界用例加入后继续联合验证。

### WP2：有限规划与执行协调

- 能力目录只读取已注册工具、SYSTEM 响应模板、KB 领域描述，不进行实体匹配或向量/BM25 海选。
- 规划器通过现有 `LLMService` 获取 JSON 步骤，保持已有模型降级链；不新增供应商。
- 验证器校验步骤数量、重复 ID、能力、查询、目标白名单；`rag.workflow.max-steps` 默认 16，可覆盖。
- `ExecutionCoordinator` 只按能力调用服务。独立 KB/MCP 步骤可并行，使用 latch 验证真正同时启动。
- 本阶段不接受 `dependsOn` 非空的执行计划；明确拒绝并澄清，不能悄悄并行化有依赖的业务。
- 加入规划器/协调器/服务测试后，联合原有回归 61 项通过。

### WP3：聊天入口与多库接入

- 主聊天入口切换到 Planner → Coordinator → Assembler。
- 普通问答不调用 KB 服务、实体识别、检索、工具；纯 MCP 不调用 KB 服务。
- 一个 KB 步骤可选择多个有证据的库，逐库分类和检索，全部结果保留并加库标识。
- 旧库仍通过意图元数据提供领域描述，不要求重新导入；其实体识别不再借用其他库的默认实体目录。
- 实体兜底的库范围受本次选择约束。若旧全局 fallback 指向另一个库，按所选库 Profile 的 fallback collection 重建精确层，并保留 `ANY_HIT` 停止条件；不存在合法配置时不跨库。
- 工具参数仅使用声明字段；校验必要参数、类型与枚举，缺失或错误不执行工具。需要身份的 `userId` 参数取认证上下文。
- 工具失败明确报告工具失败，避免统一误报“未找到文档”；非法或过期计划结束 SSE，不执行其他能力。
- 聊天入口、双通道范围与旧回归联合验证曾达到 69 项通过，后续补充错误处理和实体兜底用例再运行完整单元测试集合。

## 故障定位记录

- WP0 的四个断言失败均与源码调用相符，保留失败用例后修复，没有删测试或停用原能力。
- 新测试编译出现 `LLMService.chat` 重载匹配歧义：`any()` 同时匹配字符串与 `ChatRequest`。改为 `any(ChatRequest.class)`，恢复编译；未更改模型服务接口。
- 新协调器处理无目标的 SYSTEM/KB 步骤时，检查了不可变目标映射对 null 查询的行为，在查映射前显式判空，并由三种能力的参数化用例验证。
- 检查旧实体目录 fallback 后发现不同库共享默认目录的风险，新路径不对未配置独立目录的旧库使用该实体提示，避免错误实体引导。

## 验收覆盖

- 普通问答、纯 KB、纯 MCP、单步骤多 KB、独立 KB+MCP 并行。
- shortlist/库/类型越界拒绝；未知工具、无效计划、重复步骤/预算由验证器限制。
- 模型 JSON 无效、能力目录失败返回澄清；不回退成全局 KB 检索。
- KB 显式允许范围贯穿检索通道和后处理器；原分层检索与 RRF 限池回归保留。
- 工具参数缺失/类型错误不调用执行器；工具失败不跨能力降级。
- 会话历史、SSE 回调创建和完成、流式句柄绑定、原任务取消入口。
- 配置 YAML、阿里同步/流式思考开关、已有 ES 元数据过滤、规范化与 trace 单元测试。

最终验证命令：

```powershell
mvn -q -pl bootstrap -am '-Dtest=*Test' '-Dsurefire.failIfNoSpecifiedTests=false' test
git diff --check
```

该测试模式仅选择名称以 `Test` 结尾的单元/受控接口测试，覆盖 framework、infra-ai、bootstrap；未运行依赖真实数据库或真实工具服务器的 `*Tests` 集成测试。

最终结果：88 项测试通过，失败 0、错误 0；`git diff --check` 通过。

## 当前限制与后续范围

- 多能力结果之间的依赖、输入绑定、依赖失败传播尚未实现；不能宣称整个依赖执行目标已经完成。
- 主聊天入口使用新路径；`MetadataRetrievalEvaluationService` 仍保留旧解析入口，全面迁移评测与删除旧全局协调方法留在后续阶段。
- 未启动后端、未向真实模型发本轮问答请求、未对完整知识库跑效果评测。当前证据验证的是程序行为、接口契约和已有召回规则，模型路由准确率仍需真实问题集验证。
- KB 内多个资料意图仍沿用原 Chunk 分组和模板逻辑；逐 Chunk 的精确意图来源归属应作为后续独立改造，避免扩大本批范围。
- 没有提交或推送 Git；工作区原有 trace/Plan 注释、模型迁移及其他用户文件保持保留。

生效方式：从当前源码重新启动后端；旧运行进程和旧打包 JAR 不会自动切换。
