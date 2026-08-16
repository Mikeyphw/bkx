from __future__ import annotations

import io
import os
import tarfile
import zipfile
from pathlib import Path

import pytest

from bkx.core.errors import ValidationError
from bkx.core.impl.archiver import FallbackArchiver
from bkx.core.specs.archive import ArchiveCreateSpec, ArchiveInfoSpec, ArchiveListSpec, ArchiveVerifySpec
from bkx.core.specs.backup import CompressionProfile


def test_archive_create_uses_shared_manifest_for_filters_empty_dirs_and_totals(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    (source / "keep.txt").write_text("keep")
    (source / "skip.log").write_text("skip")
    (source / "empty").mkdir()
    output = tmp_path / "out.zip"

    result = FallbackArchiver().create(
        ArchiveCreateSpec(
            sources=(source,),
            output_path=output,
            includes=("*.txt", "source/empty/"),
            excludes=("*.log",),
            compression=CompressionProfile("none", 0),
        )
    )

    assert result.success is True
    assert result.files_archived == 1
    entries, total = FallbackArchiver().list_with_total(ArchiveListSpec(archive_path=output, page=0, page_size=1).normalize())
    assert total == 2
    assert len(entries) == 1
    all_entries, _ = FallbackArchiver().list_with_total(ArchiveListSpec(archive_path=output, page=0, page_size=100).normalize())
    assert {entry.path for entry in all_entries} == {"source/keep.txt", "source/empty/"}


def test_archive_creation_rejects_member_collisions_and_special_files(tmp_path: Path) -> None:
    left = tmp_path / "left.txt"
    right_dir = tmp_path / "nested"
    right_dir.mkdir()
    right = right_dir / "left.txt"
    left.write_text("left")
    right.write_text("right")
    with pytest.raises(ValidationError):
        FallbackArchiver().create(
            ArchiveCreateSpec(
                sources=(left, right),
                output_path=tmp_path / "collision.zip",
                compression=CompressionProfile("none", 0),
            )
        )

    if not hasattr(os, "mkfifo"):
        return
    fifo = tmp_path / "fifo"
    os.mkfifo(fifo)
    with pytest.raises(ValidationError):
        FallbackArchiver().create(
            ArchiveCreateSpec(
                sources=(fifo,),
                output_path=tmp_path / "fifo.zip",
                compression=CompressionProfile("none", 0),
            )
        )


def test_archive_publication_is_transactional_when_backend_fails(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    source = tmp_path / "source"
    source.mkdir()
    (source / "file.txt").write_text("content")
    output = tmp_path / "out.zip"
    archiver = FallbackArchiver()

    def fail(*args, **kwargs):  # type: ignore[no-untyped-def]
        raise OSError("boom")

    monkeypatch.setattr(archiver, "_write_zip", fail)
    with pytest.raises(OSError):
        archiver.create(
            ArchiveCreateSpec(
                sources=(source,),
                output_path=output,
                compression=CompressionProfile("none", 0),
            )
        )
    assert not output.exists()
    assert not list(tmp_path.glob(".out.zip.*.tmp"))


def test_archive_list_info_and_browse_use_complete_archive_not_first_page(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    for i in range(150):
        (source / f"file-{i:03d}.txt").write_text(str(i))
    output = tmp_path / "many.zip"
    archiver = FallbackArchiver()
    archiver.create(ArchiveCreateSpec(sources=(source,), output_path=output, compression=CompressionProfile("none", 0)))

    page, total = archiver.list_with_total(ArchiveListSpec(archive_path=output, page=1, page_size=25).normalize())
    assert len(page) == 25
    assert total == 151  # root dir + 150 files
    info = archiver.info(ArchiveInfoSpec(archive_path=output).normalize())
    assert info.total_entries == 151
    assert info.uncompressed_size == sum(len(str(i)) for i in range(150))


def test_verify_reads_zip_crc_tar_payload_and_reports_requested_hashes(tmp_path: Path) -> None:
    source = tmp_path / "source.txt"
    source.write_text("hello")
    good = tmp_path / "good.zip"
    archiver = FallbackArchiver()
    archiver.create(ArchiveCreateSpec(sources=(source,), output_path=good, compression=CompressionProfile("none", 0)))
    result = archiver.verify(ArchiveVerifySpec(archive_path=good, hash_algorithms=("sha256", "md5")).normalize())
    assert result.success is True
    assert set(result.archive_checksums) == {"sha256", "md5"}
    assert result.entries_checked == 1

    bad = tmp_path / "bad.zip"
    with zipfile.ZipFile(bad, "w", compression=zipfile.ZIP_STORED) as zf:
        zf.writestr("safe.txt", b"abc")
    data = bad.read_bytes()
    assert b"abc" in data
    bad.write_bytes(data.replace(b"abc", b"abd", 1))
    failed = archiver.verify(ArchiveVerifySpec(archive_path=bad).normalize())
    assert failed.success is False
    assert failed.entries_failed >= 1 or failed.structure_valid is False

    unsafe_tar = tmp_path / "unsafe.tar"
    with tarfile.open(unsafe_tar, "w") as tf:
        info = tarfile.TarInfo("../escape.txt")
        payload = b"escape"
        info.size = len(payload)
        tf.addfile(info, io.BytesIO(payload))
    unsafe = archiver.verify(ArchiveVerifySpec(archive_path=unsafe_tar).normalize())
    assert unsafe.success is False
    assert unsafe.entries_failed == 1


def test_archive_create_rejects_unsupported_builtin_compression_instead_of_downgrading(tmp_path: Path) -> None:
    source = tmp_path / "source.txt"
    source.write_text("payload")
    archiver = FallbackArchiver()

    for output_name in ("out.tar", "out.zip"):
        with pytest.raises(ValidationError) as excinfo:
            archiver.create(
                ArchiveCreateSpec(
                    sources=(source,),
                    output_path=tmp_path / output_name,
                    compression=CompressionProfile("zstd", 3),
                )
            )
        assert "unsupported" in str(excinfo.value).lower()
        assert not (tmp_path / output_name).exists()


def test_archive_create_honors_supported_compression_levels(tmp_path: Path) -> None:
    source = tmp_path / "source.txt"
    source.write_text("payload" * 100)

    tar_output = tmp_path / "out.tar.gz"
    zip_output = tmp_path / "out.zip"
    archiver = FallbackArchiver()
    tar_result = archiver.create(
        ArchiveCreateSpec(
            sources=(source,),
            output_path=tar_output,
            compression=CompressionProfile("gzip", 1),
        )
    )
    zip_result = archiver.create(
        ArchiveCreateSpec(
            sources=(source,),
            output_path=zip_output,
            compression=CompressionProfile("gzip", 1),
        )
    )

    assert tar_result.success is True
    assert zip_result.success is True
    with tarfile.open(tar_output, "r:gz") as tf:
        assert tf.getmember("source.txt").size == source.stat().st_size
    with zipfile.ZipFile(zip_output) as zf:
        assert zf.getinfo("source.txt").compress_type == zipfile.ZIP_DEFLATED
