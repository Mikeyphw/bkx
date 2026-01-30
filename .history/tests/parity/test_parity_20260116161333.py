"""Parity tests - verify same inputs produce same outcomes across interfaces.

Contract rule:
- Identical input → identical Spec.
- Identical Spec → identical service outcome.
- Same semantic intent across interfaces → same Spec.
"""

from __future__ import annotations

import pytest
from pathlib import Path
import tempfile

from typer.testing import CliRunner

from bkx.cli.app import app
from bkx.cli.spec_builders import (
    build_backup_spec,
    build_archive_spec,
    build_transfer_spec,
    build_restore_spec,
)
from bkx.core.specs.backup import BackupSpec
from bkx.core.specs.archive import ArchiveCreateSpec
from bkx.core.specs.transfer import TransferSpec
from bkx.core.specs.restore import RestoreSpec


runner = CliRunner()


class TestCLIToSpecParity:
    """Tests for CLI arguments → Spec parity."""

    def test_backup_cli_args_map_to_spec(self):
        """Verify CLI backup arguments produce correct Spec."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            dest = Path(tmpdir) / "backup"

            spec = build_backup_spec(
                sources=[source],
                destination=dest,
                archive_name="test",
                excludes=["*.pyc"],
                dry_run=False,
                verify=True,
            )

            # Verify spec structure
            assert len(spec.sources) == 1
            assert spec.sources[0] == source
            assert spec.destination == dest
            assert spec.archive_name == "test"
            assert "*.pyc" in spec.excludes
            assert spec.verify is True

    def test_archive_cli_args_map_to_spec(self):
        """Verify CLI archive arguments produce correct Spec."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            output = Path(tmpdir) / "archive.tar"

            spec = build_archive_spec(
                sources=[source],
                output=output,
                excludes=["*.log"],
                dry_run=False,
                verify=True,
            )

            assert len(spec.sources) == 1
            assert spec.output_path == output
            assert "*.log" in spec.excludes
            assert spec.verify is True

    def test_transfer_cli_args_map_to_spec_push(self):
        """Verify CLI transfer push arguments produce correct Spec."""
        source = Path("/source")
        dest = Path("/dest")

        spec = build_transfer_spec(
            source=source,
            destination=dest,
            direction="push",
            verify=True,
        )

        assert spec.direction == "push"
        assert spec.sources == (source,)
        assert spec.destination == dest

    def test_transfer_cli_args_map_to_spec_pull(self):
        """Verify CLI transfer pull arguments produce correct Spec."""
        source = Path("/remote/file")
        dest = Path("/local/file")

        spec = build_transfer_spec(
            source=source,
            destination=dest,
            direction="pull",
            verify=False,
        )

        assert spec.direction == "pull"
        assert spec.sources == (source,)
        assert spec.destination == dest

    def test_restore_cli_args_map_to_spec(self):
        """Verify CLI restore arguments produce correct Spec."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")  # Create the archive file
            dest = Path(tmpdir) / "restore"

            spec = build_restore_spec(
                archive=archive,
                destination=dest,
                paths=["file1.txt"],
                overwrite=True,
            )

            assert spec.archive == archive
            assert spec.destination == dest
            assert "file1.txt" in spec.paths
            assert spec.overwrite is True


class TestSpecDeterminism:
    """Tests for Spec normalization determinism."""

    def test_backup_spec_normalization_is_deterministic(self):
        """Test that backup spec normalization produces consistent results."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)

            # Normalize multiple times
            spec1 = BackupSpec(sources=(source,)).normalize()
            spec2 = BackupSpec(sources=(source,)).normalize()
            spec3 = BackupSpec(sources=(source,)).normalize()

            # All should be equal
            assert spec1 == spec2 == spec3

    def test_archive_spec_normalization_is_deterministic(self):
        """Test that archive spec normalization produces consistent results."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            output = Path(tmpdir) / "archive.tar"

            spec1 = ArchiveCreateSpec(sources=(source,), output_path=output).normalize()
            spec2 = ArchiveCreateSpec(sources=(source,), output_path=output).normalize()

            assert spec1 == spec2

    def test_transfer_spec_normalization_is_deterministic(self):
        """Test that transfer spec normalization produces consistent results."""
        source = Path("/source")
        dest = Path("/dest")

        spec1 = TransferSpec(
            sources=(source,),
            destination=dest,
            direction="push",
        ).normalize()
        spec2 = TransferSpec(
            sources=(source,),
            destination=dest,
            direction="push",
        ).normalize()

        assert spec1 == spec2


class TestCLIHelpParity:
    """Tests for CLI help output consistency."""

    def test_backup_help_contains_commands(self):
        """Verify backup help shows expected commands."""
        result = runner.invoke(app, ["backup", "--help"])
        assert result.exit_code == 0
        assert "create" in result.output
        assert "plan" in result.output

    def test_archive_help_contains_commands(self):
        """Verify archive help shows expected commands."""
        result = runner.invoke(app, ["archive", "--help"])
        assert result.exit_code == 0
        assert "create" in result.output
        assert "list" in result.output
        assert "info" in result.output

    def test_transfer_help_contains_commands(self):
        """Verify transfer help shows expected commands."""
        result = runner.invoke(app, ["transfer", "--help"])
        assert result.exit_code == 0
        assert "push" in result.output
        assert "pull" in result.output

    def test_restore_help_contains_commands(self):
        """Verify restore help shows expected commands."""
        result = runner.invoke(app, ["restore", "--help"])
        assert result.exit_code == 0
        assert "extract" in result.output

    def test_tools_help_contains_commands(self):
        """Verify tools help shows expected commands."""
        result = runner.invoke(app, ["tools", "--help"])
        assert result.exit_code == 0
        assert "detect" in result.output
        assert "explain" in result.output


class TestDryRunParity:
    """Tests for dry-run mode consistency across commands."""

    def test_backup_plan_is_dry_run(self):
        """Verify backup plan command produces dry-run spec."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            dest = Path(tmpdir) / "backup"

            spec = build_backup_spec(
                sources=[source],
                destination=dest,
                dry_run=True,
            )

            assert spec.dry_run is True

    def test_archive_dry_run_spec(self):
        """Verify archive create with dry-run produces dry-run spec."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            output = Path(tmpdir) / "archive.tar"

            spec = build_archive_spec(
                sources=[source],
                output=output,
                dry_run=True,
            )

            assert spec.dry_run is True

    def test_transfer_dry_run_spec(self):
        """Verify transfer with dry-run produces dry-run spec."""
        source = Path("/source")
        dest = Path("/dest")

        spec = build_transfer_spec(
            source=source,
            destination=dest,
            dry_run=True,
        )

        assert spec.dry_run is True

    def test_restore_dry_run_spec(self):
        """Verify restore with dry-run produces dry-run spec."""
        archive = Path("/backup/archive.tar")

        spec = build_restore_spec(
            archive=archive,
            dry_run=True,
        )

        assert spec.dry_run is True

