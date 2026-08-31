from __future__ import annotations

import os
import tarfile
import zipfile
from pathlib import Path

import pytest

from bkx.core.errors import NotFoundError, ValidationError
from bkx.core.impl.archiver import FallbackArchiver
from bkx.core.services.restore import RestoreService
from bkx.core.specs.restore import RestoreSpec


def _tar_with_file(path: Path, name: str, data: bytes = b"data") -> None:
    payload = path.parent / "payload.tmp"
    payload.write_bytes(data)
    with tarfile.open(path, "w") as tar:
        tar.add(payload, arcname=name)
    payload.unlink()


def test_restore_rejects_tar_path_traversal(tmp_path: Path) -> None:
    archive = tmp_path / "evil.tar"
    _tar_with_file(archive, "../escape.txt")
    service = RestoreService(archiver=FallbackArchiver())

    with pytest.raises(ValidationError):
        service.extract(RestoreSpec(archive=archive, destination=tmp_path / "out", verify=False, confirm_overwrite=False))
    assert not (tmp_path / "escape.txt").exists()


def test_restore_rejects_tar_symlink_members(tmp_path: Path) -> None:
    archive = tmp_path / "links.tar"
    with tarfile.open(archive, "w") as tar:
        link = tarfile.TarInfo("link")
        link.type = tarfile.SYMTYPE
        link.linkname = "/tmp/outside"
        tar.addfile(link)
    service = RestoreService(archiver=FallbackArchiver())

    with pytest.raises(ValidationError):
        service.extract(RestoreSpec(archive=archive, destination=tmp_path / "out", verify=False, confirm_overwrite=False))


def test_restore_refuses_preexisting_destination_symlink(tmp_path: Path) -> None:
    archive = tmp_path / "safe.zip"
    with zipfile.ZipFile(archive, "w") as zf:
        zf.writestr("dir/file.txt", "content")
    dest = tmp_path / "dest"
    dest.mkdir()
    outside = tmp_path / "outside"
    outside.mkdir()
    (dest / "dir").symlink_to(outside, target_is_directory=True)
    service = RestoreService(archiver=FallbackArchiver())

    with pytest.raises(ValidationError):
        service.extract(RestoreSpec(archive=archive, destination=dest, verify=False, confirm_overwrite=False))
    assert not (outside / "file.txt").exists()


def test_restore_refuses_symlink_destination_parent(tmp_path: Path) -> None:
    archive = tmp_path / "safe.zip"
    with zipfile.ZipFile(archive, "w") as zf:
        zf.writestr("file.txt", "content")
    outside = tmp_path / "outside"
    outside.mkdir()
    link_parent = tmp_path / "link-parent"
    link_parent.symlink_to(outside, target_is_directory=True)
    service = RestoreService(archiver=FallbackArchiver())

    with pytest.raises(ValidationError):
        service.extract(RestoreSpec(archive=archive, destination=link_parent / "dest", verify=False, confirm_overwrite=False))
    assert not (outside / "dest" / "file.txt").exists()


def test_restore_exact_subset_missing_selector_fails(tmp_path: Path) -> None:
    archive = tmp_path / "safe.zip"
    with zipfile.ZipFile(archive, "w") as zf:
        zf.writestr("dir/file.txt", "content")
    service = RestoreService(archiver=FallbackArchiver())

    with pytest.raises(NotFoundError):
        service.pick(RestoreSpec(archive=archive, destination=tmp_path / "out", paths=("missing.txt",), verify=False, confirm_overwrite=False))


def test_restore_preview_counts_files_not_directories_and_pages_all_entries(tmp_path: Path) -> None:
    archive = tmp_path / "many.zip"
    with zipfile.ZipFile(archive, "w") as zf:
        zf.writestr("dir/", b"")
        for index in range(1005):
            zf.writestr(f"dir/file-{index}.txt", b"x")
    service = RestoreService(archiver=FallbackArchiver())

    result = service.preview(RestoreSpec(archive=archive, destination=tmp_path / "out", verify=False).normalize())

    assert result.total_files == 1005
    assert len(result.files) == 1005
    assert all(not info.archive_path.endswith("/") for info in result.files)




def test_restore_preview_rejects_unsafe_archive_member_paths(tmp_path: Path) -> None:
    archive = tmp_path / "evil-preview.zip"
    with zipfile.ZipFile(archive, "w") as zf:
        zf.writestr("../escape.txt", "content")
    service = RestoreService(archiver=FallbackArchiver())

    with pytest.raises(ValidationError):
        service.preview(RestoreSpec(archive=archive, destination=tmp_path / "out", verify=False, confirm_overwrite=False).normalize())

    assert not (tmp_path / "escape.txt").exists()


def test_restore_requires_explicit_confirmation_for_existing_destination(tmp_path: Path) -> None:
    archive = tmp_path / "safe.zip"
    with zipfile.ZipFile(archive, "w") as zf:
        zf.writestr("file.txt", "content")
    dest = tmp_path / "dest"
    dest.mkdir()
    service = RestoreService(archiver=FallbackArchiver())

    with pytest.raises(ValidationError):
        service.extract(RestoreSpec(archive=archive, destination=dest, verify=False))


def test_restore_cleans_failed_staging_tree(tmp_path: Path) -> None:
    archive = tmp_path / "safe.zip"
    with zipfile.ZipFile(archive, "w") as zf:
        zf.writestr("file.txt", "new")
    dest = tmp_path / "dest"
    dest.mkdir()
    (dest / "file.txt").write_text("old")
    service = RestoreService(archiver=FallbackArchiver())

    with pytest.raises(ValidationError):
        service.extract(RestoreSpec(archive=archive, destination=dest, verify=False, confirm_overwrite=False, overwrite=False))

    assert (dest / "file.txt").read_text() == "old"
    assert not [p for p in tmp_path.iterdir() if p.name.startswith(".dest.bkx-restore.")]
