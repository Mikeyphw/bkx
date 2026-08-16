"""Hashing result types."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from ..ports.hasher import HashAlgorithm, HashResult
from .common import FrozenMapping, frozen_mapping, require_nonnegative


@dataclass(frozen=True)
class HashingResult:
    success: bool
    algorithm: HashAlgorithm
    root_path: Path
    file_count: int
    total_bytes: int
    file_hashes: FrozenMapping
    directory_checksum: str
    timing_ms: int = field(compare=False)
    error: str | None = None
    warnings: tuple[str, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        require_nonnegative(file_count=self.file_count, total_bytes=self.total_bytes, timing_ms=self.timing_ms)
        object.__setattr__(self, "file_hashes", frozen_mapping(dict(self.file_hashes)))
        object.__setattr__(self, "warnings", tuple(self.warnings))
        if self.file_count != len(self.file_hashes):
            raise ValueError("file_count must match file_hashes")
        if self.success and self.error:
            raise ValueError("successful HashingResult cannot contain an error")
