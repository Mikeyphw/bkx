"""Tests for RestoreService.pick() - partial restore operation.

Contract rule:
- Services accept Specs only.
- Services return Results only.
- Services raise typed errors only.
"""

from __future__ import annotations

import pytest
import tarfile
import zipfile
from pathlib import Path
import tempfile

from bkx.core.services.restore import RestoreService
from bkx.core.specs.restore import RestoreSpec
from bkx.core.results.restore import RestoreResult
from bkx.core.errors import ValidationError


class RealArchiver:
    """Real archiver using tarfile/zipfile for testing."""

    def __init__(self, archive_format: str = "tar"):
        self._archive_format = archive_format

    @property
    def name(self) -> str:
        return f"real-{self._archive_format}"

    @property
    def supported_formats(self) -> tuple[str, ...]:
        return (self._archive_format,)

    def create(self, spec):
        pass  # Not needed for restore tests

    def list(self, archive_path: Path, **kwargs):
        entries = []
        if str(archive_path).endswith(".zip"):
            with zipfile.ZipFile(archive_path, "r") as zf:
                for info in zf.infolist():
                    entries.append(
                        type("ArchiveEntry", (), {
                            "path": info.filename,
                            "is_dir": info.is_dir(),
                            "size": info.file_size,
                            "mtime_ns": 0,
                        })()
                    )
        else:
            with tarfile.open(archive_path, "r") as tar:
                for member in tar.getmembers():
                    entries.append(
                        type("ArchiveEntry", (), {
                            "path": member.name,
                            "is_dir": member.isdir(),
                            "size": member.size,
                            "mtime_ns": 0,
                        })()
                    )
        return entries

    def info(self, path: Path):
        pass  # Not needed

    def verify(self, archive_path: Path):
        pass  # Not needed

    def extract(self, spec: RestoreSpec) -> tuple[Path, ...]:
        """Extract files from archive, handling partial restore."""
        archive_path = spec.archive
        destination = spec.destination or Path.cwd()

        extracted_paths = []

        if str(archive_path).endswith(".zip"):
            with zipfile.ZipFile(archive_path, "r") as zf:
                for info in zf.infolist():
                    # Filter by paths if partial restore
                    if spec.paths and not any(p in info.filename for p in spec.paths):
                        continue

                    zf.extract(info.filename, destination)
                    extracted_paths.append(destination / info.filename)
        else:
            with tarfile.open(archive_path, "r") as tar:
                members = tar.getmembers()

                # Filter by paths if partial restore
                if spec.paths:
                    members = [
                        m for m in members
                        if any(p in m.name for p in spec.paths)
                    ]

                for member in members:
                    tar.extract(member, destination)
                    extracted_paths.append(destination / member.name)

        return tuple(extracted_paths)


