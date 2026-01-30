"""RestoreSpec and normalization.

Defines the normalized intent for restore operations.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path


@dataclass(frozen=True, kw_only=True)
class RestoreSpec:
    """Normalized specification for restoring from archive.

    This is the deterministic input to RestoreService.
    """

    # Required fields
    archive: Path  # Archive to restore from

    # Optional fields
    destination: Path | None = None  # Where to restore (default: original location)
    paths: tuple[str, ...] = field(default_factory=tuple)  # Specific paths to restore
    overwrite: bool = False  # Overwrite existing files
    verify: bool = True  # Verify archive integrity before restore
    dry_run: bool = False  # Just show plan, don't execute

    # Internal derived fields
    _resolved_destination: Path | None = field(default=None, compare=False)

    def normalize(
        self,
        *,
        default_destination: Path | None = None,
    ) -> RestoreSpec:
        """Normalize and validate the spec.

        Args:
            default_destination: Fallback destination if not specified.

        Returns:
            Validated and normalized RestoreSpec.

        Raises:
            ValidationError: If validation fails.
            NotFoundError: If archive doesn't exist.
        """
        from bkx.core.errors import NotFoundError, ValidationError

        # Validate archive exists
        if not self.archive.exists():
            raise NotFoundError(
                msg=f"Archive not found: {self.archive}",
                details={"archive": str(self.archive)},
            )

        # Validate destination if specified
        if self.destination:
            # Don't validate yet - might be creating parent dirs
            pass

        # Resolve destination
        destination = self.destination or default_destination

        return RestoreSpec(
            archive=self.archive,
            destination=destination,
            paths=self.paths,
            overwrite=self.overwrite,
            verify=self.verify,
            dry_run=self.dry_run,
            _resolved_destination=destination,
        )

    @property
    def effective_destination(self) -> Path | None:
        """Get the resolved destination path."""
        return self._resolved_destination

    @property
    def is_partial_restore(self) -> bool:
        """Check if this is a partial restore (specific paths)."""
        return len(self.paths) > 0

    @property
    def is_full_restore(self) -> bool:
        """Check if this is a full restore."""
        return len(self.paths) == 0

