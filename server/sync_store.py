"""Shared page snapshots and teacher marks for the tablet and MCP bridge."""
import hashlib
import json
import os
import re
import threading
from pathlib import Path

ROOT = Path(os.getenv("MATHNOTE_DATA", Path(__file__).resolve().parent / "data"))
LOCK = threading.RLock()
PAGE_ID = re.compile(r"^[a-f0-9-]{36}$")
KINDS = {"highlight", "underline", "arrow", "note"}


def _path(page_id, suffix):
    if not PAGE_ID.fullmatch(page_id):
        raise ValueError("Invalid page ID")
    ROOT.mkdir(parents=True, exist_ok=True)
    return ROOT / (page_id + suffix)


def _atomic(path, data):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(data)
    os.replace(temporary, path)


def sync_page(page_id, title, image):
    if not isinstance(title, str) or not title.strip() or len(title) > 120:
        raise ValueError("Invalid page title")
    if not image.startswith(b"\x89PNG\r\n\x1a\n") or len(image) > 3 * 1024 * 1024:
        raise ValueError("Invalid page image")
    revision = hashlib.sha256(image).hexdigest()[:20]
    with LOCK:
        _atomic(_path(page_id, ".png"), image)
        _atomic(_path(page_id, ".json"), json.dumps({"id": page_id, "title": title,
            "revision": revision, "width": 1600, "height": 1000}).encode())
    return revision


def list_pages():
    ROOT.mkdir(parents=True, exist_ok=True)
    with LOCK:
        pages = []
        for path in ROOT.glob("*.json"):
            if path.name.endswith(".marks.json"):
                continue
            try: pages.append(json.loads(path.read_text()))
            except (ValueError, OSError): pass
        return sorted(pages, key=lambda page: page["title"])


def get_page(page_id):
    with LOCK:
        metadata = json.loads(_path(page_id, ".json").read_text())
        image = _path(page_id, ".png").read_bytes()
    return metadata, image


def get_annotations(page_id):
    metadata, _ = get_page(page_id)
    path = _path(page_id, ".marks.json")
    with LOCK:
        if not path.exists():
            return {"page_id": page_id, "revision": metadata["revision"], "marks": []}
        annotations = json.loads(path.read_text())
    if annotations.get("revision") != metadata["revision"]:
        return {"page_id": page_id, "revision": metadata["revision"], "marks": []}
    return annotations


def save_annotations(page_id, revision, marks):
    metadata, _ = get_page(page_id)
    if revision != metadata["revision"]:
        raise ValueError("The page changed; read it again before marking it")
    if not isinstance(marks, list) or len(marks) > 20:
        raise ValueError("Supply at most 20 marks")
    clean = []
    for mark in marks:
        if not isinstance(mark, dict) or mark.get("kind") not in KINDS:
            raise ValueError("Invalid mark kind")
        points = mark.get("points")
        if not isinstance(points, list) or len(points) != 2:
            raise ValueError("Every mark needs two points")
        for point in points:
            if not isinstance(point, list) or len(point) != 2 or not all(
                isinstance(v, (int, float)) and 0 <= v <= 1 for v in point):
                raise ValueError("Point coordinates must be normalized to 0..1")
        text = mark.get("text", "")
        if not isinstance(text, str) or len(text) > 160:
            raise ValueError("Mark text is too long")
        clean.append({"kind": mark["kind"], "points": points, "text": text})
    result = {"page_id": page_id, "revision": revision, "marks": clean}
    with LOCK:
        _atomic(_path(page_id, ".marks.json"), json.dumps(result).encode())
    return result
