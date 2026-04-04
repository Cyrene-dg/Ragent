from __future__ import annotations

import json
import os
import re
import subprocess
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Iterable


ID_PATTERN = re.compile(r"(?i)\bID\s*[:：]\s*([A-Za-z0-9_-]{2,32})")
CN_PATTERN = re.compile(r"(?i)\bCN\s*Name\s*[:：]\s*([^\r\n]{1,60})")
EN_PATTERN = re.compile(r"(?i)\bEN\s*Name\s*[:：]\s*([^\r\n]{1,80})")


def _env(name: str, default: str) -> str:
    value = os.getenv(name)
    return value.strip() if value and value.strip() else default


@dataclass(slots=True)
class ChunkRow:
    chunk_id: str
    kb_id: str
    doc_id: str
    title: str
    content: str


def _request(url: str, method: str = "GET", body: dict | None = None, content_type: str = "application/json") -> dict:
    payload = None
    headers = {}
    if body is not None:
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url=url, method=method, headers=headers, data=payload)
    with urllib.request.urlopen(req, timeout=30) as resp:
        raw = resp.read().decode("utf-8")
        return json.loads(raw) if raw else {}


def _ensure_index(es_base: str, index: str) -> None:
    try:
        _request(f"{es_base}/{index}")
        return
    except urllib.error.HTTPError as ex:
        if ex.code != 404:
            raise
    _request(
        f"{es_base}/{index}",
        method="PUT",
        body={
            "settings": {"number_of_shards": 1, "number_of_replicas": 0},
            "mappings": {
                "properties": {
                    "chunk_id": {"type": "keyword"},
                    "kb_id": {"type": "keyword"},
                    "doc_id": {"type": "keyword"},
                    "title": {"type": "text"},
                    "content": {"type": "text"},
                    "keywords": {"type": "text"},
                    "enabled": {"type": "integer"},
                }
            },
        },
    )


def _extract_keywords(title: str, content: str) -> str:
    terms: list[str] = []

    def add_term(raw: str | None) -> None:
        if raw is None:
            return
        term = re.sub(r"\s+", " ", raw).strip()
        if not term:
            return
        if term not in terms:
            terms.append(term)
        lower = term.lower()
        if lower not in terms:
            terms.append(lower)

    add_term(title)
    for m in ID_PATTERN.finditer(content or ""):
        add_term(m.group(1))
    for m in CN_PATTERN.finditer(content or ""):
        add_term(m.group(1))
    for m in EN_PATTERN.finditer(content or ""):
        add_term(m.group(1))
    return ",".join(terms)


def _mysql_rows() -> list[ChunkRow]:
    mysql_bin = _env("KB_MYSQL_BIN", "mysql")
    host = _env("KB_MYSQL_HOST", "127.0.0.1")
    port = _env("KB_MYSQL_PORT", "3306")
    user = _env("KB_MYSQL_USER", "root")
    password = _env("KB_MYSQL_PASSWORD", "1234")
    database = _env("KB_MYSQL_DB", "ragent")
    limit = _env("KB_BACKFILL_LIMIT", "0")
    limit_clause = f" LIMIT {int(limit)}" if int(limit) > 0 else ""

    sql = (
        "SELECT c.id, c.kb_id, c.doc_id, COALESCE(d.doc_name,''), "
        "LEFT(REPLACE(REPLACE(REPLACE(c.content,'\\r',' '),'\\n',' '),'\\t',' '), 8000) "
        "FROM t_knowledge_chunk c "
        "LEFT JOIN t_knowledge_document d ON d.id = c.doc_id "
        "WHERE c.deleted = 0 AND c.enabled = 1 "
        "ORDER BY c.id" + limit_clause + ";"
    )

    cmd = [
        mysql_bin,
        "--host=" + host,
        "--port=" + port,
        "--user=" + user,
        "--password=" + password,
        "--database=" + database,
        "--batch",
        "--raw",
        "--skip-column-names",
        "-e",
        sql,
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, check=True)
    rows: list[ChunkRow] = []
    for line in result.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) < 5:
            continue
        rows.append(
            ChunkRow(
                chunk_id=parts[0].strip(),
                kb_id=parts[1].strip(),
                doc_id=parts[2].strip(),
                title=parts[3].strip(),
                content=parts[4].strip(),
            )
        )
    return rows


