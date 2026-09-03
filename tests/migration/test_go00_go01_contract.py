from __future__ import annotations

import json
import os
import subprocess
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "migration" / "contracts" / "v1"


def test_go00_contracts_match_python_backend() -> None:
    env = dict(os.environ)
    env["PYTHONPATH"] = str(ROOT / "src")
    result = subprocess.run(
        [sys.executable, str(ROOT / "tools/go_migration/export_contracts.py"), "--check"],
        cwd=ROOT,
        env=env,
        text=True,
        capture_output=True,
        check=False,
    )
    assert result.returncode == 0, result.stderr or result.stdout


def test_go00_baseline_counts_and_runtime_identity_are_frozen() -> None:
    cli = json.loads((CONTRACTS / "cli.json").read_text(encoding="utf-8"))
    bridge = json.loads((CONTRACTS / "bridge.json").read_text(encoding="utf-8"))
    release = json.loads((CONTRACTS / "release.json").read_text(encoding="utf-8"))
    assert cli["commandNodeCount"] == 23
    assert cli["leafCommandCount"] == 16
    assert bridge["protocolVersion"] == 1
    assert bridge["capabilityCount"] == 18
    assert bridge["handlerCount"] == 16
    assert bridge["advertisedOperationCount"] == 15
    assert bridge["arbitraryShell"] is False
    assert release["productVersion"] == "1.0.0"
    assert release["productionBackend"] == {"language": "python", "runtime": "embedded-cpython"}


def test_go01_devtool_lane_does_not_cut_over_production() -> None:
    data = tomllib.loads((ROOT / ".devtool.toml").read_text(encoding="utf-8"))
    assert data["targets"]["bkx"]["type"] == "python"
    assert data["targets"]["bkx_android"]["type"] == "android"
    assert data["targets"]["bkx_go"]["type"] == "go"
    assert data["targets"]["bkx_go"]["root"] == "."
    assert data["go"]["packages"] == ["./..."]
    assert data["go"]["build_target"] == "./cmd/bkx"
    assert data["go"]["build_targets"] == ["./cmd/bkx"]


def test_go01_scaffold_source_is_fail_closed_for_product_commands() -> None:
    source = (ROOT / "cmd/bkx/main.go").read_text(encoding="utf-8")
    assert "Go migration scaffold is not the production backend yet" in source
    assert "return 64" in source
    for command in ("backup", "archive", "restore", "transfer", "tools", "run", "ui"):
        assert f'"{command}"' in (ROOT / "cmd/bkx/main_test.go").read_text(encoding="utf-8")
