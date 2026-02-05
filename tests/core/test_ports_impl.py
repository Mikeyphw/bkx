"""Tests for core ports and implementations."""

from __future__ import annotations

import pytest
from pathlib import Path
import tempfile
import os

from bkx.core.impl.tool_detector import FallbackToolDetector
from bkx.core.impl.archiver import FallbackArchiver
from bkx.core.impl.transfer import FallbackTransfer
from bkx.core.impl.hasher import FallbackHasher


class TestFallbackToolDetector:
    """Tests for FallbackToolDetector."""

    def test_detect_nonexistent_tool(self):
        """Test detecting a tool that doesn't exist."""
        detector = FallbackToolDetector()
        info = detector.detect("nonexistent_tool_xyz")

        assert info.name == "nonexistent_tool_xyz"
        assert info.available is False
        assert info.path is None

    def test_detect_existing_tool(self):
        """Test detecting an existing tool like python."""
        detector = FallbackToolDetector()
        info = detector.detect("python")

        assert info.name == "python"
        assert info.available is True
        assert info.path is not None
        assert "python" in info.path.as_posix()

    def test_detect_all(self):
        """Test detecting multiple tools."""
        detector = FallbackToolDetector()
        results = detector.detect_all(("python", "nonexistent_xyz"))

        assert len(results) == 2
        assert results["python"].available is True
        assert results["nonexistent_xyz"].available is False

    def test_list_available(self):
        """Test listing available tools."""
        detector = FallbackToolDetector()
        available = detector.list_available()

        # Python should be available
        assert "python" in available


class TestFallbackArchiver:
    """Tests for FallbackArchiver."""

    def test_archiver_properties(self):
        """Test archiver name and supported formats."""
        archiver = FallbackArchiver()

        assert archiver.name == "python-builtin"
        assert "tar" in archiver.supported_formats
        assert "zip" in archiver.supported_formats

    def test_create_tar_archive(self):
        """Test creating a tar archive."""
        archiver = FallbackArchiver()

        with tempfile.TemporaryDirectory() as tmpdir:
            # Create test files
            test_dir = Path(tmpdir) / "source"
            test_dir.mkdir()
            (test_dir / "file1.txt").write_text("content1")
            (test_dir / "file2.txt").write_text("content2")

            output_path = Path(tmpdir) / "output.tar"

            from bkx.core.specs.archive import ArchiveCreateSpec

            spec = ArchiveCreateSpec(
                sources=(test_dir,),
                output_path=output_path,
            )

            result = archiver.create(spec)

            assert result.success is True
            assert result.files_archived >= 2
            assert output_path.exists()

    def test_list_archive(self):
        """Test listing archive contents."""
        archiver = FallbackArchiver()

        with tempfile.TemporaryDirectory() as tmpdir:
            # Create a test archive
            source_dir = Path(tmpdir) / "source"
            source_dir.mkdir()
            (source_dir / "file.txt").write_text("content")

            archive_path = Path(tmpdir) / "test.tar"
            from bkx.core.specs.archive import ArchiveCreateSpec

            create_spec = ArchiveCreateSpec(
                sources=(source_dir,),
                output_path=archive_path,
            )
            archiver.create(create_spec)

            # List contents
            entries = archiver.list(archive_path)

            assert len(entries) >= 1
            # Check entry has required fields
            entry = entries[0]
            assert hasattr(entry, "path")
            assert hasattr(entry, "size")


class TestFallbackTransfer:
    """Tests for FallbackTransfer."""

    def test_transfer_properties(self):
        """Test transfer properties."""
        transfer = FallbackTransfer()

        assert transfer.name == "python-shutil"
        assert transfer.supports_push is True
        assert transfer.supports_pull is True

    def test_copy_file(self):
        """Test copying a single file."""
        transfer = FallbackTransfer()

        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir) / "source.txt"
            source.write_text("test content")

            dest = Path(tmpdir) / "dest.txt"

            from bkx.core.specs.transfer import TransferSpec

            spec = TransferSpec(
                sources=(source,),
                destination=dest,
                direction="push",
            )

            result = transfer.push(spec)

            assert result.success is True
            assert result.files_transferred == 1
            assert dest.exists()
            assert dest.read_text() == "test content"

    def test_copy_directory(self):
        """Test copying a directory."""
        transfer = FallbackTransfer()

        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir) / "source"
            source.mkdir()
            (source / "file1.txt").write_text("content1")
            (source / "file2.txt").write_text("content2")

            dest = Path(tmpdir) / "dest"

            from bkx.core.specs.transfer import TransferSpec

            spec = TransferSpec(
                sources=(source,),
                destination=dest,
                direction="push",
            )

            result = transfer.push(spec)

            assert result.success is True
            assert result.files_transferred >= 2


class TestFallbackHasher:
    """Tests for FallbackHasher."""

    def test_hasher_properties(self):
        """Test hasher properties."""
        hasher = FallbackHasher()

        assert hasher.name == "python-zlib"
        assert "md5" in hasher.supported_algorithms
        assert "sha256" in hasher.supported_algorithms
        assert "sha512" in hasher.supported_algorithms

    def test_hash_file(self):
        """Test hashing a single file."""
        hasher = FallbackHasher()

        with tempfile.TemporaryDirectory() as tmpdir:
            test_file = Path(tmpdir) / "test.txt"
            test_file.write_text("hello world")

            result = hasher.hash_file(test_file, algorithm="sha256")

            assert result.algorithm == "sha256"
            assert result.checksum is not None
            assert len(result.checksum) == 64  # SHA256 hex length
            assert result.bytes_hashed == 11

    def test_hash_file_md5(self):
        """Test hashing with MD5."""
        hasher = FallbackHasher()

        with tempfile.TemporaryDirectory() as tmpdir:
            test_file = Path(tmpdir) / "test.txt"
            test_file.write_text("hello")

            result = hasher.hash_file(test_file, algorithm="md5")

            assert result.algorithm == "md5"
            assert len(result.checksum) == 32  # MD5 hex length

    def test_hash_files(self):
        """Test hashing multiple files."""
        hasher = FallbackHasher()

        with tempfile.TemporaryDirectory() as tmpdir:
            file1 = Path(tmpdir) / "file1.txt"
            file1.write_text("content1")
            file2 = Path(tmpdir) / "file2.txt"
            file2.write_text("content2")

            results = hasher.hash_files((file1, file2), algorithm="sha256")

            assert len(results) == 2
            assert all(r.checksum for r in results.values())

    def test_hash_directory(self):
        """Test hashing a directory."""
        hasher = FallbackHasher()

        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir) / "source"
            source.mkdir()
            (source / "file1.txt").write_text("content1")
            (source / "file2.txt").write_text("content2")

            result = hasher.hash_directory(source, algorithm="sha256")

            assert result.success is True
            assert result.file_count == 2
            assert result.directory_checksum is not None
