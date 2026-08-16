"""Tests for ExecutionPlan and plan generation.

Contract rule:
- Plan shows what would happen without executing.
- Used for --plan / --dry-run output.
"""

from __future__ import annotations

import tempfile
from pathlib import Path

from bkx.core.results.execution_plan import (
    ExecutionPlan,
    ExecutionStep,
    generate_archive_plan,
    generate_backup_plan,
    generate_restore_plan,
    generate_transfer_plan,
)


class TestExecutionPlan:
    """Tests for ExecutionPlan dataclass."""

    def test_empty_plan(self):
        """Test empty plan has correct properties."""
        plan = ExecutionPlan(
            command="backup create",
            steps=(),
        )

        assert plan.is_empty is True
        assert plan.step_count == 0
        assert plan.total_files == 0
        assert plan.total_bytes == 0

    def test_plan_with_steps(self):
        """Test plan with steps has correct properties."""
        steps = (
            ExecutionStep(
                step_type="archive",
                description="Create archive",
                source=Path("/source"),
                destination=Path("/dest/archive.tar"),
                files_affected=10,
                bytes_affected=1024,
            ),
        )

        plan = ExecutionPlan(
            command="archive create",
            steps=steps,
            total_files=10,
            total_bytes=1024,
        )

        assert plan.is_empty is False
        assert plan.step_count == 1
        assert plan.total_files == 10
        assert plan.total_bytes == 1024


class TestGenerateBackupPlan:
    """Tests for backup plan generation."""

    def test_backup_plan_with_sources(self):
        """Test backup plan generation with source files."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir) / "source"
            source.mkdir()
            (source / "file1.txt").write_text("content1")
            (source / "file2.txt").write_text("content2")

            plan = generate_backup_plan(
                command="backup create",
                sources=(source,),
                destination=Path(tmpdir),
                archive_name="test-backup",
                verify=True,
            )

            assert plan.command == "backup create"
            assert plan.is_empty is False
            assert plan.step_count == 3  # archive, transfer, verify

            # Check step types
            step_types = [s.step_type for s in plan.steps]
            assert "archive" in step_types
            assert "transfer" in step_types
            assert "verify" in step_types

    def test_backup_plan_no_verify(self):
        """Test backup plan without verification."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir) / "source"
            source.mkdir()
            (source / "file.txt").write_text("content")

            plan = generate_backup_plan(
                command="backup create",
                sources=(source,),
                destination=Path(tmpdir),
                archive_name="test",
                verify=False,
            )

            assert plan.step_count == 2  # archive, transfer (no verify)

    def test_backup_plan_no_destination(self):
        """Test backup plan with no destination (local only)."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir) / "source"
            source.mkdir()
            (source / "file.txt").write_text("content")

            plan = generate_backup_plan(
                command="backup create",
                sources=(source,),
                destination=None,
                archive_name="test",
                verify=False,
            )

            assert plan.step_count == 1  # archive only


class TestGenerateArchivePlan:
    """Tests for archive plan generation."""

    def test_archive_plan(self):
        """Test archive plan generation."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir) / "source"
            source.mkdir()
            (source / "file.txt").write_text("content")

            output = Path(tmpdir) / "archive.tar"

            plan = generate_archive_plan(
                command="archive create",
                sources=(source,),
                output_path=output,
                verify=True,
            )

            assert plan.command == "archive create"
            assert plan.step_count == 2  # archive, verify
            assert plan.total_files >= 1

    def test_archive_plan_no_verify(self):
        """Test archive plan without verification."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir) / "source"
            source.mkdir()
            (source / "file.txt").write_text("content")

            output = Path(tmpdir) / "archive.tar"

            plan = generate_archive_plan(
                command="archive create",
                sources=(source,),
                output_path=output,
                verify=False,
            )

            assert plan.step_count == 1  # archive only


class TestGenerateTransferPlan:
    """Tests for transfer plan generation."""

    def test_transfer_plan_push(self):
        """Test push transfer plan."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir) / "source.txt"
            source.write_text("content")
            dest = Path(tmpdir) / "dest.txt"

            plan = generate_transfer_plan(
                command="transfer push",
                sources=(source,),
                destination=dest,
                direction="push",
                verify=True,
            )

            assert plan.command == "transfer push"
            assert plan.step_count == 2  # transfer, verify
            assert plan.total_files == 1

    def test_transfer_plan_pull(self):
        """Test pull transfer plan."""
        with tempfile.TemporaryDirectory() as tmpdir:
            source = Path(tmpdir) / "source.txt"
            source.write_text("content")
            dest = Path(tmpdir) / "dest.txt"

            plan = generate_transfer_plan(
                command="transfer pull",
                sources=(source,),
                destination=dest,
                direction="pull",
                verify=False,
            )

            assert plan.command == "transfer pull"
            assert plan.step_count == 1  # transfer only
            assert plan.total_files == 1


class TestGenerateRestorePlan:
    """Tests for restore plan generation."""

    def test_restore_plan_full(self):
        """Test full restore plan."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")

            plan = generate_restore_plan(
                command="restore extract",
                archive=archive,
                destination=Path(tmpdir) / "restored",
                paths=(),
            )

            assert plan.command == "restore extract"
            assert plan.step_count == 2  # verify + restore

    def test_restore_plan_partial(self):
        """Test partial restore plan."""
        with tempfile.TemporaryDirectory() as tmpdir:
            archive = Path(tmpdir) / "archive.tar"
            archive.write_text("")

            plan = generate_restore_plan(
                command="restore pick",
                archive=archive,
                destination=Path(tmpdir) / "restored",
                paths=("file1.txt", "dir/file2.txt"),
            )

            assert plan.command == "restore pick"
            assert plan.step_count == 2
            assert plan.steps[-1].files_affected == 2


class TestHumanReadableOutput:
    """Tests for human-readable property methods."""

    def test_human_bytes_zero(self):
        """Test human bytes for zero."""
        plan = ExecutionPlan(command="test", total_bytes=0)
        assert plan.human_bytes_estimate == "0 B"

    def test_human_bytes_various(self):
        """Test human bytes for various sizes."""
        # 500 bytes
        plan = ExecutionPlan(command="test", total_bytes=500)
        assert plan.human_bytes_estimate == "500.00B"

        # 1 KB
        plan = ExecutionPlan(command="test", total_bytes=1024)
        assert plan.human_bytes_estimate == "1.00KB"

        # 1 MB
        plan = ExecutionPlan(command="test", total_bytes=1024 * 1024)
        assert plan.human_bytes_estimate == "1.00MB"
