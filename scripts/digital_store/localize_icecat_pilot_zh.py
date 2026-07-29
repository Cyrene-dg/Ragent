#!/usr/bin/env python3
"""Create a Chinese retrieval corpus from the local Icecat pilot seed.

This is intentionally a *localization* step, not a blind machine translation.
It combines a small technical-term glossary with a per-product Chinese
editorial overlay.  Brand and model conventions remain consumer-facing:
``Xiaomi`` becomes ``小米``, while ``iPhone`` and ``MacBook`` stay unchanged.

The overlay and generated corpus live under ``external_data/`` so source and
derived third-party product material do not enter Git.
"""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any


DEFAULT_SEED_ROOT = Path("external_data/digital_store_v1/derived_seed")
DEFAULT_OVERLAY = Path("external_data/digital_store_v1/localization/zh_overrides.json")
DEFAULT_OUTPUT_ROOT = Path("external_data/digital_store_v1/zh_seed")

FEATURE_NAMES = {
    "Charger type": "充电器类型",
    "Charger compatibility": "充电兼容设备",
    "Power source type": "供电类型",
    "Number of simultaneously connected devices (max)": "最大同时充电设备数",
    "USB Type-C ports quantity": "USB-C 接口数量",
    "USB Power Delivery (USB PD)": "USB PD 快充",
    "USB Power Delivery up to": "USB PD 最大输出功率",
    "Fast charging": "快速充电",
    "Fast charging technology": "快充技术",
    "Brand compatibility": "品牌兼容性",
    "Product colour": "产品颜色",
    "LED indicators": "LED 指示灯",
    "Input voltage": "输入电压",
    "Input current": "输入电流",
    "Maximum power": "最大功率",
    "Maximum output voltage": "最大输出电压",
    "Minimum output voltage": "最小输出电压",
    "Width": "宽度",
    "Depth": "深度",
    "Height": "高度",
    "Weight": "重量",
    "Quantity per pack": "包装数量",
    "Country of origin": "原产国",
    "Wireless charging": "无线充电",
    "Bluetooth": "蓝牙",
}

