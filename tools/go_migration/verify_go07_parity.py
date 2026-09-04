#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import subprocess
import tarfile
import tempfile
import zipfile
from pathlib import Path

from bkx.core.errors import BkxError
from bkx.core.impl.archiver import FallbackArchiver
from bkx.core.services.restore import RestoreService
from bkx.core.specs.restore import RestoreSpec


def payload(spec: RestoreSpec) -> str:
    return json.dumps(
        {
            "archive": str(spec.archive),
            "destination": None if spec.destination is None else str(spec.destination),
            "paths": list(spec.paths),
            "overwrite": spec.overwrite,
            "verify": spec.verify,
            "dry_run": spec.dry_run,
            "fresh_dir": spec.fresh_dir,
            "confirm_overwrite": spec.confirm_overwrite,
        },
        separators=(",", ":"),
    )


def go(binary: Path, operation: str, spec: RestoreSpec) -> subprocess.CompletedProcess[str]:
    env = dict(os.environ)
    env["TZ"] = "UTC"
    return subprocess.run(
        [str(binary), "migration", "restore", operation, payload(spec)],
        text=True,
        capture_output=True,
        check=False,
        env=env,
    )


def tree(root: Path) -> dict[str, bytes]:
    if not root.exists():
        return {}
    return {
        p.relative_to(root).as_posix(): p.read_bytes()
        for p in sorted(root.rglob("*"))
        if p.is_file()
    }


def preview_logical(result, destination: Path) -> dict:
    return {
        "success": result.success,
        "total_files": result.total_files,
        "total_size": result.total_size,
        "existing_files": result.existing_files,
        "warnings": list(result.warnings),
        "files": [
            {
                "archive_path": item.archive_path,
                "disk_path": Path(item.disk_path).relative_to(destination).as_posix(),
                "size": item.size,
                "mtime_ns": item.mtime_ns,
                "is_partial": item.is_partial,
            }
            for item in result.files
        ],
    }


def go_preview_logical(raw: dict, destination: Path) -> dict:
    return {
        "success": raw["success"],
        "total_files": raw["total_files"],
        "total_size": raw["total_size"],
        "existing_files": raw["existing_files"],
        "warnings": raw["warnings"],
        "files": [
            {
                "archive_path": item["archive_path"],
                "disk_path": Path(item["disk_path"]).relative_to(destination).as_posix(),
                "size": item["size"],
                "mtime_ns": item["mtime_ns"],
                "is_partial": item["is_partial"],
            }
            for item in raw["files"]
        ],
    }


def result_logical(result) -> dict:
    return {
        "success": result.success,
        "dry_run": result.dry_run,
        "files_restored": result.files_restored,
        "bytes_restored": result.bytes_restored,
        "verification_passed": result.verification_passed,
        "warnings": list(result.warnings),
        "errors": list(result.errors),
    }


def go_result_logical(raw: dict) -> dict:
    return {k: raw[k] for k in (
        "success", "dry_run", "files_restored", "bytes_restored",
        "verification_passed", "warnings", "errors",
    )}


def make_archives(root: Path) -> tuple[Path, Path]:
    zip_path = root / "fixture.zip"
    with zipfile.ZipFile(zip_path, "w") as zf:
        zf.writestr("dir/", b"")
        zf.writestr("dir/a.txt", b"alpha")
        zf.writestr("dir/b.txt", b"bravo")
        zf.writestr("other/c.txt", b"charlie")
    tar_path = root / "fixture.tar"
    payloads = {"dir/a.txt": b"alpha", "dir/b.txt": b"bravo", "other/c.txt": b"charlie"}
    with tarfile.open(tar_path, "w") as tf:
        info = tarfile.TarInfo("dir/")
        info.type = tarfile.DIRTYPE
        info.mode = 0o755
        info.mtime = 1_704_164_646
        tf.addfile(info)
        for name, data in payloads.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            info.mode = 0o644
            info.mtime = 1_704_164_646
            import io
            tf.addfile(info, io.BytesIO(data))
    return zip_path, tar_path


def compare_success(binary: Path, root: Path, archive: Path, selectors: tuple[str, ...], verify: bool) -> None:
    py_dest = root / f"py-{archive.suffix.lstrip('.')}-{'partial' if selectors else 'full'}"
    go_dest = root / f"go-{archive.suffix.lstrip('.')}-{'partial' if selectors else 'full'}"
    py_spec = RestoreSpec(archive=archive, destination=py_dest, paths=selectors, verify=verify, fresh_dir=False, confirm_overwrite=False)
    go_spec = RestoreSpec(archive=archive, destination=go_dest, paths=selectors, verify=verify, fresh_dir=False, confirm_overwrite=False)

    py_preview = RestoreService(archiver=FallbackArchiver()).preview(py_spec)
    cp = go(binary, "preview", go_spec)
    assert cp.returncode == 0, cp.stderr
    assert go_preview_logical(json.loads(cp.stdout), go_dest) == preview_logical(py_preview, py_dest)

    py_result = RestoreService(archiver=FallbackArchiver()).extract(py_spec)
    cp = go(binary, "extract", go_spec)
    assert cp.returncode == 0, cp.stderr
    assert go_result_logical(json.loads(cp.stdout)) == result_logical(py_result)
    assert tree(go_dest) == tree(py_dest)


