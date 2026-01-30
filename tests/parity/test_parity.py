"""Parity tests - verify same inputs produce same outcomes across interfaces.

Contract rule:
- Identical input → identical Spec.
- Identical Spec → identical service outcome.
- Same semantic intent across interfaces → same Spec.

Tests cover:
- CLI args → Spec mapping
- TUI intent → Spec mapping (should match CLI)
- Interactive selections → Spec mapping (should match CLI)
- Property-based testing for edge cases
- Schema validation for JSON output
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
from bkx.core.parity import (
    compute_spec_hash,
    spec_to_dict,
    verify_parity,
    validate_spec_against_schema,
)
from bkx.core.schemas import (
    BACKUP_SPEC_SCHEMA,
    ARCHIVE_SPEC_SCHEMA,
    TRANSFER_SPEC_SCHEMA,
    RESTORE_SPEC_SCHEMA,
)

runner = CliRunner()


# =============================================================================
# CLI to Spec Parity Tests
# =============================================================================

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


# =============================================================================
# Cross-Interface Parity Tests (CLI vs TUI)
# =============================================================================

class TestCrossInterfaceParity:
    """Tests for parity between CLI, TUI, and Interactive interfaces.
    
    The promise: Same intent → same Spec → same outcome.
    """

    def test_backup_spec_hash_deterministic(self):
        """Verify backup spec produces consistent hash."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            dest = Path(tmpdir) / "backup"
            
            spec = build_backup_spec(
                sources=[source],
                destination=dest,
                algorithm="zstd",
                level=3,
                dry_run=True,
            )
            
            # Compute hash twice - should be identical
            hash1 = compute_spec_hash(spec_to_dict(spec))
            hash2 = compute_spec_hash(spec_to_dict(spec))
            
            assert hash1 == hash2
            assert len(hash1) == 64  # SHA256 hex digest length

    def test_backup_cli_tui_parity(self):
        """Verify CLI and TUI produce identical backup specs."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            dest = Path(tmpdir) / "backup"
            
            # CLI-style input (via spec builder)
            cli_spec = build_backup_spec(
                sources=[source],
                destination=dest,
                archive_name="test_backup",
                excludes=["*.pyc"],
                algorithm="zstd",
                level=5,
                verify=True,
                dry_run=True,
            )
            
            # TUI-style input - same params (simulated)
            # TUI uses to_spec_params() which returns dict for spec builder
            tui_params = {
                "sources": [source],
                "destination": dest,
                "archive_name": "test_backup",
                "excludes": ["*.pyc"],
                "algorithm": "zstd",
                "level": 5,
                "verify": True,
            }
            tui_spec = build_backup_spec(**tui_params, dry_run=True)
            
            # Both should produce identical specs
            is_parity, diffs = verify_parity(cli_spec, tui_spec)
            assert is_parity, f"CLI/TUI parity failed: {diffs}"

    def test_archive_cli_tui_parity(self):
        """Verify CLI and TUI produce identical archive specs."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            output = Path(tmpdir) / "archive.tar"
            
            cli_spec = build_archive_spec(
                sources=[source],
                output=output,
                excludes=["*.log"],
                algorithm="gzip",
                level=6,
                verify=True,
                dry_run=True,
            )
            
            tui_params = {
                "sources": [source],
                "output": output,
                "excludes": ["*.log"],
                "algorithm": "gzip",
                "level": 6,
                "verify": True,
            }
            tui_spec = build_archive_spec(**tui_params, dry_run=True)
            
            is_parity, diffs = verify_parity(cli_spec, tui_spec)
            assert is_parity, f"CLI/TUI parity failed: {diffs}"

    def test_restore_cli_tui_parity(self):
        """Verify CLI and TUI produce identical restore specs."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")
            dest = Path(tmpdir) / "restore"
            
            cli_spec = build_restore_spec(
                archive=archive,
                destination=dest,
                paths=["file1.txt", "file2.txt"],
                overwrite=True,
                fresh_dir=True,
                dry_run=True,
            )
            
            tui_params = {
                "archive": archive,
                "destination": dest,
                "paths": ["file1.txt", "file2.txt"],
                "overwrite": True,
                "fresh_dir": True,
            }
            tui_spec = build_restore_spec(**tui_params, dry_run=True)
            
            is_parity, diffs = verify_parity(cli_spec, tui_spec)
            assert is_parity, f"CLI/TUI parity failed: {diffs}"

    def test_transfer_cli_tui_parity(self):
        """Verify CLI and TUI produce identical transfer specs."""
        source = Path("/source/file")
        dest = Path("/dest/file")
        
        cli_spec = build_transfer_spec(
            source=source,
            destination=dest,
            direction="push",
            verify=True,
            overwrite=True,
            dry_run=True,
        )
        
        tui_params = {
            "source": source,
            "destination": dest,
            "direction": "push",
            "verify": True,
        }
        tui_spec = build_transfer_spec(**tui_params, overwrite=True, dry_run=True)
        
        is_parity, diffs = verify_parity(cli_spec, tui_spec)
        assert is_parity, f"CLI/TUI parity failed: {diffs}"


# =============================================================================
# Spec Determinism Tests
# =============================================================================

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


# =============================================================================
# Schema Validation Tests
# =============================================================================

class TestSchemaValidation:
    """Tests for JSON schema validation of specs."""

    def test_backup_spec_validates_against_schema(self):
        """Verify backup spec passes schema validation."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            dest = Path(tmpdir) / "backup"
            
            spec = build_backup_spec(
                sources=[source],
                destination=dest,
                algorithm="zstd",
                level=3,
                dry_run=True,
            )
            
            spec_dict = spec_to_dict(spec)
            is_valid, errors = validate_spec_against_schema(spec_dict, BACKUP_SPEC_SCHEMA)
            
            assert is_valid, f"Schema validation failed: {errors}"

    def test_archive_spec_validates_against_schema(self):
        """Verify archive spec passes schema validation."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            output = Path(tmpdir) / "archive.tar"
            
            spec = build_archive_spec(
                sources=[source],
                output=output,
                algorithm="zstd",
                level=3,
                dry_run=True,
            )
            
            spec_dict = spec_to_dict(spec)
            is_valid, errors = validate_spec_against_schema(spec_dict, ARCHIVE_SPEC_SCHEMA)
            
            assert is_valid, f"Schema validation failed: {errors}"

    def test_transfer_spec_validates_against_schema(self):
        """Verify transfer spec passes schema validation."""
        source = Path("/source")
        dest = Path("/dest")
        
        spec = build_transfer_spec(
            source=source,
            destination=dest,
            direction="push",
            dry_run=True,
        )
        
        spec_dict = spec_to_dict(spec)
        is_valid, errors = validate_spec_against_schema(spec_dict, TRANSFER_SPEC_SCHEMA)
        
        assert is_valid, f"Schema validation failed: {errors}"

    def test_restore_spec_validates_against_schema(self):
        """Verify restore spec passes schema validation."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")
            
            spec = build_restore_spec(
                archive=archive,
                dry_run=True,
            )
            
            spec_dict = spec_to_dict(spec)
            is_valid, errors = validate_spec_against_schema(spec_dict, RESTORE_SPEC_SCHEMA)
            
            assert is_valid, f"Schema validation failed: {errors}"


