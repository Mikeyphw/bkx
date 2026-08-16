"""Truthful immutable transfer Result contracts."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from .backup import Timing
from .common import require_nonnegative

TransferLocation = Path | str


@dataclass(frozen=True, kw_only=True)
class TransferResult:
    success: bool
    direction: str
    timing: Timing = field(compare=False)
    dry_run: bool = False
    files_transferred: int = 0
    bytes_transferred: int = 0
    source: TransferLocation | None = None
    destination: TransferLocation | None = None
    verification_passed: bool | None = None
    checksum_verified: bool | None = None
    warnings: tuple[str, ...] = field(default_factory=tuple)
    errors: tuple[str, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        if self.direction not in {"push", "pull", "sync", "none"}:
            raise ValueError(f"invalid transfer result direction: {self.direction}")
        require_nonnegative(files_transferred=self.files_transferred, bytes_transferred=self.bytes_transferred)
        object.__setattr__(self, "warnings", tuple(self.warnings))
        object.__setattr__(self, "errors", tuple(self.errors))
        if self.success and self.errors:
            raise ValueError("successful TransferResult cannot contain errors")
        if not self.success and (self.verification_passed is True or self.checksum_verified is True):
            raise ValueError("failed transfer cannot claim successful verification")

    @property
    def duration_ms(self) -> int: return self.timing.duration_ms
    @property
    def human_bytes_transferred(self) -> str: return _human_bytes(self.bytes_transferred)
    @property
    def is_push(self) -> bool: return self.direction == "push"
    @property
    def is_pull(self) -> bool: return self.direction == "pull"
    @property
    def files_count(self) -> int: return self.files_transferred
    @property
    def bytes_total(self) -> int: return self.bytes_transferred


@dataclass(frozen=True, kw_only=True)
class TransferProgress:
    current_file: Path | None = None
    files_transferred: int = 0
    bytes_transferred: int = 0
    total_files: int | None = None
    total_bytes: int | None = None

    def __post_init__(self) -> None:
        require_nonnegative(files_transferred=self.files_transferred, bytes_transferred=self.bytes_transferred)
        if self.total_files is not None:
            require_nonnegative(total_files=self.total_files)
        if self.total_bytes is not None:
            require_nonnegative(total_bytes=self.total_bytes)

    @property
    def percent_complete(self) -> float | None:
        if self.total_bytes is not None and self.total_bytes > 0:
            return max(0.0, min(100.0, (self.bytes_transferred / self.total_bytes) * 100.0))
        if self.total_files is not None and self.total_files > 0:
            return max(0.0, min(100.0, (self.files_transferred / self.total_files) * 100.0))
        return None


def _human_bytes(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024: return f"{n:.2f}{unit}"
        n /= 1024
    return f"{n:.2f}PB"
