#!/usr/bin/env python3
"""Download a bounded product manifest from Icecat without media assets."""

from __future__ import annotations

import argparse
import base64
import concurrent.futures
import hashlib
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree


DEFAULT_PRODUCTS = Path("external_data/digital_store_v1/catalog_discovery/common_products.json")
DEFAULT_OUTPUT_ROOT = Path("external_data/digital_store_v1/catalog_raw")
MAX_PRODUCTS = 1600
MAX_PRODUCT_BYTES = 5 * 1024 * 1024


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


def read_products(path: Path, limit: int) -> list[dict]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    rows = payload.get("products") or []
    if not isinstance(rows, list):
        raise ValueError("products manifest must contain a products array")
    return rows[:limit]


def fetch_one(product: dict, output_root: Path, authorization: str) -> dict:
    icecat_id = str(product["icecat_id"])
    destination = output_root / "xml" / f"{icecat_id}.xml"
    url = f"https://data.icecat.biz/export/freexml/EN/{icecat_id}.xml"
    if destination.exists() and destination.stat().st_size > 0:
        body = destination.read_bytes()
        return {**product, "source_url": url, "file": f"xml/{icecat_id}.xml", "bytes": len(body), "sha256": hashlib.sha256(body).hexdigest(), "cached": True}
    request = urllib.request.Request(url, headers={"Authorization": authorization, "User-Agent": "ragent-digital-store/0.1", "Accept": "application/xml"})
    try:
        with urllib.request.urlopen(request, timeout=35) as response:
            length = int(response.headers.get("Content-Length") or 0)
            if length > MAX_PRODUCT_BYTES:
                raise RuntimeError(f"response too large: {length}")
            body = response.read(MAX_PRODUCT_BYTES + 1)
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f"HTTP {exc.code}") from exc
    if len(body) > MAX_PRODUCT_BYTES or not body.lstrip().startswith(b"<?xml"):
        raise RuntimeError("not a safe Icecat XML datasheet")
    root = ElementTree.fromstring(body)
    if root.find("Product") is None:
        raise RuntimeError("response has no Product node")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(".xml.part")
    temporary.write_bytes(body)
    temporary.replace(destination)
    return {**product, "source_url": url, "file": f"xml/{icecat_id}.xml", "bytes": len(body), "sha256": hashlib.sha256(body).hexdigest(), "cached": False}


def main() -> int:
    parser = argparse.ArgumentParser(description="Download a selected common Icecat catalog without images/PDFs.")
    parser.add_argument("--products", type=Path, default=DEFAULT_PRODUCTS)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument("--max-products", type=int, default=MAX_PRODUCTS)
    parser.add_argument("--workers", type=int, default=12)
    parser.add_argument("--prompt-credentials", action="store_true")
    parser.add_argument("--download", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.max_products <= MAX_PRODUCTS:
        parser.error(f"--max-products must be between 1 and {MAX_PRODUCTS}")
    if not 1 <= args.workers <= 16:
        parser.error("--workers must be between 1 and 16")
    products = read_products(args.products, args.max_products)
    print(f"Catalog plan: {len(products)} product sheets, workers={args.workers}, output={args.output_root}")
    if not args.download:
        print("Dry run only. Add --download to fetch XML sheets.")
        return 0
    username, password = credentials(args.prompt_credentials)
    authorization = "Basic " + base64.b64encode(f"{username}:{password}".encode()).decode()
    successes: list[dict] = []
    failures: list[dict] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = {executor.submit(fetch_one, item, args.output_root, authorization): item for item in products}
        for count, future in enumerate(concurrent.futures.as_completed(futures), start=1):
            item = futures[future]
            try:
                successes.append(future.result())
            except Exception as exc:
                failures.append({"icecat_id": item.get("icecat_id"), "error": str(exc)})
            if count % 100 == 0 or count == len(products):
                print(f"progress={count}/{len(products)}, success={len(successes)}, failed={len(failures)}", flush=True)
    successes.sort(key=lambda row: str(row["icecat_id"]))
    args.output_root.mkdir(parents=True, exist_ok=True)
    manifest = {
        "dataset": "digital_store_v1_common_openicecat_raw",
        "source": "Open Icecat authenticated XML endpoint",
        "retrieved_at": datetime.now(timezone.utc).isoformat(),
        "product_count": len(successes),
        "failed_count": len(failures),
        "records": successes,
        "failures": failures,
    }
    (args.output_root / "download_manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(f"Downloaded {len(successes)} XML sheets; failures={len(failures)}")
    return 0 if successes else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(2) from exc
