"""Tests for ArchiveService.browse() - archive browsing with filters.

Contract rule:
- Services accept Specs only.
- Services return Results only.
- Services raise typed errors only.
"""

from __future__ import annotations

import tempfile
from pathlib import Path

import pytest

from bkx.core.errors import NotFoundError, ValidationError
from bkx.core.results.archive import ArchiveCreateResult, ArchiveInfoResult, ArchiveVerifyResult, Timing
from bkx.core.services.archive import ArchiveService
from bkx.core.specs.archive import ArchiveBrowseSpec, ArchiveCreateSpec, ArchiveInfoSpec, ArchiveListSpec, ArchiveVerifySpec
from bkx.core.specs.restore import RestoreSpec


class MockArchiver:
    """Mock archiver for testing browse functionality."""

    def __init__(self, entries):
        self._entries = entries

    @property
    def name(self) -> str:
        return "mock-archiver"

    @property
    def supported_formats(self) -> tuple[str, ...]:
        return ("tar",)

    def create(
        self,
        spec: ArchiveCreateSpec,
        *,
        progress_callback=None,
    ) -> ArchiveCreateResult:
        return ArchiveCreateResult(
            success=True,
            dry_run=spec.dry_run,
            timing=Timing(start_ms=0, end_ms=0),
        )

    def list(self, spec: ArchiveListSpec) -> tuple:
        return self._entries

    def info(self, spec: ArchiveInfoSpec) -> ArchiveInfoResult:
        return ArchiveInfoResult(
            success=True,
            archive_path=spec.archive_path,
            format="tar",
            created_at=0,
            total_entries=0,
            total_size=0,
            uncompressed_size=0,
            compression_ratio=0.0,
        )

    def verify(self, spec: ArchiveVerifySpec) -> ArchiveVerifyResult:
        return ArchiveVerifyResult(
            success=True,
            archive_path=spec.archive_path,
        )

    def extract(self, spec: RestoreSpec) -> tuple:
        return ()


def make_entry(path: str, is_dir: bool = False, size: int = 100, mtime_ns: int = 0):
    """Create a mock archive entry."""
    return type(
        "ArchiveEntry",
        (),
        {
            "path": path,
            "is_dir": is_dir,
            "size": size,
            "mtime_ns": mtime_ns,
        },
    )()


class TestArchiveBrowseSpec:
    """Tests for ArchiveBrowseSpec validation."""

    def test_spec_normalization(self):
        """Test that spec normalizes correctly."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")

            spec = ArchiveBrowseSpec(
                archive_path=archive,
                query="docs",
                file_type="file",
                limit=100,
            )

            normalized = spec.normalize()

            assert normalized.query == "docs"
            assert normalized.file_type == "file"
            assert normalized.limit == 100

    def test_nonexistent_archive_raises_not_found(self):
        """Test that nonexistent archive raises NotFoundError."""
        spec = ArchiveBrowseSpec(
            archive_path=Path("/nonexistent/archive.tar"),
        )

        with pytest.raises(NotFoundError):
            spec.normalize()

    def test_invalid_limit_raises_validation_error(self):
        """Test that invalid limit raises ValidationError."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")

            with pytest.raises(ValidationError):
                ArchiveBrowseSpec(
                    archive_path=archive,
                    limit=0,  # Invalid: rejected at Spec construction
                )

    def test_invalid_sort_by_raises_validation_error(self):
        """Test that invalid sort_by raises ValidationError."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")

            with pytest.raises(ValidationError):
                ArchiveBrowseSpec(
                    archive_path=archive,
                    sort_by="invalid",  # Invalid: rejected at Spec construction
                )

    def test_invalid_file_type_raises_validation_error(self):
        """Test that invalid file_type raises ValidationError."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")

            with pytest.raises(ValidationError):
                ArchiveBrowseSpec(
                    archive_path=archive,
                    file_type="invalid",  # Invalid: rejected at Spec construction
                )


