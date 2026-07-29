#!/usr/bin/env python3
"""Rebuild a trustworthy raw-download manifest from already cached Icecat XML."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree


DEFAULT_PRODUCTS = Path("external_data/digital_store_v1/catalog_discovery/common_products.json")
DEFAULT_RAW_ROOT = Path("external_data/digital_store_v1/catalog_raw")


def is_valid_datasheet(path: Path) -> bool:
    try:
        root = ElementTree.parse(path).getroot()
        return root.find("Product") is not None
    except (OSError, ElementTree.ParseError):
        return False


def main() -> int:
    parser = argparse.ArgumentParser(description="Rebuild the Open Icecat manifest from cached XML files.")
    parser.add_argument("--products", type=Path, default=DEFAULT_PRODUCTS)
    parser.add_argument("--raw-root", type=Path, default=DEFAULT_RAW_ROOT)
    args = parser.parse_args()

    source = json.loads(args.products.read_text(encoding="utf-8"))
    candidates = {str(item["icecat_id"]): item for item in source.get("products", [])}
    records: list[dict] = []
    invalid: list[dict] = []
    for path in sorted((args.raw_root / "xml").glob("*.xml"), key=lambda item: int(item.stem)):
        candidate = candidates.get(path.stem)
        if candidate is None or not is_valid_datasheet(path):
            invalid.append({"file": str(path.relative_to(args.raw_root)).replace("\\", "/"), "reason": "unknown candidate or invalid XML"})
            continue
        body = path.read_bytes()
        records.append({
            **candidate,
            "source_url": f"https://data.icecat.biz/export/freexml/EN/{path.stem}.xml",
            "file": str(path.relative_to(args.raw_root)).replace("\\", "/"),
            "bytes": len(body),
            "sha256": hashlib.sha256(body).hexdigest(),
            "cached": True,
        })
    manifest = {
        "dataset": "digital_store_v1_common_openicecat_raw",
        "source": "Open Icecat authenticated XML endpoint",
        "retrieved_at": datetime.now(timezone.utc).isoformat(),
        "product_count": len(records),
        "failed_count": len(candidates) - len(records),
        "records": records,
        "invalid_cached_files": invalid,
        "notes": "Manifest reconstructed from atomically cached XML after a bounded public-endpoint download.",
    }
    destination = args.raw_root / "download_manifest.json"
    destination.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"Validated {len(records)} cached XML sheets; ignored={len(invalid)}; manifest={destination}")
    return 0 if records else 2


if __name__ == "__main__":
    raise SystemExit(main())
