"""Truthful immutable backup Result contracts."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import NamedTuple

from .common import FrozenMapping, frozen_mapping, require_nonnegative


class Timing(NamedTuple):
    start_ms: int
    end_ms: int

    @property
    def duration_ms(self) -> int:
        return self.end_ms - self.start_ms


@dataclass(frozen=True, kw_only=True)
class BackupResult:
    success: bool
    dry_run: bool
    timing: Timing = field(compare=False)
    files_processed: int = 0
    bytes_written: int = 0
    archives_created: int = 0
    archive_paths: tuple[Path, ...] = field(default_factory=tuple)
    verification_passed: bool | None = None
    warnings: tuple[str, ...] = field(default_factory=tuple)
    errors: tuple[str, ...] = field(default_factory=tuple)
    step_results: FrozenMapping = field(default_factory=FrozenMapping)
    checksums: FrozenMapping = field(default_factory=FrozenMapping)

    def __post_init__(self) -> None:
        require_nonnegative(files_processed=self.files_processed, bytes_written=self.bytes_written, archives_created=self.archives_created)
        object.__setattr__(self, "archive_paths", tuple(self.archive_paths))
        object.__setattr__(self, "warnings", tuple(self.warnings))
        object.__setattr__(self, "errors", tuple(self.errors))
        object.__setattr__(self, "step_results", frozen_mapping(dict(self.step_results)))
        object.__setattr__(self, "checksums", frozen_mapping(dict(self.checksums)))
        if any(not isinstance(value, bool) for value in self.step_results.values()):
            raise ValueError("step_results values must be booleans")
        if any(not isinstance(value, str) for value in self.checksums.values()):
            raise ValueError("checksums values must be strings")
        if self.success and self.errors:
            raise ValueError("successful BackupResult cannot contain errors")
        if self.verification_passed is True and not self.success:
            raise ValueError("failed BackupResult cannot claim successful verification")

    @property
    def duration_ms(self) -> int:
        return self.timing.duration_ms

    @property
    def human_bytes_written(self) -> str:
        return _human_bytes(self.bytes_written)

    @property
    def archive_path(self) -> Path | None:
        return self.archive_paths[0] if self.archive_paths else None

    @property
    def files_count(self) -> int:
        return self.files_processed

    @property
    def bytes_total(self) -> int:
        return self.bytes_written


@dataclass(frozen=True, kw_only=True)
class BackupStepResult:
    step_name: str
    success: bool
    timing: Timing | None = field(default=None, compare=False)
    bytes: int = 0
    files: int = 0
    error: str | None = None
    details: FrozenMapping = field(default_factory=FrozenMapping)

    def __post_init__(self) -> None:
        require_nonnegative(bytes=self.bytes, files=self.files)
        object.__setattr__(self, "details", frozen_mapping(dict(self.details)))
        if self.success and self.error:
            raise ValueError("successful step cannot contain an error")


def _human_bytes(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024:
            return f"{n:.2f}{unit}"
        n /= 1024
    return f"{n:.2f}PB"
