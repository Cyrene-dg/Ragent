#!/usr/bin/env python3
import argparse
import json
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple

import requests


def normalize_text(value: Optional[str]) -> Optional[str]:
    if value is None:
        return None
    # Workaround: backend currently rejects U+00B7 in intent/mapping tables.
    return value.replace("·", "")


def clamp_text(value: Optional[str], limit: int) -> Optional[str]:
    if value is None:
        return None
    return value[:limit]


def normalize_examples(raw: Any) -> List[str]:
    if raw is None:
        return []
    if isinstance(raw, str):
        try:
            parsed = json.loads(raw)
            if isinstance(parsed, list):
                raw = parsed
            else:
                raw = [raw]
        except Exception:
            raw = [raw]
    if not isinstance(raw, list):
        raw = [raw]
    out: List[str] = []
    for item in raw:
        text = normalize_text(str(item))
        if text:
            out.append(text)
    return out


class ApiClient:
    def __init__(self, base_url: str):
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()

    def request(
        self,
        method: str,
        path: str,
        token: Optional[str] = None,
        json_body: Optional[Dict[str, Any]] = None,
        params: Optional[Dict[str, Any]] = None,
        timeout: int = 30,
    ) -> Dict[str, Any]:
        headers = {}
        if token:
            headers["Authorization"] = token
        resp = self.session.request(
            method=method,
            url=f"{self.base_url}/{path.lstrip('/')}",
            headers=headers,
            json=json_body,
            params=params,
            timeout=timeout,
        )
        resp.raise_for_status()
        text = (resp.text or "").strip()
        if not text:
            return {"code": "0", "message": None, "data": None}
        data = resp.json()
        if not isinstance(data, dict):
            raise RuntimeError(f"Unexpected API response at {path}: {data!r}")
        return data

    @staticmethod
    def ok(api_resp: Dict[str, Any]) -> bool:
        return str(api_resp.get("code")) == "0"


def flatten_tree(nodes: Iterable[Dict[str, Any]]) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    stack = list(nodes)
    while stack:
        node = stack.pop()
        out.append(node)
        children = node.get("children") or []
        stack.extend(children)
    return out


