#!/usr/bin/env python3
"""deterministic large-tree performance and memory-pressure seal."""
from __future__ import annotations

import json
import tempfile
import time
import tracemalloc
from pathlib import Path

from bkx.android_api import execute_json

FILE_COUNT = 768
FILE_BYTES = 2048
PEAK_MEMORY_LIMIT = 192 * 1024 * 1024
WALL_TIME_LIMIT_SECONDS = 90.0


def execute(operation: str, request_id: str, payload: dict) -> dict:
    result = json.loads(execute_json(json.dumps({
        "protocol_version": 1,
        "request_id": request_id,
        "operation": operation,
        "payload": payload,
    })))
    if result.get("status") != "success":
        raise SystemExit(f"{operation} failed during large-tree profile: {result}")
    return result


def payload(source: Path, destination: Path) -> dict:
    return {
        "source": {"source_id": "profile", "label": "Profile", "path": str(source)},
        "destination": {"destination_id": "profile-out", "label": "Profile out", "path": str(destination)},
        "filters": {"include_patterns": [], "exclude_patterns": [], "include_hidden": True, "follow_symlinks": False},
        "archive": {"compression": "gzip", "split": {"enabled": False}, "verification": {"verify_after_create": True}},
    }


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="bkx-large-tree-") as raw:
        root = Path(raw)
        source = root / "source"
        destination = root / "backups"
        source.mkdir()
        destination.mkdir()
        chunk = b"x" * FILE_BYTES
        for index in range(FILE_COUNT):
            directory = source / f"d{index % 32:02d}" / f"n{(index // 32) % 8:02d}"
            directory.mkdir(parents=True, exist_ok=True)
            (directory / f"file-{index:04d}.bin").write_bytes(chunk)
        outside = root / "outside"
        outside.mkdir()
        (outside / "must-not-follow.txt").write_text("outside", encoding="utf-8")
        (source / "external-link").symlink_to(outside, target_is_directory=True)

        tracemalloc.start()
        started = time.monotonic()
        plan = execute("backup.plan", "large-tree-plan", payload(source, destination))
        created = execute("backup.create", "large-tree-create", payload(source, destination))
        archive = Path(next(a["path"] for a in created["artifacts"] if a["label"] == "backup_archive"))
        verified = execute("archive.verify", "large-tree-verify", {"archive": {"path": str(archive)}})
        elapsed = time.monotonic() - started
        _, peak = tracemalloc.get_traced_memory()
        tracemalloc.stop()

        if peak > PEAK_MEMORY_LIMIT:
            raise SystemExit(f"large-tree peak Python memory {peak} exceeds {PEAK_MEMORY_LIMIT}")
        if elapsed > WALL_TIME_LIMIT_SECONDS:
            raise SystemExit(f"large-tree profile took {elapsed:.2f}s, limit is {WALL_TIME_LIMIT_SECONDS:.2f}s")
        if not archive.is_file() or archive.stat().st_size <= 0:
            raise SystemExit("large-tree backup did not produce a non-empty archive")
        names: list[str] = []
        page_token = None
        page_index = 0
        while True:
            page = {"page_size": 500}
            if page_token is not None:
                page["page_token"] = page_token
            browse = execute("archive.browse", f"large-tree-browse-{page_index}", {"archive": {"path": str(archive)}, "page": page})
            names.extend(a["path"] for a in browse["artifacts"] if a["label"].startswith("archive_entry"))
            next_item = next((a for a in browse["artifacts"] if a["label"] == "archive_next_page"), None)
            if next_item is None:
                break
            page_token = next_item["path"].rsplit("/", 1)[-1]
            page_index += 1
        if any("external-link" in name or "must-not-follow" in name for name in names):
            raise SystemExit("large-tree profile followed a source symlink")

        report = {
            "files": FILE_COUNT,
            "source_bytes": FILE_COUNT * FILE_BYTES,
            "elapsed_seconds": round(elapsed, 3),
            "python_peak_bytes": peak,
            "archive_bytes": archive.stat().st_size,
            "plan_duration_ms": plan.get("metrics", {}).get("duration_ms"),
            "create_duration_ms": created.get("metrics", {}).get("duration_ms"),
            "verify_duration_ms": verified.get("metrics", {}).get("duration_ms"),
        }
        print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
