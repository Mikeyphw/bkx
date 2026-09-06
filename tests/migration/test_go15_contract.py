from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(rel: str) -> str:
    return (ROOT / rel).read_text(encoding="utf-8")


def test_go15_phase_and_public_dispatcher_own_frozen_cli():
    assert 'MigrationPhase = "GO15"' in read("internal/version/version.go")
    contract = json.loads(read("migration/contracts/v1/cli.json"))
    assert contract["commandNodeCount"] == 23
    assert contract["leafCommandCount"] == 16
    source = read("internal/publiccli/commands.go")
    for node in contract["commandNodes"]:
        if not node["isGroup"]:
            assert f'"{node["command"]}"' in source
    main = read("cmd/bkx/main.go")
    assert "publiccli.Run(args, os.Stdout, os.Stderr)" in main
    assert "publiccli.IsInternalInvocation(args)" in main


def test_go15_global_version_precedes_no_command_help_and_has_goldens():
    app = read("internal/publiccli/app.go")
    assert "--version must win over the no-command help path" in app
    assert "if globals.Version" in app
    assert (ROOT / "tests/fixtures/go15/root_help.txt").is_file()
    assert (ROOT / "tests/fixtures/go15/missing_archive.stderr").is_file()


def test_go15_keeps_migration_and_android_probe_routes_internal():
    app = read("internal/publiccli/app.go")
    for route in ("migration", "android-runtime", "contract", "version"):
        assert f'"{route}"' in app
    help_text = read("tests/fixtures/go15/root_help.txt")
    assert "migration" not in help_text
    assert "android-runtime" not in help_text
    assert "contract check" not in help_text


def test_go15_python_reference_still_owns_packaging_and_only_ui_exception():
    devtool = read(".devtool.toml")
    pyproject = read("pyproject.toml")
    ui = read("internal/publiccli/ui_reference.go")
    assert '[targets.bkx]\ntype = "python"' in devtool
    assert 'bkx = "bkx.cli.app:main"' in pyproject
    assert "sole Python-reference exception" in ui
    assert "BKX_PYTHON_REFERENCE" in ui
    assert "python3" in ui


def test_go15_cli_parity_and_historical_phase_aware_seals_exist():
    verifier = read("tools/go_migration/verify_go15_cli_parity.py")
    validator = read("scripts/validate-go15.sh")
    assert "CliRunner" in verifier
    assert "JSON cases + 3 usage cases" in verifier
    assert "len(cases)" in verifier
    assert "verify_go15_cli_parity.py" in validator
    for rel in ("scripts/validate-go11.sh", "scripts/validate-go12.sh", "scripts/validate-go13.sh", "scripts/validate-go14.sh"):
        text = read(rel)
        assert "phase_num" in text or "range(14, 100)" in text


def test_go15_android_arm64_policy_suppresses_only_inapplicable_chromeos_abi_lint():
    app = read("android/BKX.Android/app/build.gradle.kts")
    assert 'abiFilters += listOf("arm64-v8a")' in app
    assert 'ndkVersion = "29.0.14206865"' in app
    assert '"ChromeOsAbiSupport"' in app
    assert "x86_64" not in app
    assert "chaquopy" not in app.lower()


def test_go15_ci_uses_flattened_cutover_gate():
    ci = read(".github/workflows/ci.yml")
    assert "GO00-GO15 public CLI cutover gate" in ci
    assert "./scripts/validate-go15.sh" in ci
