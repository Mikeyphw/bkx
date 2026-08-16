from __future__ import annotations

import importlib
import sys
from pathlib import Path

import pytest
from typer.testing import CliRunner

from bkx.cli.app import app
from bkx.cli.interface_dispatch import build_spec_from_interface_values
from bkx.cli.spec_builders import build_archive_spec, build_backup_spec, build_restore_spec, build_transfer_spec
from bkx.core.parity import compute_spec_hash_from_obj


def test_base_cli_import_does_not_load_optional_ui_modules(monkeypatch: pytest.MonkeyPatch) -> None:
    for name in list(sys.modules):
        if name == "mikomn" or name.startswith("mikomn.") or name == "textual" or name.startswith("textual."):
            monkeypatch.delitem(sys.modules, name, raising=False)
    import bkx.cli.app as cli_app

    importlib.reload(cli_app)
    assert not any(name == "mikomn" or name.startswith("mikomn.") for name in sys.modules)
    assert not any(name == "textual" or name.startswith("textual.") for name in sys.modules)


def test_missing_optional_tui_extra_fails_cleanly(monkeypatch: pytest.MonkeyPatch) -> None:
    import builtins

    real_import = builtins.__import__

    def blocked(name, globals=None, locals=None, fromlist=(), level=0):
        if name == "mikomn" or name.startswith("mikomn.") or name == "textual" or name.startswith("textual."):
            raise ModuleNotFoundError("No module named 'mikomn'", name="mikomn")
        return real_import(name, globals, locals, fromlist, level)

    monkeypatch.setattr(builtins, "__import__", blocked)
    result = CliRunner().invoke(app, ["--json", "ui", "start", "--mode", "tui"])
    assert result.exit_code != 0
    assert "bkx[tui]" in result.stdout


def test_missing_optional_interactive_extra_fails_cleanly(monkeypatch: pytest.MonkeyPatch) -> None:
    import builtins

    real_import = builtins.__import__

    def blocked(name, globals=None, locals=None, fromlist=(), level=0):
        if name == "mikomn" or name.startswith("mikomn."):
            raise ModuleNotFoundError("No module named 'mikomn'", name="mikomn")
        return real_import(name, globals, locals, fromlist, level)

    monkeypatch.setattr(builtins, "__import__", blocked)
    result = CliRunner().invoke(app, ["--json", "ui", "start", "--mode", "interactive"])
    assert result.exit_code != 0
    assert "bkx[interactive]" in result.stdout


def test_shared_interface_adapter_matches_backup_cli_builder(tmp_path: Path) -> None:
    src = tmp_path / "src"
    src.mkdir()
    dest = tmp_path / "dest"
    values = {"sources": str(src), "destination": str(dest), "archive_name": "demo", "excludes": "*.pyc __pycache__", "algorithm": "gzip", "level": "4", "verify": "true"}
    shared = build_spec_from_interface_values("backup.create", values)
    direct = build_backup_spec([src], destination=dest, archive_name="demo", excludes=["*.pyc", "__pycache__"], algorithm="gzip", level=4, verify=True)
    assert compute_spec_hash_from_obj(shared) == compute_spec_hash_from_obj(direct)


def test_shared_interface_adapter_matches_archive_cli_builder(tmp_path: Path) -> None:
    src = tmp_path / "src"
    src.mkdir()
    output = tmp_path / "out.tar"
    values = {"sources": [src], "output": output, "includes": ["*.txt"], "excludes": ["*.tmp"], "algorithm": "gzip", "level": 6, "verify": False}
    shared = build_spec_from_interface_values("archive.create", values)
    direct = build_archive_spec([src], output=output, includes=["*.txt"], excludes=["*.tmp"], algorithm="gzip", level=6, verify=False)
    assert compute_spec_hash_from_obj(shared) == compute_spec_hash_from_obj(direct)


def test_shared_interface_adapter_matches_restore_cli_builder(tmp_path: Path) -> None:
    archive = tmp_path / "archive.tar"
    archive.write_bytes(b"not-a-real-tar-for-spec-only")
    dest = tmp_path / "dest"
    values = {"archive": archive, "destination": dest, "paths": "docs/readme.md", "overwrite": True, "confirm_overwrite": False, "fresh_dir": False, "verify": False}
    shared = build_spec_from_interface_values("restore.pick", values)
    direct = build_restore_spec(archive, destination=dest, paths=["docs/readme.md"], overwrite=True, confirm_overwrite=False, fresh_dir=False, verify=False)
    assert compute_spec_hash_from_obj(shared) == compute_spec_hash_from_obj(direct)


def test_shared_interface_adapter_matches_transfer_cli_builder(tmp_path: Path) -> None:
    source = tmp_path / "source.txt"
    source.write_text("x", encoding="utf-8")
    dest = tmp_path / "dest.txt"
    values = {"source": source, "destination": dest, "direction": "push", "overwrite": True, "verify": False}
    shared = build_spec_from_interface_values("transfer.run", values)
    direct = build_transfer_spec(source, dest, direction="push", overwrite=True, verify=False)
    assert compute_spec_hash_from_obj(shared) == compute_spec_hash_from_obj(direct)


def test_actual_cli_parse_reaches_same_backup_spec(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    import bkx.cli.app as app_module

    src = tmp_path / "src"
    src.mkdir()
    dest = tmp_path / "dest"
    captured = {}

    class FakeBackupService:
        def create(self, spec):
            captured["spec"] = spec
            from bkx.core.results.backup import BackupResult, Timing
            return BackupResult(success=True, dry_run=False, timing=Timing(0, 0), files_processed=0, bytes_written=0, archive_paths=(Path("x.bakery"),))

    monkeypatch.setattr(app_module, "get_backup_service", lambda: FakeBackupService())
    result = CliRunner().invoke(app, ["backup", "create", str(src), "--destination", str(dest), "--name", "demo", "--compression", "gzip", "--compression-level", "4", "--no-verify"])
    assert result.exit_code == 0
    expected = build_spec_from_interface_values("backup.create", {"sources": [src], "destination": dest, "archive_name": "demo", "algorithm": "gzip", "level": 4, "verify": False})
    assert compute_spec_hash_from_obj(captured["spec"]) == compute_spec_hash_from_obj(expected)