class TestArchiveBrowseService:
    """Tests for ArchiveService.browse() method."""

    def test_browse_all_entries(self):
        """Test browsing all entries without filters."""
        entries = [
            make_entry("file1.txt", size=100),
            make_entry("file2.txt", size=200),
            make_entry("dir1/", is_dir=True),
            make_entry("dir1/file3.txt", size=300),
        ]

        service = ArchiveService(archiver=MockArchiver(entries))

        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")

            spec = ArchiveBrowseSpec(archive_path=archive)
            result = service.browse(spec)

            assert result.success is True
            assert result.total_matches == 4
            assert len(result.entries) == 4
            assert result.files_count == 3
            assert result.directories_count == 1

    def test_browse_with_query_filter(self):
        """Test browsing with query filter."""
        entries = [
            make_entry("docs/file1.txt"),
            make_entry("docs/file2.txt"),
            make_entry("images/photo.jpg"),
            make_entry("other/file3.txt"),
        ]

        service = ArchiveService(archiver=MockArchiver(entries))

        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")

            spec = ArchiveBrowseSpec(archive_path=archive, query="docs")
            result = service.browse(spec)

            assert result.success is True
            assert result.total_matches == 2
            assert len(result.entries) == 2
            assert result.query == "docs"

    def test_browse_with_file_type_filter(self):
        """Test browsing with file type filter."""
        entries = [
            make_entry("file1.txt", is_dir=False),
            make_entry("dir1/", is_dir=True),
            make_entry("file2.txt", is_dir=False),
            make_entry("dir2/", is_dir=True),
        ]

        service = ArchiveService(archiver=MockArchiver(entries))

        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")

            # Get only files
            spec = ArchiveBrowseSpec(archive_path=archive, file_type="file")
            result = service.browse(spec)

            assert result.success is True
            assert result.total_matches == 2
            assert result.files_count == 2
            assert result.directories_count == 0

            # Get only directories
            spec = ArchiveBrowseSpec(archive_path=archive, file_type="dir")
            result = service.browse(spec)

            assert result.success is True
            assert result.total_matches == 2
            assert result.files_count == 0
            assert result.directories_count == 2

    def test_browse_with_size_filters(self):
        """Test browsing with size filters."""
        entries = [
            make_entry("small.txt", size=50),
            make_entry("medium.txt", size=500),
            make_entry("large.txt", size=5000),
        ]

        service = ArchiveService(archiver=MockArchiver(entries))

        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")

            # Min size filter
            spec = ArchiveBrowseSpec(archive_path=archive, min_size=100)
            result = service.browse(spec)

            assert result.success is True
            assert result.total_matches == 2
            assert result.total_size == 5500

            # Max size filter
            spec = ArchiveBrowseSpec(archive_path=archive, max_size=1000)
            result = service.browse(spec)

            assert result.success is True
            assert result.total_matches == 2
            assert result.total_size == 550

            # Min and max size filter
            spec = ArchiveBrowseSpec(archive_path=archive, min_size=100, max_size=1000)
            result = service.browse(spec)

            assert result.success is True
            assert result.total_matches == 1
            assert result.entries[0].path == "medium.txt"

    def test_browse_with_sorting(self):
        """Test browsing with sorting."""
        entries = [
            make_entry("b.txt", size=200),
            make_entry("a.txt", size=100),
            make_entry("c.txt", size=300),
        ]

        service = ArchiveService(archiver=MockArchiver(entries))

        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")

            # Sort by path asc
            spec = ArchiveBrowseSpec(archive_path=archive, sort_by="path", sort_order="asc")
            result = service.browse(spec)

            assert result.entries[0].path == "a.txt"
            assert result.entries[1].path == "b.txt"
            assert result.entries[2].path == "c.txt"

            # Sort by path desc
            spec = ArchiveBrowseSpec(archive_path=archive, sort_by="path", sort_order="desc")
            result = service.browse(spec)

            assert result.entries[0].path == "c.txt"
            assert result.entries[1].path == "b.txt"
            assert result.entries[2].path == "a.txt"

            # Sort by size asc
            spec = ArchiveBrowseSpec(archive_path=archive, sort_by="size", sort_order="asc")
            result = service.browse(spec)

            assert result.entries[0].size == 100
            assert result.entries[1].size == 200
            assert result.entries[2].size == 300

    def test_browse_with_pagination(self):
        """Test browsing with pagination."""
        entries = [make_entry(f"file{i}.txt", size=100) for i in range(10)]

        service = ArchiveService(archiver=MockArchiver(entries))

        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")

            # Get first page
            spec = ArchiveBrowseSpec(archive_path=archive, limit=3, offset=0)
            result = service.browse(spec)

            assert len(result.entries) == 3
            assert result.offset == 0
            assert result.limit == 3

            # Get second page
            spec = ArchiveBrowseSpec(archive_path=archive, limit=3, offset=3)
            result = service.browse(spec)

            assert len(result.entries) == 3
            assert result.offset == 3

    def test_browse_with_combined_filters(self):
        """Test browsing with multiple combined filters."""
        entries = [
            make_entry("docs/small.txt", size=50),
            make_entry("docs/large.txt", size=5000),
            make_entry("images/photo.jpg", size=1000),
            make_entry("other/file.txt", size=100),
        ]

        service = ArchiveService(archiver=MockArchiver(entries))

        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")

            # Query + file type + size filter
            spec = ArchiveBrowseSpec(
                archive_path=archive,
                query="docs",
                file_type="file",
                min_size=100,
            )
            result = service.browse(spec)

            assert result.success is True
            assert result.total_matches == 1
            assert result.entries[0].path == "docs/large.txt"

    def test_browse_empty_result(self):
        """Test browsing with no matching entries."""
        entries = [
            make_entry("file1.txt"),
            make_entry("file2.txt"),
        ]

        service = ArchiveService(archiver=MockArchiver(entries))

        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")

            spec = ArchiveBrowseSpec(archive_path=archive, query="nonexistent")
            result = service.browse(spec)

            assert result.success is True
            assert result.total_matches == 0
            assert len(result.entries) == 0
            assert result.files_count == 0
            assert result.directories_count == 0
