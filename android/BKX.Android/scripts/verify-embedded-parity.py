#!/usr/bin/env python3
"""stdlib-only embedded/core parity seal runnable from Gradle/Devtool."""
from __future__ import annotations

import json
import tempfile
from pathlib import Path

from bkx.android_api import execute_json, progress_json
from bkx.android_api.parity import direct_plan_token, embedded_plan_token


def execute(operation: str, request_id: str, payload: dict) -> dict:
    result = json.loads(execute_json(json.dumps({
        "protocol_version": 1,
        "request_id": request_id,
        "operation": operation,
        "payload": payload,
    })))
    if result.get("request_id") != request_id:
        raise SystemExit(f"request id parity failed for {operation}: {result}")
    return result


def backup_payload(source: Path, destination: Path) -> dict:
    return {
        "source": {"source_id": "src", "label": "Source", "path": str(source)},
        "destination": {"destination_id": "dst", "label": "Destination", "path": str(destination)},
        "filters": {"include_patterns": [], "exclude_patterns": [], "include_hidden": True, "follow_symlinks": False},
        "archive": {"compression": "gzip", "split": {"enabled": False}, "verification": {"verify_after_create": True}},
    }


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="bkx-android-parity-") as raw:
        root = Path(raw)
        source = root / "source"
        source.mkdir()
        (source / "alpha.txt").write_text("alpha", encoding="utf-8")
        destination = root / "backups"
        destination.mkdir()
        payload = backup_payload(source, destination)

        direct = direct_plan_token("backup.plan", payload)
        embedded = embedded_plan_token("backup.plan", "parity-plan", payload)
        if direct != embedded:
            raise SystemExit("backup.plan token differs from direct bkx.core plan")

        created = execute("backup.create", "parity-create", payload)
        if created.get("status") != "success" or created.get("metrics", {}).get("duration_ms", -1) < 0:
            raise SystemExit(f"backup.create parity failed: {created}")
        archive = Path(next(item["path"] for item in created["artifacts"] if item["label"] == "backup_archive"))

        verified = execute("archive.verify", "parity-verify", {"archive": {"path": str(archive)}})
        if verified.get("status") != "success":
            raise SystemExit(f"archive.verify parity failed: {verified}")

        restore = root / "restored"
        preview_payload = {"archive": str(archive), "destination_path": str(restore), "fresh_dir": True, "verify": True, "paths": []}
        preview = execute("restore.preview", "parity-restore-preview", preview_payload)
        if preview.get("status") != "success":
            raise SystemExit(f"restore.preview parity failed: {preview}")
        restored = execute("restore.extract", "parity-restore", preview_payload)
        if restored.get("status") != "success" or not (restore / source.name / "alpha.txt").is_file():
            raise SystemExit(f"restore.extract parity failed: {restored}")

        progress = json.loads(progress_json("parity-restore"))
        if progress.get("phase") != "success" or progress.get("progress") != 1.0:
            raise SystemExit(f"final progress parity failed: {progress}")

        transfer_source = root / "transfer.txt"
        transfer_source.write_text("transfer", encoding="utf-8")
        transfer_dest = root / "transfer-copy.txt"
        transfer_payload = {
            "mode": "push",
            "sources": [{"location": str(transfer_source), "remote": False}],
            "destination": {"location": str(transfer_dest), "remote": False},
            "verify": True,
            "overwrite": False,
        }
        transfer = execute("transfer.run", "parity-transfer", transfer_payload)
        if transfer.get("status") != "success" or transfer_dest.read_text(encoding="utf-8") != "transfer":
            raise SystemExit(f"transfer.run parity failed: {transfer}")

    print("BKX embedded/core parity seal passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
