from __future__ import annotations

import json
import tomllib
from importlib.resources import files
from pathlib import Path

from typer.testing import CliRunner

from hypothesis import given, strategies as st
from jsonschema.validators import validator_for

from bkx.cli.app import app
from bkx.core.parity import spec_to_dict, validate_spec_against_schema
from bkx.core.specs.archive import ArchiveCreateSpec
from bkx.core.specs.backup import BackupSpec, CompressionProfile
from bkx.core.specs.restore import RestoreSpec
from bkx.core.specs.transfer import TransferSpec


def _project() -> dict:
    return tomllib.loads(Path("pyproject.toml").read_text(encoding="utf-8"))["project"]


def test_dev_extra_contains_fail_closed_test_and_release_tools() -> None:
    optional = _project()["optional-dependencies"]
    dev = "\n".join(optional["dev"])
    for dep in ["hypothesis", "pytest", "pytest-cov", "jsonschema", "build", "ruff", "pyright"]:
        assert dep.lower() in dev.lower()


def test_ci_declares_python_matrix_and_base_plus_extras_wheel_smoke() -> None:
    ci = Path(".github/workflows/ci.yml").read_text(encoding="utf-8")
    assert "3.11" in ci and "3.12" in ci and "3.13" in ci
    assert "python -m build --wheel" in ci
    assert "python -m bkx --help" in ci
    assert "/bkx --help" in ci or "bin/bkx --help" in ci
    assert "/tmp/bkx-wheel-base-smoke" in ci
    assert "/tmp/bkx-wheel-extras-smoke" in ci
    assert "$WHEEL[interactive,tui]" in ci
    assert "import bkx.interactive.main" in ci
    assert "import bkx.tui.app" in ci
    assert "backup_spec.json" in ci
    assert ".tcss" in ci


def test_ci_fails_closed_when_optional_ui_validator_dependencies_are_absent() -> None:
    ci = Path(".github/workflows/ci.yml").read_text(encoding="utf-8")
    assert "MIKOMN_ROOT must point" in ci
    assert "exit 1" in ci
    assert "Optional Interactive/TUI tests must not disappear silently" in ci
    assert "Optional UI tests" in ci
    assert "tests/interactive tests/tui tests/ov11" in ci
    assert "optional Interactive/TUI import tests are skipped" not in ci


def test_validator_local_deps_use_explicit_local_mikomn_checkout() -> None:
    deps = Path("scripts/validator-local-deps.txt").read_text(encoding="utf-8")
    assert "mikomn[cli,tui]" in deps
    assert "${MIKOMN_ROOT}" in deps


def test_packaged_schema_and_css_assets_are_discoverable() -> None:
    assert files("bkx.core.schemas").joinpath("backup_spec.json").is_file()
    css = files("bkx.tui").joinpath("css")
    css_files = sorted(str(path) for path in css.rglob("*.tcss") if path.is_file())
    assert len(css_files) >= 21
    assert any(path.endswith("shell.tcss") for path in css_files)
    assert any("panes/transfer_progress.tcss" in path for path in css_files)


def test_json_schemas_are_valid_draft_schemas() -> None:
    for name in ["backup_spec.json", "archive_spec.json", "restore_spec.json", "transfer_spec.json"]:
        schema = __import__("json").loads(files("bkx.core.schemas").joinpath(name).read_text(encoding="utf-8"))
        validator = validator_for(schema)
        validator.check_schema(schema)


@given(st.text(alphabet=st.characters(blacklist_categories=("Cs",), blacklist_characters="\x00"), min_size=1, max_size=24))
def test_schema_rejects_runtime_invalid_archive_names(name: str) -> None:
    if name.strip() not in {"", ".", ".."} and "/" not in name and "\\" not in name:
        return
    data = {
        "sources": ["src"],
        "destination": "dest",
        "archive_name": name,
        "excludes": [],
        "includes": [],
        "split_size": None,
        "compression": {"algorithm": "zstd", "level": 3},
        "verify": True,
        "dry_run": False,
    }
    schema = __import__("json").loads(files("bkx.core.schemas").joinpath("backup_spec.json").read_text(encoding="utf-8"))
    ok, _errors = validate_spec_against_schema(data, schema)
    assert not ok


