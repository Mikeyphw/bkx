#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import tempfile
import time
from pathlib import Path

from bkx.core.errors import ExternalToolError, ValidationError
from bkx.core.impl.run_store import JsonRunStore
from bkx.core.impl.transfer import FallbackTransfer
from bkx.core.results.execution_plan import execution_plan_to_dict, plan_for_spec
from bkx.core.services.transfer import TransferService
from bkx.core.specs.transfer import TransferSpec


def payload(spec: TransferSpec) -> str:
    return json.dumps(
        {
            "sources": [str(x) for x in spec.sources],
            "destination": str(spec.destination),
            "direction": spec.direction,
            "verify": spec.verify,
            "checksum": spec.checksum,
            "dry_run": spec.dry_run,
            "overwrite": spec.overwrite,
        },
        separators=(",", ":"),
    )


def go(binary: Path, *args: str) -> subprocess.CompletedProcess[str]:
    env = dict(os.environ)
    env["TZ"] = "UTC"
    return subprocess.run([str(binary), *args], text=True, capture_output=True, env=env, check=False)


def service(runs: Path | None = None) -> TransferService:
    return TransferService(
        transfer=FallbackTransfer(),
        run_store=JsonRunStore(runs, recover_started=False) if runs is not None else None,
    )


def normalize_result(value) -> dict:
    if isinstance(value, dict):
        return {
            "success": value["success"],
            "direction": value["direction"],
            "dry_run": value["dry_run"],
            "files_transferred": value["files_transferred"],
            "bytes_transferred": value["bytes_transferred"],
            "verification_passed": value["verification_passed"],
            "checksum_verified": value["checksum_verified"],
            "warnings": value["warnings"],
            "errors": value["errors"],
        }
    return {
        "success": value.success,
        "direction": value.direction,
        "dry_run": value.dry_run,
        "files_transferred": value.files_transferred,
        "bytes_transferred": value.bytes_transferred,
        "verification_passed": value.verification_passed,
        "checksum_verified": value.checksum_verified,
        "warnings": list(value.warnings),
        "errors": list(value.errors),
    }


def tree(root: Path) -> list[tuple[str, str, int]]:
    if root.is_file():
        return [(root.name, hashlib.sha256(root.read_bytes()).hexdigest(), root.stat().st_mode & 0o777)]
    out = []
    if root.exists():
        for path in sorted(p for p in root.rglob("*") if p.is_file()):
            out.append((path.relative_to(root).as_posix(), hashlib.sha256(path.read_bytes()).hexdigest(), path.stat().st_mode & 0o777))
    return out


def make_sources(root: Path) -> tuple[Path, Path, Path]:
    one = root / "one.txt"
    one.write_text("one\n", encoding="utf-8")
    one.chmod(0o640)
    directory = root / "dir"
    (directory / "nested").mkdir(parents=True)
    (directory / "a.txt").write_text("alpha\n", encoding="utf-8")
    (directory / "nested" / "b.txt").write_text("bravo\n", encoding="utf-8")
    two = root / "two.txt"
    two.write_text("two\n", encoding="utf-8")
    epoch_ns = 1_704_164_646_000_000_000
    for p in (one, directory, directory / "nested", directory / "a.txt", directory / "nested" / "b.txt", two):
        os.utime(p, ns=(epoch_ns, epoch_ns), follow_symlinks=False)
    return one, directory, two


def check_plan(binary: Path, root: Path, source: Path) -> None:
    dest = root / "plan-dest.txt"
    spec = TransferSpec(sources=(source,), destination=dest, direction="push", verify=True)
    expected = execution_plan_to_dict(plan_for_spec(spec))
    cp = go(binary, "migration", "transfer", "plan", payload(spec))
    assert cp.returncode == 0, cp.stderr
    actual = json.loads(cp.stdout)
    assert actual == expected, (actual, expected)


