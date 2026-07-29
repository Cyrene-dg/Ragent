#!/usr/bin/env python3
"""Convert locally downloaded Icecat XML sheets into inspectable product docs.

The output is deliberately kept beneath ``external_data/``.  This makes the
first data-pipeline loop reproducible without committing third-party product
text into the source repository or importing anything into the RAG services.
"""

from __future__ import annotations

import argparse
import json
import re
from collections import OrderedDict
from pathlib import Path
from typing import Any
from xml.etree import ElementTree


DEFAULT_RAW_ROOT = Path("external_data/digital_store_v1/icecat_raw")
DEFAULT_OUTPUT_ROOT = Path("external_data/digital_store_v1/derived_seed")
MAX_SPECS_PER_PRODUCT = 60


def clean_text(value: str | None) -> str:
    return re.sub(r"\s+", " ", (value or "").strip())


def load_manifest(raw_root: Path) -> dict[str, Any]:
    path = raw_root / "download_manifest.json"
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload.get("records"), list):
        raise ValueError(f"{path} does not contain a records array")
    return payload


def xml_product(path: Path) -> ElementTree.Element:
    root = ElementTree.parse(path).getroot()
    product = root.find("Product")
    if product is None:
        raise ValueError(f"{path} does not contain an Icecat Product node")
    return product


def extract_specs(product: ElementTree.Element) -> list[dict[str, str]]:
    """Keep user-facing feature names and presentation values, not raw IDs."""
    specs: OrderedDict[str, str] = OrderedDict()
    for feature in product.findall("ProductFeature"):
        name = clean_text(feature.findtext("Feature/Name[@Value]") or "")
        if not name:
            name_node = feature.find("Feature/Name")
            name = clean_text(name_node.get("Value") if name_node is not None else "")
        value = clean_text(feature.get("Presentation_Value") or feature.get("Value"))
        if name and value and name not in specs:
            specs[name] = value
        if len(specs) >= MAX_SPECS_PER_PRODUCT:
            break
    return [{"name": name, "value": value} for name, value in specs.items()]


def extract_description(product: ElementTree.Element) -> dict[str, str]:
    description = product.find("ProductDescription")
    if description is None:
        return {"short": "", "middle": "", "long": "", "warranty": ""}
    return {
        "short": clean_text(description.get("ShortDesc")),
        "middle": clean_text(description.get("MiddleDesc")),
        "long": clean_text(description.get("LongDesc")),
        "warranty": clean_text(description.get("WarrantyInfo")),
    }


def slug(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", value.lower()).strip("-") or "product"


def product_record(source: dict[str, Any], raw_root: Path) -> dict[str, Any]:
    product = xml_product(raw_root / str(source["file"]))
    supplier = product.find("Supplier")
    brand = clean_text(supplier.get("Name") if supplier is not None else "") or "Unknown"
    title = clean_text(product.get("Title") or product.get("Name"))
    specs = extract_specs(product)
    description = extract_description(product)
    icecat_id = str(source["icecat_id"])
    return {
        "product_id": f"icecat:{icecat_id}",
        "icecat_id": icecat_id,
        "product_code": clean_text(product.get("Prod_id")),
        "brand": brand,
        "title": title,
        "store_category": source.get("category_hint") or "uncategorized",
        "source_url": source["source_url"],
        "source_sha256": source["sha256"],
        "description": description,
        "specifications": specs,
    }


def markdown(product: dict[str, Any]) -> str:
    lines = [
        f"# {product['title']}",
        "",
        "## 商品身份",
        f"- 品牌：{product['brand']}",
        f"- 店铺类目（试点映射）：{product['store_category']}",
        f"- 商品编码：{product['product_code'] or '未提供'}",
        f"- 数据源产品 ID：{product['icecat_id']}",
        "",
    ]
    description = product["description"]
    summary = description["long"] or description["middle"] or description["short"]
    if summary:
        lines.extend(["## 商品说明", summary, ""])
    if description["warranty"]:
        lines.extend(["## 保修说明", description["warranty"], ""])
    lines.extend(["## 规格参数"])
    if product["specifications"]:
        lines.extend(f"- {spec['name']}：{spec['value']}" for spec in product["specifications"])
    else:
        lines.append("- 数据源未提供可用规格")
    lines.extend(["", "## 数据来源", f"- Icecat XML：{product['source_url']}"])
    return "\n".join(lines) + "\n"


def write_jsonl(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")))
            handle.write("\n")


def main() -> int:
    parser = argparse.ArgumentParser(description="Build local product documents from the Icecat pilot XML.")
    parser.add_argument("--raw-root", type=Path, default=DEFAULT_RAW_ROOT)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    args = parser.parse_args()

    manifest = load_manifest(args.raw_root)
    products = [product_record(record, args.raw_root) for record in manifest["records"]]
    documents_dir = args.output_root / "documents"
    documents_dir.mkdir(parents=True, exist_ok=True)
    for product in products:
        filename = f"{product['icecat_id']}-{slug(product['title'])}.md"
        (documents_dir / filename).write_text(markdown(product), encoding="utf-8", newline="\n")

    write_jsonl(args.output_root / "artifacts" / "products.jsonl", products)
    output_manifest = {
        "dataset": "digital_store_v1_icecat_pilot_seed",
        "source_dataset": manifest.get("dataset"),
        "product_count": len(products),
        "documents_dir": "documents",
        "products_file": "artifacts/products.jsonl",
        "note": "Local derived output only; not imported into Ragent yet.",
    }
    (args.output_root / "manifest.json").write_text(
        json.dumps(output_manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n"
    )
    print(f"Built {len(products)} local product documents at {args.output_root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
