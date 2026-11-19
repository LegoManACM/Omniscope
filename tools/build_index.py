#!/usr/bin/env python3
"""Validate every plugin manifest and (re)build plugins/index.json, the catalogue the app reads.

    python tools/build_index.py           # validate and write plugins/index.json
    python tools/build_index.py --check   # validate and fail if index.json is out of date (CI)

The checks mirror the app's (android/.../core/Manifest.kt): a manifest the app would refuse never gets listed.
"""
import hashlib
import json
import pathlib
import re
import sys
from urllib.parse import urlparse

ROOT = pathlib.Path(__file__).resolve().parent.parent
PLUGINS = ROOT / "plugins"
INDEX = PLUGINS / "index.json"

CATEGORIES = {"conditions", "places", "history", "buildings", "nature", "terrain", "sky", "transport", "hazards",
              "surveillance", "media", "scan"}
ICONS = {"airplane-tilt", "barcode", "binoculars", "book-open", "broadcast", "buildings", "butterfly", "camera", "cloud-sun",
         "compass", "crosshair", "detective", "eye", "globe", "house", "image", "info", "key", "leaf", "link", "map-pin",
         "map-trifold", "moon", "mountains", "navigation-arrow", "planet", "radio", "scan", "security-camera", "storefront",
         "sun", "tag", "text-t", "warning", "webcam", "wind"}
COSTS = {"free", "freemium", "paid"}
FRESHNESS = {"live", "slow", "static"}
OVERPASS_HOSTS = ["overpass-api.de", "overpass.private.coffee"]
INDEX_FIELDS = ["id", "name", "version", "description", "author", "category", "icon", "cost", "tags", "native"]


def sha256(text: str) -> str:
    return hashlib.sha256(text.replace("\r\n", "\n").encode("utf-8")).hexdigest()


def problems(m: dict, official: bool) -> list:
    p = []
    if m.get("format", 1) != 1:
        p.append(f"unsupported format {m.get('format')}")
    if not re.fullmatch(r"[a-z0-9_]{2,40}", str(m.get("id", ""))):
        p.append("id must be 2-40 lowercase letters, digits or _")
    for f in ("name", "description", "category"):
        if not m.get(f):
            p.append(f"{f} is required")
    if str(m.get("category", "")).lower() not in CATEGORIES:
        p.append(f"unknown category {m.get('category')!r} (one of {', '.join(sorted(CATEGORIES))})")
    if m.get("icon") and m["icon"] not in ICONS:
        p.append(f"unknown icon {m['icon']!r} (see docs/PLUGINS.md)")
    if m.get("cost", "free") not in COSTS:
        p.append(f"cost must be one of {sorted(COSTS)}")
    if m.get("freshness", "slow") not in FRESHNESS:
        p.append(f"freshness must be one of {sorted(FRESHNESS)}")
    if not isinstance(m.get("version", 1), int) or m.get("version", 1) < 1:
        p.append("version must be a positive integer")
    r = m.get("radius")
    if r is not None and not (r.get("min", 10) <= r.get("default", 0) <= r.get("max", r.get("default", 0))):
        p.append("radius must satisfy min <= default <= max")
    if m.get("native"):
        if not official:
            p.append("only official plugins can be native (built into the app); community plugins are JSON-only")
        return p
    req = m.get("request")
    if not isinstance(req, dict):
        return p + ["request is required"]
    urls = list(req.get("urls", [])) + ([req["url"]] if req.get("url") else [])
    if not urls and not req.get("overpass"):
        p.append("request needs url, urls or overpass")
    hosts = m.get("hosts", [])
    for u in urls:
        if not u.startswith("https://"):
            p.append(f"request URLs must be https: {u}")
        literal = u.split("{", 1)[0]
        host = urlparse(literal if literal.count("/") >= 3 else literal + "/").hostname
        if not host:
            p.append(f"request URL must start with a literal host: {u}")
        elif host not in hosts:
            p.append(f"host {host} is not listed in hosts")
    item = m.get("item", {})
    if "title" not in item:
        p.append("item.title is required")
    if ("lat" in item) != ("lon" in item):
        p.append("item needs both lat and lon, or neither")
    for key in ("fields", "links"):
        for pair in item.get(key, []):
            if not (isinstance(pair, list) and len(pair) == 2 and all(isinstance(x, str) for x in pair)):
                p.append(f"item.{key} entries must be [\"Label\", \"expression\"] pairs")
                break
    return p


def main() -> int:
    check = "--check" in sys.argv
    entries, errors, seen = [], [], {}
    for path in sorted(PLUGINS.glob("*/*/plugin.json")):
        group = path.parent.parent.name
        if group not in ("official", "community"):
            errors.append(f"{path.relative_to(ROOT)}: plugins go in plugins/official/ or plugins/community/")
            continue
        text = path.read_text(encoding="utf-8")
        try:
            m = json.loads(text)
        except json.JSONDecodeError as e:
            errors.append(f"{path.relative_to(ROOT)}: invalid JSON: {e}")
            continue
        rel = path.relative_to(ROOT).as_posix()
        for msg in problems(m, official=group == "official"):
            errors.append(f"{rel}: {msg}")
        if path.parent.name != m.get("id"):
            errors.append(f"{rel}: folder name must equal the id ({m.get('id')})")
        if m.get("id") in seen:
            errors.append(f"{rel}: id {m.get('id')} is already used by {seen[m.get('id')]}")
        seen[m.get("id")] = rel
        entry = {k: m[k] for k in INDEX_FIELDS if k in m}
        entry.update(official=group == "official", path=rel, sha256=sha256(text),
                     hosts=list(m.get("hosts", [])) + (OVERPASS_HOSTS if m.get("request", {}).get("overpass") else []))
        entries.append(entry)

    if errors:
        print("Plugin problems:\n  " + "\n  ".join(errors))
        return 1

    index = json.dumps({"format": 1, "plugins": entries}, indent=2, ensure_ascii=False) + "\n"
    current = INDEX.read_text(encoding="utf-8").replace("\r\n", "\n") if INDEX.exists() else ""
    if check:
        if current != index:
            print("plugins/index.json is out of date: run python tools/build_index.py and commit the result.")
            return 1
        print(f"OK: {len(entries)} plugins, index up to date.")
        return 0
    INDEX.write_text(index, encoding="utf-8", newline="\n")
    print(f"Wrote {INDEX.relative_to(ROOT)} with {len(entries)} plugins.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