FEATURE_NAME_EXTRAS = {
    "AC adapter included": "含电源适配器",
    "Audio system": "音频系统",
    "Automatic output voltage adjustment": "自动输出电压调节",
    "Batteries included": "含电池",
    "Built-in battery": "内置电池",
    "Built-in microphone": "内置麦克风",
    "Built-in screen cover": "内置屏幕保护盖",
    "Bus type": "总线类型",
    "CPU multiplier (bus/core ratio)": "CPU 倍频（总线/核心比）",
    "Cable length": "线缆长度",
    "Cables included": "随附线缆",
    "Card pocket": "卡槽",
    "Card reader integrated": "集成读卡器",
    "Case type": "保护壳类型",
    "Certification": "认证",
    "Cigar lighter adapter included": "含点烟器适配器",
    "Closure type": "闭合方式",
    "Compatibility": "兼容性",
    "Compatible memory cards": "兼容存储卡",
    "Compliance certificates": "合规认证",
    "Desktop stand": "桌面支架",
    "Discrete graphics card": "独立显卡",
    "Discrete graphics card model": "独立显卡型号",
    "Display diagonal": "屏幕尺寸",
    "Display resolution": "显示分辨率",
    "ECC supported by processor": "处理器支持 ECC",
    "FSB Parity": "FSB 奇偶校验",
    "Fingerprint resistant": "防指纹",
    "Form factor": "外形规格",
    "Graphics card family": "显卡系列",
    "HD type": "高清类型",
    "Housing material": "外壳材质",
    "Included power plug types": "含插头类型",
    "Input frequency": "输入频率",
    "Internal memory": "内存容量",
    "Internal memory type": "内存类型",
    "LED backlight": "LED 背光",
    "Manual": "说明书",
    "Material": "材料",
    "Maximum graphics card memory": "最大显存",
    "Maximum number of PCI Express lanes": "最大 PCI Express 通道数",
    "Maximum screen size": "适用最大屏幕尺寸",
    "Memory clock speed": "内存频率",
    "Micro-USB 3.2 Gen 1 (3.1 Gen 1) ports quantity": "Micro-USB 接口数量",
    "Native aspect ratio": "原生宽高比",
    "Number of SSDs installed": "已安装 SSD 数量",
    "Number of card pockets": "卡槽数量",
    "Oleophobic coating": "疏油涂层",
    "On-board graphics card": "集成显卡",
    "On-board graphics card ID": "集成显卡 ID",
    "On-board graphics card base frequency": "集成显卡基础频率",
    "On-board graphics card dynamic frequency (max)": "集成显卡动态频率（最高）",
    "On-board graphics card family": "集成显卡系列",
    "On-board graphics card model": "集成显卡型号",
    "Optical drive type": "光驱类型",
    "Output current (12V)": "12V 输出电流",
    "Output current (5V)": "5V 输出电流",
    "Output current (9V)": "9V 输出电流",
    "PCI Express configurations": "PCI Express 配置",
    "PCI Express slots version": "PCI Express 插槽版本",
    "Package depth": "包装深度",
    "Package height": "包装高度",
    "Package weight": "包装重量",
    "Package width": "包装宽度",
    "Pattern": "图案",
    "Port 1 output current": "接口 1 输出电流",
    "Port 2 output current": "接口 2 输出电流",
    "Power plug type": "电源插头类型",
    "Power protection features": "电源保护功能",
    "Processor boost frequency": "处理器睿频",
    "Processor cache": "处理器缓存",
    "Processor cache type": "处理器缓存类型",
    "Processor codename": "处理器代号",
    "Processor cores": "处理器核心数",
    "Processor family": "处理器系列",
    "Processor frequency": "处理器主频",
    "Processor generation": "处理器代际",
    "Processor lithography": "处理器制程工艺",
    "Processor manufacturer": "处理器制造商",
    "Processor model": "处理器型号",
    "Processor operating modes": "处理器运行模式",
    "Processor series": "处理器系列",
    "Processor socket": "处理器插槽",
    "Processor threads": "处理器线程数",
    "Product type": "产品类型",
    "Protection features": "保护特性",
    "Protector type": "保护膜类型",
    "Radio Equipment Directive (RED)": "欧盟无线电设备指令（RED）",
    "SSD capacity": "SSD 容量",
    "SSD interface": "SSD 接口",
    "Speakers manufacturer": "扬声器制造商",
    "Stepping": "步进版本",
    "Storage media": "存储介质",
    "Surface coloration": "表面颜色",
    "System bus rate": "系统总线速率",
    "Thermal Design Power (TDP)": "热设计功耗（TDP）",
    "Tjunction": "结温",
    "Total storage capacity": "总存储容量",
    "Touchscreen": "触控屏",
    "USB 2.0 ports quantity": "USB 2.0 接口数量",
    "USB 3.2 Gen 1 (3.1 Gen 1) Type-A ports quantity": "USB-A 接口数量",
    "Warranty period": "质保期",
}
FEATURE_NAMES.update(FEATURE_NAME_EXTRAS)

TERMS = {
    "Xiaomi": "小米",
    "Redmi": "红米",
    "Huawei": "华为",
    "Samsung": "三星",
    "Smartphone": "手机",
    "Smartphones": "手机",
    "Tablet": "平板电脑",
    "Tablets": "平板电脑",
    "Laptop": "笔记本电脑",
    "Laptops": "笔记本电脑",
    "Auto": "车载",
    "Cigar lighter": "点烟器",
    "Universal": "通用",
    "Black": "黑色",
    "White": "白色",
    "China": "中国",
    "Yes": "是",
    "No": "否",
    "Fast charging": "快速充电",
    "Lenovo": "联想",
    "ASUS": "华硕",
    "Acer": "宏碁",
    "HP": "惠普",
    "Dell": "戴尔",
    "Sony": "索尼",
    "Philips": "飞利浦",
    "Logitech": "罗技",
    "Microsoft": "微软",
    "Samsung": "三星",
    "Huawei": "华为",
    "HONOR": "荣耀",
    "Headphones": "耳机",
    "Headset": "耳机",
    "Speaker": "音箱",
    "Speakers": "音箱",
    "Router": "路由器",
    "Monitor": "显示器",
    "Watch": "智能手表",
    "Vacuum": "扫地机器人",
    "Air purifier": "空气净化器",
    "Power bank": "移动电源",
}


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def load_overrides(path: Path) -> dict[str, dict[str, Any]]:
    if not path.exists():
        return {}
    payload = json.loads(path.read_text(encoding="utf-8"))
    entries = payload.get("products") if isinstance(payload, dict) else None
    if not isinstance(entries, dict):
        raise ValueError(f"{path} must contain a products object keyed by Icecat ID")
    return entries


