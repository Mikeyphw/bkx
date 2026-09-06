from __future__ import annotations

import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def test_go16_phase_and_devtool_production_identity_are_go():
    assert 'MigrationPhase = "GO16"' in read("internal/version/version.go")
    data = tomllib.loads(read(".devtool.toml"))
    assert data["project"]["kind"] == "go"
    assert data["targets"]["bkx"]["type"] == "go"
    assert data["targets"]["bkx"]["root"] == "."
    assert data["targets"]["bkx_reference"]["type"] == "python"
    assert "bkx_go" not in data["targets"]
    assert data["python"]["package"]["verify_cli"] is False
    assert data["python"]["package"]["console_scripts"] == []


def test_go16_python_package_is_reference_only_without_console_ownership():
    data = tomllib.loads(read("pyproject.toml"))
    project = data["project"]
    assert project["dependencies"] == []
    assert "scripts" not in project or "bkx" not in project.get("scripts", {})
    reference = "\n".join(project["optional-dependencies"]["reference"]).lower()
    for dependency in ("typer", "rich", "pyyaml", "jsonschema"):
        assert dependency in reference


def test_go16_optional_ui_is_external_plugin_only():
    assert not (ROOT / "internal/publiccli/ui_reference.go").exists()
    source = read("internal/publiccli/ui_plugins.go")
    for token in ("bkx-tui", "bkx-interactive", "BKX_TUI_COMMAND", "BKX_INTERACTIVE_COMMAND", "KindOptional", "exec.LookPath"):
        assert token in source
    assert "exec.Command(path)" in source
    assert "sh -c" not in source
    assert "python" not in source.lower()


def test_go16_release_builder_is_deterministic_and_seals_host_plus_android():
    release = read("scripts/build-go-release.sh")
    android = read("scripts/build-go-android-runtime.sh")
    for token in ("-trimpath", "-buildvcs=false", "-buildid=", "SHA256SUMS", "build-go-android-runtime.sh", "BKX_BUILD_COMMIT"):
        assert token in release
    for token in ("-trimpath", "-buildvcs=false", "-buildid=", "BKX_BUILD_COMMIT", "GOOS=android GOARCH=arm64"):
        assert token in android


def test_go16_ci_separates_python_reference_from_go_production():
    ci = read(".github/workflows/ci.yml")
    assert "python-reference:" in ci
    assert "go-production:" in ci
    assert ".[reference,dev]" in ci
    assert "assert-reference-has-no-console-script" in ci
    assert "GO00-GO16 production Go release gate" in ci
    assert "./scripts/validate-go16.sh" in ci


def test_go16_docs_state_python_reference_and_go_production_identity():
    readme = read("README.md")
    migration = read("docs/migration/python-to-go.md")
    assert "Go production CLI" in readme
    assert "Python reference" in readme
    assert "GO16 — Go production identity and deterministic release — COMPLETE" in migration
    assert "GO17" in migration
