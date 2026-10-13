# RAG Pipeline 全链路追踪：推导式学习笔记

> 学习目标：不是逐行背代码，而是能从一次用户问题进入 RAG 开始，推导出追踪上下文、AOP 节点、异步线程、异常、流式 SSE、落库与管理端展示之间的因果关系。

## 1. 本次采用的学习方式

这次没有先从代码细节或概念定义讲起，而是采用“工程推演”的方式学习：

1. 先给出真实目标或故障场景。
2. 基于已有 Java、Spring、AOP、线程池知识提出直觉方案。
3. 用一个关键约束修正方案，再继续推演下一步。
4. 最后才把推导出的模型和项目代码对照。

这很适合已经熟悉单项技术、但暂时难把它们串成一条运行时链路的情况。重点不是学习新名词，而是回答：**为什么此刻需要这项技术，以及它在整条因果链里承担什么职责。**

本次推演顺序：

```text
定位 RAG 慢/错的目标
→ AOP + 注解选择观测边界
→ 节点的通用生命周期
→ 节点栈形成父子关系
→ TTL 跨线程传递上下文
→ 并行检索与关键路径耗时
→ CompletableFuture 的异常汇总与降级
→ SSE 首包、持续输出与终态
→ MySQL 落库与管理端查询
→ 回看当前项目的实现边界
```

后续学习也可复用这个节奏：先设计，再看实现；先用运行时故事解释，再看单个类。

## 2. 链路追踪到底在做什么

链路追踪不是一个额外的“神秘框架”。它是给一次用户请求拍一部全过程录像：

```text
用户问题：退款规则是什么？

Trace 1001
├─ 问题改写：20ms，SUCCESS
├─ 意图识别：35ms，SUCCESS
├─ 知识库检索：420ms，SUCCESS
│  ├─ Milvus：380ms，SUCCESS
│  └─ ES：120ms，SUCCESS
└─ 模型流式回答：首包 800ms，总完成 4.2s，SUCCESS
```

它要让后台在一次用户说“慢”或“报错”时回答：

- 这属于哪次请求？
- 哪个 RAG 阶段慢？
- 哪个外部依赖失败？
- 是整个请求失败，还是部分降级后仍返回了回答？
- 异步任务是否仍属于同一条请求？

因此，链路追踪采集的是**运行证据**，不是业务结果本身。

## 3. 为什么使用 AOP 和注解

RAG 的慢点分散在不同业务中：问题改写、意图/实体处理、Milvus、ES、MCP、模型网络调用、SSE 流式输出等。不能在每个业务方法里手写：

```java
long start = System.currentTimeMillis();
try {
    // business code
} catch (Exception e) {
    // record exception
} finally {
    // record duration
}
```

那会造成：

- 业务代码被计时、日志、异常记录污染；
- 格式和字段不统一；
- 容易漏埋点；
- 后续改动很难审计。

正确抽象不是把不同的业务逻辑强行变成一样，而是抽取它们共同拥有的生命周期：

```text
进入一个关键步骤
→ 开始时间
→ 执行业务逻辑（千差万别，追踪不干预）
→ 正常返回或抛异常
→ 结束时间、耗时、状态、异常
```

注解负责声明“这个方法是有业务意义的观测边界”，AOP 负责统一执行这个生命周期。项目中使用的代表性注解是：

```java
@RagTraceNode(name = "query-rewrite", type = "REWRITE")
@RagTraceNode(name = "intent-resolve", type = "INTENT")
@RagTraceNode(name = "retrieval-engine", type = "RETRIEVE")
```

不应给每一个普通方法加注解。应该只覆盖：

```text
阶段入口：rewrite / intent / retrieval / prompt / generation
外部依赖：Milvus / ES / MCP / LLM Provider
并发分支：子问题、检索通道等有独立排障价值的工作
```

## 4. 一个节点需要记录什么

不管节点实际做的是改写、检索还是模型调用，最小的统一记录是：

```text
关联：traceId、nodeId、parentNodeId
语义：nodeName、nodeType（类名/方法名可选）
时间：startTime、endTime、durationMs
结果：RUNNING / SUCCESS / ERROR
证据：errorMessage
```

这里的抽象不是“业务内容相同”，而是“每一次关键方法执行都能被写成一张运行记录卡”。

## 5. 父子节点关系：人工维护的影子调用栈

项目并不依赖 native 方法、JVM 调用栈扫描或编译器记录来推断父子关系。它在应用层维护一个当前节点栈：

