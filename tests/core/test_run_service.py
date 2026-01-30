"""Tests for RunStore and RunService.

Contract rule:
- Services accept Specs only.
- Services return Results only.
- Services raise typed errors only.
"""

from __future__ import annotations

import pytest
import tempfile
import json
from pathlib import Path
from datetime import datetime, timedelta

from bkx.core.impl.run_store import JsonRunStore, _RunRecord
from bkx.core.ports.run_store import RunMetadata, RunStorePort
from bkx.core.services.run import RunService
from bkx.core.results.run import RunListResult, RunShowResult
from bkx.core.errors import NotFoundError


class MockRunStore(RunStorePort):
    """In-memory mock run store for testing."""

    def __init__(self) -> None:
        self._runs: list[RunMetadata] = []

    def record_start(
        self,
        run_id: str,
        operation: str,
        source_paths: tuple[str, ...],
        destination_path: str | None,
        archive_path: str | None,
        spec_snapshot: dict | None = None,
    ) -> RunMetadata:
        meta = RunMetadata(
            run_id=run_id,
            started_at=datetime.now(),
            completed_at=None,
            operation=operation,
            status="started",
            source_paths=source_paths,
            destination_path=destination_path,
            archive_path=archive_path,
            files_count=None,
            bytes_processed=None,
            error_message=None,
            spec_snapshot=spec_snapshot,
        )
        self._runs.append(meta)
        return meta

    def record_completion(
        self,
        run_id: str,
        status: str,
        files_count: int | None = None,
        bytes_processed: int | None = None,
        error_message: str | None = None,
    ) -> RunMetadata:
        for i, run in enumerate(self._runs):
            if run.run_id == run_id:
                meta = RunMetadata(
                    run_id=run.run_id,
                    started_at=run.started_at,
                    completed_at=datetime.now(),
                    operation=run.operation,
                    status=status,
                    source_paths=run.source_paths,
                    destination_path=run.destination_path,
                    archive_path=run.archive_path,
                    files_count=files_count,
                    bytes_processed=bytes_processed,
                    error_message=error_message,
                    spec_snapshot=run.spec_snapshot,
                )
                self._runs[i] = meta
                return meta
        # If not found, return a new completed run
        return RunMetadata(
            run_id=run_id,
            started_at=datetime.now(),
            completed_at=datetime.now(),
            operation="unknown",
            status=status,
            source_paths=tuple(),
            destination_path=None,
            archive_path=None,
            files_count=files_count,
            bytes_processed=bytes_processed,
            error_message=error_message,
            spec_snapshot=None,
        )

    def list_runs(
        self,
        limit: int = 20,
        operation: str | None = None,
    ) -> tuple[RunMetadata, ...]:
        runs = self._runs
        if operation:
            runs = [r for r in runs if r.operation == operation]
        runs.sort(key=lambda r: r.started_at, reverse=True)
        return tuple(runs[:limit])

    def get_run(self, run_id: str) -> RunMetadata | None:
        for run in self._runs:
            if run.run_id == run_id:
                return run
        return None

    def clear_all(self) -> int:
        count = len(self._runs)
        self._runs = []
        return count


