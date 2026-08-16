"""Tests for core services - orchestration logic.

Contract rule:
- Services accept Specs only.
- Services return Results only.
- Services raise typed errors only.
"""

from __future__ import annotations

import tempfile
from pathlib import Path
from typing import Any

import pytest

from bkx.core.errors import NotFoundError, ValidationError
from bkx.core.ports.hasher import HashAlgorithm
from bkx.core.ports.tool_detector import ToolInfo
from bkx.core.results.archive import ArchiveCreateResult, ArchiveEntry, ArchiveInfoResult, ArchiveVerifyResult, Timing
from bkx.core.results.hashing import HashResult
from bkx.core.results.transfer import TransferResult
from bkx.core.services.archive import ArchiveService
from bkx.core.services.backup import BackupService
from bkx.core.services.restore import RestoreService
from bkx.core.services.transfer import TransferService
from bkx.core.specs.archive import ArchiveCreateSpec, ArchiveInfoSpec, ArchiveListSpec, ArchiveVerifySpec
from bkx.core.specs.backup import BackupSpec
from bkx.core.specs.restore import RestoreSpec
from bkx.core.specs.transfer import TransferSpec, TransferSyncSpec


class FakeHasher:
    """Fake hasher for testing."""

    @property
    def algorithm(self) -> HashAlgorithm:
        return "sha256"

    @property
    def digest_length(self) -> int:
        return 64

    @property
    def name(self) -> str:
        return "fake-hasher"

    @property
    def supported_algorithms(self) -> tuple[HashAlgorithm, ...]:
        return ("md5", "sha256")

    def hash_file(
        self,
        path: Path,
        algorithm: HashAlgorithm = "sha256",
        *,
        chunk_size: int = 8192,
    ) -> HashResult:
        return HashResult(
            algorithm=algorithm,
            checksum="abc123" * 10,
            bytes_hashed=1024,
        )

    def hash_files(
        self,
        paths: tuple[Path, ...],
        algorithm: HashAlgorithm = "sha256",
        *,
        progress_callback: Any | None = None,
    ) -> dict[Path, HashResult]:
        return {p: self.hash_file(p, algorithm) for p in paths}

    def hash_bytes(self, data: bytes) -> str:
        """Compute hash of bytes.

        Returns:
            Hex digest string.
        """
        return "abc123" * 10

    def verify(
        self,
        path: Path,
        expected: str,
        *,
        chunk_size: int = 64 * 1024,
    ) -> bool:
        return True

    def hash_stream(
        self,
        stream: bytes,
        *,
        chunk_size: int = 64 * 1024,
    ) -> str:
        """Compute hash from a stream.

        Returns:
            Hex digest string.
        """
        return "abc123" * 10


class FakeArchiver:
    """Fake archiver for testing."""

    @property
    def name(self) -> str:
        return "fake-archiver"

    @property
    def supported_formats(self) -> tuple[str, ...]:
        return ("tar", "zip")

    def create(
        self,
        spec: ArchiveCreateSpec,
        *,
        progress_callback: Any | None = None,
    ) -> ArchiveCreateResult:
        if spec.dry_run:
            return ArchiveCreateResult(
                success=True,
                dry_run=True,
                timing=Timing(start_ms=0, end_ms=0),
                warnings=("Dry run",),
            )
        archive_path = spec.output_path or Path("/fake/archive.tar")
        if not archive_path.exists():
            archive_path.parent.mkdir(parents=True, exist_ok=True)
            archive_path.write_bytes(b"fake archive")
        return ArchiveCreateResult(
            success=True,
            dry_run=False,
            timing=Timing(start_ms=0, end_ms=0),
            files_archived=5,
            bytes_written=archive_path.stat().st_size,
            archive_path=archive_path,
            verification_passed=True if spec.verify else None,
        )

    def extract(
        self,
        spec: RestoreSpec,
    ) -> tuple[Path, ...]:
        """Extract files from archive."""
        return ()

    def list(self, spec: ArchiveListSpec) -> tuple[ArchiveEntry, ...]:
        return (
            ArchiveEntry(path="file1.txt", is_dir=False, size=100, mtime_ns=0),
            ArchiveEntry(path="dir/", is_dir=True, size=0, mtime_ns=0),
        )

    def info(self, spec: ArchiveInfoSpec) -> ArchiveInfoResult:
        return ArchiveInfoResult(
            success=True,
            archive_path=spec.archive_path,
            format="tar",
            created_at=0,
            total_entries=5,
            total_size=1024,
            uncompressed_size=2048,
            compression_ratio=0.5,
        )

    def verify(self, spec: ArchiveVerifySpec) -> ArchiveVerifyResult:
        return ArchiveVerifyResult(
            success=True,
            archive_path=spec.archive_path,
        )