def check_push_and_history(binary: Path, root: Path, source: Path) -> None:
    py_dest = root / "python-push.txt"
    go_dest = root / "go-push.txt"
    py_runs = root / "python-runs.jsonl"
    go_runs = root / "go-runs.jsonl"
    py_spec = TransferSpec(sources=(source,), destination=py_dest, direction="push", verify=True)
    go_spec = TransferSpec(sources=(source,), destination=go_dest, direction="push", verify=True)
    py_result = service(py_runs).push(py_spec)
    cp = go(binary, "migration", "transfer", "run", payload(go_spec), str(go_runs), "-")
    assert cp.returncode == 0, cp.stderr
    go_result = json.loads(cp.stdout)
    assert normalize_result(go_result) == normalize_result(py_result)
    assert py_dest.read_bytes() == go_dest.read_bytes() == source.read_bytes()
    assert (py_dest.stat().st_mode & 0o777) == (go_dest.stat().st_mode & 0o777) == (source.stat().st_mode & 0o777)

    py_record = JsonRunStore(py_runs, recover_started=False).list_runs(limit=1)[0]
    go_record = json.loads(go_runs.read_text(encoding="utf-8").splitlines()[-1])
    assert go_record["operation"] == py_record.operation == "transfer"
    assert go_record["status"] == py_record.status == "success"
    assert go_record["source_paths"] == [str(x) for x in py_record.source_paths] == [str(source.resolve())]
    assert Path(go_record["destination_path"]).name == "go-push.txt"
    assert Path(py_record.destination_path).name == "python-push.txt"
    assert go_record["files_count"] == py_record.files_count == 1
    assert go_record["bytes_processed"] == py_record.bytes_processed == source.stat().st_size
    assert go_record["spec_snapshot"]["type"] == py_record.spec_snapshot["type"] == "TransferSpec"
    for key in ("direction", "verify", "checksum", "dry_run", "overwrite"):
        assert go_record["spec_snapshot"]["values"][key] == py_record.spec_snapshot["values"][key]


def check_directory(binary: Path, root: Path, directory: Path) -> None:
    py_dest = root / "python-dir"
    go_dest = root / "go-dir"
    py_result = service().push(TransferSpec(sources=(directory,), destination=py_dest, verify=True))
    cp = go(binary, "migration", "transfer", "run", payload(TransferSpec(sources=(directory,), destination=go_dest, verify=True)), "-", "-")
    assert cp.returncode == 0, cp.stderr
    assert normalize_result(json.loads(cp.stdout)) == normalize_result(py_result)
    assert tree(py_dest) == tree(go_dest)


def check_multi(binary: Path, root: Path, one: Path, two: Path) -> None:
    py_dest = root / "python-multi"
    go_dest = root / "go-multi"
    py_dest.mkdir(); go_dest.mkdir()
    py_spec = TransferSpec(sources=(one, two), destination=py_dest, verify=True)
    go_spec = TransferSpec(sources=(one, two), destination=go_dest, verify=True)
    py_result = service().push(py_spec)
    cp = go(binary, "migration", "transfer", "run", payload(go_spec), "-", "-")
    assert cp.returncode == 0, cp.stderr
    assert normalize_result(json.loads(cp.stdout)) == normalize_result(py_result)
    assert [(p.name, p.read_bytes()) for p in sorted(py_dest.iterdir())] == [(p.name, p.read_bytes()) for p in sorted(go_dest.iterdir())]


def check_pull(binary: Path, root: Path, source: Path) -> None:
    py_dest = root / "python-pull.txt"
    go_dest = root / "go-pull.txt"
    py_spec = TransferSpec(sources=(source,), destination=py_dest, direction="pull", verify=True)
    go_spec = TransferSpec(sources=(source,), destination=go_dest, direction="pull", verify=True)
    py_result = service().pull(py_spec)
    cp = go(binary, "migration", "transfer", "run", payload(go_spec), "-", "-")
    assert cp.returncode == 0, cp.stderr
    assert normalize_result(json.loads(cp.stdout)) == normalize_result(py_result)
    assert py_dest.read_bytes() == go_dest.read_bytes()


