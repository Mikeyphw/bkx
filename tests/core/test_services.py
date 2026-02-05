"""Tests for core services - orchestration logic.

Contract rule:
- Services accept Specs only.
- Services return Results only.
- Services raise typed errors only.
"""

from __future__ import annotations

import pytest
from pathlib import Path
import tempfile

from bkx.core.services.backup import BackupService
from bkx.core.services.archive import ArchiveService
from bkx.core.services.transfer import TransferService
from bkx.core.specs.backup import BackupSpec
from bkx.core.specs.archive import ArchiveCreateSpec
from bkx.core.specs.transfer import TransferSpec
from bkx.core.results.archive import ArchiveCreateResult, ArchiveEntry, Timing
from bkx.core.results.transfer import TransferResult
from bkx.core.results.hashing import HashResult
from bkx.core.errors import ValidationError, NotFoundError


class FakeArchiver:
    """Fake archiver for testing."""

    def __init__(self, success: bool = True):
        self._success = success

    @property
    def name(self) -> str:
        return "fake-archiver"

    @property
    def supported_formats(self) -> tuple[str, ...]:
        return ("tar", "zip")

    def create(self, spec: ArchiveCreateSpec) -> ArchiveCreateResult:
        if spec.dry_run:
            return ArchiveCreateResult(
                success=True,
                dry_run=True,
                timing=Timing(start_ms=0, end_ms=0),
                warnings=("Dry run",),
            )
        if not self._success:
            return ArchiveCreateResult(
                success=False,
                dry_run=False,
                timing=Timing(start_ms=0, end_ms=0),
                warnings=("Fake failure",),
            )
        return ArchiveCreateResult(
            success=True,
            dry_run=False,
            timing=Timing(start_ms=0, end_ms=0),
            files_archived=5,
            bytes_written=1024,
            archive_path=spec.output_path or Path("/fake/archive.tar"),
        )

    def list(self, path: Path, **kwargs) -> tuple[ArchiveEntry, ...]:
        return (
            ArchiveEntry(path="file1.txt", is_dir=False, size=100, mtime_ns=0),
            ArchiveEntry(path="dir/", is_dir=True, size=0, mtime_ns=0),
        )

    def info(self, path: Path):
        return type(
            "ArchiveInfoResult",
            (),
            {
                "success": True,
                "path": path,
                "format": "tar",
                "total_entries": 5,
                "total_size": 1024,
            },
        )()

    def verify(self, path: Path):
        return type(
            "ArchiveVerifyResult",
            (),
            {
                "success": True,
                "path": path,
                "checksums_valid": True,
                "structure_valid": True,
                "entries_checked": 5,
            },
        )()


class FakeTransfer:
    """Fake transfer for testing."""

    def __init__(self, success: bool = True):
        self._success = success

    @property
    def name(self) -> str:
        return "fake-transfer"

    @property
    def supports_push(self) -> bool:
        return True

    @property
    def supports_pull(self) -> bool:
        return True

    def push(self, spec: TransferSpec, **kwargs) -> TransferResult:
        if spec.dry_run:
            return TransferResult(
                success=True,
                dry_run=True,
                direction="push",
                timing=Timing(start_ms=0, end_ms=0),
                warnings=("Dry run",),
            )
        if not self._success:
            return TransferResult(
                success=False,
                dry_run=False,
                direction="push",
                timing=Timing(start_ms=0, end_ms=0),
                errors=("Fake failure",),
            )
        return TransferResult(
            success=True,
            dry_run=False,
            direction="push",
            timing=Timing(start_ms=0, end_ms=0),
            files_transferred=1,
            bytes_transferred=1024,
            source=spec.sources[0] if spec.sources else None,
            destination=spec.destination,
        )

    def pull(self, spec: TransferSpec, **kwargs) -> TransferResult:
        if spec.dry_run:
            return TransferResult(
                success=True,
                dry_run=True,
                direction="pull",
                timing=Timing(start_ms=0, end_ms=0),
                warnings=("Dry run",),
            )
        return TransferResult(
            success=True,
            dry_run=False,
            direction="pull",
            timing=Timing(start_ms=0, end_ms=0),
            files_transferred=1,
            bytes_transferred=1024,
            source=spec.sources[0] if spec.sources else None,
            destination=spec.destination,
        )

    def sync(self, **kwargs) -> TransferResult:
        return TransferResult(
            success=True,
            dry_run=False,
            direction="push",
            timing=Timing(start_ms=0, end_ms=0),
        )


class FakeHasher:
    """Fake hasher for testing."""

    @property
    def name(self) -> str:
        return "fake-hasher"

    @property
    def supported_algorithms(self) -> tuple[str, ...]:
        return ("md5", "sha256")

    def hash_file(self, path: Path, algorithm: str = "sha256") -> HashResult:
        return HashResult(
            algorithm=algorithm,
            checksum="abc123" * 10,
            bytes_hashed=1024,
        )

    def hash_files(self, paths, **kwargs):
        return {p: self.hash_file(p) for p in paths}

    def hash_directory(self, path: Path, **kwargs):
        return type(
            "DirHashResult",
            (),
            {
                "success": True,
                "file_count": 5,
                "directory_checksum": "dir123",
            },
        )()


class FakeToolDetector:
    """Fake tool detector for testing."""

    def detect(self, name: str):
        return type(
            "ToolInfo",
            (),
            {
                "name": name,
                "available": False,
                "path": None,
                "version": None,
            },
        )()

    def detect_all(self, names):
        return {n: self.detect(n) for n in names}

    def list_available(self):
        return {}


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

    def test_backup_archiver_failure(self):
        """Test backup handles archiver failure."""
        service = BackupService(
            archiver=FakeArchiver(success=False),
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
            ).normalize()

            result = service.create(spec)

            assert result.success is False


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
            result = service.list(archive_path)

            assert result.success is True
            assert len(result.entries) == 2

    def test_archive_not_found(self):
        """Test archive not found error."""
        from bkx.core.errors import NotFoundError

        service = ArchiveService(archiver=FakeArchiver())

        with tempfile.TemporaryDirectory() as tmpdir:
            with pytest.raises(NotFoundError):
                service.list(Path(tmpdir) / "nonexistent.tar")


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
        from bkx.core.errors import ValidationError

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