def localize_terms(value: str) -> str:
    localized = value
    for source, target in sorted(TERMS.items(), key=lambda item: len(item[0]), reverse=True):
        localized = re.sub(rf"\b{re.escape(source)}\b", target, localized, flags=re.IGNORECASE)
    return localized


def localized_specs(specifications: list[dict[str, str]]) -> list[dict[str, str]]:
    return [
        {
            "name": FEATURE_NAMES.get(spec["name"], localize_terms(spec["name"])),
            "value": localize_terms(spec["value"]),
            "source_name": spec["name"],
            "source_value": spec["value"],
        }
        for spec in specifications
    ]


def generated_description(product: dict[str, Any], specifications: list[dict[str, str]]) -> str:
    """Produce a readable Chinese retrieval summary without an external model call."""
    core_specs = "；".join(f"{item['name']}：{item['value'][:160]}" for item in specifications[:8])
    identity = f"{localize_terms(product['brand'])} {localize_terms(product['title'])}".strip()
    category = product.get("store_category") or "数码商品"
    if core_specs:
        return f"这是 {identity}，归入{category}。可检索的核心规格包括：{core_specs}。"
    return f"这是 {identity}，归入{category}。数据源未提供可用的结构化规格参数。"


def markdown(product: dict[str, Any]) -> str:
    lines = [
        f"# {product['zh_title']}",
        "",
        "## 商品身份",
        f"- 品牌：{product['zh_brand']}",
        f"- 店铺类目（试点映射）：{product['zh_store_category']}",
        f"- 商品编码：{product['product_code'] or '未提供'}",
        f"- 数据源产品 ID：{product['icecat_id']}",
        "",
        "## 商品说明",
        product["zh_description"] or "数据源未提供中文商品说明。",
        "",
        "## 规格参数",
    ]
    lines.extend(f"- {spec['name']}：{spec['value']}" for spec in product["zh_specifications"])
    lines.extend(
        [
            "",
            "## 数据来源与原文标识",
            f"- Icecat XML：{product['source_url']}",
            f"- 原始英文标题：{product['title']}",
            "- 本文为中文检索语料；产品型号、协议名和品牌惯用名按中文消费者常用方式保留或本地化。",
        ]
    )
    return "\n".join(lines) + "\n"


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")))
            handle.write("\n")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build Chinese product documents from the Icecat pilot seed.")
    parser.add_argument("--seed-root", type=Path, default=DEFAULT_SEED_ROOT)
    parser.add_argument("--overrides", type=Path, default=DEFAULT_OVERLAY)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    args = parser.parse_args()

    source_products = load_jsonl(args.seed_root / "artifacts" / "products.jsonl")
    overrides = load_overrides(args.overrides)
    localized: list[dict[str, Any]] = []
    for product in source_products:
        icecat_id = product["icecat_id"]
        override = overrides.get(icecat_id)
        localized_product = dict(product)
        specs = localized_specs(product["specifications"])
        if override:
            title = override["title"]
            description = override["description"]
            method = "curated_pilot_translation_plus_term_glossary"
        else:
            title = localize_terms(product["title"])
            description = generated_description(product, specs)
            method = "template_localization_plus_term_glossary"
        localized_product.update(
            {
                "zh_title": title,
                "zh_description": description,
                "zh_brand": (override or {}).get("brand") or localize_terms(product["brand"]),
                "zh_store_category": (override or {}).get("store_category") or product["store_category"],
                "zh_specifications": specs,
                "localization_method": method,
            }
        )
        localized.append(localized_product)

    docs = args.output_root / "documents"
    docs.mkdir(parents=True, exist_ok=True)
    for product in localized:
        (docs / f"{product['icecat_id']}.md").write_text(markdown(product), encoding="utf-8", newline="\n")
    write_jsonl(args.output_root / "artifacts" / "products_zh.jsonl", localized)
    (args.output_root / "manifest.json").write_text(
        json.dumps(
            {
                "dataset": "digital_store_v1_icecat_pilot_zh",
                "source_dataset": "digital_store_v1_icecat_pilot_seed",
                "product_count": len(localized),
                "language": "zh-CN",
                "localization_method": "curated pilot translation where available; template localization plus controlled glossary otherwise",
                "note": "Local output only; not imported into Ragent yet.",
            },
            ensure_ascii=False,
            indent=2,
        )
        + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(f"Built {len(localized)} Chinese product documents at {args.output_root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
