from __future__ import annotations

import json

import pytest
from pathlib import Path

from bkx.android_api import cancel_json, execute_json, readiness_json
from bkx.android_api.parity import direct_plan_token, embedded_plan_token
from bkx.core.errors import PermissionDeniedError, ValidationError


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


def test_direct_path_aliases_do_not_require_staging_field_names(tmp_path: Path) -> None:
    source = tmp_path / "source-direct"
    source.mkdir()
    (source / "direct.txt").write_text("direct", encoding="utf-8")
    destination = tmp_path / "destination-direct"
    destination.mkdir()
    payload = backup_payload(source, destination)
    payload["source"] = {"source_id": "src", "label": "Source", "path": str(source)}
    payload["destination"] = {"destination_id": "dst", "label": "Destination", "path": str(destination)}

    result = request("backup.plan", "direct-path-plan", payload)
    assert result["status"] == "success", result


def test_filesystem_list_and_stat_use_real_paths(tmp_path: Path) -> None:
    folder = tmp_path / "browse"
    folder.mkdir()
    (folder / "alpha.txt").write_text("alpha", encoding="utf-8")
    (folder / "nested").mkdir()

    listed = request("filesystem.list", "filesystem-list", {"path": str(folder), "limit": 20})
    assert listed["status"] == "success", listed
    labels = {(item["label"], Path(item["path"]).name) for item in listed["artifacts"]}
    assert ("directory", "nested") in labels
    assert ("file", "alpha.txt") in labels

    info = request("filesystem.stat", "filesystem-stat", {"path": str(folder / "alpha.txt")})
    assert info["status"] == "success", info
    artifact = info["artifacts"][0]
    assert artifact["label"] == "file"
    assert artifact["path"] == str(folder / "alpha.txt")
    assert artifact["size_bytes"] == 5
    assert artifact["modified_at_epoch_millis"] >= 0
    assert "5 bytes" in info["summary"]


def test_storage_locations_operation_is_part_of_embedded_contract() -> None:
    readiness = json.loads(readiness_json())
    assert "storage.locations" in readiness["capabilities"]
    assert "filesystem.list" in readiness["capabilities"]
    result = request("storage.locations", "storage-locations", {})
    assert result["status"] == "success"


def test_progress_and_metrics_are_published_for_completed_operation(tmp_path: Path) -> None:
    from bkx.android_api import progress_json

    source = tmp_path / "metrics-source"
    source.mkdir()
    for index in range(3):
        (source / f"file-{index}.txt").write_text("x" * (index + 1), encoding="utf-8")
    destination = tmp_path / "metrics-backups"
    destination.mkdir()

    result = request("backup.create", "metrics-backup", backup_payload(source, destination))
    assert result["status"] == "success", result
    assert result["metrics"]["duration_ms"] >= 0
    progress = json.loads(progress_json("metrics-backup"))
    assert progress["request_id"] == "metrics-backup"
    assert progress["seq"] >= 2
    assert progress["phase"] == "success"
    assert progress["progress"] == 1.0


def test_android_backup_skips_symlinks_without_following_them(tmp_path: Path) -> None:
    source = tmp_path / "termux-like-home"
    source.mkdir()
    (source / "real.txt").write_text("real", encoding="utf-8")
    external = tmp_path / "external"
    external.mkdir()
    (external / "must-not-be-archived.txt").write_text("outside", encoding="utf-8")
    (source / "storage").symlink_to(external, target_is_directory=True)
    destination = tmp_path / "symlink-backups"
    destination.mkdir()

    created = request("backup.create", "skip-symlink", backup_payload(source, destination))
    assert created["status"] == "success", created
    archive = Path(next(item["path"] for item in created["artifacts"] if item["label"] == "backup_archive"))
    browse = request("archive.browse", "skip-symlink-browse", {"archive": {"staged_path": str(archive)}, "page": {"page_size": 50}})
    names = [item["path"] for item in browse["artifacts"] if item["label"].startswith("archive_entry")]
    assert any(name.endswith("real.txt") for name in names)
    assert not any("must-not-be-archived.txt" in name or name.endswith("storage") for name in names)


