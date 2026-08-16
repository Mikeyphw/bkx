from __future__ import annotations

import os
from pathlib import Path

import pytest

from bkx.core.errors import NotFoundError, ValidationError
from bkx.core.impl.transfer import FallbackTransfer
from bkx.core.services.transfer import TransferService
from bkx.core.specs.transfer import TransferSpec, TransferSyncSpec


def test_transfer_missing_source_fails(tmp_path: Path) -> None:
    service = TransferService(transfer=FallbackTransfer())
    with pytest.raises(NotFoundError):
        service.push(TransferSpec(sources=(tmp_path / "missing.txt",), destination=tmp_path / "dest", verify=False))


def test_transfer_overwrite_policy_is_honored(tmp_path: Path) -> None:
    source = tmp_path / "source.txt"
    source.write_text("new")
    dest = tmp_path / "dest.txt"
    dest.write_text("old")
    service = TransferService(transfer=FallbackTransfer())

    with pytest.raises(ValidationError):
        service.push(TransferSpec(sources=(source,), destination=dest, verify=False, overwrite=False))
    assert dest.read_text() == "old"

    result = service.push(TransferSpec(sources=(source,), destination=dest, verify=True, overwrite=True))
    assert result.success is True
    assert result.verification_passed is True
    assert result.checksum_verified is True
    assert dest.read_text() == "new"


def test_transfer_detects_destination_collisions_before_copy(tmp_path: Path) -> None:
    first_dir = tmp_path / "a"
    second_dir = tmp_path / "b"
    first_dir.mkdir()
    second_dir.mkdir()
    first = first_dir / "same.txt"
    second = second_dir / "same.txt"
    first.write_text("one")
    second.write_text("two")
    dest = tmp_path / "dest"
    dest.mkdir()
    service = TransferService(transfer=FallbackTransfer())

    with pytest.raises(ValidationError):
        service.push(TransferSpec(sources=(first, second), destination=dest, verify=False, overwrite=True))
    assert not (dest / "same.txt").exists()


def test_pull_uses_pull_result_direction_and_local_mapping(tmp_path: Path) -> None:
    source = tmp_path / "remote-ish.txt"
    source.write_text("content")
    dest = tmp_path / "local"
    service = TransferService(transfer=FallbackTransfer())

    result = service.pull(TransferSpec(sources=(source,), destination=dest, direction="pull", verify=True))

    assert result.direction == "pull"
    assert dest.read_text() == "content"
    assert result.files_transferred == 1


def test_transfer_refuses_destination_symlink(tmp_path: Path) -> None:
    source = tmp_path / "source.txt"
    source.write_text("content")
    outside = tmp_path / "outside.txt"
    dest = tmp_path / "dest.txt"
    dest.symlink_to(outside)
    service = TransferService(transfer=FallbackTransfer())

    with pytest.raises(ValidationError):
        service.push(TransferSpec(sources=(source,), destination=dest, verify=False, overwrite=True))
    assert not outside.exists()


def test_transfer_refuses_special_file(tmp_path: Path) -> None:
    fifo = tmp_path / "pipe"
    os.mkfifo(fifo)
    service = TransferService(transfer=FallbackTransfer())

    with pytest.raises(ValidationError):
        service.push(TransferSpec(sources=(fifo,), destination=tmp_path / "dest", verify=False))


def test_sync_delete_keeps_excluded_destination_paths(tmp_path: Path) -> None:
    src = tmp_path / "src"
    dst = tmp_path / "dst"
    src.mkdir()
    dst.mkdir()
    (src / "keep.txt").write_text("source")
    (dst / "old.txt").write_text("remove")
    (dst / "cache.tmp").write_text("protected")
    service = TransferService(transfer=FallbackTransfer())

    result = service.sync(TransferSyncSpec(source=src, destination=dst, delete=True, excludes=("*.tmp",), verify=True))

    assert result.success is True
    assert (dst / "keep.txt").read_text() == "source"
    assert not (dst / "old.txt").exists()
    assert (dst / "cache.tmp").read_text() == "protected"


def test_sync_uses_content_comparison_not_mtime_only(tmp_path: Path) -> None:
    src = tmp_path / "src"
    dst = tmp_path / "dst"
    src.mkdir()
    dst.mkdir()
    source = src / "file.txt"
    dest = dst / "file.txt"
    source.write_text("new-content")
    dest.write_text("old-content")
    os.utime(dest, (source.stat().st_atime + 1000, source.stat().st_mtime + 1000))
    service = TransferService(transfer=FallbackTransfer())

    result = service.sync(TransferSyncSpec(source=src, destination=dst, verify=True))

    assert result.files_transferred == 1
    assert dest.read_text() == "new-content"


def test_service_dry_run_validates_transfer_plan_and_reports_counts(tmp_path: Path) -> None:
    source = tmp_path / "source.txt"
    source.write_text("content")
    service = TransferService(transfer=FallbackTransfer())

    result = service.push(TransferSpec(sources=(source,), destination=tmp_path / "dest.txt", dry_run=True, verify=False))

    assert result.dry_run is True
    assert result.files_transferred == 1
    assert result.bytes_transferred == len("content")
    assert not (tmp_path / "dest.txt").exists()

    with pytest.raises(NotFoundError):
        service.push(TransferSpec(sources=(tmp_path / "missing.txt",), destination=tmp_path / "dest.txt", dry_run=True, verify=False))


def test_service_sync_dry_run_reports_only_content_changes(tmp_path: Path) -> None:
    src = tmp_path / "src"
    dst = tmp_path / "dst"
    src.mkdir()
    dst.mkdir()
    (src / "same.txt").write_text("same")
    (dst / "same.txt").write_text("same")
    (src / "changed.txt").write_text("new")
    (dst / "changed.txt").write_text("old")
    service = TransferService(transfer=FallbackTransfer())

    result = service.sync(TransferSyncSpec(source=src, destination=dst, dry_run=True, verify=False))

    assert result.dry_run is True
    assert result.files_transferred == 1
    assert result.bytes_transferred == len("new")
    assert (dst / "changed.txt").read_text() == "old"
