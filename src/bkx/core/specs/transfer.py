"""Transfer Specs with URI-preserving location semantics."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from bkx.core.errors import NotFoundError, ValidationError

from .common import (
    TransferLocation,
    canonical_local_path,
    is_remote_location,
    normalize_patterns,
    normalize_transfer_location,
    paths_overlap,
)


def _validate_checksum(value: str | None) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str) or not value.strip():
        raise ValidationError(msg="checksum must be a non-empty string when provided")
    return value.strip()


@dataclass(frozen=True, kw_only=True)
class TransferSpec:
    sources: tuple[TransferLocation, ...]
    destination: TransferLocation
    direction: str = field(default="push")
    verify: bool = True
    checksum: str | None = None
    dry_run: bool = False
    overwrite: bool = False

    def __post_init__(self) -> None:
        if not isinstance(self.sources, tuple) or not self.sources:
            raise ValidationError(msg="Transfer sources must be a non-empty tuple")
        for source in self.sources:
            if not isinstance(source, (Path, str)):
                raise ValidationError(msg="Transfer source entries must be Path or URI strings", details={"type": type(source).__name__})
        if not isinstance(self.destination, (Path, str)):
            raise ValidationError(msg="Transfer destination must be a Path or URI string")
        if self.direction not in {"push", "pull"}:
            raise ValidationError(msg="Invalid transfer direction", details={"direction": self.direction, "valid": ["push", "pull"]})
        if self.direction == "push" and any(isinstance(source, str) and is_remote_location(source.strip()) for source in self.sources):
            raise ValidationError(msg="Push sources must be local paths")
        if self.direction == "pull" and isinstance(self.destination, str) and is_remote_location(self.destination.strip()):
            raise ValidationError(msg="Pull destination must be local")
        if not isinstance(self.verify, bool) or not isinstance(self.dry_run, bool) or not isinstance(self.overwrite, bool):
            raise ValidationError(msg="verify, dry_run, and overwrite must be booleans")
        object.__setattr__(self, "checksum", _validate_checksum(self.checksum))

    def normalize(self) -> TransferSpec:
        sources = tuple(normalize_transfer_location(src, field_name="sources[]") for src in self.sources)
        destination = normalize_transfer_location(self.destination, field_name="destination")

        if self.direction == "push":
            for source in sources:
                if not isinstance(source, Path):
                    raise ValidationError(msg="Push sources must be local paths", details={"source": str(source)})
        if self.direction == "pull" and not isinstance(destination, Path):
            raise ValidationError(msg="Pull destination must be a local Path", details={"destination": str(destination)})

        if len(sources) > 1 and isinstance(destination, Path) and (not destination.exists() or not destination.is_dir()):
            raise ValidationError(
                msg="Multiple local transfer sources require an existing directory destination",
                details={"destination": str(destination), "source_count": len(sources)},
            )

        if isinstance(destination, Path):
            for source in sources:
                if isinstance(source, Path) and (source == destination or (source.exists() and source.is_dir() and paths_overlap(source, destination))):
                    raise ValidationError(
                        msg="Transfer destination overlaps a source tree",
                        details={"source": str(source), "destination": str(destination)},
                    )

        return TransferSpec(
            sources=sources,
            destination=destination,
            direction=self.direction,
            verify=self.verify,
            checksum=self.checksum,
            dry_run=self.dry_run,
            overwrite=self.overwrite,
        )

    @property
    def effective_sources(self) -> tuple[TransferLocation, ...]:
        return self.sources

    @property
    def effective_source(self) -> TransferLocation | None:
        return self.sources[0] if self.sources else None

    @property
    def effective_destination(self) -> TransferLocation:
        return self.destination

    @property
    def is_push(self) -> bool:
        return self.direction == "push"

    @property
    def is_pull(self) -> bool:
        return self.direction == "pull"


@dataclass(frozen=True, kw_only=True)
class TransferSyncSpec:
    source: Path
    destination: Path
    delete: bool = False
    excludes: tuple[str, ...] = field(default_factory=tuple)
    verify: bool = True
    checksum: str | None = None
    dry_run: bool = False
    overwrite: bool = False

    def __post_init__(self) -> None:
        if not isinstance(self.source, Path) or not isinstance(self.destination, Path):
            raise ValidationError(msg="Sync source and destination must be local Path objects")
        object.__setattr__(self, "excludes", normalize_patterns(self.excludes, field_name="excludes"))
        object.__setattr__(self, "checksum", _validate_checksum(self.checksum))
        if not all(isinstance(value, bool) for value in (self.delete, self.verify, self.dry_run, self.overwrite)):
            raise ValidationError(msg="Sync policy flags must be booleans")

    def normalize(self) -> TransferSyncSpec:
        source = canonical_local_path(self.source)
        destination = canonical_local_path(self.destination)
        if not source.exists():
            raise NotFoundError(msg=f"Source path does not exist: {source}", details={"path": str(source)})
        if paths_overlap(source, destination):
            raise ValidationError(msg="Sync source and destination must not overlap", details={"source": str(source), "destination": str(destination)})
        return TransferSyncSpec(
            source=source,
            destination=destination,
            delete=self.delete,
            excludes=self.excludes,
            verify=self.verify,
            checksum=self.checksum,
            dry_run=self.dry_run,
            overwrite=self.overwrite,
        )
