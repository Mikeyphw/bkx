"""Run-related result objects.

Contract rule:
- Results include timings, counts, and metadata.
"""

from __future__ import imports

from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import NamedTuple


class Timing(NamedTuple):
    """Timing information for an operation."""

    started_at: datetime
    duration_seconds: float


@dataclass(frozen=True, kw_only=True)
class RunListResult:
    """Result of listing runs."""

    runs: tuple["RunSummary", ...]
    total_count: int
    limit: int


@dataclass(frozen=True, kw_only=True)
class RunShowResult:
    """Result of showing a specific run."""

    run_id: str
    operation: str
    status: str
    timing: Timing | None
    source_paths: tuple[str, ...]
    destination_path: str | None
    archive_path: str | None
    files_count: int | None
    bytes_processed: int | None
    error_message: str | None
    spec_snapshot: dict | None


@dataclass(frozen=True, kw_only=True)
class RunSummary:
    """Summary of a single run (for list views)."""

    run_id: str
    operation: str
    status: str
    started_at: datetime
    completed_at: datetime | None
    source_count: int