```text
进入 A 前：[]
创建 A：当前栈顶为空，A 没有父节点
push(A)：[A]

在 A 内进入 B：
创建 B：当前栈顶为 A，所以 B.parentNodeId = A
push(B)：[B, A]

B 结束：pop()，回到 [A]
A 结束：pop()，回到 []
```

这就是 `RagTraceContext` 中 `NODE_STACK` 的职责。它是一个 `Deque<String>`，因为方法嵌套天然是后进先出。

需要区分两点：

- `ThreadLocal` 不是业务上的 Map。JDK 内部每个线程有 `ThreadLocalMap`，将某个 `ThreadLocal` 对象映射到该线程的值。
- 项目在上下文里分别存了 `TRACE_ID`、`TASK_ID` 和 `NODE_STACK`；其中真正表达调用关系的是栈。

同一 Trace 下顺序执行的顶层阶段，不会天然互为父子。例如根流程依次调用 rewrite、intent、retrieve，但根流程本身不是一个追踪节点时，这三者都是同一 Trace 下的顶层节点。只有嵌套的、实际被 AOP 拦截的节点才形成父子关系。

## 6. 为什么 ThreadLocal 到线程池会断，TTL 解决什么

`ThreadLocal` 的值是按线程绑定的。父线程把任务提交到线程池后，工作线程是另一个、甚至被复用过的线程，普通 `ThreadLocal` 默认拿不到父线程的 `traceId` 和节点栈。

结果会是：异步的 ES、Milvus 或模型任务不知道自己属于哪次请求，Trace 断链。

项目使用 `TransmittableThreadLocal`（TTL）和 `TtlExecutors` 包装线程池。提交任务时，TTL 捕获提交线程的上下文；执行任务时，把该上下文安装到工作线程；任务结束后恢复工作线程原来的上下文。

这解决的是：

```text
提交任务时属于 Trace 1001
→ 在线程池工作线程执行时，仍能读取 Trace 1001
```

但有一个重要的数据结构边界：

- `traceId`、`taskId` 是不可变字符串，传递安全；
- `NODE_STACK` 是可变 `ArrayDeque`；并行任务若共享同一个栈对象，会同时 `push/pop`，造成竞争和父子关系串线。

所以严谨实现必须在任务提交时复制节点栈：

```text
父上下文：[A]
任务 B 收到副本：[A]，进入后变为 [B, A]
任务 C 收到副本：[A]，进入后变为 [C, A]
```

不能让 B、C 共享同一个可变 `Deque`。

## 7. 并行检索：兄弟节点与关键路径

典型 RAG 检索结构：

```text
A：检索总入口
├─ B：Milvus
└─ C：ES
```

B、C 是并行兄弟：

```text
B.traceId = C.traceId = A.traceId
B.parentNodeId = C.parentNodeId = A.nodeId
```

它们彼此没有父子关系，耗时也不能相加作为用户等待时间。

```text
A 的端到端耗时 ≈ 准备时间 + max(B 耗时, C 耗时) + 汇总时间
```

`B + C` 更接近总资源消耗，不是用户真实等待时间。管理端应重点看关键路径：若 Milvus 480ms、ES 120ms，检索慢点是 Milvus。

## 8. 节点异常、部分成功与 CompletableFuture

并行检索常见情形：Milvus 失败，但 ES 成功且能返回足够 chunk。此时不应把整条 RAG 简单记为失败：

```text
Milvus 节点：ERROR
ES 节点：SUCCESS
检索总入口：可继续（理想状态为 DEGRADED / PARTIAL_SUCCESS）
整体 RAG：若最终完整回答，可继续成功或降级成功
```

设计原则：

> 子节点负责如实抛出和记录异常；父节点负责决定是否可容错、降级和继续。

这比让 Milvus 在内部吞异常、返回空结果更干净。后者会让 AOP 看到“正常返回”，从而错误标记为 SUCCESS，除非再额外传递失败状态。

### CompletableFuture 复习

`Future` 是未来结果占位符；`CompletableFuture` 除了等待，还能组合并行任务、转换结果与处理异常。

```java
CompletableFuture<Result> esFuture =
    CompletableFuture.supplyAsync(() -> searchEs(), executor);
```

状态只有三种：未完成、成功完成、异常完成。

```java
future.get();   // 需要处理受检异常
future.join();  // 失败时抛 CompletionException
```

