"""Run-related result objects.

Contract rule:
- Results include timings, counts, and metadata.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from typing import NamedTuple

from .common import FrozenMapping, frozen_mapping, require_nonnegative


class Timing(NamedTuple):
    """Timing information for an operation."""

    started_at: datetime
    duration_seconds: float


@dataclass(frozen=True, kw_only=True)
class RunListResult:
    """Result of listing runs."""

    runs: tuple[RunSummary, ...]
    total_count: int
    limit: int

    def __post_init__(self) -> None:
        require_nonnegative(total_count=self.total_count, limit=self.limit)
        object.__setattr__(self, "runs", tuple(self.runs))
        if self.total_count < len(self.runs):
            raise ValueError("total_count cannot be smaller than returned runs")


@dataclass(frozen=True, kw_only=True)
class RunShowResult:
    """Result of showing a specific run."""

    run_id: str
    operation: str
    status: str
    timing: Timing | None = field(compare=False)
    source_paths: tuple[str, ...]
    destination_path: str | None
    archive_path: str | None
    files_count: int | None
    bytes_processed: int | None
    error_message: str | None
    spec_snapshot: FrozenMapping | None

    def __post_init__(self) -> None:
        if self.files_count is not None:
            require_nonnegative(files_count=self.files_count)
        if self.bytes_processed is not None:
            require_nonnegative(bytes_processed=self.bytes_processed)
        object.__setattr__(self, "source_paths", tuple(self.source_paths))
        if self.spec_snapshot is not None:
            object.__setattr__(self, "spec_snapshot", frozen_mapping(dict(self.spec_snapshot)))
        if self.status == "success" and self.error_message:
            raise ValueError("successful run cannot contain an error_message")


@dataclass(frozen=True, kw_only=True)
class RunSummary:
    """Summary of a single run (for list views)."""

    run_id: str
    operation: str
    status: str
    started_at: datetime
    completed_at: datetime | None
    source_count: int

    def __post_init__(self) -> None:
        require_nonnegative(source_count=self.source_count)
