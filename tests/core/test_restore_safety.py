"""Tests for RestoreSpec safety features - fresh_dir, confirm_overwrite.

Contract rule:
- Services accept Specs only.
- Services return Results only.
- Services raise typed errors only.
"""

from __future__ import annotations

import pytest
from pathlib import Path
import tempfile

from bkx.core.specs.restore import RestoreSpec
from bkx.core.errors import ValidationError, NotFoundError


class TestRestoreSpecFreshDir:
    """Tests for fresh_dir safety feature."""

    def test_fresh_dir_default_creates_timestamped_dir(self):
        """Test that fresh_dir=True creates timestamped directory by default."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "test.archive"
            archive.write_text("test")

            spec = RestoreSpec(archive=archive)
            normalized = spec.normalize()

            # Should create a fresh directory
            assert normalized.effective_destination is not None
            assert normalized.effective_destination.name.startswith("restored_test_")
            assert normalized.fresh_dir is True

    def test_fresh_dir_false_uses_destination(self):
        """Test that fresh_dir=False uses destination as-is."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "test.archive"
            archive.write_text("test")
            dest = Path(tmpdir) / "custom_dest"

            spec = RestoreSpec(archive=archive, destination=dest, fresh_dir=False)
            normalized = spec.normalize()

            assert normalized.effective_destination == dest
            assert normalized.fresh_dir is False

    def test_explicit_destination_overrides_fresh_dir(self):
        """Test that explicit destination takes precedence over fresh_dir."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "test.archive"
            archive.write_text("test")
            dest = Path(tmpdir) / "my_restore"

            spec = RestoreSpec(archive=archive, destination=dest)
            normalized = spec.normalize()

            # With explicit destination, fresh_dir still applies
            # but uses the explicit path
            assert normalized.effective_destination == dest

    def test_fresh_dir_with_default_destination(self):
        """Test fresh_dir with default_destination parameter."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "test.archive"
            archive.write_text("test")
            default_dest = Path(tmpdir) / "default"

            spec = RestoreSpec(archive=archive)
            normalized = spec.normalize(default_destination=default_dest)

            # With default_destination, uses that instead of fresh dir
            assert normalized.effective_destination == default_dest


class TestRestoreSpecConfirmOverwrite:
    """Tests for confirm_overwrite safety feature."""

    def test_overwrite_with_confirm_raises_error(self):
        """Test that overwrite=True with confirm_overwrite=True raises ValidationError."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "test.archive"
            archive.write_text("test")

            spec = RestoreSpec(
                archive=archive,
                overwrite=True,
                confirm_overwrite=True,
            )

            with pytest.raises(ValidationError) as exc_info:
                spec.normalize()

            assert "overwrite" in str(exc_info.value).lower()
            assert "confirm" in str(exc_info.value).lower()

    def test_overwrite_without_confirm_succeeds(self):
        """Test that overwrite=True with confirm_overwrite=False succeeds."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "test.archive"
            archive.write_text("test")

            spec = RestoreSpec(
                archive=archive,
                destination=Path(tmpdir) / "dest",
                overwrite=True,
                confirm_overwrite=False,
            )

            normalized = spec.normalize()

            assert normalized.overwrite is True
            assert normalized.confirm_overwrite is False

    def test_confirm_overwrite_false_allows_overwrite(self):
        """Test that confirm_overwrite=False allows overwrite operations."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "test.archive"
            archive.write_text("test")

            spec = RestoreSpec(
                archive=archive,
                destination=Path(tmpdir) / "dest",
                overwrite=True,
                confirm_overwrite=False,
            )

            normalized = spec.normalize()

            assert normalized.overwrite is True


class TestRestoreSpecRequiresConfirmation:
    """Tests for requires_confirmation property."""

    def test_requires_confirmation_when_dest_exists(self):
        """Test that requires_confirmation is True when destination exists."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "test.archive"
            archive.write_text("test")
            dest = Path(tmpdir) / "existing"
            dest.mkdir()

            spec = RestoreSpec(
                archive=archive,
                destination=dest,
                fresh_dir=False,
            )
            normalized = spec.normalize()

            assert normalized.requires_confirmation is True

    def test_no_confirmation_when_dest_not_exists(self):
        """Test that requires_confirmation is False when destination doesn't exist."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "test.archive"
            archive.write_text("test")

            spec = RestoreSpec(
                archive=archive,
                destination=Path(tmpdir) / "new_dest",
                fresh_dir=False,
            )
            normalized = spec.normalize()

            assert normalized.requires_confirmation is False

    def test_no_confirmation_with_fresh_dir(self):
        """Test that requires_confirmation is False with fresh_dir."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "test.archive"
            archive.write_text("test")

            spec = RestoreSpec(archive=archive)
            normalized = spec.normalize()

            # Fresh dir creates a new timestamped directory
            assert normalized.effective_destination is not None
            # The fresh directory shouldn't exist yet
            assert not normalized.effective_destination.exists()
            assert normalized.requires_confirmation is False


class TestRestoreSpecValidation:
    """Additional validation tests."""

    def test_nonexistent_archive_raises_not_found(self):
        """Test that nonexistent archive raises NotFoundError."""
        spec = RestoreSpec(archive=Path("/nonexistent/test.archive"))

        with pytest.raises(NotFoundError):
            spec.normalize()

    def test_full_restore_property(self):
        """Test is_full_restore property."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "test.archive"
            archive.write_text("test")

            # No paths = full restore
            spec = RestoreSpec(archive=archive)
            assert spec.is_full_restore is True
            assert spec.is_partial_restore is False

    def test_partial_restore_property(self):
        """Test is_partial_restore property."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "test.archive"
            archive.write_text("test")

            # With paths = partial restore
            spec = RestoreSpec(archive=archive, paths=("file1.txt",))
            assert spec.is_partial_restore is True
            assert spec.is_full_restore is False
