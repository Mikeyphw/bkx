from __future__ import annotations

import time
from pathlib import Path

import pytest

from bkx.core.errors import BkxError, NotFoundError, ValidationError
from bkx.core.impl.run_store import JsonRunStore
from bkx.core.ports.archiver import ArchiverPort
from bkx.core.results.archive import ArchiveCreateResult, ArchiveEntry, ArchiveInfoResult, ArchiveVerifyResult
from bkx.core.results.backup import Timing
from bkx.core.results.restore import RestoreResult
from bkx.core.services.archive import ArchiveService
from bkx.core.services.restore import RestoreService
from bkx.core.services.transfer import TransferService
from bkx.core.specs.archive import ArchiveBrowseSpec, ArchiveCreateSpec, ArchiveInfoSpec, ArchiveListSpec, ArchiveVerifySpec
from bkx.core.specs.backup import CompressionProfile
from bkx.core.specs.restore import RestoreSpec
from bkx.core.specs.transfer import TransferSpec


class PagingArchiver:
    name = "paging"
    supported_formats = ("zip",)

    def __init__(self, count: int = 1205) -> None:
        self.entries = tuple(ArchiveEntry(path=f"Docs/File-{i}.TXT", is_dir=False, size=i, mtime_ns=i) for i in range(count))

    def create(self, spec: ArchiveCreateSpec, *, progress_callback=None) -> ArchiveCreateResult:
        return ArchiveCreateResult(success=True, dry_run=False, timing=Timing(0, 0), archive_path=spec.effective_output_path, files_archived=1, bytes_written=2)

    def list(self, spec: ArchiveListSpec) -> tuple[ArchiveEntry, ...]:
        start = spec.page * spec.page_size
        entries = self.entries
        if spec.filter_pattern:
            entries = tuple(e for e in entries if "File" in e.path)
        return entries[start : start + spec.page_size]

    def info(self, spec: ArchiveInfoSpec) -> ArchiveInfoResult:
        return ArchiveInfoResult(success=True, archive_path=spec.archive_path, format="zip", created_at=0, total_entries=len(self.entries), total_size=0, uncompressed_size=0, compression_ratio=1)

    def verify(self, spec: ArchiveVerifySpec) -> ArchiveVerifyResult:
        return ArchiveVerifyResult(success=True, archive_path=spec.archive_path, warnings=("verified",))

    def extract(self, spec: RestoreSpec) -> tuple[Path, ...]:
        destination = spec.effective_destination or spec.destination or spec.archive.parent / "out"
        destination.mkdir(parents=True, exist_ok=True)
        restored = destination / "file.txt"
        restored.write_bytes(b"12345")
        return (restored,)


def test_archive_service_fallback_paginates_all_entries_and_normalizes_query(tmp_path: Path) -> None:
    archive = tmp_path / "archive.zip"
    archive.write_bytes(b"placeholder")
    service = ArchiveService(archiver=PagingArchiver())

    listed = service.list(ArchiveListSpec(archive_path=archive, page=2, page_size=7))
    browsed = service.browse(ArchiveBrowseSpec(archive_path=archive, query="DOCS/FILE-120", limit=20, offset=0))

    assert listed.page == 2
    assert listed.page_size == 7
    assert listed.total_entries == 1205
    assert browsed.total_matches >= 6
    assert all("file-120" in entry.path.lower() for entry in browsed.entries)


class PermissionTransfer:
    name = "blocked"
    supports_push = True
    supports_pull = True

    def push(self, spec, *, progress_callback=None):
        raise PermissionError("blocked")

    def pull(self, spec, *, progress_callback=None):
        raise PermissionError("blocked")

    def sync(self, spec, *, progress_callback=None):
        raise PermissionError("blocked")


def test_transfer_service_translates_backend_errors(tmp_path: Path) -> None:
    source = tmp_path / "file.txt"
    source.write_text("x")
    spec = TransferSpec(sources=(source,), destination=tmp_path / "out.txt", direction="push", verify=False, overwrite=True)
    service = TransferService(transfer=PermissionTransfer())

    with pytest.raises(BkxError):
        service.push(spec)


def test_restore_service_reports_verification_warnings_and_bytes(tmp_path: Path) -> None:
    archive = tmp_path / "archive.zip"
    archive.write_bytes(b"placeholder")
    dest = tmp_path / "dest"
    service = RestoreService(archiver=PagingArchiver())

    result = service.extract(RestoreSpec(archive=archive, destination=dest, verify=True, confirm_overwrite=False))

    assert result.verification_passed is True
    assert result.bytes_restored == 5
    assert "verified" in result.warnings


def test_service_run_history_records_real_archive_workflow(tmp_path: Path) -> None:
    archive = tmp_path / "out.zip"
    source = tmp_path / "src"
    source.mkdir()
    store = JsonRunStore(runs_file=tmp_path / "runs.jsonl", recover_started=False)
    service = ArchiveService(archiver=PagingArchiver(), run_store=store)

    service.create(ArchiveCreateSpec(sources=(source,), output_path=archive, compression=CompressionProfile("none", 0), verify=False))

    runs = store.list_runs()
    assert len(runs) == 1
    assert runs[0].operation == "archive"
    assert runs[0].status == "success"
    assert runs[0].files_count == 1



def test_restore_dry_run_propagates_preview_safety_errors(tmp_path: Path) -> None:
    import zipfile

    archive = tmp_path / "evil.zip"
    with zipfile.ZipFile(archive, "w") as zf:
        zf.writestr("../escape.txt", "x")
    service = RestoreService(archiver=PagingArchiver())
    # Use the real fallback archiver for member-path validation.
    from bkx.core.impl.archiver import FallbackArchiver

    service = RestoreService(archiver=FallbackArchiver())

    with pytest.raises(ValidationError):
        service.extract(RestoreSpec(archive=archive, destination=tmp_path / "out", dry_run=True, verify=False, confirm_overwrite=False))


class LooseRestoreArchiver(PagingArchiver):
    def __init__(self) -> None:
        super().__init__(count=1)
        self.entries = (ArchiveEntry(path="file.txt", is_dir=False, size=1, mtime_ns=0),)
        self.extract_called = False

    def extract(self, spec: RestoreSpec) -> tuple[Path, ...]:
        self.extract_called = True
        return ()


def test_restore_pick_validates_missing_selectors_at_service_boundary(tmp_path: Path) -> None:
    archive = tmp_path / "archive.zip"
    archive.write_bytes(b"placeholder")
    archiver = LooseRestoreArchiver()
    service = RestoreService(archiver=archiver)

    with pytest.raises(NotFoundError):
        service.pick(RestoreSpec(archive=archive, destination=tmp_path / "out", paths=("missing.txt",), verify=False, confirm_overwrite=False))

    assert archiver.extract_called is False


def test_restore_service_normalizes_explicit_destination_specs_before_backend(tmp_path: Path) -> None:
    archiver = LooseRestoreArchiver()
    service = RestoreService(archiver=archiver)

    with pytest.raises(NotFoundError):
        service.extract(RestoreSpec(archive=tmp_path / "missing.zip", destination=tmp_path / "out", verify=False, confirm_overwrite=False))

    assert archiver.extract_called is False
