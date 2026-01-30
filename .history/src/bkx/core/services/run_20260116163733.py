"""RunService - handles run history operations.

Contract rule:
- Services accept Specs only.
- Services return Results only.
- Services raise typed errors only.
- No rendering, no parsing.
"""

from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING

from ..results.run import (
    RunListResult,
    RunShowResult,
    RunSummary,
    Timing,
)

if TYPE_CHECKING:
    from ..ports.run_store import RunMetadata


class RunService:
    """Handles run history listing and display.

    Operations:
    - list: List recent runs
    - show: Show details of a specific run
    """

    def __init__(
        self,
        *,
        run_store: "RunMetadata",
    ) -> None:
        """Initialize service with required ports.

        Args:
            run_store: Port for run storage operations.
        """
        self._run_store = run_store

    def list(
        self,
        *,
        limit: int = 20,
        operation: str | None = None,
    ) -> RunListResult:
        """List recent runs.

        Args:
            limit: Maximum number of runs to return.
            operation: Optional filter by operation type.

        Returns:
            RunListResult with run summaries.
        """
        runs = self._run_store.list_runs(limit=limit, operation=operation)

        summaries = tuple(
            RunSummary(
                run_id=r.run_id,
                operation=r.operation,
                status=r.status,
                started_at=r.started_at,
                completed_at=r.completed_at,
                source_count=len(r.source_paths),
            )
            for r in runs
        )

        return RunListResult(
            runs=summaries,
            total_count=len(runs),
            limit=limit,
        )

    def show(self, run_id: str) -> RunShowResult:
        """Show details of a specific run.

        Args:
            run_id: Unique identifier for the run.

        Returns:
            RunShowResult with full run details.

        Raises:
            NotFoundError: If run doesn't exist.
        """
        run = self._run_store.get_run(run_id)

        if run is None:
            from ..errors import NotFoundError

            raise NotFoundError(
                msg=f"Run not found: {run_id}",
                details={"run_id": run_id},
            )

        # Calculate timing if completed
        timing = None
        if run.started_at and run.completed_at:
            duration = (run.completed_at - run.started_at).total_seconds()
            timing = Timing(started_at=run.started_at, duration_seconds=duration)

        return RunShowResult(
            run_id=run.run_id,
            operation=run.operation,
            status=run.status,
            timing=timing,
            source_paths=run.source_paths,
            destination_path=run.destination_path,
            archive_path=run.archive_path,
            files_count=run.files_count,
            bytes_processed=run.bytes_processed,
            error_message=run.error_message,
            spec_snapshot=run.spec_snapshot,
        )

