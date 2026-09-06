#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import tarfile
import tempfile
import time
from dataclasses import asdict
from pathlib import Path

from bkx.core.errors import ValidationError
from bkx.core.impl.archiver import FallbackArchiver
from bkx.core.impl.hasher import FallbackHasher
from bkx.core.impl.run_store import JsonRunStore
from bkx.core.impl.tool_detector import FallbackToolDetector
from bkx.core.impl.transfer import FallbackTransfer
from bkx.core.results.execution_plan import execution_plan_to_dict, plan_for_spec
from bkx.core.services.backup import BackupService
from bkx.core.specs.backup import BackupSpec, CompressionProfile


def payload(spec: BackupSpec) -> str:
    return json.dumps(
        {
            "sources": [str(x) for x in spec.sources],
            "destination": str(spec.destination) if spec.destination is not None else None,
            "archive_name": spec.archive_name,
            "excludes": list(spec.excludes),
            "includes": list(spec.includes),
            "split_size": spec.split_size,
            "compression": {"algorithm": spec.compression.algorithm, "level": spec.compression.level},
            "skip_symlinks": spec.skip_symlinks,
            "verify": spec.verify,
            "dry_run": spec.dry_run,
        },
        separators=(",", ":"),
    )


def go(binary: Path, *args: str) -> subprocess.CompletedProcess[str]:
    env = dict(os.environ)
    env["TZ"] = "UTC"
    return subprocess.run([str(binary), *args], text=True, capture_output=True, env=env, check=False)


def python_service(runs: Path | None = None) -> BackupService:
    return BackupService(
        archiver=FallbackArchiver(),
        transfer=FallbackTransfer(),
        hasher=FallbackHasher(),
        tool_detector=FallbackToolDetector(),
        run_store=JsonRunStore(runs, recover_started=False) if runs is not None else None,
    )


def make_source(root: Path) -> Path:
    src = root / "source"
    (src / "empty").mkdir(parents=True)
    (src / "nested").mkdir()
    (src / "a.txt").write_bytes(b"alpha\n")
    (src / "nested" / "b.txt").write_bytes(b"bravo\n")
    (src / "nested" / "ignore.log").write_bytes(b"ignored\n")
    epoch_ns = 1_704_164_646_000_000_000
    for path in (src, src / "empty", src / "nested", src / "a.txt", src / "nested" / "b.txt", src / "nested" / "ignore.log"):
        os.utime(path, ns=(epoch_ns, epoch_ns), follow_symlinks=False)
    return src


def logical_result(value) -> dict:
    if isinstance(value, dict):
        raw = value
        return {
            "success": raw["success"],
            "dry_run": raw["dry_run"],
            "files_processed": raw["files_processed"],
            "archives_created": raw["archives_created"],
            "archive_names": [Path(x).name for x in raw["archive_paths"]],
            "verification_passed": raw["verification_passed"],
            "warnings": raw["warnings"],
            "errors": raw["errors"],
            "step_results": raw["step_results"],
        }
    return {
        "success": value.success,
        "dry_run": value.dry_run,
        "files_processed": value.files_processed,
        "archives_created": value.archives_created,
        "archive_names": [x.name for x in value.archive_paths],
        "verification_passed": value.verification_passed,
        "warnings": list(value.warnings),
        "errors": list(value.errors),
        "step_results": dict(value.step_results),
    }


def member_snapshot(path: Path) -> list[tuple[str, bool, int, str | None]]:
    out: list[tuple[str, bool, int, str | None]] = []
    with tarfile.open(path, "r:*") as tf:
        for member in tf.getmembers():
            digest = None
            if member.isfile():
                stream = tf.extractfile(member)
                assert stream is not None
                digest = hashlib.sha256(stream.read()).hexdigest()
            out.append((member.name, member.isdir(), member.size, digest))
    return out


def check_plan(binary: Path, root: Path, source: Path) -> None:
    destination = root / "plan-destination"
    spec = BackupSpec(sources=(source,), destination=destination, excludes=("*.log",), verify=True)
    expected = execution_plan_to_dict(plan_for_spec(spec))
    cp = go(binary, "migration", "backup", "plan", payload(spec))
    if cp.returncode != 0:
        raise AssertionError(f"GO08 plan failed:\nstdout={cp.stdout}\nstderr={cp.stderr}")
    actual = json.loads(cp.stdout)
    assert actual == expected, (actual, expected)


