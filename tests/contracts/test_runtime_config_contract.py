"""executable regression/negative contract tests for RM-001..RM-008."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import textwrap
import tomllib
from pathlib import Path
from types import SimpleNamespace

import pytest
from typer.testing import CliRunner

from bkx.cli.app import app
from bkx.cli.spec_builders import build_backup_spec, build_restore_spec, build_transfer_spec
from bkx.core.config import BkxConfig, reset_config
from bkx.core.errors import (
    ExternalToolError,
    NotFoundError,
    PermissionDeniedError,
    ValidationError,
)
from bkx.core.impl.tool_detector import FallbackToolDetector
from bkx.core.results.archive import ArchiveCreateResult
from bkx.core.results.backup import Timing
from bkx.core.results.transfer import TransferResult
from bkx.core.services.archive import ArchiveService
from bkx.core.services.backup import BackupService
from bkx.core.services.restore import RestoreService
from bkx.core.services.run import RunService
from bkx.core.services.transfer import TransferService
from bkx.core.specs.archive import ArchiveCreateSpec
from bkx.core.specs.backup import BackupSpec
from bkx.core.specs.restore import RestoreSpec
from bkx.core.specs.transfer import TransferSpec

ROOT = Path(__file__).resolve().parents[2]
RUNNER = CliRunner()


def _subprocess_env() -> dict[str, str]:
    env = os.environ.copy()
    env["PYTHONPATH"] = str(ROOT / "src")
    for name in tuple(env):
        if name.startswith("BKX_"):
            env.pop(name, None)
    return env


def test_base_cli_import_does_not_import_optional_ui_dependencies() -> None:
    code = textwrap.dedent(
        """
        import sys
        class Blocker:
            def find_spec(self, fullname, path=None, target=None):
                root = fullname.split('.', 1)[0]
                if root in {'mikomn', 'textual'}:
                    exc = ModuleNotFoundError(f'blocked optional dependency: {fullname}')
                    exc.name = root
                    raise exc
                return None
        sys.meta_path.insert(0, Blocker())
        import bkx.cli.app
        from bkx.cli.render import get_renderer
        get_renderer(False)
        assert not any(name == 'mikomn' or name.startswith('mikomn.') for name in sys.modules)
        assert not any(name == 'textual' or name.startswith('textual.') for name in sys.modules)
        print('BASE_IMPORT_OK')
        """
    )
    result = subprocess.run([sys.executable, "-c", code], cwd=ROOT, env=_subprocess_env(), capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    assert "BASE_IMPORT_OK" in result.stdout


def test_optional_tui_request_fails_with_typed_actionable_error() -> None:
    code = textwrap.dedent(
        """
        import sys
        class Blocker:
            def find_spec(self, fullname, path=None, target=None):
                root = fullname.split('.', 1)[0]
                if root in {'mikomn', 'textual'}:
                    exc = ModuleNotFoundError(f'blocked optional dependency: {fullname}')
                    exc.name = root
                    raise exc
                return None
        sys.meta_path.insert(0, Blocker())
        from bkx.cli.app import _optional_tui_runner
        from bkx.core.errors import OptionalFeatureError
        try:
            _optional_tui_runner()
        except OptionalFeatureError as exc:
            assert exc.extra == 'tui'
            assert exc.details['extra'] == 'bkx[tui]'
            print('OPTIONAL_FEATURE_TYPED')
        else:
            raise AssertionError('TUI unexpectedly loaded')
        """
    )
    result = subprocess.run([sys.executable, "-c", code], cwd=ROOT, env=_subprocess_env(), capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    assert "OPTIONAL_FEATURE_TYPED" in result.stdout


def test_pyproject_declares_reference_and_optional_ui_dependencies() -> None:
    data = tomllib.loads((ROOT / "pyproject.toml").read_text())
    base = data["project"]["dependencies"]
    optional = data["project"]["optional-dependencies"]
    assert base == []
    reference = optional["reference"]
    for dependency in ("typer", "rich", "pyyaml", "jsonschema"):
        assert any(dep.lower().startswith(dependency) for dep in reference)
    assert any(dep.startswith("mikomn[cli]") for dep in optional["interactive"])
    assert any(dep.startswith("mikomn[tui]") for dep in optional["tui"])
    assert any(dep.lower().startswith("textual") for dep in optional["tui"])


def test_module_bootstrap_works_with_optional_dependencies_blocked() -> None:
    code = textwrap.dedent(
        """
        import runpy, sys
        class Blocker:
            def find_spec(self, fullname, path=None, target=None):
                root = fullname.split('.', 1)[0]
                if root in {'mikomn', 'textual'}:
                    exc = ModuleNotFoundError(fullname); exc.name = root; raise exc
                return None
        sys.meta_path.insert(0, Blocker())
        sys.argv = ['bkx', '--version']
        try:
            runpy.run_module('bkx', run_name='__main__')
        except SystemExit as exc:
            if exc.code not in (None, 0): raise
        """
    )
    result = subprocess.run([sys.executable, "-c", code], cwd=ROOT, env=_subprocess_env(), capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
    assert "bkx 0.0.1" in result.stdout


def test_package_data_contract_declares_schemas_and_tui_css() -> None:
    data = tomllib.loads((ROOT / "pyproject.toml").read_text())
    package_data = data["tool"]["setuptools"]["package-data"]
    assert "*.json" in package_data["bkx.core.schemas"]
    assert any("tcss" in pattern for pattern in package_data["bkx.tui.css"])


def test_error_details_and_external_metadata_are_immutable_and_comparable() -> None:
    first = ExternalToolError(
        msg="failed", details={"x": 1, "nested": {"items": [1, 2]}}, tool_name="tar", exit_code=7, stderr="boom"
    )
    second = ExternalToolError(
        msg="failed", details={"x": 1, "nested": {"items": [1, 2]}}, tool_name="tar", exit_code=7, stderr="boom"
    )
    assert first == second
    assert "tool_name='tar'" in repr(first)
    with pytest.raises(TypeError):
        first.details["x"] = 2
    with pytest.raises(TypeError):
        first.details["nested"]["items"].append(3)
    with pytest.raises(Exception):
        first.tool_name = "zip"  # type: ignore[misc]


def test_strict_environment_boolean_and_integer_errors_are_typed() -> None:
    with pytest.raises(ValidationError):
        BkxConfig.from_env({"BKX_VERIFY": "definitely"})
    with pytest.raises(ValidationError):
        BkxConfig.from_env({"BKX_COMPRESSION_LEVEL": "fast"})


def test_strict_file_types_unknown_keys_and_malformed_yaml_fail_closed(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    with pytest.raises(ValidationError):
        BkxConfig.from_dict({"verify_after_operation": "false"})
    with pytest.raises(ValidationError):
        BkxConfig.from_dict({"preserve_permissions": True})
    with pytest.raises(ValidationError):
        BkxConfig.from_dict({"verbose": True})
    with pytest.raises(ValidationError):
        BkxConfig.from_dict({"confirm_overwrite": False})

    monkeypatch.chdir(tmp_path)
    (tmp_path / ".bkx.yaml").write_text("compression_level: [broken\n", encoding="utf-8")
    with pytest.raises(ValidationError):
        BkxConfig.load()


def test_precedence_provenance_and_config_path_are_exact(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.chdir(tmp_path)
    cfg_file = tmp_path / ".bkx.yaml"
    cfg_file.write_text("verify_after_operation: false\ndefault_destination: relative-backups\ncompression_level: 5\n", encoding="utf-8")
    monkeypatch.setenv("BKX_VERIFY", "true")
    config = BkxConfig.load()

    assert config.verify_after_operation is True
    assert config.source_of("verify_after_operation") == "env"
    assert config.compression_level == 5
    assert config.source_of("compression_level") == "file"
    assert config.source_of("compression_algorithm") == "default"
    assert config.resolve("compression_level", cli_value=9).source == "cli"
    assert config._config_path == cfg_file.resolve()
    assert config.default_destination == (tmp_path / "relative-backups").resolve()


def test_from_env_without_bkx_variables_does_not_claim_env_source() -> None:
    config = BkxConfig.from_env({})
    assert config._config_source == "defaults"
    assert all(config.source_of(name) == "default" for name in ("compression_level", "verify_after_operation"))


def test_config_search_order_and_relative_env_paths_are_deterministic(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "bkx.yaml").write_text("compression_level: 8\n", encoding="utf-8")
    preferred = tmp_path / ".bkx.yaml"
    preferred.write_text("compression_level: 4\n", encoding="utf-8")

    config = BkxConfig.load()
    assert config._config_path == preferred.resolve()
    assert config.compression_level == 4

    env_config = BkxConfig.from_env({"BKX_DESTINATION": "env-relative"})
    assert env_config.default_destination == (tmp_path / "env-relative").resolve()


def test_live_verify_and_destination_defaults_reach_specs(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    source = tmp_path / "source"; source.mkdir()
    destination = tmp_path / "configured-destination"; destination.mkdir()
    archive = tmp_path / "backup.tar"; archive.write_bytes(b"x")
    monkeypatch.setenv("BKX_VERIFY", "false")
    monkeypatch.setenv("BKX_DESTINATION", str(destination))
    reset_config()
    try:
        backup = build_backup_spec([source])
        transfer = build_transfer_spec(source, destination / "copy")
        restore = build_restore_spec(archive, fresh_dir=False)
        assert backup.verify is False and backup.effective_destination == destination
        assert transfer.verify is False
        assert restore.verify is False and restore.effective_destination == destination

        explicit = build_transfer_spec(source, destination / "copy2", verify=True)
        assert explicit.verify is True
    finally:
        reset_config()


def test_configured_tool_preference_is_live_in_detector(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr("shutil.which", lambda name: "/bin/true" if name == "my-rsync" else None)
    detector = FallbackToolDetector(preferences={"transfer": "my-rsync"})
    assert detector.get_preferred("transfer") == "my-rsync"


def test_configured_tool_preference_is_live_in_public_explain_command(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("BKX_PREFERRED_TRANSFER", "my-rsync")
    monkeypatch.setattr("shutil.which", lambda name: "/bin/true" if name == "my-rsync" else None)
    reset_config()
    try:
        result = RUNNER.invoke(app, ["--json", "tools", "explain"])
        assert result.exit_code == 0, result.output
        assert json.loads(result.stdout)["preferred"]["transfer"] == "my-rsync"
    finally:
        reset_config()


def test_run_history_limit_is_enforced_by_service() -> None:
    class Store:
        def list_runs(self, limit=20, operation=None):
            self.limit = limit
            return ()
        def get_run(self, run_id): return None
    store = Store()
    service = RunService(run_store=store, max_list_limit=3)  # type: ignore[arg-type]
    result = service.list(limit=20)
    assert store.limit == 3
    assert result.limit == 3


def test_disabled_run_history_is_live_in_factory(monkeypatch: pytest.MonkeyPatch) -> None:
    from bkx.core.services.factory import get_run_service
    monkeypatch.setenv("BKX_HISTORY_ENABLED", "false")
    reset_config()
    try:
        result = get_run_service().list(limit=20)
        assert result.runs == ()
    finally:
        reset_config()


class _RaisingArchiver:
    name = "raising-archive"
    supported_formats = ("tar",)
    def create(self, spec, **kwargs): raise OSError(5, "backend failure")
    def list(self, *args, **kwargs): raise OSError(5, "backend failure")
    def info(self, *args, **kwargs): raise OSError(5, "backend failure")
    def verify(self, *args, **kwargs): raise OSError(5, "backend failure")
    def extract(self, *args, **kwargs): raise OSError(5, "backend failure")


class _UnsupportedTransfer:
    name = "unsupported"
    supports_push = False
    supports_pull = False
    supports_sync = False


class _PermissionTransfer:
    name = "permission-transfer"
    supports_push = True
    supports_pull = True
    supports_sync = True
    def push(self, *args, **kwargs): raise PermissionError(13, "denied")
    def pull(self, *args, **kwargs): raise FileNotFoundError(2, "missing")
    def sync(self, *args, **kwargs): raise OSError(5, "failed")


class _Hasher:
    name = "hasher"
    def hash_file(self, path): raise OSError(5, "hash failed")


class _Detector:
    def detect(self, name): raise AssertionError


def test_archive_restore_backup_backend_failures_are_typed(tmp_path: Path) -> None:
    source = tmp_path / "source"; source.mkdir()
    output = tmp_path / "out.tar"
    archive = tmp_path / "archive.tar"; archive.write_bytes(b"x")

    archive_spec = ArchiveCreateSpec(sources=(source,), output_path=output).normalize()
    with pytest.raises(ExternalToolError):
        ArchiveService(archiver=_RaisingArchiver()).create(archive_spec)  # type: ignore[arg-type]

    restore_spec = RestoreSpec(archive=archive, destination=tmp_path / "restore", verify=True).normalize()
    with pytest.raises(ExternalToolError):
        RestoreService(archiver=_RaisingArchiver()).extract(restore_spec)  # type: ignore[arg-type]

    backup_spec = BackupSpec(sources=(source,), destination=tmp_path, archive_name="x").normalize(default_destination=tmp_path)
    with pytest.raises(ExternalToolError):
        BackupService(archiver=_RaisingArchiver(), transfer=_PermissionTransfer(), hasher=_Hasher(), tool_detector=_Detector()).create(backup_spec)  # type: ignore[arg-type]


def test_transfer_unsupported_permission_and_missing_are_typed(tmp_path: Path) -> None:
    source = tmp_path / "source"; source.write_text("x")
    destination = tmp_path / "dest"
    push = TransferSpec(sources=(source,), destination=destination, direction="push").normalize()
    pull = TransferSpec(sources=(source,), destination=destination, direction="pull").normalize()
    with pytest.raises(ExternalToolError):
        TransferService(transfer=_UnsupportedTransfer()).push(push)  # type: ignore[arg-type]
    service = TransferService(transfer=_PermissionTransfer())  # type: ignore[arg-type]
    with pytest.raises(PermissionDeniedError):
        service.push(push)
    with pytest.raises(NotFoundError):
        service.pull(pull)


def test_trace_mode_preserves_typed_exit_code() -> None:
    reset_config()
    normal = RUNNER.invoke(app, ["archive", "list", "/definitely/missing/archive.tar"])
    traced = RUNNER.invoke(app, ["--trace", "archive", "list", "/definitely/missing/archive.tar"])
    assert normal.exit_code == 3
    assert traced.exit_code == 3
    assert "NotFoundError" in traced.stderr


def test_failed_result_never_exits_zero(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    failed = SimpleNamespace(
        success=False,
        archive_path=tmp_path / "bad.tar",
        checksums_valid=False,
        structure_valid=False,
        entries_checked=1,
        entries_failed=1,
        failed_entries=("broken",),
        warnings=(),
    )
    monkeypatch.setattr("bkx.cli.app.get_archive_service", lambda: SimpleNamespace(verify=lambda _path: failed))
    result = RUNNER.invoke(app, ["archive", "verify", str(tmp_path / "bad.tar")])
    assert result.exit_code != 0


def test_malformed_config_reaches_cli_as_usage_error(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / ".bkx.yaml").write_text("verify_after_operation: maybe\n", encoding="utf-8")
    reset_config()
    try:
        result = RUNNER.invoke(app, ["archive", "list", "missing.tar"])
        assert result.exit_code == 2
        assert "Invalid type" in result.output
    finally:
        reset_config()