class FakeTransfer:
    """Fake transfer for testing."""

    @property
    def name(self) -> str:
        return "fake-transfer"

    @property
    def supports_push(self) -> bool:
        return True

    @property
    def supports_pull(self) -> bool:
        return True

    def push(
        self,
        spec: TransferSpec,
        *,
        progress_callback: Any | None = None,
    ) -> TransferResult:
        return TransferResult(
            success=True,
            dry_run=spec.dry_run,
            direction="push",
            source=spec.effective_source,
            destination=spec.effective_destination,
            files_transferred=1 if spec.dry_run else 0,
            bytes_transferred=7 if spec.dry_run else 0,
            verification_passed=True if spec.verify else None,
            checksum_verified=True if spec.verify or spec.checksum else None,
            timing=Timing(start_ms=0, end_ms=0),
        )

    def pull(
        self,
        spec: TransferSpec,
        *,
        progress_callback: Any | None = None,
    ) -> TransferResult:
        return TransferResult(
            success=True,
            dry_run=spec.dry_run,
            direction="pull",
            source=spec.effective_source,
            destination=spec.effective_destination,
            files_transferred=1 if spec.dry_run else 0,
            bytes_transferred=7 if spec.dry_run else 0,
            verification_passed=True if spec.verify else None,
            checksum_verified=True if spec.verify or spec.checksum else None,
            timing=Timing(start_ms=0, end_ms=0),
        )

    def sync(
        self,
        spec: TransferSyncSpec,
        *,
        progress_callback: Any | None = None,
    ) -> TransferResult:
        return TransferResult(
            success=True,
            dry_run=spec.dry_run,
            direction="sync",
            source=spec.source,
            destination=spec.destination,
            timing=Timing(start_ms=0, end_ms=0),
        )


class FakeToolDetector:
    """Fake tool detector for testing."""

    def get_preferred(self, tool_category: str) -> str | None:
        return None

    def detect(self, tool_name: str) -> ToolInfo:
        return ToolInfo(
            name=tool_name,
            available=False,
            path=None,
            version=None,
            capabilities=(),
        )

    def detect_all(self, tool_names: tuple[str, ...]) -> dict[str, ToolInfo]:
        return {n: self.detect(n) for n in tool_names}

    def list_available(self) -> tuple[str, ...]:
        return ()


