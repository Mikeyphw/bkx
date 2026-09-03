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
import zipfile
from dataclasses import asdict
from pathlib import Path

from bkx.core.impl.archiver import FallbackArchiver
from bkx.core.services.archive import ArchiveService
from bkx.core.specs.archive import ArchiveBrowseSpec, ArchiveInfoSpec, ArchiveListSpec, ArchiveVerifySpec


def go_json(binary: Path, *args: str) -> dict:
    env = dict(os.environ)
    env["TZ"] = "UTC"
    cp = subprocess.run([str(binary), *args], text=True, capture_output=True, env=env, check=False)
    if cp.returncode != 0:
        raise AssertionError(f"Go probe failed ({cp.returncode}): {' '.join(args)}\nstdout={cp.stdout}\nstderr={cp.stderr}")
    return json.loads(cp.stdout)


def entry_dict(entry) -> dict:
    return {
        "path": entry.path,
        "is_dir": entry.is_dir,
        "size": entry.size,
        "mtime_ns": entry.mtime_ns,
        "entry_type": entry.entry_type,
    }


def py_list(path: Path, page: int, page_size: int, pattern: str | None) -> dict:
    result = ArchiveService(archiver=FallbackArchiver()).list(
        ArchiveListSpec(archive_path=path, page=page, page_size=page_size, filter_pattern=pattern)
    )
    return {
        "success": result.success,
        "archive_path": str(result.archive_path),
        "entries": [entry_dict(x) for x in result.entries],
        "total_entries": result.total_entries,
        "page": result.page,
        "page_size": result.page_size,
        "filter_pattern": result.filter_pattern,
    }


def py_info(path: Path) -> dict:
    result = ArchiveService(archiver=FallbackArchiver()).info(ArchiveInfoSpec(archive_path=path))
    return {
        "success": result.success,
        "archive_path": str(result.archive_path),
        "format": result.format,
        "created_at": result.created_at,
        "total_entries": result.total_entries,
        "total_size": result.total_size,
        "uncompressed_size": result.uncompressed_size,
        "compression_ratio": result.compression_ratio,
    }


def py_browse(path: Path) -> dict:
    result = ArchiveService(archiver=FallbackArchiver()).browse(
        ArchiveBrowseSpec(
            archive_path=path,
            query="txt",
            file_type="file",
            min_size=2,
            max_size=20,
            limit=2,
            offset=0,
            sort_by="size",
            sort_order="desc",
        )
    )
    return {
        "success": result.success,
        "archive_path": str(result.archive_path),
        "query": result.query,
        "file_type": result.file_type,
        "min_size": result.min_size,
        "max_size": result.max_size,
        "entries": [entry_dict(x) for x in result.entries],
        "total_matches": result.total_matches,
        "limit": result.limit,
        "offset": result.offset,
        "sort_by": result.sort_by,
        "sort_order": result.sort_order,
        "total_size": result.total_size,
        "directories_count": result.directories_count,
        "files_count": result.files_count,
    }


def py_verify(path: Path, algorithms: tuple[str, ...] = ("sha256", "md5")) -> dict:
    result = ArchiveService(archiver=FallbackArchiver()).verify(
        ArchiveVerifySpec(archive_path=path, hash_algorithms=algorithms)
    )
    return {
        "success": result.success,
        "archive_path": str(result.archive_path),
        "checksums_valid": result.checksums_valid,
        "structure_valid": result.structure_valid,
        "entries_checked": result.entries_checked,
        "entries_failed": result.entries_failed,
        "failed_entries": list(result.failed_entries),
        "archive_checksums": dict(result.archive_checksums),
        "warnings": list(result.warnings),
        "errors": list(result.errors),
    }


def normalize_verify_errors(value: dict) -> dict:
    # Backend exception wording differs; parity is the failure classification,
    # failed member set/count, structure/checksum booleans and archive hashes.
    value = dict(value)
    value["errors"] = ["<error>" for _ in value.get("errors", [])]
    return value