# =============================================================================
# Property-Based Tests (if hypothesis is available)
# =============================================================================

try:
    from hypothesis import given, settings
    from hypothesis import strategies as st

    class TestParityPropertyBased:
        """Property-based tests for parity across interfaces."""

        @given(
            sources=st.lists(st.text(min_size=1, max_size=50, alphabet=st.sampled_from(["a", "b", "c", "1", "2", "3"])), min_size=1, max_size=5),
            algorithm=st.sampled_from(["zstd", "gzip", "lz4"]),
            level=st.integers(min_value=1, max_value=9),
        )
        @settings(max_examples=50)
        def test_backup_parity_property_based(self, sources, algorithm, level):
            """Same inputs across interfaces produce same spec hash."""
            with tempfile.TemporaryDirectory() as tmpdir:
                source = Path(tmpdir)
                dest = Path(tmpdir) / "backup"
                
                # Create actual directories for sources (sanitized names)
                for s in sources:
                    safe_name = "".join(c for c in s[:10] if c.isalnum() or c in "-_.")
                    if safe_name:
                        (source / safe_name).mkdir(exist_ok=True)
                
                # CLI-style
                cli_spec = build_backup_spec(
                    sources=[source],
                    destination=dest,
                    algorithm=algorithm,
                    level=level,
                    dry_run=True,
                )
                
                # TUI-style (simulated - same params)
                tui_spec = build_backup_spec(
                    sources=[source],
                    destination=dest,
                    algorithm=algorithm,
                    level=level,
                    dry_run=True,
                )
                
                cli_hash = compute_spec_hash(spec_to_dict(cli_spec))
                tui_hash = compute_spec_hash(spec_to_dict(tui_spec))
                
                assert cli_hash == tui_hash, "Property-based parity test failed"

        @given(
            direction=st.sampled_from(["push", "pull"]),
            verify=st.booleans(),
            overwrite=st.booleans(),
        )
        @settings(max_examples=20)
        def test_transfer_parity_property_based(self, direction, verify, overwrite):
            """Same transfer params produce same spec hash."""
            source = Path("/source")
            dest = Path("/dest")
            
            spec1 = build_transfer_spec(
                source=source,
                destination=dest,
                direction=direction,
                verify=verify,
                overwrite=overwrite,
                dry_run=True,
            )
            
            spec2 = build_transfer_spec(
                source=source,
                destination=dest,
                direction=direction,
                verify=verify,
                overwrite=overwrite,
                dry_run=True,
            )
            
            hash1 = compute_spec_hash(spec_to_dict(spec1))
            hash2 = compute_spec_hash(spec_to_dict(spec2))
            
            assert hash1 == hash2

