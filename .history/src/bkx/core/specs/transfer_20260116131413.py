"""TransferSpec and normalization.

Defines the normalized intent for transfer operations (push/pull).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path


@dataclass(frozen=True, kw_only=True)
class TransferSpec:
    """Normalized specification for transferring artifacts.

    This is the deterministic input to TransferService.

    Direction:
    - push: local -> destination
    - pull: destination -> local
    """

    # Required fields
    source: Path  # Source path (local or remote depending on direction)
    destination: Path  # Destination path (local or remote depending on direction)

    # Operation mode
    direction: str = field(default="push")  # "push" or "pull"

    # Optional fields
    verify: bool = True  # Verify transfer integrity
    checksum: str | None = None  # Expected checksum for verification
    dry_run: bool = False  # Just show plan, don't execute
    overwrite: bool = False  # Overwrite existing files

    # Internal derived fields
    _effective_source: Path | None = field(default=None, compare=False)
    _effective_destination: Path | None = field(default=None, compare=False)

    def normalize(self) -> TransferSpec:
        """Normalize and validate the spec.

        Returns:
            Validated and normalized TransferSpec.

        Raises:
            ValidationError: If validation fails.
        """
        from bkx.core.errors import NotFoundError, ValidationError

        # Validate direction
        if self.direction not in ("push", "pull"):
            raise ValidationError(
                msg=f"Invalid transfer direction: {self.direction}",
                details={"direction": self.direction, "valid": ["push", "pull"]},
            )

        # Validate paths
        if not self.source:
            raise ValidationError(msg="Source path is required")

        if not self.destination:
            raise ValidationError(msg="Destination path is required")

        # For validation, we need to check if source exists
        # (this depends on whether it's local or remote)
        # For now, just validate format
        if not isinstance(self.source, Path):
            raise ValidationError(
                msg="Source must be a Path object",
                details={"source_type": type(self.source).__name__},
            )

        if not isinstance(self.destination, Path):
            raise ValidationError(
                msg="Destination must be a Path object",
                details={"destination_type": type(self.destination).__name__},
            )

        return TransferSpec(
            source=self.source,
            destination=self.destination,
            direction=self.direction,
            verify=self.verify,
            checksum=self.checksum,
            dry_run=self.dry_run,
            overwrite=self.overwrite,
            _effective_source=self.source,
            _effective_destination=self.destination,
        )

    @property
    def effective_source(self) -> Path | None:
        """Get the resolved source path."""
        return self._effective_source

    @property
    def effective_destination(self) -> Path | None:
        """Get the resolved destination path."""
        return self._effective_destination

    @property
    def is_push(self) -> bool:
        """Check if this is a push operation."""
        return self.direction == "push"

    @property
    def is_pull(self) -> bool:
        """Check if this is a pull operation."""
        return self.direction == "pull"

