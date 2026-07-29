#!/usr/bin/env python3
"""Build an import-ready, but not-yet-imported, Chinese store RAG seed.

The script reads the local Chinese Icecat pilot corpus and produces all
offline artifacts needed before a later import: a product taxonomy, an intent
tree, an entity catalog, a metadata contract, semantic chunks and a readable
chunk preview.  It deliberately makes no HTTP request and never touches
MySQL, Milvus or Elasticsearch.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable


DEFAULT_SOURCE_ROOT = Path("external_data/digital_store_v1/zh_seed")
DEFAULT_OUTPUT_ROOT = Path("external_data/digital_store_v1/rag_seed_v1")
TARGET_CHARS = 430
MAX_CHARS = 1100
DEFAULT_MAX_CHUNKS = 8000
CONSUMER_CHARGER_PATTERN = re.compile(r"usb|\bpd\b|gan|phone|mobile|smartphone|wall charger|travel charger|power adapter", re.IGNORECASE)
NON_CONSUMER_TITLE_PATTERN = re.compile(r"\b(ups|rack|server|enterprise|industrial|barcode|scanner|printer|toner|camera|camcorder|eos)\b", re.IGNORECASE)


@dataclass(frozen=True)
class ProductClassification:
    category: str
    product_kind: str
    category_name: str


CLASSIFICATIONS = {
    "车载充电器": ProductClassification("power_accessory", "vehicle_charger", "充电与供电 / 车载充电器"),
    "桌面充电器": ProductClassification("power_accessory", "desk_charger", "充电与供电 / 桌面充电器"),
    "充电器": ProductClassification("power_accessory", "wall_charger", "充电与供电 / 电源充电器"),
    "手机壳": ProductClassification("phone_protection", "phone_case", "手机防护配件 / 手机壳"),
    "翻盖手机壳": ProductClassification("phone_protection", "folio_case", "手机防护配件 / 翻盖手机壳"),
    "手机贴膜": ProductClassification("phone_protection", "screen_protector", "手机防护配件 / 手机贴膜"),
    "笔记本电脑（格式验证样本）": ProductClassification("computer", "laptop", "电脑与平板 / 笔记本电脑"),
    "智能手机": ProductClassification("mobile_device", "smartphone", "手机与平板 / 智能手机"),
    "平板电脑": ProductClassification("mobile_device", "tablet", "手机与平板 / 平板电脑"),
    "笔记本电脑": ProductClassification("computer", "laptop", "电脑与平板 / 笔记本电脑"),
    "显示器": ProductClassification("computer", "monitor", "电脑与平板 / 显示器"),
    "无线路由器": ProductClassification("network", "wireless_router", "网络设备 / 无线路由器"),
    "耳机": ProductClassification("audio", "headphones", "音频设备 / 耳机"),
    "音箱": ProductClassification("audio", "speaker", "音频设备 / 音箱"),
    "移动电源": ProductClassification("power_accessory", "power_bank", "充电与供电 / 移动电源"),
    "智能手表": ProductClassification("wearable", "smartwatch", "智能穿戴 / 智能手表"),
    "空气净化器": ProductClassification("smart_home", "air_purifier", "智能家居 / 空气净化器"),
    "扫地机器人": ProductClassification("smart_home", "robot_vacuum", "智能家居 / 扫地机器人"),
}

DEVICE_CATALOG = {
    "device:xiaomi-17": {"name": "小米 17", "aliases": ["小米17", "Xiaomi 17"]},
    "device:redmi-note-15-pro-4g": {"name": "红米 Note 15 Pro 4G", "aliases": ["红米Note15Pro 4G", "Redmi Note 15 Pro 4G"]},
    "device:redmi-note-15-pro-5g": {"name": "红米 Note 15 Pro 5G", "aliases": ["红米Note15Pro 5G", "Redmi Note 15 Pro 5G"]},
    "device:redmi-note-11-4g": {"name": "红米 Note 11 4G", "aliases": ["红米Note11 4G", "Redmi Note 11 4G"]},
    "device:redmi-note-11s-4g": {"name": "红米 Note 11S 4G", "aliases": ["红米Note11S 4G", "Redmi Note 11S 4G"]},
}

COMPATIBILITY = {
    "77079529": {"brands": ["iPhone", "三星", "小米", "OPPO"], "devices": []},
    "101364912": {"brands": ["iPhone", "三星", "小米", "OPPO"], "devices": []},
    "93980280": {"brands": ["iPhone", "三星", "华为", "小米", "MacBook"], "devices": []},
    "77014320": {"brands": ["iPhone", "三星", "华为", "小米"], "devices": []},
    "140052397": {"brands": ["小米"], "devices": ["device:xiaomi-17"]},
    "141993088": {"brands": ["红米"], "devices": ["device:redmi-note-15-pro-4g"]},
    "140052393": {"brands": ["红米"], "devices": ["device:redmi-note-15-pro-5g"]},
    "140052405": {"brands": ["小米"], "devices": ["device:xiaomi-17"]},
    "99771126": {"brands": ["红米"], "devices": ["device:redmi-note-11-4g", "device:redmi-note-11s-4g"]},
    "13214266": {"brands": [], "devices": []},
}


def normalize(value: str) -> str:
    return re.sub(r"\s+", " ", value or "").strip()


MODEL_ALIAS_STOP_PATTERN = re.compile(
    r"\s+(?:Intel|AMD|Apple|Qualcomm|MediaTek|Android|Windows|ChromeOS|Dual|Single|Hybrid|Wi-Fi|USB|Bluetooth)\b"
    r"|\s+\d+(?:\.\d+)?\s*cm\b|\s+\d+\s*(?:GB|TB|mAh|W)\b",
    re.IGNORECASE,
)


def concise_product_aliases(product: dict[str, Any]) -> list[str]:
    """Generate safe human-length model aliases from a verbose catalogue title.

    A short alias is intentionally only an entity hint: collisions are allowed
    and become AMBIGUOUS rather than forcing a potentially wrong entity_id.
    """
    title = normalize(product.get("zh_title") or "")
    brand = normalize(product.get("zh_brand") or "")
    if not title:
        return []
    short = normalize(MODEL_ALIAS_STOP_PATTERN.split(title, maxsplit=1)[0])
    aliases = []
    if 4 <= len(short) <= 80 and short != title:
        aliases.append(short)
        if brand and short.lower().startswith(brand.lower() + " "):
            without_brand = normalize(short[len(brand):])
            if 4 <= len(without_brand) <= 60:
                aliases.append(without_brand)
    return aliases


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_json(path: Path, payload: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")


def write_jsonl(path: Path, rows: Iterable[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")))
            handle.write("\n")


def classification(product: dict[str, Any]) -> ProductClassification:
    category = product.get("zh_store_category") or ""
    if category not in CLASSIFICATIONS:
        raise ValueError(f"No stable category mapping for {product['icecat_id']}: {category!r}")
    return CLASSIFICATIONS[category]


def is_in_scope_consumer_product(product: dict[str, Any]) -> bool:
    """Keep the corpus focused on ordinary consumer-electronics store questions."""
    title = product.get("title") or ""
    if NON_CONSUMER_TITLE_PATTERN.search(title):
        return False
    if product.get("zh_store_category") == "充电器":
        return bool(CONSUMER_CHARGER_PATTERN.search(title))
    return True


def title_identity(product: dict[str, Any], kind: ProductClassification) -> list[str]:
    return [
        f"# {product['zh_title']}",
        "",
        "## 商品身份",
        f"- 品牌：{product['zh_brand']}",
        f"- 商品类目：{kind.category_name}",
        f"- 商品编码：{product['product_code'] or '未提供'}",
        f"- 商品实体 ID：{product['product_id']}",
    ]


def metadata(product: dict[str, Any], kind: ProductClassification, doc_type: str, section_path: str) -> dict[str, Any]:
    compatibility = COMPATIBILITY.get(product["icecat_id"], {"brands": [], "devices": []})
    return {
        "schema_version": "digital_store_v1",
        "source_version": "icecat_catalog_zh",
        "source_document": f"products/{product['icecat_id']}.md",
        "document_title": product["zh_title"],
        "category": kind.category,
        "product_kind": kind.product_kind,
        "store_category": product["zh_store_category"],
        "doc_type": doc_type,
        "entity_type": "product",
        "entity_id": product["product_id"],
        "brand": product["zh_brand"],
        "product_code": product["product_code"],
        "compatible_brand_names": compatibility["brands"],
        "compatible_device_ids": compatibility["devices"],
        "section_path": section_path,
        "language": "zh-CN",
    }


def make_chunk(key: str, content: str, metadata_value: dict[str, Any]) -> dict[str, Any]:
    normalized = content.replace("\r\n", "\n").strip() + "\n"
    return {
        "logical_chunk_key": key,
        "content": normalized,
        "content_hash": hashlib.sha256(normalized.encode("utf-8")).hexdigest(),
        "char_count": len(normalized),
        "metadata": metadata_value,
    }


def specification_chunks(product: dict[str, Any], kind: ProductClassification) -> list[dict[str, Any]]:
    prefix = title_identity(product, kind) + ["", "## 规格参数"]
    prefix_text = "\n".join(prefix)
    # Reserve room for the repeated field name and the continuation marker.
    row_budget = max(180, MAX_CHARS - len(prefix_text) - 100)
    rows: list[str] = []
    for spec in product["zh_specifications"]:
        row = f"- {spec['name']}：{spec['value']}"
        if len(row) <= row_budget:
            rows.append(row)
            continue
        value = spec["value"]
        parts = [value[index:index + row_budget] for index in range(0, len(value), row_budget)]
        for index, part in enumerate(parts, start=1):
            rows.append(f"- {spec['name']}（第 {index}/{len(parts)} 段）：{part}")
    groups: list[list[str]] = []
    current: list[str] = []
    for row in rows:
        candidate = "\n".join([prefix_text, *current, row])
        if current and len(candidate) > TARGET_CHARS:
            groups.append(current)
            current = [row]
        else:
            current.append(row)
    if current:
        groups.append(current)

    chunks: list[dict[str, Any]] = []
    for index, group in enumerate(groups, start=1):
        content = "\n".join([prefix_text, *group])
        if len(content) > MAX_CHARS:
            raise ValueError(f"Specification chunk too long for {product['icecat_id']}: {len(content)}")
        suffix = "" if len(groups) == 1 else f"-{index}"
        chunks.append(
            make_chunk(
                f"products/{product['icecat_id']}.md#specifications{suffix}",
                content,
                metadata(product, kind, "product_specification", "商品规格参数"),
            )
        )
    return chunks


def product_chunks(product: dict[str, Any]) -> list[dict[str, Any]]:
    kind = classification(product)
    identity = title_identity(product, kind)
    description = product.get("zh_description") or "数据源未提供中文商品说明。"
    summary = make_chunk(
        f"products/{product['icecat_id']}.md#summary",
        "\n".join([*identity, "", "## 商品说明", description]),
        metadata(product, kind, "product_summary", "商品说明"),
    )
    chunks = [summary, *specification_chunks(product, kind)]
    compatibility = COMPATIBILITY.get(product["icecat_id"], {"brands": [], "devices": []})
    if compatibility["brands"] or compatibility["devices"]:
        device_names = [DEVICE_CATALOG[item]["name"] for item in compatibility["devices"]]
        lines = [*identity, "", "## 适配与兼容性"]
        if device_names:
            lines.append("- 明确适配机型：" + "、".join(device_names))
        if compatibility["brands"]:
            lines.append("- 来源说明中提及的兼容品牌或设备：" + "、".join(compatibility["brands"]))
        chunks.append(
            make_chunk(
                f"products/{product['icecat_id']}.md#compatibility",
                "\n".join(lines),
                metadata(product, kind, "product_compatibility", "适配与兼容性"),
            )
        )
    return chunks


def taxonomy() -> dict[str, Any]:
    """Emit the controlled metadata vocabulary used by this catalogue."""
    category_names = {
        "audio": "音频设备", "computer": "电脑设备", "mobile_device": "手机与平板",
        "network": "网络设备", "power_accessory": "充电与供电", "smart_home": "智能家居",
        "wearable": "智能穿戴", "phone_protection": "手机防护配件",
    }
    nodes = [{"code": "digital_store", "name": "数码商品店铺", "parent_code": None, "level": 0}]
    used_categories = sorted({item.category for item in CLASSIFICATIONS.values()})
    for category in used_categories:
        nodes.append({"code": category, "name": category_names.get(category, category), "parent_code": "digital_store", "level": 1})
    for kind in sorted({item for item in CLASSIFICATIONS.values()}, key=lambda item: (item.category, item.product_kind)):
        nodes.append({"code": kind.product_kind, "name": kind.category_name.split(" / ")[-1], "parent_code": kind.category, "level": 2})
    return {
        "taxonomy_version": "digital_store_v1",
        "purpose": "商品导航与检索元数据的受控词表；不等同于运行时意图树。",
        "nodes": nodes,
    }


def intent_nodes() -> list[dict[str, Any]]:
    return [
        {
            "intentCode": "digital_store_v1_domain", "name": "数码商品知识问答", "level": 0, "parentCode": None,
            "description": "商品规格、适配关系与基础商品信息查询", "examples": ["这款充电器支持什么快充", "小米 17 手机壳是否适配"],
            "topK": 6, "kind": 0, "sortOrder": 1, "enabled": 1,
        },
        {
            "intentCode": "digital_store_v1_product", "name": "商品信息", "level": 1, "parentCode": "digital_store_v1_domain",
            "description": "商品型号、规格、接口、功率、材质和基础说明", "examples": ["这款充电器是多少瓦", "手机壳是什么材质"],
            "topK": 6, "kind": 0, "sortOrder": 2, "enabled": 1,
        },
        {
            "intentCode": "digital_store_v1_product_specification", "name": "规格参数", "level": 2, "parentCode": "digital_store_v1_product",
            "description": "商品功率、接口、尺寸、材质和技术规格", "examples": ["有几个 USB-C 接口", "最大输出功率是多少"],
            "topK": 6, "kind": 0, "sortOrder": 3, "enabled": 1,
        },
        {
            "intentCode": "digital_store_v1_product_compatibility", "name": "适配关系", "level": 2, "parentCode": "digital_store_v1_product",
            "description": "商品与手机型号、品牌或设备的适配关系", "examples": ["适配小米 17 吗", "红米 Note 11 能不能用"],
            "topK": 6, "kind": 0, "sortOrder": 4, "enabled": 1,
        },
    ]


def entity_catalog(products: list[dict[str, Any]]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    used_devices: set[str] = set()
    for product in products:
        kind = classification(product)
        aliases = [product["zh_title"], product["product_code"], *concise_product_aliases(product)]
        rows.append(
            {
                "entity_type": "product", "entity_id": product["product_id"], "entity_name": product["zh_title"],
                "aliases": list(dict.fromkeys(item for item in aliases if item)), "category": kind.category,
                "source_document": f"products/{product['icecat_id']}.md", "source_version": "icecat_catalog_zh",
            }
        )
        used_devices.update(COMPATIBILITY.get(product["icecat_id"], {"devices": []})["devices"])
    for device_id in sorted(used_devices):
        device = DEVICE_CATALOG[device_id]
        rows.append(
            {
                "entity_type": "device_model", "entity_id": device_id, "entity_name": device["name"],
                "aliases": [device["name"], *device["aliases"]], "category": "device_model",
                "source_document": "compatibility_catalog", "source_version": "icecat_catalog_zh",
            }
        )
    return rows


def metadata_contract() -> dict[str, Any]:
    return {
        "schema_version": "digital_store_v1",
        "filter_ready_fields": {
            "category": "稳定一级商品类目代码，例如 power_accessory",
            "product_kind": "稳定叶子商品类型，例如 vehicle_charger",
            "doc_type": "product_summary / product_specification / product_compatibility",
            "entity_type": "当前均为 product",
            "entity_id": "稳定商品实体 ID，例如 icecat:77079529",
            "brand": "商品品牌，不等于兼容品牌",
            "product_code": "供应商商品编码",
        },
        "supplementary_fields": {
            "compatible_brand_names": "来源说明里提及的兼容品牌或设备名称列表，不作为商品品牌过滤",
            "compatible_device_ids": "明确兼容机型的实体 ID 列表；后续 ES/Milvus 映射支持数组过滤后再启用",
            "store_category": "中文展示类目",
            "source_document": "可追溯的逻辑源文档",
            "language": "固定 zh-CN",
        },
        "boundary": "本契约只定义离线数据。当前主项目尚未添加数码店铺的检索 Plan 或顶层知识库路由。",
    }


def preview(chunks: list[dict[str, Any]]) -> str:
    lines = [
        "# 数码店铺 v1 切块预览", "", f"- Chunk 数：{len(chunks)}", f"- 目标长度：约 {TARGET_CHARS} 字符", f"- 最大长度：{MAX_CHARS} 字符", "",
        "以下展示每种切块类型的第一个实例。", "",
    ]
    seen: set[str] = set()
    for chunk in chunks:
        doc_type = chunk["metadata"]["doc_type"]
        if doc_type in seen:
            continue
        seen.add(doc_type)
        lines.extend([f"## {doc_type}", "", "```markdown", chunk["content"].rstrip(), "```", ""])
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description="Build offline import-ready artifacts for digital_store_v1.")
    parser.add_argument("--source-root", type=Path, default=DEFAULT_SOURCE_ROOT)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument("--max-chunks", type=int, default=DEFAULT_MAX_CHUNKS,
                        help="hard safety cap; the intended final corpus must stay near v7 scale")
    args = parser.parse_args()

    source_products = load_jsonl(args.source_root / "artifacts" / "products_zh.jsonl")
    prepared = [(product, product_chunks(product), is_in_scope_consumer_product(product)) for product in source_products]
    all_chunk_count = sum(len(candidate_chunks) for _, candidate_chunks, _ in prepared)
    if all_chunk_count <= args.max_chunks:
        # Keep every valid record when the corpus already fits the safety cap.
        selected = prepared
        excluded_by_chunk_cap = 0
        excluded_low_priority = 0
    else:
        # Only under pressure from the cap do ordinary consumer products win first.
        selected = []
        excluded_by_chunk_cap = 0
        excluded_low_priority = 0
        selected_chunk_count = 0
        for product, candidate_chunks, in_scope in sorted(prepared, key=lambda item: not item[2]):
            if selected_chunk_count + len(candidate_chunks) <= args.max_chunks:
                selected.append((product, candidate_chunks, in_scope))
                selected_chunk_count += len(candidate_chunks)
            else:
                excluded_by_chunk_cap += 1
                if not in_scope:
                    excluded_low_priority += 1
    products = [product for product, _, _ in selected]
    chunks = [chunk for _, candidate_chunks, _ in selected for chunk in candidate_chunks]
    if any(chunk["char_count"] > MAX_CHARS for chunk in chunks):
        raise ValueError("A chunk exceeds the configured maximum length")

    output = args.output_root
    write_json(output / "config" / "product_taxonomy.json", taxonomy())
    write_json(output / "config" / "intent_nodes.json", intent_nodes())
    write_json(output / "config" / "metadata_contract.json", metadata_contract())
    write_jsonl(output / "config" / "entity_catalog.jsonl", entity_catalog(products))
    write_jsonl(output / "artifacts" / "chunks.jsonl", chunks)
    write_jsonl(output / "artifacts" / "products_zh.jsonl", products)
    (output / "artifacts" / "chunk_preview.md").parent.mkdir(parents=True, exist_ok=True)
    (output / "artifacts" / "chunk_preview.md").write_text(preview(chunks), encoding="utf-8", newline="\n")
    manifest = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "dataset": "digital_store_v1",
        "source_dataset": "digital_store_v1_icecat_catalog_zh",
        "language": "zh-CN",
        "total_products": len(products),
        "total_chunks": len(chunks),
        "entity_count": len(entity_catalog(products)),
        "intent_node_count": len(intent_nodes()),
        "category_chunk_count": dict(sorted(Counter(chunk["metadata"]["category"] for chunk in chunks).items())),
        "doc_type_chunk_count": dict(sorted(Counter(chunk["metadata"]["doc_type"] for chunk in chunks).items())),
        "chunk_policy": {
            "target_chars": TARGET_CHARS,
            "max_chars": MAX_CHARS,
            "notes": "按商品说明、规格参数、适配关系切分；规格仅在超过最大长度时继续拆分。",
        },
        "max_chunks": args.max_chunks,
        "excluded_product_count_by_chunk_cap": excluded_by_chunk_cap,
        "excluded_low_priority_product_count_by_chunk_cap": excluded_low_priority,
        "next_step": "Validate artifacts, then import through an explicit digital_store_v1 importer. This build has not called any database or search service.",
    }
    write_json(output / "manifest.json", manifest)
    print(f"Built offline seed: products={len(products)}, chunks={len(chunks)}, output={output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