def make_fixtures(root: Path) -> list[Path]:
    epoch = 1_704_164_646
    payloads = {
        "root/a.txt": b"aaa",
        "root/nested/b.txt": b"bbbbbb",
        "root/nested/ignore.log": b"log",
    }

    zip_path = root / "fixture.zip"
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        directory = zipfile.ZipInfo("root/")
        directory.date_time = (2024, 1, 2, 3, 4, 6)
        directory.external_attr = 0o40755 << 16
        zf.writestr(directory, b"")
        nested = zipfile.ZipInfo("root/nested/")
        nested.date_time = (2024, 1, 2, 3, 4, 6)
        nested.external_attr = 0o40755 << 16
        zf.writestr(nested, b"")
        for name, payload in payloads.items():
            info = zipfile.ZipInfo(name)
            info.date_time = (2024, 1, 2, 3, 4, 6)
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            zf.writestr(info, payload)

    paths = [zip_path]
    for suffix, mode in ((".tar", "w"), (".tar.gz", "w:gz"), (".tar.bz2", "w:bz2")):
        path = root / f"fixture{suffix}"
        with tarfile.open(path, mode) as tf:
            for name in ("root/", "root/nested/"):
                info = tarfile.TarInfo(name)
                info.type = tarfile.DIRTYPE
                info.mode = 0o755
                info.mtime = epoch
                tf.addfile(info)
            for name, payload in payloads.items():
                info = tarfile.TarInfo(name)
                info.mode = 0o644
                info.mtime = epoch
                info.size = len(payload)
                import io
                tf.addfile(info, io.BytesIO(payload))
        paths.append(path)
    return paths


def check(binary: Path) -> None:
    os.environ["TZ"] = "UTC"
    if hasattr(time, "tzset"):
        time.tzset()
    with tempfile.TemporaryDirectory(prefix="bkx-go05-parity-") as td:
        root = Path(td)
        fixtures = make_fixtures(root)
        for path in fixtures:
            expected = py_list(path, 0, 2, "*.txt")
            actual = go_json(binary, "migration", "archive", "list", str(path), "0", "2", "*.txt")
            assert actual == expected, (path, "list", actual, expected)

            expected_info = py_info(path)
            actual_info = go_json(binary, "migration", "archive", "info", str(path))
            assert actual_info.keys() == expected_info.keys()
            for key in expected_info:
                if key == "compression_ratio":
                    assert abs(actual_info[key] - expected_info[key]) < 1e-12, (path, key, actual_info[key], expected_info[key])
                else:
                    assert actual_info[key] == expected_info[key], (path, key, actual_info[key], expected_info[key])

            expected_browse = py_browse(path)
            actual_browse = go_json(binary, "migration", "archive", "browse", str(path), "txt", "file", "2", "20", "2", "0", "size", "desc")
            assert actual_browse == expected_browse, (path, "browse", actual_browse, expected_browse)

            expected_verify = normalize_verify_errors(py_verify(path))
            actual_verify = normalize_verify_errors(go_json(binary, "migration", "archive", "verify", str(path), "sha256,md5"))
            assert actual_verify == expected_verify, (path, "verify", actual_verify, expected_verify)

        unsafe = root / "unsafe.tar"
        import io
        with tarfile.open(unsafe, "w") as tf:
            info = tarfile.TarInfo("../escape.txt")
            payload = b"escape"
            info.size = len(payload)
            tf.addfile(info, io.BytesIO(payload))
        py_unsafe = normalize_verify_errors(py_verify(unsafe, ("sha256",)))
        go_unsafe = normalize_verify_errors(go_json(binary, "migration", "archive", "verify", str(unsafe), "sha256"))
        assert go_unsafe == py_unsafe, (go_unsafe, py_unsafe)

        bad = root / "bad.zip"
        with zipfile.ZipFile(bad, "w", compression=zipfile.ZIP_STORED) as zf:
            zf.writestr("safe.txt", b"abc")
        data = bad.read_bytes()
        bad.write_bytes(data.replace(b"abc", b"abd", 1))
        py_bad = normalize_verify_errors(py_verify(bad, ("sha256",)))
        go_bad = normalize_verify_errors(go_json(binary, "migration", "archive", "verify", str(bad), "sha256"))
        assert go_bad["success"] == py_bad["success"] is False
        assert go_bad["structure_valid"] == py_bad["structure_valid"]
        assert go_bad["entries_failed"] == py_bad["entries_failed"]
        assert go_bad["archive_checksums"] == py_bad["archive_checksums"]

    print("BKX GO05 Python-Go archive read parity valid.")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--go-binary", type=Path, required=True)
    args = parser.parse_args()
    check(args.go_binary.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
