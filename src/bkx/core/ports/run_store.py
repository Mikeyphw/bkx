"""RunStorePort - interface for persisting run metadata.

Contract rule:
- Ports are pure interfaces with fallback implementations.
- Core uses ports for IO, never writes files directly.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Protocol


@dataclass(frozen=True)
class RunMetadata:
    """Metadata about a single run."""

    run_id: str
    started_at: datetime
    completed_at: datetime | None
    operation: str  # "backup", "archive", "transfer", "restore"
    status: str  # "started", "success", "failed"
    source_paths: tuple[str, ...]
    destination_path: str | None
    archive_path: str | None
    files_count: int | None
    bytes_processed: int | None
    error_message: str | None
    spec_snapshot: dict | None  # Serialized spec for reproducibility


class RunStorePort(Protocol):
    """Protocol for persisting run metadata.

    Implement this to store run history for `run list` and `run show`.
    Fallback implementations should work with simple file storage.
    """

    def record_start(
        self,
        run_id: str,
        operation: str,
        source_paths: tuple[str, ...],
        destination_path: str | None,
        archive_path: str | None,
        spec_snapshot: dict | None = None,
    ) -> RunMetadata:
        """Record the start of a run.

        Args:
            run_id: Unique identifier for this run.
            operation: Type of operation (backup, archive, transfer, restore).
            source_paths: Source paths involved.
            destination_path: Destination path if applicable.
            archive_path: Archive path if applicable.
            spec_snapshot: Serialized spec for reproducibility.

        Returns:
            RunMetadata with recorded start time.
        """
        ...

    def record_completion(
        self,
        run_id: str,
        status: str,
        files_count: int | None = None,
        bytes_processed: int | None = None,
        error_message: str | None = None,
    ) -> RunMetadata:
        """Record the completion of a run.

        Args:
            run_id: Unique identifier for this run.
            status: Final status (success, failed).
            files_count: Number of files processed.
            bytes_processed: Number of bytes processed.
            error_message: Error message if failed.

        Returns:
            RunMetadata with completed time.
        """
        ...

    def list_runs(
        self,
        limit: int = 20,
        operation: str | None = None,
    ) -> tuple[RunMetadata, ...]:
        """List recent runs.

        Args:
            limit: Maximum number of runs to return.
            operation: Optional filter by operation type.

        Returns:
            Tuple of RunMetadata, most recent first.
        """
        ...

    def get_run(self, run_id: str) -> RunMetadata | None:
        """Get a specific run by ID.

        Args:
            run_id: Unique identifier for the run.

        Returns:
            RunMetadata if found, None otherwise.
        """
        ...

    def clear_all(self) -> int:
        """Clear all run history.

        Returns:
            Number of runs that were cleared.
        """
        ...

