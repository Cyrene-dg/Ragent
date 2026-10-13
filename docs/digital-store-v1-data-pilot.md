# 数码店铺 RAG：Open Icecat 商品语料离线构建

## 目的与边界

这是一个与 `genshin_v7` 完全独立的数码店铺知识库数据源试运行。数据管道完成了“授权下载 XML → 本地保留来源 → 转换为 RAG 文档 → 导入独立知识库”的闭环，不修改或重建原神知识库。

不下载完整 `files.index` 到本机、不抓取京东、淘宝或小米网站，也不下载图片、视频、PDF。商品页单条上限为 **5 MiB**，最终 Chunk 硬上限为 **8,000**。

| 阶段 | 数据量 | 目的 |
| --- | ---: | --- |
| 烟雾测试 | 10 个数据表 | 验证账户认证、XML 格式和本地留档 |
| 当前：离线候选包 | 1,108 个商品数据表 | 构建商品树、实体目录、元数据和待导入 Chunk |
| 已完成导入 | 7,532 个 Chunk | 创建独立知识库并写入 MySQL、Milvus 与 Elasticsearch |

原始第三方数据写入 `external_data/digital_store_v1/`，该目录已被 `.gitignore` 忽略；仓库只保留脚本、清单和由我们后续生成的结构化种子文件。

## 数据来源与选品边界

Open Icecat 的公开目录不等同于“小米官方商品库”，因此本数据包不能宣传为“小米官方自营店商品数据”。它采用“数码店铺”业务形态，并从公开目录中按 `product_view` 优先选择高频消费电子品类：智能手机、平板、笔记本、显示器、耳机、音箱、无线路由器、充电器、移动电源、智能手表、空气净化器和扫地机器人。

初次候选为 1,506 条；受公开端点可访问性限制，最终校验通过 **1,108** 份 XML。UPS、相机/摄像机充电器、工业或企业设备被标记为“低优先级”，但并不默认删除：只有未来超过 8,000 条 Chunk 上限时才优先跳过它们。本次全量只生成 7,532 条，因此 1,108 个有效商品均被保留。失败商品未被伪造成资料或补写进语料。

## 当前离线产物

截至本次构建，`external_data/digital_store_v1/catalog_rag_seed_v1/` 已生成：

- 1,108 个商品实体；
- 7,532 个中文检索 Chunk，接近 `genshin_v7` 的 7,742 条，且未超过 8,000 条硬上限；
- 类目、商品类型、品牌、商品实体、文档类型等元数据；
- 商品概述与规格参数两类 Chunk；规格采用约 430 字符目标粒度，最长不超过 1,100 字符。

中文化采用“中文类目与结构化字段 + 常用品牌术语表 + 中文模板概述”。型号、协议名和无法可靠本地化的原始技术值会保留英文，例如 `Galaxy S25 Ultra`、`USB-C`、`Wi-Fi`。这是一份可检索的中文优先语料，不应宣称为人工逐句翻译的官方中文商品文档。

## 本机运行

先只看计划（不会联网下载）：

```powershell
python scripts/digital_store/fetch_icecat_pilot.py
```

下载时，凭据只在本机终端输入，**不要发进聊天**：

```powershell
python scripts/digital_store/fetch_icecat_pilot.py --download --prompt-credentials
```

脚本会请求用户名/邮箱与密码，输入密码时终端不回显。环境变量方式仅适合你已经在本机安全地配置了 `ICECAT_USERNAME` 与 `ICECAT_PASSWORD` 的场景；第一轮建议使用上面的本地交互输入。

下载成功后应只有以下本地文件：

```text
external_data/digital_store_v1/icecat_raw/
  xml/<icecat-id>.xml
  download_manifest.json
```

将本地 XML 转为可人工检查的商品文档（仍不导入 Ragent）：

```powershell
python scripts/digital_store/build_icecat_pilot_seed.py --raw-root external_data/digital_store_v1/catalog_raw --output-root external_data/digital_store_v1/catalog_derived_seed
```

输出在 `external_data/digital_store_v1/catalog_derived_seed/`，其中每个 Markdown 都包含品牌、店铺类目映射、商品编号、商品说明和规格参数。`products.jsonl` 是同一批数据的结构化视图，供下一步建立实体目录、商品树和检索元数据使用。

## 中文本地化试运行

原始 Icecat 数据表以英文为主，不能直接作为中文问答的最终语料。当前 10 条试点使用“受控术语表 + 按商品人工校对的中文说明”生成中文版本：

```powershell
python scripts/digital_store/localize_icecat_pilot_zh.py --seed-root external_data/digital_store_v1/catalog_derived_seed --output-root external_data/digital_store_v1/catalog_zh_seed
```

输出位于 `external_data/digital_store_v1/catalog_zh_seed/`。规则是保留型号、USB-C、Power Delivery、Quick Charge、iPhone、MacBook 等行业常用写法；将 Xiaomi / Redmi / Huawei / Samsung 等品牌与商品类目、常用规格名称本地化为中文。英文原文标题和 Icecat 链接仍随每条中文文档保存，便于核对。

## 导入前 RAG 数据包

下列命令只构建本地离线产物，不调用后端 API、不创建知识库、不写 MySQL、Milvus 或 Elasticsearch：

```powershell
python scripts/digital_store/build_digital_store_v1_seed.py --source-root external_data/digital_store_v1/catalog_zh_seed --output-root external_data/digital_store_v1/catalog_rag_seed_v1 --max-chunks 8000
```

输出 `external_data/digital_store_v1/catalog_rag_seed_v1/` 包含：

- `config/product_taxonomy.json`：商品类目树；
- `config/intent_nodes.json`：商品信息 / 规格参数 / 适配关系的意图树；
- `config/entity_catalog.jsonl`：商品实体和明确兼容的设备型号实体；
- `config/metadata_contract.json`：可用于后续精确过滤的元数据字段；
- `artifacts/chunks.jsonl`：待导入的中文语义 Chunk；
- `artifacts/chunk_preview.md`：人工检查切块边界的预览。

## 已完成导入与后续工作

已创建 `digital_store_kb_v1` / `digitalstorev1`，并完成 MySQL、Milvus、Elasticsearch 三边导入与计数核对。数码库目前可以作为独立知识库使用。

已完成的运行时工作：

1. 增加顶层知识库路由，按强关键词、实体目录和 LLM 兜底区分不同知识库；
2. 为数码库接入独立实体目录与意图规则，并复用 Milvus / Elasticsearch 分层检索、去重与 Rerank 链路；
3. 将知识库 ID、实体目录、意图规则和 Milvus collection 收口到按知识库配置的 Profile，避免在检索代码中硬编码。

仍需单独完成的验证工作：

1. 建立商品事实问答、规格比较、兼容性和组合筛选的人工 Gold 评测集；
2. 对路由准确率、Hit@5、MRR@10、Recall@20、Top5 无关结果占比和 P95 延迟做正式评测；
3. 兼容设备数组只有在 ES 与 Milvus 均验证数组过滤行为后，才能升级为强过滤条件。