class TestRestorePick:
    """Tests for RestoreService.pick() partial restore."""

    def test_pick_requires_paths(self):
        """Test that pick() requires paths to be specified."""
        service = RestoreService(archiver=RealArchiver())

        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")

            # Try pick without paths
            spec = RestoreSpec(
                archive=archive,
                destination=Path(tmpdir) / "dest",
            )

            with pytest.raises(ValidationError) as exc_info:
                service.pick(spec)

            assert "requires specific paths" in str(exc_info.value)

    def test_pick_with_paths(self):
        """Test pick() with specific paths."""
        service = RestoreService(archiver=RealArchiver())

        with tempfile.TemporaryDirectory() as tmpdir:
            # Create a test archive
            source_dir = Path(tmpdir) / "source"
            source_dir.mkdir()
            (source_dir / "file1.txt").write_text("content1")
            (source_dir / "file2.txt").write_text("content2")
            (source_dir / "subdir").mkdir()
            (source_dir / "subdir" / "file3.txt").write_text("content3")

            archive_path = Path(tmpdir) / "archive.tar"
            with tarfile.open(archive_path, "w") as tar:
                for root, dirs, fnames in source_dir.walk():
                    for fname in fnames:
                        fpath = Path(root) / fname
                        tar.add(fpath, arcname=str(fpath.relative_to(source_dir.parent)))

            dest_dir = Path(tmpdir) / "dest"
            dest_dir.mkdir()

            # Pick specific files
            spec = RestoreSpec(
                archive=archive_path,
                destination=dest_dir,
                paths=("source/file1.txt", "source/file2.txt"),
            )

            result = service.pick(spec)

            assert result.success is True
            assert result.files_restored == 2
            assert (dest_dir / "source/file1.txt").exists()
            assert (dest_dir / "source/file2.txt").exists()
            assert not (dest_dir / "source/subdir/file3.txt").exists()

    def test_pick_dry_run(self):
        """Test pick() dry run."""
        service = RestoreService(archiver=RealArchiver())

        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")

            spec = RestoreSpec(
                archive=archive,
                destination=Path(tmpdir) / "dest",
                paths=("file1.txt",),
                dry_run=True,
            )

            result = service.pick(spec)

            assert result.success is True
            assert result.dry_run is True
            assert "Dry run" in result.warnings[0]

    def test_pick_is_alias_for_extract(self):
        """Test that pick() calls extract internally."""
        service = RestoreService(archiver=RealArchiver())

        with tempfile.TemporaryDirectory() as tmpdir:
            # Create a test archive
            source_dir = Path(tmpdir) / "source"
            source_dir.mkdir()
            (source_dir / "file1.txt").write_text("content1")

            archive_path = Path(tmpdir) / "archive.tar"
            with tarfile.open(archive_path, "w") as tar:
                tar.add(source_dir / "file1.txt", arcname="file1.txt")

            dest_dir = Path(tmpdir) / "dest"
            dest_dir.mkdir()

            # Extract with paths (same as pick)
            spec = RestoreSpec(
                archive=archive_path,
                destination=dest_dir,
                paths=("file1.txt",),
            )

            extract_result = service.extract(spec)
            pick_result = service.pick(spec)

            # Results should be equivalent
            assert extract_result.success == pick_result.success
            assert extract_result.files_restored == pick_result.files_restored


class TestArchiveVerify:
    """Tests for ArchiveService.verify() with FallbackArchiver."""

    def test_verify_tar_archive(self):
        """Test verifying a tar archive."""
        from bkx.core.impl.archiver import FallbackArchiver

        archiver = FallbackArchiver()

        with tempfile.TemporaryDirectory() as tmpdir:
            # Create a test archive
            source_dir = Path(tmpdir) / "source"
            source_dir.mkdir()
            (source_dir / "file.txt").write_text("content")

            archive_path = Path(tmpdir) / "archive.tar"
            with tarfile.open(archive_path, "w") as tar:
                tar.add(source_dir / "file.txt", arcname="file.txt")

            result = archiver.verify(archive_path)

            assert result.success is True
            assert result.checksums_valid is True
            assert result.structure_valid is True

    def test_verify_zip_archive(self):
        """Test verifying a zip archive."""
        from bkx.core.impl.archiver import FallbackArchiver

        archiver = FallbackArchiver()

        with tempfile.TemporaryDirectory() as tmpdir:
            # Create a test archive
            source_dir = Path(tmpdir) / "source"
            source_dir.mkdir()
            (source_dir / "file.txt").write_text("content")

            archive_path = Path(tmpdir) / "archive.zip"
            with zipfile.ZipFile(archive_path, "w", zipfile.ZIP_DEFLATED) as zf:
                zf.write(source_dir / "file.txt", arcname="file.txt")

            result = archiver.verify(archive_path)

            assert result.success is True
            assert result.checksums_valid is True
            assert result.structure_valid is True

    def test_verify_nonexistent_archive(self):
        """Test verifying a nonexistent archive raises NotFoundError."""
        from bkx.core.impl.archiver import FallbackArchiver
        from bkx.core.errors import NotFoundError

        archiver = FallbackArchiver()

        with pytest.raises(NotFoundError):
            archiver.verify(Path("/nonexistent/archive.tar"))