def _extra_genshin_keyword_docs() -> list[ChunkRow]:
    return [
        ChunkRow("genshin-kw-0001", "genshin_kw_pack", "genshin_kw_doc", "赤沙之杖别名", "主名: 赤沙之杖; 英文: Staff of the Scarlet Sands; ID: 13511",),
        ChunkRow("genshin-kw-0002", "genshin_kw_pack", "genshin_kw_doc", "阿莫斯之弓别名", "主名: 阿莫斯之弓; 英文: Amos Bow; ID: 15502",),
        ChunkRow("genshin-kw-0003", "genshin_kw_pack", "genshin_kw_doc", "若水别名", "主名: 若水; 英文: Aqua Simulacra; ID: 15508",),
        ChunkRow("genshin-kw-0004", "genshin_kw_pack", "genshin_kw_doc", "冬极白星别名", "主名: 冬极白星; 英文: Polar Star; ID: 15507",),
        ChunkRow("genshin-kw-0005", "genshin_kw_pack", "genshin_kw_doc", "飞雷之弦振别名", "主名: 飞雷之弦振; 英文: Thundering Pulse; ID: 15509",),
        ChunkRow("genshin-kw-0006", "genshin_kw_pack", "genshin_kw_doc", "终末嗟叹之诗别名", "主名: 终末嗟叹之诗; 英文: Elegy for the End; ID: 15503",),
        ChunkRow("genshin-kw-0007", "genshin_kw_pack", "genshin_kw_doc", "猎人之径别名", "主名: 猎人之径; 英文: Hunters Path; ID: 15511",),
        ChunkRow("genshin-kw-0008", "genshin_kw_pack", "genshin_kw_doc", "最初的大魔术别名", "主名: 最初的大魔术; 英文: The First Great Magic; ID: 15512",),
    ]


def _bulk_upsert(es_base: str, index: str, rows: Iterable[ChunkRow], batch_size: int = 500) -> int:
    batch: list[ChunkRow] = []
    total = 0
    for row in rows:
        batch.append(row)
        if len(batch) >= batch_size:
            total += _flush(es_base, index, batch)
            batch.clear()
    if batch:
        total += _flush(es_base, index, batch)
    return total


def _flush(es_base: str, index: str, rows: list[ChunkRow]) -> int:
    lines: list[str] = []
    for row in rows:
        lines.append(json.dumps({"index": {"_index": index, "_id": row.chunk_id}}, ensure_ascii=False))
        lines.append(
            json.dumps(
                {
                    "chunk_id": row.chunk_id,
                    "kb_id": row.kb_id,
                    "doc_id": row.doc_id,
                    "title": row.title,
                    "content": row.content,
                    "keywords": _extract_keywords(row.title, row.content),
                    "enabled": 1,
                },
                ensure_ascii=False,
            )
        )
    ndjson = ("\n".join(lines) + "\n").encode("utf-8")
    req = urllib.request.Request(
        url=f"{es_base}/_bulk?refresh=wait_for",
        method="POST",
        headers={"Content-Type": "application/x-ndjson"},
        data=ndjson,
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        result = json.loads(resp.read().decode("utf-8"))
    if result.get("errors"):
        raise RuntimeError("bulk upsert contains errors")
    return len(rows)


def main() -> None:
    es_base = _env("KB_ES_BASE", "http://localhost:9200")
    index = _env("KB_ES_INDEX", "ragent_chunk_v1")
    batch_size = int(_env("KB_ES_BATCH_SIZE", "500"))
    include_extra = _env("KB_INCLUDE_GENSHIN_KEYWORD_DOCS", "true").lower() in {"1", "true", "yes", "y"}

    _ensure_index(es_base, index)
    mysql_rows = _mysql_rows()
    indexed = _bulk_upsert(es_base, index, mysql_rows, batch_size=batch_size)
    extra_indexed = 0
    if include_extra:
        extra_rows = _extra_genshin_keyword_docs()
        extra_indexed = _bulk_upsert(es_base, index, extra_rows, batch_size=batch_size)

    count = _request(f"{es_base}/{index}/_count").get("count", 0)
    print(f"[backfill-keyword-es] mysql rows indexed={indexed}")
    print(f"[backfill-keyword-es] extra genshin docs indexed={extra_indexed}")
    print(f"[backfill-keyword-es] index={index}, total_docs={count}")


if __name__ == "__main__":
    main()

