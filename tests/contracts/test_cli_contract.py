from __future__ import annotations

import json
import tarfile

import pytest
from pathlib import Path

from typer.testing import CliRunner

from bkx.cli.app import app
from bkx.cli.spec_builders import build_restore_spec
from bkx.core.errors import ValidationError

runner = CliRunner()


def _tar_with_many_entries(path: Path, count: int = 3) -> None:
    root = path.parent / "src"
    root.mkdir()
    for idx in range(count):
        (root / f"f{idx}.txt").write_text(str(idx), encoding="utf-8")
    with tarfile.open(path, "w") as tf:
        for item in sorted(root.iterdir()):
            tf.add(item, arcname=item.name)


def test_unknown_global_option_is_rejected() -> None:
    result = runner.invoke(app, ["--definitely-unknown", "tools", "detect"])
    assert result.exit_code == 2


def test_invalid_progress_is_rejected() -> None:
    result = runner.invoke(app, ["--progress", "sparkles", "tools", "detect"])
    assert result.exit_code == 2


def test_json_tools_detect_is_single_parseable_document() -> None:
    result = runner.invoke(app, ["--json", "tools", "detect"])
    assert result.exit_code == 0
    data = json.loads(result.stdout)
    assert data["type"] == "tools_detect"
    assert isinstance(data["tools"], dict)


def test_archive_list_json_is_one_document_with_pagination(tmp_path: Path) -> None:
    archive = tmp_path / "items.tar"
    _tar_with_many_entries(archive, count=5)

    result = runner.invoke(app, ["--json", "archive", "list", str(archive), "--page", "1", "--page-size", "2"])

    assert result.exit_code == 0
    data = json.loads(result.stdout)
    assert data["type"] == "archive_list"
    assert data["page"] == 1
    assert data["page_size"] == 2
    assert len(data["entries"]) == 2
    assert data["total_entries"] == 5


def test_archive_browse_accepts_offset(tmp_path: Path) -> None:
    archive = tmp_path / "items.tar"
    _tar_with_many_entries(archive, count=5)

    result = runner.invoke(app, ["--json", "archive", "browse", str(archive), "--limit", "2", "--offset", "3"])

    assert result.exit_code == 0
    data = json.loads(result.stdout)
    assert data["type"] == "archive_browse"
    assert data["offset"] == 3
    assert len(data["entries"]) == 2


def test_global_plan_alias_renders_plan(tmp_path: Path) -> None:
    source = tmp_path / "src"
    source.mkdir()
    result = runner.invoke(app, ["--json", "--plan", "backup", "create", str(source)])
    assert result.exit_code == 0
    data = json.loads(result.stdout)
    assert data["type"] == "plan"
    assert data["spec_type"] == "backup"


def test_include_and_exclude_from_files_are_consumed(tmp_path: Path) -> None:
    src = tmp_path / "src"
    src.mkdir()
    (src / "keep.txt").write_text("ok", encoding="utf-8")
    includes = tmp_path / "includes.txt"
    excludes = tmp_path / "excludes.txt"
    includes.write_text("*.txt\n", encoding="utf-8")
    excludes.write_text("# comment\n*.tmp\n", encoding="utf-8")
    result = runner.invoke(app, ["--json", "--plan", "archive", "create", str(src), "-o", str(tmp_path / "x.tar"), "--include-from", str(includes), "--exclude-from", str(excludes)])
    assert result.exit_code == 0
    data = json.loads(result.stdout)
    assert data["type"] == "plan"
    assert data["spec_type"] == "archive"


def test_restore_excludes_are_not_silently_discarded(tmp_path: Path) -> None:
    archive = tmp_path / "x.tar"
    archive.write_bytes(b"x")
    try:
        build_restore_spec(archive, excludes=["*.tmp"])
    except ValidationError as exc:
        assert "not supported" in str(exc)
    else:  # pragma: no cover
        raise AssertionError("restore excludes were silently accepted")


def test_restore_pick_command_exists_and_validates_archive(tmp_path: Path) -> None:
    result = runner.invoke(app, ["restore", "pick", str(tmp_path / "missing.tar"), "file.txt"])
    assert result.exit_code == 3


def test_ui_start_exists_and_top_level_tui_is_not_a_grammar_variant() -> None:
    help_result = runner.invoke(app, ["ui", "start", "--help"])
    assert help_result.exit_code == 0
    bad_result = runner.invoke(app, ["tui"])
    assert bad_result.exit_code == 2


def test_trace_preserves_json_error_document_and_stderr_trace(monkeypatch: pytest.MonkeyPatch) -> None:
    from typer.testing import CliRunner

    from bkx.cli.app import app
    from bkx.cli import app as app_module
    from bkx.core.errors import ValidationError

    def boom(*args, **kwargs):
        raise ValidationError(msg="bad backup input", details={"field": "sources"})

    monkeypatch.setattr(app_module, "build_backup_spec", boom)
    result = CliRunner().invoke(app, ["--json", "--trace", "backup", "create", "missing-src"])

    assert result.exit_code != 0
    payload = json.loads(result.stdout)
    assert payload["type"] == "error"
    assert payload["success"] is False
    assert payload["message"] == "bad backup input"
    assert "ValidationError" in result.stderr


def test_text_renderer_backup_method_is_plain_and_usable(capsys: pytest.CaptureFixture[str]) -> None:
    from bkx.cli.render.renderer import TextRenderer
    from bkx.core.results.backup import BackupResult, Timing

    TextRenderer().render_backup_result(
        BackupResult(
            success=True,
            dry_run=False,
            timing=Timing(0, 0),
            files_processed=1,
            bytes_written=2,
            archive_paths=(Path("backup.bakery"),),
            warnings=("warn",),
            verification_passed=True,
        )
    )

    out = capsys.readouterr().out
    assert "OK backup" in out
    assert "warn" in out
    assert "[" not in out or "[/" not in out
