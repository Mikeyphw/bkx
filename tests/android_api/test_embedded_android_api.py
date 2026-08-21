from __future__ import annotations

import json
from pathlib import Path

from bkx.android_api import cancel_json, execute_json, readiness_json
from bkx.android_api.parity import direct_plan_token, embedded_plan_token


def request(operation: str, request_id: str, payload: dict) -> dict:
    return json.loads(
        execute_json(
            json.dumps(
                {
                    "protocol_version": 1,
                    "request_id": request_id,
                    "operation": operation,
                    "payload": payload,
                }
            )
        )
    )


def backup_payload(source: Path, destination: Path) -> dict:
    return {
        "operation_mode": "plan_then_create",
        "source": {"source_id": "src", "label": "Source", "staged_path": str(source)},
        "destination": {
            "destination_id": "dst",
            "label": "Destination",
            "staged_directory_path": str(destination),
        },
        "filters": {
            "include_patterns": [],
            "exclude_patterns": [],
            "include_hidden": True,
            "follow_symlinks": False,
        },
        "archive": {
            "compression": "gzip",
            "split": {"enabled": False},
            "verification": {"verify_after_create": True, "expected_hash_algorithm": "sha256"},
        },
    }


def test_readiness_is_embedded_and_bridge_compatible() -> None:
    document = json.loads(readiness_json())
    assert document["protocol_version"] == 1
    assert document["runtime"] == "embedded-cpython"
    assert document["python_version"].startswith("3.")
    assert {"backup.plan", "backup.create", "restore.preview", "transfer.run"} <= set(document["capabilities"])


def test_backup_plan_is_direct_core_plan_parity(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    (source / "hello.txt").write_text("hello", encoding="utf-8")
    destination = tmp_path / "backups"
    destination.mkdir()
    payload = backup_payload(source, destination)

    assert embedded_plan_token("backup.plan", "plan-parity", payload) == direct_plan_token("backup.plan", payload)


def test_backup_archive_restore_and_verify_execute_inside_core(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    (source / "hello.txt").write_text("hello embedded BKX", encoding="utf-8")
    destination = tmp_path / "backups"
    destination.mkdir()
    payload = backup_payload(source, destination)

    created = request("backup.create", "backup-create", payload)
    assert created["status"] == "success", created
    archive = Path(next(item["path"] for item in created["artifacts"] if item["label"] == "backup_archive"))
    assert archive.is_file()

    verify = request("archive.verify", "archive-verify", {"archive": {"staged_path": str(archive)}})
    assert verify["status"] == "success", verify
    assert next(item for item in verify["artifacts"] if item["label"] == "archive_verification")["sha256"]

    browse = request(
        "archive.browse",
        "archive-browse",
        {"archive": {"staged_path": str(archive)}, "page": {"page_size": 20}},
    )
    assert browse["status"] == "success", browse
    assert any(item["label"] == "archive_entry" and item["path"].endswith("hello.txt") for item in browse["artifacts"])

    restore_dir = tmp_path / "restored"
    restore_payload = {
        "archive": str(archive),
        "destination_path": str(restore_dir),
        "fresh_dir": True,
        "verify": True,
        "dry_run": True,
        "overwrite": False,
        "confirm_overwrite": False,
        "paths": [],
    }
    preview = request("restore.preview", "restore-preview", restore_payload)
    assert preview["status"] == "success", preview
    assert next(item for item in preview["artifacts"] if item["label"] == "restore_plan")["sha256"]

    restored = request("restore.extract", "restore-extract", {**restore_payload, "dry_run": False})
    assert restored["status"] == "success", restored
    assert (restore_dir / source.name / "hello.txt").read_text(encoding="utf-8") == "hello embedded BKX"


def test_local_transfer_plan_and_run(tmp_path: Path) -> None:
    source = tmp_path / "payload.txt"
    source.write_text("transfer", encoding="utf-8")
    destination = tmp_path / "destination"
    payload = {
        "mode": "push",
        "direction": "push",
        "sources": [{"source_id": "s", "label": "payload", "location": str(source), "remote": False}],
        "destination": {"destination_id": "d", "label": "destination", "location": str(destination), "remote": False},
        "verify": True,
        "checksum": None,
        "dry_run": True,
        "overwrite": False,
        "delete_extraneous_on_sync": False,
    }
    assert embedded_plan_token("transfer.plan", "transfer-plan", payload) == direct_plan_token("transfer.plan", payload)
    result = request("transfer.run", "transfer-run", {**payload, "dry_run": False})
    assert result["status"] == "success", result
    assert destination.read_text(encoding="utf-8") == "transfer"


def test_cancel_prevents_queued_execution(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    (source / "hello.txt").write_text("hello", encoding="utf-8")
    destination = tmp_path / "backups"
    destination.mkdir()
    payload = backup_payload(source, destination)

    cancellation = json.loads(cancel_json("queued-request"))
    assert cancellation["status"] == "success"
    result = request("backup.create", "queued-request", payload)
    assert result["status"] == "cancelled"
    assert not list(destination.iterdir())


def test_malformed_and_unsupported_requests_fail_closed() -> None:
    malformed = json.loads(execute_json("{}"))
    assert malformed["status"] == "failure"
    assert malformed["errors"][0]["code"] == "validation_error"

    unsupported = request("shell.exec", "no-shell", {})
    assert unsupported["status"] == "failure"
    assert unsupported["errors"][0]["code"] == "validation_error"
