#!/usr/bin/env python3
"""Select a bounded, popular consumer-electronics catalog from Open Icecat.

The Open Icecat index is streamed once and never saved.  Selection is limited
to common, on-market consumer categories and ranks candidates by Icecat product
views.  The resulting small manifest is local input for the downloader.
"""

from __future__ import annotations

import argparse
import base64
import csv
import gzip
import heapq
import json
import os
import urllib.request
from pathlib import Path


INDEX_URL = "https://data.icecat.biz/export/freexml/EN/files.index.csv.gz"
DEFAULT_OUTPUT = Path("external_data/digital_store_v1/catalog_discovery/common_products.json")

# Quotas total 2,500 products.  The later seed builder has an independent
# 8,000-chunk hard cap, so this is an input target rather than a promise.
CATEGORY_PLAN = {
    "1893": (300, "智能手机"),
    "897": (180, "平板电脑"),
    "151": (380, "笔记本电脑"),
    "222": (160, "显示器"),
    "8763": (120, "显示器"),
    "3982": (180, "无线路由器"),
    "1637": (260, "耳机"),
    "1399": (160, "充电器"),
    "829": (80, "充电器"),
    "2778": (160, "移动电源"),
    "2315": (100, "音箱"),
    "8786": (80, "音箱"),
    "2647": (180, "智能手表"),
    "1437": (70, "空气净化器"),
    "2588": (90, "扫地机器人"),
}


def credentials(prompt: bool) -> tuple[str, str]:
    username = os.getenv("ICECAT_USERNAME", "").strip()
    password = os.getenv("ICECAT_PASSWORD", "")
    if prompt and not username:
        username = input("Icecat username or e-mail: ").strip()
    if prompt and not password:
        import getpass
        password = getpass.getpass("Icecat password (input is hidden): ")
    if not username or not password:
        raise RuntimeError("Set ICECAT_USERNAME/ICECAT_PASSWORD or use --prompt-credentials.")
    return username, password


def request(username: str, password: str) -> urllib.request.Request:
    token = base64.b64encode(f"{username}:{password}".encode()).decode()
    return urllib.request.Request(INDEX_URL, headers={"Authorization": f"Basic {token}", "User-Agent": "ragent-digital-store/0.1"})


def popularity(row: dict[str, str]) -> int:
    try:
        return int(row.get("product_view") or 0)
    except ValueError:
        return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Select common product IDs from the Open Icecat index.")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--prompt-credentials", action="store_true")
    parser.add_argument("--scan-index", action="store_true", help="required: streams the 247 MB compressed index once")
    args = parser.parse_args()
    if not args.scan_index:
        print(f"Dry run: would stream {INDEX_URL} and write at most {sum(limit for limit, _ in CATEGORY_PLAN.values())} selected IDs to {args.output}.")
        return 0

    username, password = credentials(args.prompt_credentials)
    heaps: dict[str, list[tuple[int, int, dict[str, str]]]] = {category_id: [] for category_id in CATEGORY_PLAN}
    sequence = 0
    with urllib.request.urlopen(request(username, password), timeout=300) as response:
        with gzip.GzipFile(fileobj=response) as stream:
            reader = csv.DictReader((line.decode("utf-8", "replace") for line in stream), delimiter="\t")
            for row in reader:
                category_id = row.get("catid") or ""
                if category_id not in CATEGORY_PLAN or row.get("on_market") != "1" or row.get("quality") != "ICECAT":
                    continue
                product_id = (row.get("product_id") or "").strip()
                if not product_id.isdigit():
                    continue
                score = popularity(row)
                sequence += 1
                heap = heaps[category_id]
                candidate = (score, sequence, row)
                limit = CATEGORY_PLAN[category_id][0]
                if len(heap) < limit:
                    heapq.heappush(heap, candidate)
                elif candidate[0] > heap[0][0]:
                    heapq.heapreplace(heap, candidate)

    selected: dict[str, dict[str, str]] = {}
    for category_id, heap in heaps.items():
        _, category_name = CATEGORY_PLAN[category_id]
        for _, _, row in heap:
            product_id = row["product_id"]
            existing = selected.get(product_id)
            if existing is None or popularity(row) > popularity(existing):
                row = dict(row)
                row["category_hint"] = category_name
                selected[product_id] = row

    products = [
        {
            "icecat_id": row["product_id"],
            "label": row.get("model_name") or row.get("prod_id") or row["product_id"],
            "category_hint": row["category_hint"],
            "product_code_hint": row.get("prod_id") or "",
            "product_view": popularity(row),
            "source_category_id": row.get("catid"),
        }
        for row in selected.values()
    ]
    products.sort(key=lambda item: (item["category_hint"], -item["product_view"], item["icecat_id"]))
    payload = {
        "dataset": "digital_store_v1_common_openicecat_selection",
        "selection_policy": "on_market=1, quality=ICECAT, common consumer categories only, top Icecat product_view per category",
        "planned_product_count": len(products),
        "products": products,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"Selected {len(products)} common product IDs at {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
