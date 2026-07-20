"""BackupResult - structured result for backup operations.

Returns structured data, never strings.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import NamedTuple


class Timing(NamedTuple):
    """Timing information for an operation step."""

    start_ms: int  # Epoch milliseconds
    end_ms: int  # Epoch milliseconds

    @property
    def duration_ms(self) -> int:
        """Calculate duration in milliseconds."""
        return self.end_ms - self.start_ms


@dataclass(frozen=True, kw_only=True)
class BackupResult:
    """Structured result from a backup operation.

    Contains all metrics and artifacts produced by the operation.
    """

    # Overall status
    success: bool
    dry_run: bool

    # Timing
    timing: Timing

    # Metrics
    files_processed: int = 0
    bytes_written: int = 0
    archives_created: int = 0

    # Paths produced
    archive_paths: tuple[Path, ...] = field(default_factory=tuple)

    # Verification
    verification_passed: bool | None = None

    # Warnings (non-fatal issues)
    warnings: tuple[str, ...] = field(default_factory=tuple)

    # Step results (for debugging/audit)
    step_results: dict[str, bool | str] = field(default_factory=dict)

    @property
    def duration_ms(self) -> int:
        """Get total duration in milliseconds."""
        return self.timing.duration_ms

    @property
    def human_bytes_written(self) -> str:
        """Get human-readable byte count."""
        return _human_bytes(self.bytes_written)

    @property
    def archive_path(self) -> Path | None:
        """Get first archive path (for single archive backups)."""
        return self.archive_paths[0] if self.archive_paths else None

    # Aliases for TUI compatibility
    @property
    def files_count(self) -> int:
        """Alias for files_processed for TUI compatibility."""
        return self.files_processed

    @property
    def bytes_total(self) -> int:
        """Alias for bytes_written for TUI compatibility."""
        return self.bytes_written

    @property
    def errors(self) -> tuple[str, ...]:
        """Alias for warnings for TUI compatibility (errors are stored as warnings)."""
        return self.warnings


def _human_bytes(n: float) -> str:
    """Format bytes as human-readable string."""
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024:
            return f"{n:.2f}{unit}"
        n = n / 1024
    return f"{n:.2f}PB"


@dataclass(frozen=True, kw_only=True)
class BackupStepResult:
    """Result for a single backup step."""

    step_name: str
    success: bool
    timing: Timing | None = None
    bytes: int = 0
    files: int = 0
    error: str | None = None
    details: dict[str, str] = field(default_factory=dict)
