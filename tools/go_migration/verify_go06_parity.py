#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tarfile
import tempfile
import time
import zipfile
from pathlib import Path

from bkx.core.errors import ValidationError
from bkx.core.impl.archiver import FallbackArchiver
from bkx.core.services.archive import ArchiveService
from bkx.core.specs.archive import ArchiveCreateSpec
from bkx.core.specs.backup import CompressionProfile


def go_run(binary: Path, spec: ArchiveCreateSpec, *, implicit_default: bool = False) -> subprocess.CompletedProcess[str]:
    payload = {
        "spec": {
            "compression": {"algorithm": spec.compression.algorithm, "level": spec.compression.level},
            "dry_run": spec.dry_run,
            "excludes": list(spec.excludes),
            "includes": list(spec.includes),
            "output_path": str(spec.output_path),
            "sources": [str(x) for x in spec.sources],
            "split_size": spec.split_size,
            "skip_symlinks": spec.skip_symlinks,
            "verify": spec.verify,
        },
        "implicit_default": implicit_default,
    }
    env = dict(os.environ)
    env["TZ"] = "UTC"
    return subprocess.run(
        [str(binary), "migration", "archive", "create", json.dumps(payload, separators=(",", ":")), str(implicit_default).lower()],
        text=True,
        capture_output=True,
        env=env,
        check=False,
    )


def py_create(spec: ArchiveCreateSpec):
    return ArchiveService(archiver=FallbackArchiver()).create(spec)


def logical_result(result) -> dict:
    return {
        "success": result.success,
        "dry_run": result.dry_run,
        "files_archived": result.files_archived,
        "original_size": result.original_size,
        "verification_passed": result.verification_passed,
        "warnings": list(result.warnings),
        "errors": list(result.errors),
    }


def logical_go_result(raw: dict) -> dict:
    return {key: raw[key] for key in ("success", "dry_run", "files_archived", "original_size", "verification_passed", "warnings", "errors")}


def member_snapshot(path: Path) -> list[tuple[str, bool, int, str, str | None]]:
    out: list[tuple[str, bool, int, str, str | None]] = []
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as zf:
            for info in zf.infolist():
                is_dir = info.is_dir()
                payload_hash = None if is_dir else hashlib.sha256(zf.read(info)).hexdigest()
                out.append((info.filename, is_dir, info.file_size, "directory" if is_dir else "file", payload_hash))
        return out
    with tarfile.open(path, "r:*") as tf:
        for member in tf.getmembers():
            is_dir = member.isdir()
            payload_hash = None
            if member.isfile():
                stream = tf.extractfile(member)
                assert stream is not None
                payload_hash = hashlib.sha256(stream.read()).hexdigest()
            out.append((member.name, is_dir, member.size, "directory" if is_dir else "file", payload_hash))
    return out


def make_source(root: Path) -> Path:
    source = root / "source"
    (source / "empty").mkdir(parents=True)
    (source / "nested").mkdir()
    (source / "a.txt").write_bytes(b"alpha\n")
    (source / "nested" / "b.txt").write_bytes(b"bravo\n")
    (source / "nested" / "ignore.log").write_bytes(b"ignored\n")
    epoch_ns = 1_704_164_646_000_000_000
    for path in (source, source / "empty", source / "nested", source / "a.txt", source / "nested" / "b.txt", source / "nested" / "ignore.log"):
        os.utime(path, ns=(epoch_ns, epoch_ns), follow_symlinks=False)
    return source


def compare_case(binary: Path, root: Path, source: Path, suffix: str, algorithm: str, level: int) -> None:
    py_out = root / f"python{suffix}"
    go_out = root / f"go{suffix}"
    kwargs = dict(
        sources=(source,),
        excludes=("*.log",),
        compression=CompressionProfile(algorithm, level),
        verify=True,
    )
    py_spec = ArchiveCreateSpec(output_path=py_out, **kwargs)
    go_spec = ArchiveCreateSpec(output_path=go_out, **kwargs)
    py_result = py_create(py_spec)
    cp = go_run(binary, go_spec)
    if cp.returncode != 0:
        raise AssertionError(f"GO06 create failed for {suffix}/{algorithm}:\nstdout={cp.stdout}\nstderr={cp.stderr}")
    go_result = json.loads(cp.stdout)
    assert logical_go_result(go_result) == logical_result(py_result), (suffix, algorithm, go_result, py_result)
    assert member_snapshot(go_out) == member_snapshot(py_out), (suffix, algorithm, member_snapshot(go_out), member_snapshot(py_out))

    # Cross-reader proof: Python reads the Go artifact and the GO05 reader verifies
    # the Python artifact, including optional ZIP BZIP2/LZMA methods.
    verify = subprocess.run([str(binary), "migration", "archive", "verify", str(py_out), "sha256"], text=True, capture_output=True, check=False)
    if verify.returncode != 0:
        raise AssertionError(f"GO05 reader could not verify Python-created {algorithm} artifact: {verify.stderr}")
    assert json.loads(verify.stdout)["success"] is True


