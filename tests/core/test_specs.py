"""Tests for core/specs/ - Spec normalization."""

from __future__ import annotations

import pytest
from pathlib import Path

from bkx.core.specs.backup import BackupSpec
from bkx.core.specs.archive import ArchiveCreateSpec
from bkx.core.specs.transfer import TransferSpec
from bkx.core.specs.restore import RestoreSpec


class TestBackupSpec:
    """Tests for BackupSpec normalization."""

    def test_default_values(self):
        """Test that defaults are applied correctly."""
        spec = BackupSpec(
            sources=(Path("/test"),),
            destination=Path("/backup"),
        )

        # Verify defaults are set
        assert spec.verify is True
        assert spec.dry_run is False
        assert spec.compression.algorithm == "zstd"
        assert spec.compression.level == 3

    def test_spec_is_hashable(self):
        """Test that specs are hashable for caching."""
        spec1 = BackupSpec(
            sources=(Path("/test1"),),
            destination=Path("/backup"),
        )
        spec2 = BackupSpec(
            sources=(Path("/test1"),),
            destination=Path("/backup"),
        )

        # Should be able to add to set
        spec_set = {spec1, spec2}
        assert len(spec_set) == 1

    def test_spec_with_excludes(self):
        """Test spec with exclude patterns."""
        spec = BackupSpec(
            sources=(Path("/test"),),
            destination=Path("/backup"),
            excludes=("*.pyc", "__pycache__"),
        )

        assert "*.pyc" in spec.excludes
        assert "__pycache__" in spec.excludes


class TestArchiveCreateSpec:
    """Tests for ArchiveCreateSpec normalization."""

    def test_spec_requires_output_path(self):
        """ArchiveCreateSpec should accept optional output_path."""
        spec = ArchiveCreateSpec(
            sources=(Path("/test"),),
        )

        assert spec.output_path is None

    def test_spec_with_compression_profile(self):
        """Test spec with compression profile."""
        spec = ArchiveCreateSpec(
            sources=(Path("/test"),),
            output_path=Path("/test/archive.tar.zst"),
        )

        assert spec.compression.algorithm == "zstd"
        assert spec.compression.level == 3


class TestTransferSpec:
    """Tests for TransferSpec normalization."""

    def test_transfer_spec_push(self):
        """Test transfer spec for push direction."""
        spec = TransferSpec(
            sources=(Path("/test"),),
            destination=Path("/backup"),
            direction="push",
        )

        assert spec.direction == "push"
        assert spec.verify is True

    def test_transfer_spec_pull(self):
        """Test transfer spec for pull direction."""
        spec = TransferSpec(
            sources=(Path("/data"),),
            destination=Path("/local"),
            direction="pull",
        )

        assert spec.direction == "pull"


class TestRestoreSpec:
    """Tests for RestoreSpec normalization."""

    def test_restore_spec_full(self):
        """Test full restore spec."""
        spec = RestoreSpec(
            archive=Path("/backup/archive.tar.zst"),
            destination=Path("/restore"),
        )

        assert spec.archive == Path("/backup/archive.tar.zst")
        assert spec.destination == Path("/restore")
        assert spec.verify is True

    def test_restore_spec_partial(self):
        """Test partial restore with path filter."""
        spec = RestoreSpec(
            archive=Path("/backup/archive.tar.zst"),
            paths=("file1.txt", "dir/"),
        )

        assert len(spec.paths) == 2
        assert "file1.txt" in spec.paths