`CompletableFuture.allOf(milvusFuture, esFuture).join()` 表示等待两者结束，但只要任一 Future 异常完成，`join()` 就会抛异常；成功的 ES 结果不会自动进入正常汇总流程。

若要支持部分成功，应在父级汇总边界把每个 Future 转换成统一结果：

```java
CompletableFuture<ChannelOutcome> outcome = future.handle((value, error) -> {
    if (error != null) {
        return ChannelOutcome.failed("milvus", error);
    }
    return ChannelOutcome.success("milvus", value);
});
```

常用方法的角色：

| 方法 | 成功时 | 失败时 | Trace 场景中的用途 |
|---|---|---|---|
| `handle` | 可转换结果 | 可转换异常 | 汇总每个通道的成功/失败结果 |
| `exceptionally` | 原样传递 | 提供兜底值 | 简单降级 |
| `whenComplete` | 观察结果 | 观察异常 | 日志、埋点，不改变原结果 |

## 9. SSE：首包不是完成，流结束才是根 Trace 终态

流式问答至少有以下时间点：

```text
t0 HTTP 请求到达
t1 进入排队器
t2 真正开始 RAG（改写、意图、检索、Prompt）
t3 发起 LLM 流；Java 方法拿到取消句柄并返回
t4 模型持续输出 token
t5 最后一个 token 输出，SSE 正常完成
```

首包探测很有价值，但它是一个指标，不是根 Trace 的结束：

```text
首包耗时（TTFT）：模型请求 → 第一个 token
生成耗时：第一个 token → 正常完成
端到端耗时：真正开始 RAG → SSE 完成
```

持续 token 不需要默认每三秒轮询，也不需要默认把每个 token 落库。流式回调已经提供事件：

```text
onContent(delta)：记录首次内容时间、最近内容时间、token 数（可选）
onComplete()：正常结束
onError(error)：异常结束
```

最终可以落汇总指标：首包耗时、总生成耗时、输出 token 数、最大 token 间停顿、最终状态。若要检测首包后卡死，再独立加入基于 `lastTokenAt` 的 watchdog；这属于可用性保障，不是基础 Trace 的必需部分。

客户端断开、用户主动停止，不应记为 SUCCESS 或 ERROR。当前没有任务持久化与恢复协议时，合理状态是 `CANCELLED`。真正支持 `PAUSED → RESUMED` 需要服务端持久任务、可重连订阅、内容回放，以及模型供应商可能支持的恢复能力；普通 SSE 默认不具备这种能力。

推荐的状态语义：

```text
SUCCESS    完整生成并正常结束
DEGRADED   部分可降级步骤失败，但最终完整回答
ERROR      系统、模型、网络等异常使请求无法完成
CANCELLED  用户停止或断开后取消
TIMEOUT    首包或 token 长时间无进展，被看门狗终止
RUNNING    尚未结束
```

## 10. 什么该落库，什么只放内存

只在运行中有用的内容适合内存：

```text
traceId、当前节点栈、取消句柄、首包时间、最近 token 时间
```

请求结束后还要用于检索、统计、排障的证据才落库：

```text
整次请求做了什么、关键阶段耗时、状态、异常、父子关系
```

理想的记录时序：

```text
RAG 开始          → insert run (RUNNING)
节点开始           → insert node (RUNNING)
节点结束/异常      → update node（状态、结束时间、耗时、异常）
SSE 完成/错误/取消 → update run（最终状态、总耗时、最终异常）
```

开始时就插入，而非结束时一次性插入，是为了保留崩溃、卡死和强制终止的证据：即使进程没机会收尾，数据库仍显示它曾运行到某个 `RUNNING` 节点。

默认不应落：完整 token 内容、完整 Prompt、完整知识库 chunk、ThreadLocal 栈、线程池内部状态、每个普通工具方法。它们通常带来高成本、隐私风险和低查询价值。需要时应落脱敏摘要，例如问题长度、命中 chunk 数、模型名、首包耗时和输出 token 数。

高并发下，节点的“开始 insert + 结束 update”会带来额外数据库写入。常见优化是采样、异步批量写入，或仅保留慢请求/异常请求的完整 Trace；原则是不能让追踪本身成为 RAG 的瓶颈。

## 11. 当前项目的 MySQL 表

项目通过 MyBatis-Plus 实体和 Mapper 写入 MySQL；追踪数据使用两张表：

```text
t_rag_trace_run   一次完整 RAG 请求一条记录
t_rag_trace_node  每一次被追踪的节点执行一条记录
```