def check_checksum_and_dry_run(binary: Path, root: Path, source: Path) -> None:
    py_dest = root / "python-sha1.txt"
    go_dest = root / "go-sha1.txt"
    py_spec = TransferSpec(sources=(source,), destination=py_dest, verify=False, checksum="sha1")
    go_spec = TransferSpec(sources=(source,), destination=go_dest, verify=False, checksum="sha1")
    py_result = service().push(py_spec)
    cp = go(binary, "migration", "transfer", "run", payload(go_spec), "-", "-")
    assert cp.returncode == 0, cp.stderr
    assert normalize_result(json.loads(cp.stdout)) == normalize_result(py_result)

    expected = hashlib.sha256(source.read_bytes()).hexdigest()
    py_expected = root / "python-expected.txt"; go_expected = root / "go-expected.txt"
    py_result = service().push(TransferSpec(sources=(source,), destination=py_expected, verify=False, checksum=expected))
    cp = go(binary, "migration", "transfer", "run", payload(TransferSpec(sources=(source,), destination=go_expected, verify=False, checksum=expected)), "-", "-")
    assert cp.returncode == 0, cp.stderr
    assert normalize_result(json.loads(cp.stdout)) == normalize_result(py_result)

    py_dry = root / "python-dry"; go_dry = root / "go-dry"
    py_result = service().push(TransferSpec(sources=(source,), destination=py_dry, verify=False, dry_run=True))
    cp = go(binary, "migration", "transfer", "run", payload(TransferSpec(sources=(source,), destination=go_dry, verify=False, dry_run=True)), "-", "-")
    assert cp.returncode == 0, cp.stderr
    assert normalize_result(json.loads(cp.stdout)) == normalize_result(py_result)
    assert not py_dry.exists() and not go_dry.exists()


def check_overwrite_collision_and_remote(binary: Path, root: Path, one: Path, two: Path) -> None:
    py_dest = root / "python-overwrite"; go_dest = root / "go-overwrite"
    py_dest.write_text("old", encoding="utf-8"); go_dest.write_text("old", encoding="utf-8")
    for spec, is_go in ((TransferSpec(sources=(one,), destination=py_dest, verify=False), False), (TransferSpec(sources=(one,), destination=go_dest, verify=False), True)):
        if is_go:
            cp = go(binary, "migration", "transfer", "run", payload(spec), "-", "-")
            assert cp.returncode != 0
        else:
            try: service().push(spec)
            except ValidationError: pass
            else: raise AssertionError("Python no-overwrite unexpectedly succeeded")
    assert py_dest.read_text() == go_dest.read_text() == "old"
    py_result = service().push(TransferSpec(sources=(one,), destination=py_dest, verify=True, overwrite=True))
    cp = go(binary, "migration", "transfer", "run", payload(TransferSpec(sources=(one,), destination=go_dest, verify=True, overwrite=True)), "-", "-")
    assert cp.returncode == 0, cp.stderr
    assert normalize_result(json.loads(cp.stdout)) == normalize_result(py_result)

    a = root / "a"; b = root / "b"; a.mkdir(); b.mkdir()
    a_same = a / "same.txt"; b_same = b / "same.txt"; a_same.write_text("a"); b_same.write_text("b")
    py_collision = root / "python-collision"; go_collision = root / "go-collision"; py_collision.mkdir(); go_collision.mkdir()
    try: service().push(TransferSpec(sources=(a_same,b_same), destination=py_collision, verify=False, overwrite=True))
    except ValidationError: pass
    else: raise AssertionError("Python collision unexpectedly succeeded")
    cp = go(binary, "migration", "transfer", "run", payload(TransferSpec(sources=(a_same,b_same), destination=go_collision, verify=False, overwrite=True)), "-", "-")
    assert cp.returncode != 0
    assert not any(py_collision.iterdir()) and not any(go_collision.iterdir())

    remote = TransferSpec(sources=("s3://bucket/key",), destination=root / "remote-dest", direction="pull")
    try: service().pull(remote)
    except ExternalToolError: pass
    else: raise AssertionError("Python local fallback unexpectedly accepted remote source")
    cp = go(binary, "migration", "transfer", "plan", payload(remote))
    assert cp.returncode != 0


def check(binary: Path) -> None:
    os.environ["TZ"] = "UTC"
    if hasattr(time, "tzset"): time.tzset()
    with tempfile.TemporaryDirectory(prefix="bkx-go09-parity-") as td:
        root = Path(td)
        one, directory, two = make_sources(root)
        check_plan(binary, root, one)
        check_push_and_history(binary, root, one)
        check_directory(binary, root, directory)
        check_multi(binary, root, one, two)
        check_pull(binary, root, one)
        check_checksum_and_dry_run(binary, root, one)
        check_overwrite_collision_and_remote(binary, root, one, two)
    print("BKX GO09 Python-Go local transfer parity valid.")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--go-binary", type=Path, required=True)
    args = parser.parse_args()
    check(args.go_binary.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