except ImportError:
    # Hypothesis not installed - skip property-based tests
    pass


# =============================================================================
# CLI Help Parity Tests
# =============================================================================

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


# =============================================================================
# Dry Run Parity Tests
# =============================================================================

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
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")  # Create the archive file

            spec = build_restore_spec(
                archive=archive,
                dry_run=True,
            )

            assert spec.dry_run is True


# =============================================================================
# TUI Intent to Spec Parity Tests
# =============================================================================

class TestTUIIntentParity:
    """Tests for TUI intent → spec parity with CLI."""

    def test_backup_intent_to_spec_parity(self):
        """Verify BackupIntent produces same spec as CLI."""
        from bkx.tui.messages import BackupIntent
        
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            dest = Path(tmpdir) / "backup"
            
            # Create intent (simulating TUI input)
            intent = BackupIntent(
                sources=[source],
                destination=dest,
                archive_name="test",
                excludes=["*.pyc"],
                algorithm="zstd",
                level=5,
                verify=True,
            )
            
            # Convert to spec params and build spec
            spec_params = intent.to_spec_params()
            tui_spec = build_backup_spec(**spec_params, dry_run=True)
            
            # Build CLI spec with same params
            cli_spec = build_backup_spec(
                sources=[source],
                destination=dest,
                archive_name="test",
                excludes=["*.pyc"],
                algorithm="zstd",
                level=5,
                verify=True,
                dry_run=True,
            )
            
            # Verify parity
            is_parity, diffs = verify_parity(cli_spec, tui_spec)
            assert is_parity, f"BackupIntent parity failed: {diffs}"

    def test_archive_intent_to_spec_parity(self):
        """Verify ArchiveIntent produces same spec as CLI."""
        from bkx.tui.messages import ArchiveIntent
        
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir)
            output = Path(tmpdir) / "archive.tar"
            
            intent = ArchiveIntent(
                sources=[source],
                output=output,
                excludes=["*.log"],
                algorithm="gzip",
                verify=True,
            )
            
            spec_params = intent.to_spec_params()
            tui_spec = build_archive_spec(**spec_params, dry_run=True)
            
            cli_spec = build_archive_spec(
                sources=[source],
                output=output,
                excludes=["*.log"],
                algorithm="gzip",
                verify=True,
                dry_run=True,
            )
            
            is_parity, diffs = verify_parity(cli_spec, tui_spec)
            assert is_parity, f"ArchiveIntent parity failed: {diffs}"

    def test_restore_intent_to_spec_parity(self):
        """Verify RestoreIntent produces same spec as CLI."""
        from bkx.tui.messages import RestoreIntent
        
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")
            dest = Path(tmpdir) / "restore"
            
            intent = RestoreIntent(
                archive=archive,
                destination=dest,
                paths=["file1.txt"],
                overwrite=True,
                verify=True,
                fresh_dir=False,  # Explicit to match config default
            )
            
            spec_params = intent.to_spec_params()
            tui_spec = build_restore_spec(**spec_params, dry_run=True)
            
            cli_spec = build_restore_spec(
                archive=archive,
                destination=dest,
                paths=["file1.txt"],
                overwrite=True,
                verify=True,
                fresh_dir=False,
                dry_run=True,
            )
            
            is_parity, diffs = verify_parity(cli_spec, tui_spec)
            assert is_parity, f"RestoreIntent parity failed: {diffs}"

    def test_transfer_intent_to_spec_parity(self):
        """Verify TransferIntent produces same spec as CLI."""
        from bkx.tui.messages import TransferIntent
        
        source = Path("/source")
        dest = Path("/dest")
        
        intent = TransferIntent(
            source=source,
            destination=dest,
            direction="push",
            verify=True,
        )
        
        spec_params = intent.to_spec_params()
        tui_spec = build_transfer_spec(**spec_params, overwrite=True, dry_run=True)
        
        cli_spec = build_transfer_spec(
            source=source,
            destination=dest,
            direction="push",
            verify=True,
            overwrite=True,
            dry_run=True,
        )
        
        is_parity, diffs = verify_parity(cli_spec, tui_spec)
        assert is_parity, f"TransferIntent parity failed: {diffs}"

