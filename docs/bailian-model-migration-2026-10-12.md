# 百炼主模型迁移记录（2026-10-12）

## 变更

- 默认聊天及深度思考模型从 `qwen3-max` 替换为 `qwen3.8-max`，同时更新候选模型 ID。
- 百炼客户端对支持思考的模型显式发送 `enable_thinking`：请求指定 `true` 才开启，`false` 或未指定时关闭。同步和流式请求共用此规则，避免新模型默认思考影响意图分类等调用。
- 保留现有 `BAILIAN_API_KEY`、北京地域接入地址及备用模型。没有新增供应商或调整业务意图路由。

## 真实接口检查

使用项目当前配置解析到的 API Key，在北京地域进行最小请求；没有将密钥写入测试或本记录。

| 检查 | 结果 |
| --- | --- |
| 原 `qwen3-max` | HTTP 404，`model_not_found` |
| `qwen3.8-max` 普通同步调用 | 成功，返回正文 |
| `qwen3.8-max` 普通流式调用 | 成功，返回正文及 `[DONE]`，无思考正文 |
| `qwen3.8-max` 思考流式调用 | 成功，返回思考、正文及 `[DONE]` |
| 备用 `qwen-plus-latest` | 成功，返回正文 |
| `qwen3-rerank` 现有 DashScope 契约 | 成功，返回 `output.results`，无需迁移 |

真实思考流式检查使用短问题和 `thinking_budget=128` 控制测试消耗；生产请求未新增此限制。

## 自动验证

`BaiLianChatClientTest` 使用模拟 HTTP 响应，检查同步和流式请求中思考开关的三种输入（未指定、关闭、开启），以及不支持思考的模型不发送开关；同时检查正文、思考和结束回调。共 7 项通过。

## 生效方式

从更新后的源码重新启动后端；已启动进程或旧打包 JAR 不会自动加载此次修改。

## 契约依据

- [百炼 Chat Completions API](https://help.aliyun.com/en/model-studio/qwen-api-via-openai-chat-completions)：HTTP 请求将 `enable_thinking` 放在顶层。
- [深度思考模型用法](https://help.aliyun.com/zh/model-studio/deep-thinking)：`qwen3.8-max` 的同步和流式调用。
- [API Key 管理](https://help.aliyun.com/zh/model-studio/get-api-key)：同地域、同业务空间内切换模型无需为每个模型重新创建 API Key，仍需满足模型权限。
