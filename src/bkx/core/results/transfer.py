"""TransferResult - structured result for transfer operations.

Returns structured data, never strings.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import NamedTuple

from .backup import Timing


@dataclass(frozen=True, kw_only=True)
class TransferResult:
    """Structured result from a transfer operation.

    Contains all metrics and artifacts produced by the operation.
    """

    # Overall status
    success: bool
    dry_run: bool = False
    direction: str  # "push" or "pull"

    # Timing
    timing: Timing

    # Metrics
    files_transferred: int = 0
    bytes_transferred: int = 0

    # Paths (optional - set if available)
    source: Path | None = None
    destination: Path | None = None

    # Verification
    verification_passed: bool | None = None
    checksum_verified: bool | None = None

    # Warnings
    warnings: tuple[str, ...] = field(default_factory=tuple)

    # Errors (partial failures)
    errors: tuple[str, ...] = field(default_factory=tuple)

    @property
    def duration_ms(self) -> int:
        """Get total duration in milliseconds."""
        return self.timing.duration_ms

    @property
    def human_bytes_transferred(self) -> str:
        """Get human-readable byte count."""
        return _human_bytes(self.bytes_transferred)

    @property
    def is_push(self) -> bool:
        """Check if this was a push operation."""
        return self.direction == "push"

    @property
    def is_pull(self) -> bool:
        """Check if this was a pull operation."""
        return self.direction == "pull"


@dataclass(frozen=True, kw_only=True)
class TransferProgress:
    """Progress update during transfer."""

    current_file: Path | None = None
    files_transferred: int = 0
    bytes_transferred: int = 0
    total_files: int | None = None
    total_bytes: int | None = None

    @property
    def percent_complete(self) -> float | None:
        """Calculate percentage complete."""
        if self.total_bytes and self.total_bytes > 0:
            return (self.bytes_transferred / self.total_bytes) * 100
        return None


def _human_bytes(n: int) -> str:
    """Format bytes as human-readable string."""
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024:
            return f"{n:.2f}{unit}"
        n /= 1024
    return f"{n:.2f}PB"
