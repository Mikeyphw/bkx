"""BackupSpec and canonical normalization."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from bkx.core.errors import ValidationError

from .common import (
    canonical_local_path,
    deterministic_name,
    normalize_archive_name,
    normalize_source_paths,
    reject_output_inside_sources,
    validate_pattern_sets,
    validate_split_size,
)

_COMPRESSION_LEVELS: dict[str, tuple[int, int]] = {
    "zstd": (1, 22),
    "gzip": (1, 9),
    "lz4": (0, 16),
    "xz": (0, 9),
    "bzip2": (1, 9),
    "none": (0, 22),
}


@dataclass(frozen=True)
class CompressionProfile:
    """Validated compression intent."""

    algorithm: str
    level: int = 3

    def __post_init__(self) -> None:
        if not isinstance(self.algorithm, str):
            raise ValidationError(msg="Compression algorithm must be a string")
        algorithm = self.algorithm.strip().lower()
        if algorithm not in _COMPRESSION_LEVELS:
            raise ValidationError(msg="Unsupported compression algorithm", details={"algorithm": self.algorithm, "valid": sorted(_COMPRESSION_LEVELS)})
        if isinstance(self.level, bool) or not isinstance(self.level, int):
            raise ValidationError(msg="Compression level must be an integer", details={"level": self.level})
        minimum, maximum = _COMPRESSION_LEVELS[algorithm]
        if not minimum <= self.level <= maximum:
            raise ValidationError(
                msg=f"Compression level for {algorithm} must be between {minimum} and {maximum}",
                details={"algorithm": algorithm, "level": self.level, "minimum": minimum, "maximum": maximum},
            )
        object.__setattr__(self, "algorithm", algorithm)


DEFAULT_COMPRESSION = CompressionProfile(algorithm="zstd", level=3)


@dataclass(frozen=True, kw_only=True)
class BackupSpec:
    """Normalized specification for a backup operation."""

    sources: tuple[Path, ...]
    destination: Path | None = None
    archive_name: str | None = None
    excludes: tuple[str, ...] = field(default_factory=tuple)
    includes: tuple[str, ...] = field(default_factory=tuple)
    split_size: int | None = None
    compression: CompressionProfile = DEFAULT_COMPRESSION
    verify: bool = True
    dry_run: bool = False
    skip_symlinks: bool = False
    _resolved_destination: Path | None = field(default=None, compare=False, repr=False)
    _archive_path: Path | None = field(default=None, compare=False, repr=False)

    def __post_init__(self) -> None:
        if not isinstance(self.sources, tuple) or not self.sources:
            raise ValidationError(msg="At least one source path is required")
        if any(not isinstance(src, Path) for src in self.sources):
            raise ValidationError(msg="Backup sources must be Path objects")
        if self.destination is not None and not isinstance(self.destination, Path):
            raise ValidationError(msg="Backup destination must be a Path")
        if self.archive_name is not None:
            object.__setattr__(self, "archive_name", normalize_archive_name(self.archive_name))
        includes, excludes = validate_pattern_sets(self.includes, self.excludes)
        object.__setattr__(self, "includes", includes)
        object.__setattr__(self, "excludes", excludes)
        object.__setattr__(self, "split_size", validate_split_size(self.split_size))
        if not isinstance(self.compression, CompressionProfile):
            raise ValidationError(msg="compression must be a CompressionProfile")
        if not isinstance(self.verify, bool) or not isinstance(self.dry_run, bool) or not isinstance(self.skip_symlinks, bool):
            raise ValidationError(msg="verify, dry_run, and skip_symlinks must be booleans")

    def normalize(self, *, default_destination: Path | None = None, auto_name: bool = True) -> BackupSpec:
        sources = normalize_source_paths(self.sources)
        destination_raw = self.destination if self.destination is not None else default_destination
        destination = canonical_local_path(destination_raw) if destination_raw is not None else None
        archive_name = self.archive_name
        if auto_name and archive_name is None:
            archive_name = deterministic_name(sources[0].name or "backup", *(str(src) for src in sources))
        archive_path = destination / f"{archive_name}.bakery" if destination is not None and archive_name is not None else None
        if archive_path is not None:
            reject_output_inside_sources(archive_path, sources)
        return BackupSpec(
            sources=sources,
            destination=destination,
            archive_name=archive_name,
            excludes=self.excludes,
            includes=self.includes,
            split_size=self.split_size,
            compression=self.compression,
            verify=self.verify,
            dry_run=self.dry_run,
            skip_symlinks=self.skip_symlinks,
            _resolved_destination=destination,
            _archive_path=archive_path,
        )

    @property
    def effective_destination(self) -> Path | None:
        return self._resolved_destination if self._resolved_destination is not None else self.destination

    @property
    def archive_path(self) -> Path | None:
        return self._archive_path