class TestJsonRunStore:
    """Tests for JsonRunStore implementation."""

    def test_record_start(self):
        """Test recording run start."""
        with tempfile.TemporaryDirectory() as tmpdir:
            runs_file = Path(tmpdir) / "runs.jsonl"
            store = JsonRunStore(runs_file=runs_file)

            meta = store.record_start(
                run_id="test-123",
                operation="backup",
                source_paths=("/home/user/docs",),
                destination_path="/backup",
                archive_path="/backup/test-123.tar.zst",
                spec_snapshot={"sources": ["/home/user/docs"]},
            )

            assert meta.run_id == "test-123"
            assert meta.operation == "backup"
            assert meta.status == "started"
            assert meta.completed_at is None

    def test_record_completion(self):
        """Test recording run completion."""
        with tempfile.TemporaryDirectory() as tmpdir:
            runs_file = Path(tmpdir) / "runs.jsonl"
            store = JsonRunStore(runs_file=runs_file)

            # Start a run
            store.record_start(
                run_id="test-456",
                operation="archive",
                source_paths=("/home/user/downloads",),
                destination_path=None,
                archive_path=None,
            )

            # Complete it
            meta = store.record_completion(
                run_id="test-456",
                status="success",
                files_count=100,
                bytes_processed=1024000,
            )

            assert meta.run_id == "test-456"
            assert meta.status == "success"
            assert meta.completed_at is not None
            assert meta.files_count == 100
            assert meta.bytes_processed == 1024000

    def test_list_runs(self):
        """Test listing runs."""
        with tempfile.TemporaryDirectory() as tmpdir:
            runs_file = Path(tmpdir) / "runs.jsonl"
            store = JsonRunStore(runs_file=runs_file)

            # Record some runs
            store.record_start("run-1", "backup", ("/src1",), "/dest", None)
            store.record_completion("run-1", "success", files_count=10)

            store.record_start("run-2", "archive", ("/src2",), None, "/archive.zip")
            store.record_completion("run-2", "failed", error_message="Disk full")

            store.record_start("run-3", "backup", ("/src3",), "/dest", None)
            store.record_completion("run-3", "success", files_count=20)

            runs = store.list_runs(limit=10)

            assert len(runs) == 3
            # Should be sorted by start time (newest first)
            assert runs[0].run_id == "run-3"

    def test_list_runs_with_filter(self):
        """Test listing runs with operation filter."""
        with tempfile.TemporaryDirectory() as tmpdir:
            runs_file = Path(tmpdir) / "runs.jsonl"
            store = JsonRunStore(runs_file=runs_file)

            # Record some runs
            store.record_start("run-1", "backup", ("/src1",), "/dest", None)
            store.record_completion("run-1", "success")

            store.record_start("run-2", "archive", ("/src2",), None, "/archive.zip")
            store.record_completion("run-2", "success")

            store.record_start("run-3", "backup", ("/src3",), "/dest", None)
            store.record_completion("run-3", "success")

            backup_runs = store.list_runs(operation="backup")

            assert len(backup_runs) == 2
            for run in backup_runs:
                assert run.operation == "backup"

    def test_get_run(self):
        """Test getting a specific run."""
        with tempfile.TemporaryDirectory() as tmpdir:
            runs_file = Path(tmpdir) / "runs.jsonl"
            store = JsonRunStore(runs_file=runs_file)

            store.record_start("specific-run", "restore", ("/archive",), "/dest", None)
            store.record_completion("specific-run", "success")

            run = store.get_run("specific-run")

            assert run is not None
            assert run.run_id == "specific-run"
            assert run.operation == "restore"

    def test_get_nonexistent_run(self):
        """Test getting a run that doesn't exist."""
        with tempfile.TemporaryDirectory() as tmpdir:
            runs_file = Path(tmpdir) / "runs.jsonl"
            store = JsonRunStore(runs_file=runs_file)

            run = store.get_run("nonexistent")

            assert run is None

    def test_clear_all(self):
        """Test clearing all runs."""
        with tempfile.TemporaryDirectory() as tmpdir:
            runs_file = Path(tmpdir) / "runs.jsonl"
            store = JsonRunStore(runs_file=runs_file)

            store.record_start("run-1", "backup", ("/src",), "/dest", None)
            store.record_completion("run-1", "success")

            store.record_start("run-2", "archive", ("/src",), None, "/archive.zip")
            store.record_completion("run-2", "success")

            count = store.clear_all()

            assert count == 2
            assert len(store.list_runs()) == 0

    def test_persistence(self):
        """Test that runs persist across store instances."""
        with tempfile.TemporaryDirectory() as tmpdir:
            runs_file = Path(tmpdir) / "runs.jsonl"

            # First store: record a run
            store1 = JsonRunStore(runs_file=runs_file)
            store1.record_start("persistent-run", "transfer", ("/src",), "/dest", None)
            store1.record_completion("persistent-run", "success", files_count=50)

            # Second store: read the run
            store2 = JsonRunStore(runs_file=runs_file)
            runs = store2.list_runs()

            assert len(runs) == 1
            assert runs[0].run_id == "persistent-run"
            assert runs[0].files_count == 50