`traceId` 是逻辑关联键：

```text
t_rag_trace_run
traceId = 1001

t_rag_trace_node
traceId = 1001, node = query-rewrite
traceId = 1001, node = intent-resolve
traceId = 1001, node = milvus-search
traceId = 1001, node = es-search
```

“节点记录会重复”指的是：每一次被追踪的节点执行实例都会有一行，而不是同一个节点必然反复执行。例如一个问题拆成三个子问题后，可能有三条同类型 `milvus-search` 记录；它们拥有不同 `nodeId`、时间和状态。

两张表的原因不是为了形式上的拆分，而是它们的基数和生命周期不同：

```text
一次 run → 多次 node 执行
```

若只有一张“每节点一行”的表，会重复保存会话、用户、总状态等根信息；若把节点塞进 run 的 JSON，则很难按节点类型查询慢点、异常率、运行中节点或独立更新并行节点。两表让根请求与每个节点各自独立写入、更新和查询。

## 12. 当前项目实现的关键对照与边界

项目已有以下核心组件：

- `framework/.../trace/RagTraceContext.java`：TTL 上下文，保存 traceId、taskId、节点栈。
- `framework/.../trace/RagTraceRoot.java` 与 `RagTraceNode.java`：根和节点注解定义。
- `bootstrap/.../rag/aop/RagTraceAspect.java`：围绕节点创建、计时、状态更新、异常记录。
- `bootstrap/.../rag/aop/ChatRateLimitAspect.java`：当前流式聊天实际创建 run 和 traceId 的入口。
- `bootstrap/.../rag/config/ThreadPoolExecutorConfig.java`：线程池经 `TtlExecutors` 包装。
- `bootstrap/.../rag/service/impl/RagTraceRecordServiceImpl.java`：run/node 的 MyBatis-Plus 写入与更新。
- `bootstrap/.../rag/service/impl/RagTraceQueryServiceImpl.java`：管理端的分页、详情与节点查询。
- `frontend/.../pages/admin/traces/RagTraceDetailPage.tsx`：按节点开始时间、层级、耗时展示时间线。

已经实现的节点包括 query rewrite、intent resolve、retrieval、LLM routing、不同模型供应商等。

根据本次推导，审查当前实现时应优先关注：

1. **SSE 结束时机**：根 run 当前在 `streamChat()` Java 方法返回后即可结束，但真正流式输出可能还在后台执行。若目标是用户端到端耗时，应把 run 的最终更新绑定到 `onComplete / onError / onCancel`。
2. **并发节点栈隔离**：TTL 传递可变 `Deque` 时必须确认是否复制，避免并行任务共享同一栈对象。
3. **状态语义**：当前基础状态是 `RUNNING / SUCCESS / ERROR`；若要准确呈现降级、取消和超时，需要扩展状态与根节点判定策略。
4. **注解覆盖范围**：漏掉关键阶段不会必然丢 `traceId`，但会丢失中间节点，子节点会挂到最近的已追踪祖先，排障粒度变粗。关键阶段应有约束、代码审查或集成测试。
5. **AOP 代理边界**：只有实际经过 Spring AOP 代理的方法调用才会被拦截；同类内部自调用等情况需要额外确认。
6. **前端语义**：当前管理端主要是按 `depth` 缩进和时间轴展示；应确认是否需要真正按 `parentNodeId` 组装可折叠树，以及是否要显式展示关键路径与降级状态。

## 13. 最终心智模型

整条链路可压缩为：

```text
用户发起 RAG
→ 根入口创建 traceId 和 run
→ traceId、任务 ID、当前节点栈放入 TTL 上下文
→ 注解标记关键业务边界
→ AOP 自动创建 node、记录开始、入栈
→ 执行业务；异步任务通过 TTL 继承上下文
→ 并行分支以父节点为共同父亲，各自记录耗时/异常
→ 节点结束时 AOP 统一更新 SUCCESS/ERROR、耗时、异常，出栈
→ 父节点按业务规则决定全成功、降级还是失败
→ SSE 的 complete/error/cancel 决定流式问答的真正终态
→ run 与 node 落 MySQL
→ 管理端按 traceId 查询、时间轴/层级展示，定位慢点和错误
```

一句话总结：

> 链路追踪是在正常业务调用之上，维护一个可跨线程、可落库、可回放的影子调用栈；AOP 负责记录边界，TTL 负责不断链，数据库负责保存证据，管理端负责把证据变成排障视图。
