#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import subprocess
import tempfile
import zipfile
from pathlib import Path


def core(document: dict) -> dict:
    return {k: v for k, v in document.items() if k not in {"protocol_version", "request_id", "metrics"}}


def run_go(binary: Path, operation: str, payload: dict, runs: str = "-") -> dict:
    cp = subprocess.run(
        [str(binary), "migration", "service", "dispatch", operation, json.dumps(payload, separators=(",", ":")), runs, "-"],
        text=True, capture_output=True, check=False,
    )
    assert cp.returncode == 0, (operation, cp.stderr, cp.stdout)
    return json.loads(cp.stdout)


def run_python(engine, operation: str, payload: dict) -> dict:
    request = {"protocol_version": 1, "request_id": "service-probe", "operation": operation, "payload": payload}
    result = json.loads(engine.execute_json(json.dumps(request, separators=(",", ":"))))
    assert result["status"] == "success", (operation, result)
    return core(result)


def assert_exact(engine, binary: Path, operation: str, payload: dict, runs: str = "-") -> None:
    py = run_python(engine, operation, payload)
    go = run_go(binary, operation, payload, runs)
    assert go == py, (operation, go, py)


def check(binary: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="bkx-go10-service-") as td:
        root = Path(td)
        home = root / "home"
        home.mkdir()
        os.environ["HOME"] = str(home)
        os.environ["TZ"] = "UTC"
        from bkx.android_api import engine

        source = root / "source"
        source.mkdir()
        alpha = source / "alpha.txt"
        alpha.write_text("alpha\n", encoding="utf-8")
        (source / ".hidden").write_text("hidden\n", encoding="utf-8")
        epoch_ns = 1_704_164_646_123_000_000
        for path in (source, alpha, source / ".hidden"):
            os.utime(path, ns=(epoch_ns, epoch_ns), follow_symlinks=False)

        assert_exact(engine, binary, "filesystem.stat", {"path": str(alpha)})
        assert_exact(engine, binary, "filesystem.list", {"path": str(source), "show_hidden": False, "limit": 10, "offset": 0})
        assert_exact(engine, binary, "storage.locations", {})

        archive = root / "fixture.zip"
        with zipfile.ZipFile(archive, "w") as zf:
            zf.writestr("dir/", b"")
            zf.writestr("dir/a.txt", b"alpha\n")
        os.utime(archive, ns=(epoch_ns, epoch_ns))
        archive_payload = {"archive": {"path": str(archive)}}
        assert_exact(engine, binary, "archive.info", archive_payload)
        assert_exact(engine, binary, "archive.browse", {**archive_payload, "page": {"page_size": 1}})
        assert_exact(engine, binary, "archive.verify", archive_payload)
        assert_exact(engine, binary, "archive.list", {"roots": [str(root)], "max_depth": 2, "limit": 10})

        backup_dir = root / "backups"
        backup_dir.mkdir()
        backup_payload = {
            "source": {"path": str(source)},
            "destination": {"path": str(backup_dir)},
            "filters": {"include_patterns": [], "exclude_patterns": [], "follow_symlinks": False},
            "archive": {
                "archive_name": "service-fixture",
                "compression": "gzip",
                "compression_level": 3,
                "split": {"enabled": False},
                "verification": {"verify_after_create": True},
            },
        }
        assert_exact(engine, binary, "backup.plan", backup_payload)

        transfer_payload = {
            "sources": [{"location": str(alpha), "remote": False}],
            "destination": {"location": str(root / "copy.txt"), "remote": False},
            "mode": "push", "verify": True, "checksum": None, "overwrite": False,
        }
        assert_exact(engine, binary, "transfer.plan", transfer_payload)

        restore_payload = {
            "archive": str(archive), "destination_path": str(root / "restored"), "paths": [],
            "overwrite": False, "verify": True, "fresh_dir": True, "confirm_overwrite": False,
        }
        assert_exact(engine, binary, "restore.preview", restore_payload)

        # Feed both implementations one fixed run-history file so list/show token
        # parity is deterministic and independent of wall-clock timestamps.
        runs_path = home / ".local" / "share" / "bkx" / "runs" / "runs.jsonl"
        runs_path.parent.mkdir(parents=True, exist_ok=True)
        record = {
            "archive_path": None,
            "bytes_processed": 6,
            "completed_at": "2026-09-05T12:00:05+00:00",
            "destination_path": str(root / "done"),
            "error_message": None,
            "files_count": 1,
            "operation": "transfer",
            "run_id": "fixed-run",
            "source_paths": [str(alpha)],
            "spec_snapshot": {"type": "TransferSpec", "values": {"verify": True}},
            "started_at": "2026-09-05T12:00:00+00:00",
            "status": "success",
        }
        runs_path.write_text(json.dumps(record, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
        assert_exact(engine, binary, "runs.list", {"limit": 20}, str(runs_path))
        assert_exact(engine, binary, "runs.show", {"run_id": "fixed-run"}, str(runs_path))

        operations = json.loads(subprocess.check_output([str(binary), "migration", "service", "operations"], text=True))
        assert operations == list(engine.supported_operations()), (operations, engine.supported_operations())

    print("BKX GO10 transport-neutral service parity valid for all read/plan/history surfaces.")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--go-binary", type=Path, required=True)
    args = parser.parse_args()
    check(args.go_binary.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
