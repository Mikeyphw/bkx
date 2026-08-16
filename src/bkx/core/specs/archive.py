"""Archive Specs and canonical normalization."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Literal

from bkx.core.errors import NotFoundError, ValidationError

from .backup import DEFAULT_COMPRESSION, CompressionProfile
HashAlgorithm = Literal["md5", "sha256", "sha512"]

from .common import (
    canonical_local_path,
    deterministic_name,
    normalize_patterns,
    normalize_source_paths,
    reject_output_inside_sources,
    validate_pattern_sets,
    validate_split_size,
)


@dataclass(frozen=True, kw_only=True)
class ArchiveCreateSpec:
    sources: tuple[Path, ...]
    output_path: Path | None = None
    excludes: tuple[str, ...] = field(default_factory=tuple)
    includes: tuple[str, ...] = field(default_factory=tuple)
    split_size: int | None = None
    compression: CompressionProfile = DEFAULT_COMPRESSION
    verify: bool = True
    dry_run: bool = False
    _resolved_output: Path | None = field(default=None, compare=False, repr=False)

    def __post_init__(self) -> None:
        if not isinstance(self.sources, tuple) or not self.sources or any(not isinstance(src, Path) for src in self.sources):
            raise ValidationError(msg="Archive sources must be a non-empty tuple of Path objects")
        if self.output_path is not None and not isinstance(self.output_path, Path):
            raise ValidationError(msg="Archive output_path must be a Path or None")
        includes, excludes = validate_pattern_sets(self.includes, self.excludes)
        object.__setattr__(self, "includes", includes)
        object.__setattr__(self, "excludes", excludes)
        object.__setattr__(self, "split_size", validate_split_size(self.split_size))
        if not isinstance(self.compression, CompressionProfile):
            raise ValidationError(msg="compression must be a CompressionProfile")
        if not isinstance(self.verify, bool) or not isinstance(self.dry_run, bool):
            raise ValidationError(msg="verify and dry_run must be booleans")

    def normalize(self, *, default_tmp_dir: Path | None = None, auto_name: bool = True) -> ArchiveCreateSpec:
        del default_tmp_dir
        sources = normalize_source_paths(self.sources)
        output_path = canonical_local_path(self.output_path) if self.output_path is not None else None
        if output_path is None and auto_name:
            name = deterministic_name(sources[0].name or "archive", *(str(src) for src in sources))
            output_path = canonical_local_path(Path(f"{name}.archive"))
        if output_path is not None:
            reject_output_inside_sources(output_path, sources)
        return ArchiveCreateSpec(
            sources=sources,
            output_path=output_path,
            excludes=self.excludes,
            includes=self.includes,
            split_size=self.split_size,
            compression=self.compression,
            verify=self.verify,
            dry_run=self.dry_run,
            _resolved_output=output_path,
        )

    @property
    def effective_output_path(self) -> Path | None:
        return self._resolved_output if self._resolved_output is not None else self.output_path


@dataclass(frozen=True, kw_only=True)
class ArchiveListSpec:
    archive_path: Path
    filter_pattern: str | None = None
    page: int = 0
    page_size: int = 100

    def __post_init__(self) -> None:
        if not isinstance(self.archive_path, Path):
            raise ValidationError(msg="archive_path must be a Path")
        if isinstance(self.page, bool) or not isinstance(self.page, int) or self.page < 0:
            raise ValidationError(msg="page must be a non-negative integer", details={"page": self.page})
        if isinstance(self.page_size, bool) or not isinstance(self.page_size, int) or not 1 <= self.page_size <= 1000:
            raise ValidationError(msg="page_size must be between 1 and 1000", details={"page_size": self.page_size})
        if self.filter_pattern is not None:
            patterns = normalize_patterns((self.filter_pattern,), field_name="filter_pattern")
            object.__setattr__(self, "filter_pattern", patterns[0])

    def normalize(self) -> ArchiveListSpec:
        path = canonical_local_path(self.archive_path)
        if not path.exists():
            raise NotFoundError(msg=f"Archive not found: {path}", details={"path": str(path)})
        return ArchiveListSpec(archive_path=path, filter_pattern=self.filter_pattern, page=self.page, page_size=self.page_size)


@dataclass(frozen=True, kw_only=True)
class ArchiveInfoSpec:
    archive_path: Path

    def __post_init__(self) -> None:
        if not isinstance(self.archive_path, Path):
            raise ValidationError(msg="archive_path must be a Path")

    def normalize(self) -> ArchiveInfoSpec:
        path = canonical_local_path(self.archive_path)
        if not path.exists():
            raise NotFoundError(msg=f"Archive not found: {path}", details={"path": str(path)})
        return ArchiveInfoSpec(archive_path=path)


@dataclass(frozen=True, kw_only=True)
class ArchiveVerifySpec:
    archive_path: Path
    hash_algorithms: tuple[HashAlgorithm, ...] = ("sha256",)

    def __post_init__(self) -> None:
        if not isinstance(self.archive_path, Path):
            raise ValidationError(msg="archive_path must be a Path")
        if not isinstance(self.hash_algorithms, tuple) or not self.hash_algorithms:
            raise ValidationError(msg="hash_algorithms must be a non-empty tuple")
        supported = {"md5", "sha256", "sha512"}
        normalized: list[HashAlgorithm] = []
        for algorithm in self.hash_algorithms:
            if algorithm not in supported:
                raise ValidationError(msg="Unsupported archive verification hash algorithm", details={"algorithm": algorithm, "supported": sorted(supported)})
            if algorithm not in normalized:
                normalized.append(algorithm)
        object.__setattr__(self, "hash_algorithms", tuple(normalized))

    def normalize(self) -> ArchiveVerifySpec:
        path = canonical_local_path(self.archive_path)
        if not path.exists():
            raise NotFoundError(msg=f"Archive not found: {path}", details={"path": str(path)})
        return ArchiveVerifySpec(archive_path=path, hash_algorithms=self.hash_algorithms)


@dataclass(frozen=True, kw_only=True)
class ArchiveBrowseSpec:
    archive_path: Path
    query: str | None = None
    file_type: str | None = None
    min_size: int | None = None
    max_size: int | None = None
    limit: int = 50
    offset: int = 0
    sort_by: str = "path"
    sort_order: str = "asc"

    def __post_init__(self) -> None:
        if not isinstance(self.archive_path, Path):
            raise ValidationError(msg="archive_path must be a Path")
        if self.file_type is not None and self.file_type not in {"file", "dir"}:
            raise ValidationError(msg="file_type must be 'file' or 'dir'", details={"file_type": self.file_type})
        for name, value in (("min_size", self.min_size), ("max_size", self.max_size)):
            if value is not None and (isinstance(value, bool) or not isinstance(value, int) or value < 0):
                raise ValidationError(msg=f"{name} must be a non-negative integer", details={name: value})
        if self.min_size is not None and self.max_size is not None and self.min_size > self.max_size:
            raise ValidationError(msg="min_size cannot exceed max_size")
        if isinstance(self.limit, bool) or not isinstance(self.limit, int) or not 1 <= self.limit <= 1000:
            raise ValidationError(msg="limit must be between 1 and 1000", details={"limit": self.limit})
        if isinstance(self.offset, bool) or not isinstance(self.offset, int) or self.offset < 0:
            raise ValidationError(msg="offset must be non-negative", details={"offset": self.offset})
        if self.sort_by not in {"path", "size", "mtime"}:
            raise ValidationError(msg="sort_by must be path, size, or mtime", details={"sort_by": self.sort_by})
        if self.sort_order not in {"asc", "desc"}:
            raise ValidationError(msg="sort_order must be asc or desc", details={"sort_order": self.sort_order})
        if self.query is not None and not isinstance(self.query, str):
            raise ValidationError(msg="query must be a string or None")

    def normalize(self) -> ArchiveBrowseSpec:
        path = canonical_local_path(self.archive_path)
        if not path.exists():
            raise NotFoundError(msg=f"Archive not found: {path}", details={"path": str(path)})
        return ArchiveBrowseSpec(
            archive_path=path,
            query=self.query,
            file_type=self.file_type,
            min_size=self.min_size,
            max_size=self.max_size,
            limit=self.limit,
            offset=self.offset,
            sort_by=self.sort_by,
            sort_order=self.sort_order,
        )