def check_default_compat(binary: Path, root: Path, source: Path, suffix: str) -> None:
    py_out = root / f"python-default{suffix}"
    go_out = root / f"go-default{suffix}"
    py_spec = ArchiveCreateSpec(sources=(source,), output_path=py_out, verify=True)
    go_spec = ArchiveCreateSpec(sources=(source,), output_path=go_out, verify=True)
    py_result = py_create(py_spec)
    cp = go_run(binary, go_spec, implicit_default=True)
    assert cp.returncode == 0, cp.stderr
    go_result = json.loads(cp.stdout)
    assert logical_go_result(go_result) == logical_result(py_result)
    assert member_snapshot(go_out) == member_snapshot(py_out)


def check_fail_closed(binary: Path, root: Path, source: Path) -> None:
    for suffix in (".tar", ".zip"):
        py_out = root / f"python-explicit-zstd{suffix}"
        go_out = root / f"go-explicit-zstd{suffix}"
        py_spec = ArchiveCreateSpec(sources=(source,), output_path=py_out, compression=CompressionProfile("zstd", 3), verify=True)
        go_spec = ArchiveCreateSpec(sources=(source,), output_path=go_out, compression=CompressionProfile("zstd", 3), verify=True)
        try:
            py_create(py_spec)
        except ValidationError:
            pass
        else:
            raise AssertionError("Python explicit zstd unexpectedly succeeded")
        cp = go_run(binary, go_spec)
        assert cp.returncode != 0, cp.stdout
        assert not go_out.exists()

    split = 1024
    py_out = root / "python-split.tar"
    go_out = root / "go-split.tar"
    py_spec = ArchiveCreateSpec(sources=(source,), output_path=py_out, compression=CompressionProfile("none", 0), split_size=split)
    go_spec = ArchiveCreateSpec(sources=(source,), output_path=go_out, compression=CompressionProfile("none", 0), split_size=split)
    try:
        py_create(py_spec)
    except ValidationError:
        pass
    else:
        raise AssertionError("Python split archive unexpectedly succeeded")
    cp = go_run(binary, go_spec)
    assert cp.returncode != 0
    assert not go_out.exists()


def check_dry_run(binary: Path, root: Path, source: Path) -> None:
    py_out = root / "python-dry.tar"
    go_out = root / "go-dry.tar"
    py_spec = ArchiveCreateSpec(sources=(source,), output_path=py_out, compression=CompressionProfile("none", 0), dry_run=True)
    go_spec = ArchiveCreateSpec(sources=(source,), output_path=go_out, compression=CompressionProfile("none", 0), dry_run=True)
    py_result = py_create(py_spec)
    cp = go_run(binary, go_spec)
    assert cp.returncode == 0, cp.stderr
    assert logical_go_result(json.loads(cp.stdout)) == logical_result(py_result)
    assert not py_out.exists() and not go_out.exists()


def check(binary: Path) -> None:
    os.environ["TZ"] = "UTC"
    if hasattr(time, "tzset"):
        time.tzset()
    with tempfile.TemporaryDirectory(prefix="bkx-go06-parity-") as td:
        root = Path(td)
        source = make_source(root)
        compare_case(binary, root, source, ".tar", "none", 0)
        compare_case(binary, root, source, ".tar.gz", "gzip", 1)
        compare_case(binary, root, source, ".zip", "none", 0)
        compare_case(binary, root, source, "-deflate.zip", "gzip", 6)
        if shutil.which("bzip2"):
            compare_case(binary, root, source, ".tar.bz2", "bzip2", 5)
            compare_case(binary, root, source, "-bzip2.zip", "bzip2", 5)
        if shutil.which("xz"):
            compare_case(binary, root, source, ".tar.xz", "xz", 3)
            compare_case(binary, root, source, "-lzma.zip", "xz", 3)
        check_default_compat(binary, root, source, ".tar")
        check_default_compat(binary, root, source, ".zip")
        check_fail_closed(binary, root, source)
        check_dry_run(binary, root, source)
    print("BKX GO06 Python-Go archive creation parity valid.")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--go-binary", type=Path, required=True)
    args = parser.parse_args()
    check(args.go_binary.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
