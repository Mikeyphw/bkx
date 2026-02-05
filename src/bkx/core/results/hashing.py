"""Hashing result types."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from ..ports.hasher import HashAlgorithm, HashResult


@dataclass(frozen=True)
class HashingResult:
    """Result of hashing a directory or multiple files."""

    success: bool
    algorithm: HashAlgorithm
    root_path: Path
    file_count: int
    total_bytes: int
    file_hashes: dict[Path, HashResult]
    directory_checksum: str
    timing_ms: int
    error: str | None = None
    warnings: list[str] = field(default_factory=list)
