#!/usr/bin/env python3
"""GO15 public CLI parity: Python reference in-process vs Go production parser."""
from __future__ import annotations

import argparse
import contextlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

from typer.testing import CliRunner

from bkx.cli.app import app
import bkx.core.config as python_config
import bkx.core.impl.run_store as python_run_store


def _reset_python_state(home: Path) -> None:
    python_config._config = None
    python_run_store.DEFAULT_RUNS_DIR = home / ".local" / "share" / "bkx" / "runs"
    python_run_store.RUNS_FILE = python_run_store.DEFAULT_RUNS_DIR / "runs.jsonl"
    python_run_store._default_store = None
    python_run_store._default_store_key = None


def invoke_python(args: list[str], *, cwd: Path, env: dict[str, str]) -> tuple[int, str, str]:
    old = Path.cwd()
    home = Path(env["HOME"])
    try:
        os.chdir(cwd)
        _reset_python_state(home)
        result = CliRunner().invoke(app, args, env=env)
        # Click/Typer compatibility: Click 8.3 may not capture stderr
        # separately and raises ValueError when Result.stderr is accessed.
        # GO15 parity compares exit codes and stdout/JSON; stderr is only
        # diagnostic context, so preserve it when available and otherwise
        # use an empty string instead of failing the verifier itself.
        try:
            stderr = result.stderr
        except ValueError:
            stderr = ""
        return result.exit_code, result.stdout, stderr
    finally:
        os.chdir(old)
        _reset_python_state(home)


def invoke_go(binary: Path, args: list[str], *, cwd: Path, env: dict[str, str]) -> tuple[int, str, str]:
    result = subprocess.run([str(binary), *args], cwd=cwd, env=env, text=True, capture_output=True, check=False)
    return result.returncode, result.stdout, result.stderr


def load_json(text: str) -> object:
    return json.loads(text)


def compare(name: str, binary: Path, args: list[str], *, cwd: Path, env: dict[str, str], normalize=None) -> None:
    state_root = cwd / ".parity-state" / name
    py_env = dict(env)
    go_env = dict(env)
    py_env["HOME"] = str(state_root / "python-home")
    go_env["HOME"] = str(state_root / "go-home")
    Path(py_env["HOME"]).mkdir(parents=True, exist_ok=True)
    Path(go_env["HOME"]).mkdir(parents=True, exist_ok=True)
    py = invoke_python(args, cwd=cwd, env=py_env)
    go = invoke_go(binary, args, cwd=cwd, env=go_env)
    if py[0] != go[0]:
        raise AssertionError(f"{name}: exit mismatch Python={py[0]} Go={go[0]}\nPY={py}\nGO={go}")
    if "--json" in args:
        py_payload = load_json(py[1])
        go_payload = load_json(go[1])
        if normalize:
            py_payload = normalize(py_payload)
            go_payload = normalize(go_payload)
        if py_payload != go_payload:
            raise AssertionError(f"{name}: JSON mismatch\nPython={json.dumps(py_payload, indent=2, sort_keys=True)}\nGo={json.dumps(go_payload, indent=2, sort_keys=True)}")
    elif normalize:
        if normalize(py[1]) != normalize(go[1]):
            raise AssertionError(f"{name}: output mismatch\nPython={py[1]!r}\nGo={go[1]!r}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--go-binary", required=True, type=Path)
    ns = parser.parse_args()
    binary = ns.go_binary.resolve()
    with tempfile.TemporaryDirectory(prefix="bkx-go15-parity-") as raw:
        root = Path(raw)
        home = root / "home"
        work = root / "work"
        source = work / "source"
        home.mkdir()
        source.mkdir(parents=True)
        (source / "alpha.txt").write_text("alpha\n", encoding="utf-8")
        (source / "beta.bin").write_bytes(b"beta" * 13)
        archive = work / "fixture.zip"
        with zipfile.ZipFile(archive, "w") as zf:
            zf.write(source / "alpha.txt", "alpha.txt")
            zf.write(source / "beta.bin", "nested/beta.bin")
        patterns = work / "exclude.txt"
        patterns.write_text("# comment\n*.tmp\n", encoding="utf-8")
        env = dict(os.environ)
        env["HOME"] = str(home)
        env["PYTHONPATH"] = str(Path(__file__).resolve().parents[2] / "src") + os.pathsep + env.get("PYTHONPATH", "")

        cases = [
            ("archive-list", ["--json", "archive", "list", str(archive)]),
            ("archive-info", ["--json", "archive", "info", str(archive)]),
            ("archive-browse", ["--json", "archive", "browse", str(archive), "--query", "beta", "--sort", "size", "--order", "desc"]),
            ("archive-verify", ["--json", "archive", "verify", str(archive)]),
            ("archive-create-plan", ["--json", "--plan", "archive", "create", str(source), "-o", str(work / "new.tar.gz"), "--exclude-from", str(patterns)]),
            ("backup-plan", ["--json", "backup", "plan", str(source), "-d", str(work / "backups")]),
            ("backup-create-plan", ["--json", "--dry-run", "backup", "create", str(source), "-d", str(work / "backups2"), "--name", "manual"]),
            ("transfer-push-plan", ["--json", "--plan", "transfer", "push", str(source / "alpha.txt"), str(work / "copy.txt")]),
            ("restore-extract-plan", ["--json", "--plan", "restore", "extract", str(archive), "-d", str(work / "restore")]),
            ("restore-pick-plan", ["--json", "--plan", "restore", "pick", str(archive), "alpha.txt", "-d", str(work / "pick")]),
            ("tools-explain", ["--json", "tools", "explain"]),
            ("run-list", ["--json", "run", "list"]),
            ("not-found", ["--json", "archive", "info", str(work / "missing.zip")]),
            ("typed-ui-error", ["--json", "ui", "start", "--mode", "invalid"]),
        ]
        for name, args in cases:
            compare(name, binary, args, cwd=work, env=env)

        # Usage semantics are contract-significant even though Typer owns the
        # exact reference wording. Exit 2 and no accidental JSON document are
        # the frozen machine behavior for parser failures.
        for name, args in [
            ("missing-archive", ["archive", "info"]),
            ("unknown-root", ["unknown"]),
            ("bad-progress", ["--progress", "loud", "tools", "detect"]),
        ]:
            py = invoke_python(args, cwd=work, env=env)
            go = invoke_go(binary, args, cwd=work, env=env)
            if py[0] != 2 or go[0] != 2:
                raise AssertionError(f"{name}: expected usage exit 2, Python={py[0]} Go={go[0]}")

    print(f"GO15 public CLI parity passed: {len(cases)} JSON cases + 3 usage cases")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