def compare_dry_run(binary: Path, root: Path, archive: Path) -> None:
    py_dest = root / "py-dry"
    go_dest = root / "go-dry"
    py_spec = RestoreSpec(archive=archive, destination=py_dest, verify=False, dry_run=True, fresh_dir=False, confirm_overwrite=False)
    go_spec = RestoreSpec(archive=archive, destination=go_dest, verify=False, dry_run=True, fresh_dir=False, confirm_overwrite=False)
    py_result = RestoreService(archiver=FallbackArchiver()).extract(py_spec)
    cp = go(binary, "extract", go_spec)
    assert cp.returncode == 0, cp.stderr
    assert go_result_logical(json.loads(cp.stdout)) == result_logical(py_result)
    assert not py_dest.exists() and not go_dest.exists()


def compare_failures(binary: Path, root: Path, archive: Path) -> None:
    for prefix, py_spec, go_spec in (
        (
            "missing",
            RestoreSpec(archive=archive, destination=root/"py-missing", paths=("missing",), verify=False, fresh_dir=False, confirm_overwrite=False),
            RestoreSpec(archive=archive, destination=root/"go-missing", paths=("missing",), verify=False, fresh_dir=False, confirm_overwrite=False),
        ),
        (
            "confirmation",
            RestoreSpec(archive=archive, destination=root/"py-existing", verify=False, fresh_dir=False, confirm_overwrite=True),
            RestoreSpec(archive=archive, destination=root/"go-existing", verify=False, fresh_dir=False, confirm_overwrite=True),
        ),
    ):
        if prefix == "confirmation":
            py_spec.destination.mkdir()
            go_spec.destination.mkdir()
        try:
            RestoreService(archiver=FallbackArchiver()).extract(py_spec)
        except BkxError:
            pass
        else:
            raise AssertionError(f"Python {prefix} restore unexpectedly succeeded")
        cp = go(binary, "extract", go_spec)
        assert cp.returncode != 0, (prefix, cp.stdout, cp.stderr)

    py_dest = root / "py-collision"; go_dest = root / "go-collision"
    py_dest.mkdir(); go_dest.mkdir()
    (py_dest/"dir").mkdir(); (go_dest/"dir").mkdir()
    (py_dest/"dir"/"a.txt").write_bytes(b"old"); (go_dest/"dir"/"a.txt").write_bytes(b"old")
    py_spec = RestoreSpec(archive=archive, destination=py_dest, verify=False, fresh_dir=False, confirm_overwrite=False)
    go_spec = RestoreSpec(archive=archive, destination=go_dest, verify=False, fresh_dir=False, confirm_overwrite=False)
    try:
        RestoreService(archiver=FallbackArchiver()).extract(py_spec)
    except BkxError:
        pass
    else:
        raise AssertionError("Python collision unexpectedly succeeded")
    cp = go(binary, "extract", go_spec)
    assert cp.returncode != 0
    assert (go_dest/"dir"/"a.txt").read_bytes() == b"old"


def unsafe_cases(binary: Path, root: Path) -> None:
    traversal = root / "evil.tar"
    with tarfile.open(traversal, "w") as tf:
        import io
        info = tarfile.TarInfo("../escape.txt"); info.size = 1
        tf.addfile(info, io.BytesIO(b"x"))
    py_spec = RestoreSpec(archive=traversal, destination=root/"py-unsafe", verify=False, fresh_dir=False, confirm_overwrite=False)
    go_spec = RestoreSpec(archive=traversal, destination=root/"go-unsafe", verify=False, fresh_dir=False, confirm_overwrite=False)
    try:
        RestoreService(archiver=FallbackArchiver()).preview(py_spec)
    except BkxError:
        pass
    else:
        raise AssertionError("Python traversal preview unexpectedly succeeded")
    cp = go(binary, "preview", go_spec)
    assert cp.returncode != 0, (cp.stdout, cp.stderr)

    # Python preview lists link-like entries but extraction rejects them. GO07
    # preserves that observable preview behavior while keeping extraction safe.
    link = root / "link.tar"
    with tarfile.open(link, "w") as tf:
        info = tarfile.TarInfo("link"); info.type = tarfile.SYMTYPE; info.linkname = "/tmp/outside"
        tf.addfile(info)
    py_link_dest = root / "py-link"
    go_link_dest = root / "go-link"
    py_link = RestoreSpec(archive=link, destination=py_link_dest, verify=False, fresh_dir=False, confirm_overwrite=False)
    go_link = RestoreSpec(archive=link, destination=go_link_dest, verify=False, fresh_dir=False, confirm_overwrite=False)
    py_preview = RestoreService(archiver=FallbackArchiver()).preview(py_link)
    cp = go(binary, "preview", go_link)
    assert cp.returncode == 0, cp.stderr
    assert go_preview_logical(json.loads(cp.stdout), go_link_dest) == preview_logical(py_preview, py_link_dest)
    try:
        RestoreService(archiver=FallbackArchiver()).extract(py_link)
    except BkxError:
        pass
    else:
        raise AssertionError("Python link extraction unexpectedly succeeded")
    cp = go(binary, "extract", go_link)
    assert cp.returncode != 0, (cp.stdout, cp.stderr)
    assert not (root/"escape.txt").exists()


def check(binary: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="bkx-go07-parity-") as td:
        root = Path(td)
        zip_path, tar_path = make_archives(root)
        compare_success(binary, root, zip_path, (), False)
        compare_success(binary, root, tar_path, ("dir",), True)
        compare_dry_run(binary, root, zip_path)
        compare_failures(binary, root, zip_path)
        unsafe_cases(binary, root)
    print("BKX GO07 Python-Go restore preview/extraction parity valid.")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--go-binary", required=True, type=Path)
    args = parser.parse_args()
    check(args.go_binary.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