def test_normalized_specs_validate_against_packaged_schemas(tmp_path: Path) -> None:
    src = tmp_path / "src"
    src.mkdir()
    archive = tmp_path / "a.tar"
    archive.write_bytes(b"x")
    specs = [
        (BackupSpec(sources=(src,), destination=tmp_path / "out", archive_name="demo", compression=CompressionProfile("gzip", 4)).normalize(), "backup_spec.json"),
        (ArchiveCreateSpec(sources=(src,), output_path=tmp_path / "out.tar", compression=CompressionProfile("gzip", 4)).normalize(), "archive_spec.json"),
        (RestoreSpec(archive=archive, destination=tmp_path / "restore", fresh_dir=False).normalize(), "restore_spec.json"),
        (TransferSpec(sources=(src,), destination=tmp_path / "copy", direction="push", verify=False).normalize(), "transfer_spec.json"),
    ]
    for spec, schema_name in specs:
        schema = __import__("json").loads(files("bkx.core.schemas").joinpath(schema_name).read_text(encoding="utf-8"))
        ok, errors = validate_spec_against_schema(spec_to_dict(spec), schema)
        assert ok, (schema_name, errors, spec_to_dict(spec))


def test_readme_no_longer_claims_empty_skeleton() -> None:
    text = Path("README.md").read_text(encoding="utf-8").lower()
    assert "empty implementation skeleton" not in text
    assert "no business logic" not in text
    assert "current verified capabilities" in text


def test_release_gate_json_outputs_are_single_parseable_documents(tmp_path: Path) -> None:
    result = CliRunner().invoke(app, ["--json", "tools", "detect"])
    assert result.exit_code == 0
    tools_payload = json.loads(result.stdout)
    assert isinstance(tools_payload, dict)
    assert set(tools_payload) >= {"tools", "type"}

    src = tmp_path / "src"
    src.mkdir()
    plan = CliRunner().invoke(app, ["--json", "--plan", "backup", "create", str(src), "--destination", str(tmp_path / "out"), "--name", "snapshot"])
    assert plan.exit_code == 0
    plan_payload = json.loads(plan.stdout)
    assert plan_payload["success"] is True
    assert plan_payload["spec"]["dry_run"] is True
    assert plan_payload["type"] == "plan"


def test_release_seal_names_required_adversarial_probe_families() -> None:
    # This test fails closed if the release-seal checklist forgets the adversarial
    # families required by RM-051.
    checklists = "\n".join(path.read_text(encoding="utf-8").lower() for path in Path("docs/checklists").glob("CHECKLIST_*.md"))
    required = [
        "path traversal",
        "symlink",
        "fifo",
        "overlap",
        "partial-write",
        "corruption",
        "json output",
        "schema",
        "service failure",
    ]
    missing = [word for word in required if word not in checklists]
    assert not missing, missing


def test_release_seal_collects_required_adversarial_probe_tests() -> None:
    # RM-051 requires the release seal to fail if these probe families disappear
    # from collected tests. Use stable test names/constructs rather than checklist
    # prose so documentation alone cannot satisfy the gate.
    test_sources = "\n".join(path.read_text(encoding="utf-8").lower() for path in Path("tests").rglob("test_*.py"))
    required_markers = {
        "path traversal": "test_restore_rejects_tar_path_traversal",
        "symlink": "test_restore_rejects_tar_symlink_members",
        "fifo": "mkfifo",
        "overlap/collision": "test_transfer_detects_destination_collisions_before_copy",
        "partial-write": "test_archive_publication_is_transactional_when_backend_fails",
        "corruption": "bad.write_bytes(data.replace",
        "json output snapshot": "test_release_gate_json_outputs_are_single_parseable_documents",
        "schema negative": "test_schema_rejects_runtime_invalid_archive_names",
        "service failure": "test_archive_restore_backup_backend_failures_are_typed",
    }
    missing = [label for label, marker in required_markers.items() if marker.lower() not in test_sources]
    assert not missing, missing