class TestRunService:
    """Tests for RunService."""

    def test_list_runs(self):
        """Test listing runs through service."""
        mock_store = MockRunStore()

        # Record some test data
        mock_store.record_start("run-1", "backup", ("/src",), "/dest", None)
        mock_store.record_completion("run-1", "success", files_count=10)

        mock_store.record_start("run-2", "archive", ("/src",), None, "/archive.zip")
        mock_store.record_completion("run-2", "success", files_count=20)

        service = RunService(run_store=mock_store)
        result = service.list(limit=10)

        assert isinstance(result, RunListResult)
        assert result.total_count == 2
        assert result.limit == 10
        assert len(result.runs) == 2

    def test_list_runs_with_filter(self):
        """Test listing runs with operation filter."""
        mock_store = MockRunStore()

        mock_store.record_start("run-1", "backup", ("/src",), "/dest", None)
        mock_store.record_completion("run-1", "success")

        mock_store.record_start("run-2", "restore", ("/archive",), "/dest", None)
        mock_store.record_completion("run-2", "success")

        service = RunService(run_store=mock_store)
        result = service.list(operation="backup")

        assert len(result.runs) == 1
        assert result.runs[0].operation == "backup"

    def test_show_run(self):
        """Test showing a specific run."""
        mock_store = MockRunStore()

        mock_store.record_start(
            "test-show-run",
            "backup",
            ("/home/user", "/home/user/docs"),
            "/backup",
            "/backup/archive.tar.zst",
            spec_snapshot={"sources": ["/home/user", "/home/user/docs"]},
        )
        mock_store.record_completion(
            "test-show-run",
            "success",
            files_count=150,
            bytes_processed=10240000,
        )

        service = RunService(run_store=mock_store)
        result = service.show("test-show-run")

        assert isinstance(result, RunShowResult)
        assert result.run_id == "test-show-run"
        assert result.operation == "backup"
        assert result.status == "success"
        assert result.files_count == 150
        assert result.bytes_processed == 10240000
        assert len(result.source_paths) == 2
        assert result.destination_path == "/backup"
        assert result.archive_path == "/backup/archive.tar.zst"
        assert result.timing is not None

    def test_show_run_not_found(self):
        """Test showing a nonexistent run raises NotFoundError."""
        mock_store = MockRunStore()
        service = RunService(run_store=mock_store)

        with pytest.raises(NotFoundError):
            service.show("nonexistent-run")

    def test_show_run_timing(self):
        """Test that timing is calculated correctly."""
        mock_store = MockRunStore()

        # Manually add a run with known times
        now = datetime.now()
        meta = RunMetadata(
            run_id="timing-test",
            operation="backup",
            status="success",
            started_at=now - timedelta(seconds=30),
            completed_at=now,
            source_paths=tuple(),
            destination_path=None,
            archive_path=None,
            files_count=100,
            bytes_processed=1000000,
            error_message=None,
            spec_snapshot=None,
        )
        mock_store._runs.append(meta)

        service = RunService(run_store=mock_store)
        result = service.show("timing-test")

        assert result.timing is not None
        assert result.timing.duration_seconds == pytest.approx(30.0, abs=1.0)

    def test_show_run_in_progress(self):
        """Test showing a run that hasn't completed."""
        mock_store = MockRunStore()

        meta = RunMetadata(
            run_id="in-progress",
            operation="backup",
            status="started",
            started_at=datetime.now(),
            completed_at=None,
            source_paths=tuple(),
            destination_path=None,
            archive_path=None,
            files_count=None,
            bytes_processed=None,
            error_message=None,
            spec_snapshot=None,
        )
        mock_store._runs.append(meta)

        service = RunService(run_store=mock_store)
        result = service.show("in-progress")

        assert result.timing is None
        assert result.files_count is None
        assert result.bytes_processed is None