def check_create(binary: Path, root: Path, source: Path) -> None:
    py_dest = root / "python-final"
    go_dest = root / "go-final"
    py_runs = root / "python-runs.jsonl"
    go_runs = root / "go-runs.jsonl"
    kwargs = dict(sources=(source,), archive_name="explicit", excludes=("*.log",), verify=True)
    py_spec = BackupSpec(destination=py_dest, **kwargs)
    go_spec = BackupSpec(destination=go_dest, **kwargs)
    py_result = python_service(py_runs).create(py_spec)
    cp = go(binary, "migration", "backup", "create", payload(go_spec), "true", str(go_runs), "-")
    if cp.returncode != 0:
        raise AssertionError(f"GO08 create failed:\nstdout={cp.stdout}\nstderr={cp.stderr}")
    go_result = json.loads(cp.stdout)
    assert logical_result(go_result) == logical_result(py_result), (logical_result(go_result), logical_result(py_result))
    py_archive = py_dest / "explicit.bakery"
    go_archive = go_dest / "explicit.bakery"
    assert member_snapshot(go_archive) == member_snapshot(py_archive)
    go_digest = hashlib.sha256(go_archive.read_bytes()).hexdigest()
    py_digest = hashlib.sha256(py_archive.read_bytes()).hexdigest()
    assert go_result["checksums"]["archive"] == go_digest == go_result["checksums"]["explicit.bakery"]
    assert dict(py_result.checksums)["archive"] == py_digest == dict(py_result.checksums)["explicit.bakery"]
    assert go_result["bytes_written"] == go_archive.stat().st_size * 2
    assert py_result.bytes_written == py_archive.stat().st_size * 2

    py_record = JsonRunStore(py_runs, recover_started=False).list_runs(limit=1)[0]
    go_record = json.loads(go_runs.read_text(encoding="utf-8").splitlines()[-1])
    assert go_record["operation"] == py_record.operation == "backup"
    assert go_record["status"] == py_record.status == "success"
    assert go_record["source_paths"] == [str(x) for x in py_record.source_paths]
    assert Path(go_record["destination_path"]).name == Path(py_record.destination_path).name.replace("python-final", "go-final")
    assert Path(go_record["archive_path"]).name == Path(py_record.archive_path).name == "explicit.bakery"
    assert go_record["files_count"] == py_record.files_count
    assert go_record["bytes_processed"] == go_result["bytes_written"]
    assert py_record.bytes_processed == py_result.bytes_written
    assert go_record["error_message"] == py_record.error_message
    assert go_record["spec_snapshot"]["type"] == py_record.spec_snapshot["type"] == "BackupSpec"
    go_values = go_record["spec_snapshot"]["values"]
    py_values = py_record.spec_snapshot["values"]
    for key in ("archive_name", "excludes", "includes", "split_size", "compression", "verify", "dry_run", "skip_symlinks"):
        assert go_values[key] == py_values[key], (key, go_values[key], py_values[key])


def check_dry_run(binary: Path, root: Path, source: Path) -> None:
    py_dest = root / "python-dry-final"
    go_dest = root / "go-dry-final"
    kwargs = dict(sources=(source,), archive_name="dry", compression=CompressionProfile("none", 0), verify=True, dry_run=True)
    py_result = python_service().create(BackupSpec(destination=py_dest, **kwargs))
    go_spec = BackupSpec(destination=go_dest, **kwargs)
    cp = go(binary, "migration", "backup", "create", payload(go_spec), "false", "-", "-")
    assert cp.returncode == 0, cp.stderr
    assert logical_result(json.loads(cp.stdout)) == logical_result(py_result)
    assert not py_dest.exists() and not go_dest.exists()


def check_fail_closed(binary: Path, root: Path, source: Path) -> None:
    split = 1024
    spec = BackupSpec(
        sources=(source,), destination=root / "split-final", archive_name="split",
        compression=CompressionProfile("none", 0), split_size=split,
    )
    try:
        python_service().create(spec)
    except ValidationError:
        pass
    else:
        raise AssertionError("Python split backup unexpectedly succeeded")
    cp = go(binary, "migration", "backup", "create", payload(spec), "false", "-", "-")
    assert cp.returncode != 0, cp.stdout
    assert not (root / "split-final").exists()

    malformed = json.loads(payload(BackupSpec(sources=(source,), archive_name="shape")))
    malformed["extra"] = 1
    cp = go(binary, "migration", "backup", "plan", json.dumps(malformed, separators=(",", ":")))
    assert cp.returncode != 0


def check_cancel(binary: Path, root: Path, source: Path) -> None:
    destination = root / "cancel-final"
    cancel = root / "cancel.marker"
    cancel.write_text("cancel", encoding="utf-8")
    runs = root / "cancel-runs.jsonl"
    spec = BackupSpec(sources=(source,), destination=destination, archive_name="cancel")
    cp = go(binary, "migration", "backup", "create", payload(spec), "true", str(runs), str(cancel))
    assert cp.returncode != 0
    assert not destination.exists()
    record = json.loads(runs.read_text(encoding="utf-8").splitlines()[-1])
    assert record["status"] == "interrupted"
    assert "cancel" in (record["error_message"] or "").lower()


def check(binary: Path) -> None:
    os.environ["TZ"] = "UTC"
    if hasattr(time, "tzset"):
        time.tzset()
    with tempfile.TemporaryDirectory(prefix="bkx-go08-parity-") as td:
        root = Path(td)
        source = make_source(root)
        check_plan(binary, root, source)
        check_create(binary, root, source)
        check_dry_run(binary, root, source)
        check_fail_closed(binary, root, source)
        check_cancel(binary, root, source)
    print("BKX GO08 Python-Go backup orchestration parity valid.")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--go-binary", type=Path, required=True)
    args = parser.parse_args()
    check(args.go_binary.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