class TestBackupService:
    """Tests for BackupService."""

    def test_backup_create_success(self):
        """Test successful backup creation."""
        service = BackupService(
            archiver=FakeArchiver(),
            transfer=FakeTransfer(),
            hasher=FakeHasher(),
            tool_detector=FakeToolDetector(),
        )

        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir) / "source"
            source.mkdir()
            (source / "file.txt").write_text("content")

            spec = BackupSpec(
                sources=(source,),
                destination=Path(tmpdir),
                archive_name="test",
            ).normalize()

            result = service.create(spec)

            assert result.success is True
            assert result.archives_created >= 0
            assert result.verification_passed is True
            assert result.checksums["archive"] == "abc123" * 10

    def test_backup_dry_run(self):
        """Test backup dry run."""
        service = BackupService(
            archiver=FakeArchiver(),
            transfer=FakeTransfer(),
            hasher=FakeHasher(),
            tool_detector=FakeToolDetector(),
        )

        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir) / "source"
            source.mkdir()

            spec = BackupSpec(
                sources=(source,),
                destination=Path(tmpdir),
                archive_name="test",
                dry_run=True,
            ).normalize()

            result = service.create(spec)

            assert result.success is True
            assert result.dry_run is True

    def test_backup_nonexistent_source(self):
        """Test backup fails with nonexistent source."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir) / "nonexistent_source"

            spec = BackupSpec(
                sources=(source,),
                destination=Path(tmpdir),
                archive_name="test",
            )

            # Should raise NotFoundError during normalization
            with pytest.raises(NotFoundError):
                spec.normalize()


class TestArchiveService:
    """Tests for ArchiveService."""

    def test_archive_create_success(self):
        """Test successful archive creation."""
        service = ArchiveService(archiver=FakeArchiver())

        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir) / "source"
            source.mkdir()
            (source / "file.txt").write_text("content")

            output = Path(tmpdir) / "archive.tar"

            spec = ArchiveCreateSpec(
                sources=(source,),
                output_path=output,
            ).normalize()

            result = service.create(spec)

            assert result.success is True
            assert result.files_archived >= 0

    def test_archive_dry_run(self):
        """Test archive dry run."""
        service = ArchiveService(archiver=FakeArchiver())

        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir) / "source"
            source.mkdir()

            spec = ArchiveCreateSpec(
                sources=(source,),
                dry_run=True,
            ).normalize()

            result = service.create(spec)

            assert result.success is True
            assert result.dry_run is True

    def test_archive_list(self):
        """Test listing archive contents."""
        service = ArchiveService(archiver=FakeArchiver())

        with tempfile.TemporaryDirectory() as tmpdir:
            archive_path = Path(tmpdir) / "archive.tar"
            # Create the archive file so it exists
            archive_path.write_text("")
            result = service.list(ArchiveListSpec(archive_path=archive_path))

            assert result.success is True
            assert len(result.entries) == 2

    def test_archive_not_found(self):
        """Test archive not found error."""

        service = ArchiveService(archiver=FakeArchiver())

        with tempfile.TemporaryDirectory() as tmpdir:
            with pytest.raises(NotFoundError):
                service.list(ArchiveListSpec(archive_path=Path(tmpdir) / "nonexistent.tar"))


class TestRestoreService:
    """Tests for RestoreService."""

    def test_restore_preview_returns_file_info(self):
        """Test restore preview returns structured file info."""
        service = RestoreService(archiver=FakeArchiver())

        with tempfile.TemporaryDirectory() as tmpdir:
            archive_path = Path(tmpdir) / "archive.tar"
            archive_path.write_text("")
            destination = Path(tmpdir) / "restore"

            spec = RestoreSpec(
                archive=archive_path,
                destination=destination,
                verify=False,
            ).normalize()

            result = service.preview(spec)

            assert result.success is True
            assert result.archive_path == archive_path
            assert result.total_files == 1
            assert result.total_size == 100
            assert result.files[0].archive_path == "file1.txt"
            assert result.files[0].disk_path == destination / "file1.txt"

    def test_restore_preview_matches_requested_file_exactly(self):
        """Test restore preview does not use substring matching for files."""

        class NestedFileArchiver(FakeArchiver):
            def list(self, spec: ArchiveListSpec) -> tuple[ArchiveEntry, ...]:
                return (
                    ArchiveEntry(path="file1.txt", is_dir=False, size=100, mtime_ns=0),
                    ArchiveEntry(path="dir/file1.txt", is_dir=False, size=200, mtime_ns=0),
                )

        service = RestoreService(archiver=NestedFileArchiver())

        with tempfile.TemporaryDirectory() as tmpdir:
            archive_path = Path(tmpdir) / "archive.tar"
            archive_path.write_text("")

            spec = RestoreSpec(
                archive=archive_path,
                paths=("file1.txt",),
                verify=False,
            ).normalize()

            result = service.preview(spec)

            assert result.total_files == 1
            assert result.files[0].archive_path == "file1.txt"

    def test_restore_preview_matches_requested_directory_prefix(self):
        """Test restore preview includes children for explicit directory selections."""

        class DirectoryArchiver(FakeArchiver):
            def list(self, spec: ArchiveListSpec) -> tuple[ArchiveEntry, ...]:
                return (
                    ArchiveEntry(path="file1.txt", is_dir=False, size=100, mtime_ns=0),
                    ArchiveEntry(path="dir/file2.txt", is_dir=False, size=200, mtime_ns=0),
                    ArchiveEntry(path="dir/nested/file3.txt", is_dir=False, size=300, mtime_ns=0),
                )

        service = RestoreService(archiver=DirectoryArchiver())

        with tempfile.TemporaryDirectory() as tmpdir:
            archive_path = Path(tmpdir) / "archive.tar"
            archive_path.write_text("")

            spec = RestoreSpec(
                archive=archive_path,
                paths=("dir/",),
                verify=False,
            ).normalize()

            result = service.preview(spec)

            assert result.total_files == 2
            assert {file.archive_path for file in result.files} == {"dir/file2.txt", "dir/nested/file3.txt"}


class TestTransferService:
    """Tests for TransferService."""

    def test_transfer_push_success(self):
        """Test successful push transfer."""
        service = TransferService(
            transfer=FakeTransfer(),
            hasher=FakeHasher(),
        )

        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir) / "source.txt"
            source.write_text("content")
            dest = Path(tmpdir) / "dest.txt"

            spec = TransferSpec(
                sources=(source,),
                destination=dest,
                direction="push",
            ).normalize()

            result = service.push(spec)

            assert result.success is True
            assert result.direction == "push"

    def test_transfer_pull_success(self):
        """Test successful pull transfer."""
        service = TransferService(
            transfer=FakeTransfer(),
            hasher=FakeHasher(),
        )

        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir) / "source.txt"
            source.write_text("content")
            dest = Path(tmpdir) / "dest.txt"

            spec = TransferSpec(
                sources=(source,),
                destination=dest,
                direction="pull",
            ).normalize()

            result = service.pull(spec)

            assert result.success is True
            assert result.direction == "pull"

    def test_transfer_dry_run(self):
        """Test transfer dry run."""
        service = TransferService(
            transfer=FakeTransfer(),
            hasher=FakeHasher(),
        )

        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir) / "source.txt"
            source.write_text("content")
            dest = Path(tmpdir) / "dest.txt"

            spec = TransferSpec(
                sources=(source,),
                destination=dest,
                direction="push",
                dry_run=True,
            ).normalize()

            result = service.push(spec)

            assert result.success is True
            assert result.dry_run is True

    def test_transfer_wrong_direction(self):
        """Test transfer with wrong direction raises error."""

        service = TransferService(
            transfer=FakeTransfer(),
            hasher=FakeHasher(),
        )

        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir) / "source.txt"
            dest = Path(tmpdir) / "dest.txt"

            spec = TransferSpec(
                sources=(source,),
                destination=dest,
                direction="push",
            ).normalize()

            # Try to pull with push spec
            with pytest.raises(ValidationError):
                service.pull(spec)
