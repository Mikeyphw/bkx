#!/usr/bin/env python3
"""Differential GO02/GO03 parity checks against the production Python backend."""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import tempfile
from pathlib import Path

from bkx.core.config import BkxConfig
from bkx.core.impl.run_store import JsonRunStore


def _run(go_binary: Path, *args: str, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [str(go_binary), *args],
        text=True,
        capture_output=True,
        env=env,
        check=False,
    )


def check_run_history(go_binary: Path, root: Path) -> None:
    runs = root / "runs.jsonl"
    python_store = JsonRunStore(runs_file=runs, recover_started=False)
    python_store.record_start(
        "py-probe",
        "backup",
        ("/python/source",),
        "/python/destination",
        None,
        {"phase": "GO02", "verify": True},
    )
    python_store.record_completion("py-probe", "success", files_count=2, bytes_processed=11)

    result = _run(go_binary, "migration", "run-history", "append-probe", str(runs))
    assert result.returncode == 0, result.stderr or result.stdout

    reopened = JsonRunStore(runs_file=runs, recover_started=False)
    assert reopened.count_runs() == 2
    py_record = reopened.get_run("py-probe")
    go_record = reopened.get_run("go-probe")
    assert py_record is not None and py_record.status == "success"
    assert go_record is not None and go_record.status == "success"
    assert go_record.files_count == 1
    assert go_record.bytes_processed == 7
    assert go_record.spec_snapshot == {"phase": "GO02", "verify": True}

    # Both writers must leave canonical one-object-per-line JSON accepted by the
    # frozen Python parser. Go field order is deliberately chosen to match
    # Python's sort_keys=True representation byte-for-byte for record keys.
    for raw in runs.read_text(encoding="utf-8").splitlines():
        payload = json.loads(raw)
        canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"))
        assert raw == canonical, (raw, canonical)

    # Go must quarantine a malformed line without losing the valid records.
    with runs.open("a", encoding="utf-8") as stream:
        stream.write("not-json\n")
    result = _run(go_binary, "migration", "run-history", "retention-probe", str(runs), "50")
    assert result.returncode == 0, result.stderr or result.stdout
    assert (root / "runs.jsonl.corrupt").is_file()
    reopened = JsonRunStore(runs_file=runs, recover_started=False)
    assert reopened.count_runs() == 2


def check_config(go_binary: Path, root: Path) -> None:
    cwd = root / "cwd"
    home = root / "home"
    cwd.mkdir(parents=True)
    home.mkdir(parents=True)
    (cwd / ".bkx.yaml").write_text(
        "\n".join(
            [
                "default_destination: relative-backups",
                "verify_after_operation: false",
                "compression_algorithm: gzip",
                "compression_level: 5",
                "fresh_restore_dir: true",
                "run_history_enabled: true",
                "run_history_limit: 21",
                "trace: false",
            ]
        )
        + "\n",
        encoding="utf-8",
    )
    updates = {
        "BKX_COMPRESSION_LEVEL": "8",
        "BKX_HISTORY_LIMIT": "33",
        "BKX_TRACE": "yes",
    }
    file_config = BkxConfig.from_dict(
        {
            "default_destination": "relative-backups",
            "verify_after_operation": False,
            "compression_algorithm": "gzip",
            "compression_level": 5,
            "fresh_restore_dir": True,
            "run_history_enabled": True,
            "run_history_limit": 21,
            "trace": False,
        },
        base_dir=cwd,
        config_path=cwd / ".bkx.yaml",
    )
    python_config = BkxConfig._merge(BkxConfig(), file_config)
    python_config = BkxConfig._merge(python_config, BkxConfig.from_env(updates))
    env = {name: value for name, value in os.environ.items() if not name.startswith("BKX_")}
    env.update(updates)

    result = _run(go_binary, "migration", "config", "probe", str(cwd), str(home), env=env)
    assert result.returncode == 0, result.stderr or result.stdout
    go_config = json.loads(result.stdout)

    expected = {
        "default_destination": str(python_config.default_destination),
        "verify_after_operation": python_config.verify_after_operation,
        "compression_algorithm": python_config.compression_algorithm,
        "compression_level": python_config.compression_level,
        "fresh_restore_dir": python_config.fresh_restore_dir,
        "run_history_enabled": python_config.run_history_enabled,
        "run_history_limit": python_config.run_history_limit,
        "trace": python_config.trace,
    }
    for key, value in expected.items():
        assert go_config[key] == value, (key, go_config[key], value)
        assert go_config["sources"][key] == python_config.source_of(key), key


def check_fail_closed(go_binary: Path) -> None:
    for command in (("backup",), ("archive", "list"), ("restore",), ("transfer",), ("run",), ("ui",)):
        result = _run(go_binary, *command)
        assert result.returncode == 64, (command, result.returncode, result.stdout, result.stderr)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--go-binary", required=True, type=Path)
    args = parser.parse_args()
    binary = args.go_binary.resolve()
    if not binary.is_file():
        raise SystemExit(f"Go probe binary not found: {binary}")
    with tempfile.TemporaryDirectory(prefix="bkx-go02-go03-") as raw:
        root = Path(raw)
        check_run_history(binary, root / "history")
        check_config(binary, root / "config")
        check_fail_closed(binary)
    print("BKX GO02-GO03 Python/Go differential parity passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