def test_android_backup_rejects_follow_symlink_request(tmp_path: Path) -> None:
    source = tmp_path / "source-follow"
    source.mkdir()
    (source / "file.txt").write_text("x", encoding="utf-8")
    destination = tmp_path / "destination-follow"
    destination.mkdir()
    payload = backup_payload(source, destination)
    payload["filters"]["follow_symlinks"] = True

    result = request("backup.plan", "reject-follow-symlink", payload)
    assert result["status"] == "failure"
    assert "does not follow symbolic links" in result["errors"][0]["message"]


def test_restore_destination_modes_fail_closed(tmp_path: Path) -> None:
    source = tmp_path / "restore-mode-source"
    source.mkdir()
    (source / "file.txt").write_text("restore", encoding="utf-8")
    backups = tmp_path / "restore-mode-backups"
    backups.mkdir()
    created = request("backup.create", "restore-mode-backup", backup_payload(source, backups))
    archive = Path(next(item["path"] for item in created["artifacts"] if item["label"] == "backup_archive"))

    existing = tmp_path / "already-there"
    existing.mkdir()
    fresh = request(
        "restore.preview",
        "fresh-existing-destination",
        {
            "archive": str(archive),
            "destination_path": str(existing),
            "fresh_dir": True,
            "verify": False,
            "paths": [],
        },
    )
    assert fresh["status"] == "failure"
    assert "does not already exist" in fresh["errors"][0]["message"]

    missing = tmp_path / "missing-existing-dir"
    existing_mode = request(
        "restore.preview",
        "existing-missing-destination",
        {
            "archive": str(archive),
            "destination_path": str(missing),
            "fresh_dir": False,
            "verify": False,
            "paths": [],
        },
    )
    assert existing_mode["status"] == "failure"
    assert "requires an existing directory" in existing_mode["errors"][0]["message"]


def test_root_sensitive_restore_allows_current_process_owned_private_tree(monkeypatch: pytest.MonkeyPatch) -> None:
    from bkx.android_api import engine

    destination = Path("/data/data/com.termux/files/usr/tmp/bkx-parity-owned/restored")
    monkeypatch.setattr(engine, "_is_root_runtime", lambda: False)
    monkeypatch.setattr(
        engine,
        "_restore_destination_writable_by_current_process",
        lambda candidate: candidate == destination,
    )

    # A pathname under /data/data is not sufficient evidence that root is
    # required: Termux owns its own private tree, and BKX owns its own private
    # tree. Preview and extraction must both be allowed when the current UID
    # already has write/search permission.
    engine._validate_android_restore_destination(
        destination,
        {"fresh_dir": True},
        dry_run=True,
    )
    engine._validate_android_restore_destination(
        destination,
        {"fresh_dir": True},
        dry_run=False,
    )


def test_root_sensitive_restore_still_requires_root_for_foreign_private_tree(monkeypatch: pytest.MonkeyPatch) -> None:
    from bkx.android_api import engine

    destination = Path("/data/data/com.termux/files/home/foreign-restore")
    monkeypatch.setattr(engine, "_is_root_runtime", lambda: False)
    monkeypatch.setattr(
        engine,
        "_restore_destination_writable_by_current_process",
        lambda _candidate: False,
    )

    with pytest.raises(PermissionDeniedError, match="requires BKX root execution"):
        engine._validate_android_restore_destination(
            destination,
            {"fresh_dir": True},
            dry_run=True,
        )


def test_actual_root_restore_keeps_restore_root_confirmation(monkeypatch: pytest.MonkeyPatch) -> None:
    from bkx.android_api import engine

    destination = Path("/data/data/com.termux/files/home/root-restore")
    monkeypatch.setattr(engine, "_is_root_runtime", lambda: True)

    # Root preview is safe without the destructive confirmation, but the write
    # operation remains fail-closed until the exact phrase is provided.
    engine._validate_android_restore_destination(
        destination,
        {"fresh_dir": True},
        dry_run=True,
    )
    with pytest.raises(ValidationError, match="RESTORE ROOT"):
        engine._validate_android_restore_destination(
            destination,
            {"fresh_dir": True},
            dry_run=False,
        )
    engine._validate_android_restore_destination(
        destination,
        {"fresh_dir": True, "root_confirmation": "RESTORE ROOT"},
        dry_run=False,
    )

