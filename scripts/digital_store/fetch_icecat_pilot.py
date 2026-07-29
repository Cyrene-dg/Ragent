#!/usr/bin/env python3
"""Download a deliberately small, authenticated Icecat pilot dataset.

This is a source-data acquisition step only.  It never calls the Ragent API,
Milvus, Elasticsearch, or an embedding model.  The downloaded XML and its
runtime manifest live under ``external_data/`` and are intentionally ignored by
Git, because the files are third-party licensed source material.

Safety defaults:
* dry-run unless --download is present;
* no more than ten product sheets per invocation;
* no more than 5 MiB per sheet;
* requests are serial, with a small delay, rather than an index-file scrape.
"""

from __future__ import annotations

import argparse
import base64
import getpass
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from xml.etree import ElementTree


DEFAULT_MANIFEST = Path("scripts/digital_store/icecat_pilot_products.json")
DEFAULT_OUTPUT_ROOT = Path("external_data/digital_store_v1/icecat_raw")
MAX_PILOT_PRODUCTS = 10
MAX_PRODUCT_BYTES = 5 * 1024 * 1024
DEFAULT_LANGUAGE = "EN"


def load_products(path: Path) -> list[dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    products = payload.get("products") if isinstance(payload, dict) else payload
    if not isinstance(products, list) or not products:
        raise ValueError(f"{path} must contain a non-empty products array")

    result: list[dict[str, Any]] = []
    for index, product in enumerate(products, start=1):
        icecat_id = str((product or {}).get("icecat_id") or "").strip()
        if not icecat_id.isdigit():
            raise ValueError(f"product #{index} has an invalid icecat_id: {icecat_id!r}")
        result.append(dict(product))
    return result


def credentials(prompt: bool) -> tuple[str, str]:
    username = os.getenv("ICECAT_USERNAME", "").strip()
    password = os.getenv("ICECAT_PASSWORD", "")
    if prompt and not username:
        username = input("Icecat username or e-mail: ").strip()
    if prompt and not password:
        password = getpass.getpass("Icecat password (input is hidden): ")
    if not username or not password:
        raise RuntimeError(
            "Missing Icecat credentials. Set ICECAT_USERNAME and ICECAT_PASSWORD "
            "for this PowerShell session, or re-run with --prompt-credentials."
        )
    return username, password


def product_url(icecat_id: str, language: str) -> str:
    return f"https://data.icecat.biz/export/freexml/{language}/{icecat_id}.xml"


def make_request(url: str, username: str, password: str) -> urllib.request.Request:
    token = base64.b64encode(f"{username}:{password}".encode("utf-8")).decode("ascii")
    return urllib.request.Request(
        url,
        headers={
            "Authorization": f"Basic {token}",
            "User-Agent": "ragent-digital-store-pilot/0.1 (+local development)",
            "Accept": "application/xml,text/xml;q=0.9,*/*;q=0.1",
        },
    )


def download_xml(url: str, username: str, password: str, max_bytes: int) -> bytes:
    request = make_request(url, username, password)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            header_size = response.headers.get("Content-Length")
            if header_size and int(header_size) > max_bytes:
                raise RuntimeError(f"Icecat response exceeds {max_bytes} bytes: {header_size}")
            body = response.read(max_bytes + 1)
    except urllib.error.HTTPError as exc:
        if exc.code == 401:
            raise RuntimeError("Icecat rejected the credentials (HTTP 401).") from exc
        raise RuntimeError(f"Icecat request failed with HTTP {exc.code}: {url}") from exc
    except urllib.error.URLError as exc:
        raise RuntimeError(f"Icecat request could not be completed: {exc.reason}") from exc

    if len(body) > max_bytes:
        raise RuntimeError(f"Icecat response exceeds {max_bytes} bytes: {url}")
    if not body.lstrip().startswith(b"<?xml"):
        raise RuntimeError(f"Icecat did not return XML for {url}")
    try:
        ElementTree.fromstring(body)
    except ElementTree.ParseError as exc:
        raise RuntimeError(f"Icecat returned malformed XML for {url}") from exc
    return body


def safe_write(path: Path, content: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".part")
    temporary.write_bytes(content)
    temporary.replace(path)


def main() -> int:
    parser = argparse.ArgumentParser(description="Fetch at most ten authenticated Icecat XML datasheets.")
    parser.add_argument("--products", type=Path, default=DEFAULT_MANIFEST, help="JSON product manifest")
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT, help="ignored local raw-data directory")
    parser.add_argument("--language", default=DEFAULT_LANGUAGE, help="Icecat language code, default: EN")
    parser.add_argument("--max-products", type=int, default=MAX_PILOT_PRODUCTS, help="hard maximum: 10")
    parser.add_argument("--max-bytes-per-product", type=int, default=MAX_PRODUCT_BYTES, help="response safety limit")
    parser.add_argument("--delay-seconds", type=float, default=0.4, help="delay between product requests")
    parser.add_argument("--prompt-credentials", action="store_true", help="prompt locally instead of requiring env vars")
    parser.add_argument("--download", action="store_true", help="perform requests; otherwise print the plan only")
    args = parser.parse_args()

    if args.max_products < 1 or args.max_products > MAX_PILOT_PRODUCTS:
        parser.error(f"--max-products must be between 1 and {MAX_PILOT_PRODUCTS}; this script is pilot-only")
    if args.max_bytes_per_product < 1024 or args.max_bytes_per_product > MAX_PRODUCT_BYTES:
        parser.error(f"--max-bytes-per-product must be between 1024 and {MAX_PRODUCT_BYTES}")

    products = load_products(args.products)[: args.max_products]
    language = args.language.strip().upper()
    print(f"Pilot plan: {len(products)} product sheets, language={language}, output={args.output_root}")
    for product in products:
        print(f"  - {product['icecat_id']}: {product.get('label', 'unnamed product')}")

    if not args.download:
        print("Dry run only. Add --download to fetch these sheets.")
        return 0

    username, password = credentials(args.prompt_credentials)
    records: list[dict[str, Any]] = []
    for position, product in enumerate(products, start=1):
        icecat_id = str(product["icecat_id"])
        url = product_url(icecat_id, language)
        print(f"[{position}/{len(products)}] downloading Icecat ID {icecat_id} ...", flush=True)
        body = download_xml(url, username, password, args.max_bytes_per_product)
        filename = f"{icecat_id}.xml"
        safe_write(args.output_root / "xml" / filename, body)
        records.append(
            {
                "icecat_id": icecat_id,
                "label": product.get("label"),
                "category_hint": product.get("category_hint"),
                "source_url": url,
                "file": f"xml/{filename}",
                "bytes": len(body),
                "sha256": hashlib.sha256(body).hexdigest(),
                "retrieved_at": datetime.now(timezone.utc).isoformat(),
            }
        )
        if position < len(products):
            time.sleep(args.delay_seconds)

    output_manifest = {
        "dataset": "digital_store_v1_icecat_pilot",
        "source": "Icecat Open Catalog authenticated XML endpoint",
        "language": language,
        "product_count": len(records),
        "records": records,
    }
    safe_write(
        args.output_root / "download_manifest.json",
        (json.dumps(output_manifest, ensure_ascii=False, indent=2) + "\n").encode("utf-8"),
    )
    print(f"Downloaded {len(records)} sheets. Raw data remains local at {args.output_root}.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        raise SystemExit(2) from exc