def load_seed(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def sync_intents(
    api: ApiClient,
    token: str,
    kb_id: str,
    intent_seed: List[Dict[str, Any]],
) -> Tuple[int, int, int]:
    tree_resp = api.request("GET", "/intent-tree/trees", token=token, timeout=60)
    tree_data = tree_resp.get("data") or []
    existing_nodes = flatten_tree(tree_data)
    existing_by_code = {str(node.get("intentCode")): node for node in existing_nodes if node.get("intentCode")}

    created = 0
    updated = 0
    failed = 0

    ordered = sorted(intent_seed, key=lambda x: (int(x.get("level", 0)), int(x.get("sortOrder", 0))))

    for node in ordered:
        code = str(node.get("intentCode") or "").strip()
        if not code:
            continue

        desired_name = clamp_text(normalize_text(str(node.get("name") or "")), 60) or ""
        desired_desc = clamp_text(normalize_text(str(node.get("description") or "")), 500) or ""
        desired_examples = normalize_examples(node.get("examples"))
        desired_level = int(node.get("level") or 0)
        desired_parent = node.get("parentCode")
        desired_topk = node.get("topK")
        desired_kind = int(node.get("kind") or 0)
        desired_sort = int(node.get("sortOrder") or 0)
        desired_enabled = int(node.get("enabled") or 1)

        create_body = {
            "kbId": kb_id,
            "intentCode": code,
            "name": desired_name,
            "level": desired_level,
            "parentCode": desired_parent,
            "description": desired_desc,
            "examples": desired_examples,
            "topK": desired_topk,
            "kind": desired_kind,
            "sortOrder": desired_sort,
            "enabled": desired_enabled,
        }

        existing = existing_by_code.get(code)
        if existing is None:
            resp = api.request("POST", "/intent-tree", token=token, json_body=create_body)
            if api.ok(resp):
                created += 1
                continue
            failed += 1
            print(f"[intent:create:fail] code={code} message={resp.get('message')}")
            continue

        existing_examples = normalize_examples(existing.get("examples"))
        update_needed = (
            str(existing.get("name") or "") != desired_name
            or int(existing.get("level") or 0) != desired_level
            or (existing.get("parentCode") or None) != (desired_parent or None)
            or str(existing.get("description") or "") != desired_desc
            or existing_examples != desired_examples
            or (existing.get("topK")) != desired_topk
            or int(existing.get("kind") or 0) != desired_kind
            or int(existing.get("sortOrder") or 0) != desired_sort
            or int(existing.get("enabled") or 0) != desired_enabled
        )
        if not update_needed:
            continue

        update_body = {
            "name": desired_name,
            "level": desired_level,
            "parentCode": desired_parent,
            "description": desired_desc,
            "examples": desired_examples,
            "topK": desired_topk,
            "kind": desired_kind,
            "sortOrder": desired_sort,
            "enabled": desired_enabled,
        }
        resp = api.request(
            "PUT",
            f"/intent-tree/{existing.get('id')}",
            token=token,
            json_body=update_body,
        )
        if api.ok(resp):
            updated += 1
            continue
        failed += 1
        print(f"[intent:update:fail] code={code} id={existing.get('id')} message={resp.get('message')}")

    return created, updated, failed


def fetch_all_mappings(api: ApiClient, token: str) -> List[Dict[str, Any]]:
    out: List[Dict[str, Any]] = []
    current = 1
    size = 500
    while True:
        resp = api.request(
            "GET",
            "/mappings",
            token=token,
            params={"current": current, "size": size},
            timeout=60,
        )
        data = resp.get("data") or {}
        records = data.get("records") or []
        if not records:
            break
        out.extend(records)
        pages = int(data.get("pages") or 1)
        if current >= pages:
            break
        current += 1
    return out


def sync_mappings(
    api: ApiClient,
    token: str,
    mapping_seed: List[Dict[str, Any]],
) -> Tuple[int, int]:
    existing = fetch_all_mappings(api, token)
    existing_keys = set()
    for item in existing:
        source = normalize_text(str(item.get("sourceTerm") or "")) or ""
        target = normalize_text(str(item.get("targetTerm") or "")) or ""
        key = f"{source.lower()}|{target}"
        existing_keys.add(key)

    created = 0
    failed = 0
    for item in mapping_seed:
        source = clamp_text(normalize_text(str(item.get("sourceTerm") or "").strip()), 120) or ""
        target = clamp_text(normalize_text(str(item.get("targetTerm") or "").strip()), 120) or ""
        if not source or not target or source == target:
            continue
        key = f"{source.lower()}|{target}"
        if key in existing_keys:
            continue

        body = {
            "sourceTerm": source,
            "targetTerm": target,
            "matchType": int(item.get("matchType") or 1),
            "priority": int(item.get("priority") or 0),
            "enabled": bool(item.get("enabled", True)),
            "remark": normalize_text(str(item.get("remark") or "")),
        }
        resp = api.request("POST", "/mappings", token=token, json_body=body)
        if api.ok(resp):
            created += 1
            existing_keys.add(key)
            continue
        failed += 1
        print(
            "[mapping:create:fail] "
            f"source={source} target={target} message={resp.get('message')}"
        )
    return created, failed


def resolve_kb_id(api: ApiClient, token: str, kb_name: str) -> str:
    resp = api.request(
        "GET",
        "/knowledge-base",
        token=token,
        params={"current": 1, "size": 500, "name": kb_name},
    )
    data = resp.get("data") or {}
    records = data.get("records") or []
    for kb in records:
        if str(kb.get("name")) == kb_name:
            return str(kb.get("id"))
    raise RuntimeError(f"Knowledge base not found: {kb_name}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Reconcile Genshin seed into Ragent.")
    parser.add_argument("--api-base-url", default="http://localhost:9090/api/ragent")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin")
    parser.add_argument("--seed-root", default="resources/genshin_seed")
    parser.add_argument("--knowledge-base-name", default="genshin_kb_bailian_v4")
    args = parser.parse_args()

    seed_root = Path(args.seed_root)
    intent_path = seed_root / "config" / "intent_nodes.json"
    mapping_path = seed_root / "config" / "query_term_mappings.json"
    if not intent_path.exists():
        raise FileNotFoundError(f"Missing seed file: {intent_path}")
    if not mapping_path.exists():
        raise FileNotFoundError(f"Missing seed file: {mapping_path}")

    api = ApiClient(args.api_base_url)
    login_resp = api.request(
        "POST",
        "/auth/login",
        json_body={"username": args.username, "password": args.password},
    )
    if not api.ok(login_resp):
        raise RuntimeError(f"Login failed: {login_resp}")
    token = str((login_resp.get("data") or {}).get("token") or "")
    if not token:
        raise RuntimeError("Login succeeded but token is empty")

    kb_id = resolve_kb_id(api, token, args.knowledge_base_name)
    intents = load_seed(intent_path)
    mappings = load_seed(mapping_path)

    created_intent, updated_intent, failed_intent = sync_intents(api, token, kb_id, intents)
    created_mapping, failed_mapping = sync_mappings(api, token, mappings)

    print("Reconcile complete.")
    print(
        f"Intent: created={created_intent} updated={updated_intent} failed={failed_intent}; "
        f"Mapping: created={created_mapping} failed={failed_mapping}"
    )


if __name__ == "__main__":
    main()
