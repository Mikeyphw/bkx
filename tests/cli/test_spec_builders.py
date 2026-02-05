"""Tests for CLI spec builders.

Contract rule:
- CLI translates inputs -> Specs.
- Deterministic normalization lives in Specs.
- Same inputs should produce same normalized Spec.
"""

from __future__ import annotations

import pytest
from pathlib import Path
import tempfile

from bkx.cli.spec_builders import (
    build_backup_spec,
    build_archive_spec,
    build_transfer_spec,
    build_restore_spec,
)


class TestBuildBackupSpec:
    """Tests for build_backup_spec."""

    def test_minimal_input(self):
        """Test backup spec with minimal input."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            spec = build_backup_spec(sources=[source])

            assert len(spec.sources) == 1
            assert spec.verify is True
            assert spec.compression.algorithm == "zstd"

    def test_with_destination(self):
        """Test backup spec with destination."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            dest = Path(tmpdir) / "backup"
            spec = build_backup_spec(sources=[source], destination=dest)

            assert spec.destination == dest
            assert spec.archive_name is not None

    def test_with_excludes(self):
        """Test backup spec with exclude patterns."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            spec = build_backup_spec(
                sources=[source],
                excludes=["*.pyc", "__pycache__"],
            )

            assert "*.pyc" in spec.excludes
            assert "__pycache__" in spec.excludes

    def test_with_compression(self):
        """Test backup spec with custom compression."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            spec = build_backup_spec(
                sources=[source],
                algorithm="gzip",
                level=6,
            )

            assert spec.compression.algorithm == "gzip"
            assert spec.compression.level == 6

    def test_dry_run_flag(self):
        """Test backup spec with dry run."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            spec = build_backup_spec(sources=[source], dry_run=True)

            assert spec.dry_run is True

    def test_verify_flag_false(self):
        """Test backup spec with verify disabled."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            spec = build_backup_spec(sources=[source], verify=False)

            assert spec.verify is False

    def test_deterministic_output(self):
        """Test that same inputs produce same spec."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            spec1 = build_backup_spec(sources=[source])
            spec2 = build_backup_spec(sources=[source])

            assert spec1 == spec2


class TestBuildArchiveSpec:
    """Tests for build_archive_spec."""

    def test_required_output(self):
        """Test archive spec requires output path."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            output = Path(tmpdir) / "archive.tar"
            spec = build_archive_spec(sources=[source], output=output)

            assert spec.output_path == output
            assert spec.verify is True

    def test_with_excludes(self):
        """Test archive spec with excludes."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            output = Path(tmpdir) / "archive.tar"
            spec = build_archive_spec(
                sources=[source],
                output=output,
                excludes=["*.log"],
            )

            assert "*.log" in spec.excludes

    def test_compression_settings(self):
        """Test archive spec compression settings."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            output = Path(tmpdir) / "archive.tar.zst"
            spec = build_archive_spec(
                sources=[source],
                output=output,
                algorithm="zstd",
                level=5,
            )

            assert spec.compression.algorithm == "zstd"
            assert spec.compression.level == 5

    def test_dry_run(self):
        """Test archive spec dry run."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            output = Path(tmpdir) / "archive.tar"
            spec = build_archive_spec(
                sources=[source],
                output=output,
                dry_run=True,
            )

            assert spec.dry_run is True


class TestBuildTransferSpec:
    """Tests for build_transfer_spec."""

    def test_push_direction(self):
        """Test transfer spec for push."""
        source = Path("/source/file.txt")
        dest = Path("/dest/file.txt")
        spec = build_transfer_spec(
            source=source,
            destination=dest,
            direction="push",
        )

        assert spec.direction == "push"
        assert spec.sources == (source,)
        assert spec.destination == dest

    def test_pull_direction(self):
        """Test transfer spec for pull."""
        source = Path("/remote/file.txt")
        dest = Path("/local/file.txt")
        spec = build_transfer_spec(
            source=source,
            destination=dest,
            direction="pull",
        )

        assert spec.direction == "pull"
        assert spec.sources == (source,)
        assert spec.destination == dest

    def test_verify_flag(self):
        """Test transfer spec verify flag."""
        source = Path("/source")
        dest = Path("/dest")
        spec = build_transfer_spec(
            source=source,
            destination=dest,
            verify=True,
        )

        assert spec.verify is True

    def test_checksum_option(self):
        """Test transfer spec with checksum."""
        source = Path("/source")
        dest = Path("/dest")
        spec = build_transfer_spec(
            source=source,
            destination=dest,
            checksum="sha256:abc123",
        )

        assert spec.checksum == "sha256:abc123"

    def test_overwrite_flag(self):
        """Test transfer spec overwrite flag."""
        source = Path("/source")
        dest = Path("/dest")
        spec = build_transfer_spec(
            source=source,
            destination=dest,
            overwrite=True,
        )

        assert spec.overwrite is True


class TestBuildRestoreSpec:
    """Tests for build_restore_spec."""

    def test_required_archive(self):
        """Test restore spec requires archive path."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")  # Create the archive file
            spec = build_restore_spec(archive=archive)

            assert spec.archive == archive

    def test_with_destination(self):
        """Test restore spec with destination."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")  # Create the archive file
            dest = Path(tmpdir) / "restore"
            spec = build_restore_spec(archive=archive, destination=dest)

            assert spec.destination == dest

    def test_partial_restore_paths(self):
        """Test restore spec with path filters."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")  # Create the archive file
            spec = build_restore_spec(
                archive=archive,
                paths=["file1.txt", "dir/"],
            )

            assert len(spec.paths) == 2
            assert "file1.txt" in spec.paths

    def test_overwrite_flag(self):
        """Test restore spec overwrite flag."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")  # Create the archive file
            spec = build_restore_spec(
                archive=archive,
                overwrite=True,
            )

            assert spec.overwrite is True

    def test_verify_flag(self):
        """Test restore spec verify flag."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")  # Create the archive file
            spec = build_restore_spec(
                archive=archive,
                verify=True,
            )

            assert spec.verify is True

    def test_dry_run(self):
        """Test restore spec dry run."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")  # Create the archive file
            spec = build_restore_spec(
                archive=archive,
                dry_run=True,
            )

            assert spec.dry_run is True
