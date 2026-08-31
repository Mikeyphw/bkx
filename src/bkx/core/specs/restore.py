"""RestoreSpec canonical validation and archive-member normalization."""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from pathlib import Path

from bkx.core.errors import NotFoundError, ValidationError

from .common import absolute_local_path, canonical_local_path, normalize_archive_member


@dataclass(frozen=True, kw_only=True)
class RestoreSpec:
    archive: Path
    destination: Path | None = None
    paths: tuple[str, ...] = field(default_factory=tuple)
    overwrite: bool = False
    verify: bool = True
    dry_run: bool = False
    fresh_dir: bool = True
    confirm_overwrite: bool = True
    _resolved_destination: Path | None = field(default=None, compare=False, repr=False)

    def __post_init__(self) -> None:
        if not isinstance(self.archive, Path):
            raise ValidationError(msg="Restore archive must be a Path")
        if self.destination is not None and not isinstance(self.destination, Path):
            raise ValidationError(msg="Restore destination must be a Path or None")
        if not isinstance(self.paths, tuple):
            raise ValidationError(msg="Restore paths must be a tuple")
        normalized_paths = tuple(normalize_archive_member(path) for path in self.paths)
        if len(set(normalized_paths)) != len(normalized_paths):
            raise ValidationError(msg="Duplicate restore path selectors are not allowed")
        object.__setattr__(self, "paths", normalized_paths)
        if not all(isinstance(value, bool) for value in (self.overwrite, self.verify, self.dry_run, self.fresh_dir, self.confirm_overwrite)):
            raise ValidationError(msg="Restore policy flags must be booleans")
        if self.overwrite and self.confirm_overwrite:
            raise ValidationError(
                msg="Cannot use overwrite while confirmation is required",
                details={"overwrite": self.overwrite, "confirm_overwrite": self.confirm_overwrite},
            )

    def normalize(self, *, default_destination: Path | None = None) -> RestoreSpec:
        if self._resolved_destination is not None:
            return self
        archive = canonical_local_path(self.archive)
        if not archive.exists():
            raise NotFoundError(msg=f"Archive not found: {archive}", details={"archive": str(archive)})

        explicit = absolute_local_path(self.destination) if self.destination is not None else None
        fallback = absolute_local_path(default_destination) if default_destination is not None else None
        semantic_destination = explicit or fallback

        # Preserve the existing fresh-directory behavior for RM-028 owns
        # the safety-model redesign. Keep timestamp output in derived state only,
        # so semantic equality/hash remain deterministic.
        if self.fresh_dir and explicit is None and fallback is None:
            resolved = absolute_local_path(Path(f"restored_{archive.stem}_{int(time.time())}"))
        else:
            resolved = semantic_destination

        return RestoreSpec(
            archive=archive,
            destination=semantic_destination,
            paths=self.paths,
            overwrite=self.overwrite,
            verify=self.verify,
            dry_run=self.dry_run,
            fresh_dir=self.fresh_dir,
            confirm_overwrite=self.confirm_overwrite,
            _resolved_destination=resolved,
        )

    @property
    def effective_destination(self) -> Path | None:
        return self._resolved_destination if self._resolved_destination is not None else self.destination

    @property
    def is_partial_restore(self) -> bool:
        return bool(self.paths)

    @property
    def is_full_restore(self) -> bool:
        return not self.paths

    @property
    def requires_confirmation(self) -> bool:
        destination = self.effective_destination
        return bool(destination is not None and destination.exists())
