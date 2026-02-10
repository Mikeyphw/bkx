"""TransferService - handles transfer operations.

Contract rule:
- Services accept Specs only.
- Services return Results only.
- Services raise typed errors only.
- No rendering, no parsing.
"""

from __future__ import annotations

from collections.abc import Callable
from pathlib import Path
from typing import TYPE_CHECKING, Any

from ..results.transfer import Timing, TransferProgress, TransferResult

if TYPE_CHECKING:
    from ..ports.hasher import HasherPort
    from ..ports.transfer import TransferPort
    from ..specs.transfer import TransferSpec


class TransferService:
    """Handles push/pull transfer operations.

    Operations:
    - push: Transfer local -> destination
    - pull: Transfer destination -> local
    - sync: Mirror source to destination
    """

    def __init__(
        self,
        *,
        transfer: TransferPort,
        hasher: HasherPort | None = None,
    ) -> None:
        """Initialize service with required ports.

        Args:
            transfer: Port for transfer operations.
            hasher: Optional port for checksum verification.
        """
        self._transfer = transfer
        self._hasher = hasher

    def push(
        self,
        spec: TransferSpec,
        *,
        progress_callback: Callable[[TransferProgress], Any] | None = None,
    ) -> TransferResult:
        """Push files from local to destination.

        Args:
            spec: Normalized transfer specification (direction=push).
            progress_callback: Optional callback for progress updates.

        Returns:
            TransferResult with metrics.

        Raises:
            ValidationError: If spec is invalid.
        """

        if spec.direction != "push":
            from ..errors import ValidationError

            raise ValidationError(
                msg="Spec direction must be 'push' for push()",
                details={"direction": spec.direction},
            )

        if spec.dry_run:
            return self._dry_run(spec, "push")

        if not self._transfer.supports_push:
            raise NotImplementedError(f"Transfer method '{self._transfer.name}' does not support push")

        return self._transfer.push(spec, progress_callback=progress_callback)

    def pull(
        self,
        spec: TransferSpec,
        *,
        progress_callback: Callable[[TransferProgress], Any] | None = None,
    ) -> TransferResult:
        """Pull files from destination to local.

        Args:
            spec: Normalized transfer specification (direction=pull).
            progress_callback: Optional callback for progress updates.

        Returns:
            TransferResult with metrics.

        Raises:
            ValidationError: If spec is invalid.
        """

        if spec.direction != "pull":
            from ..errors import ValidationError

            raise ValidationError(
                msg="Spec direction must be 'pull' for pull()",
                details={"direction": spec.direction},
            )

        if spec.dry_run:
            return self._dry_run(spec, "pull")

        if not self._transfer.supports_pull:
            raise NotImplementedError(f"Transfer method '{self._transfer.name}' does not support pull")

        return self._transfer.pull(spec, progress_callback=progress_callback)

    def sync(
        self,
        source: Path,
        destination: Path,
        *,
        delete: bool = False,
        exclude: tuple[str, ...] = (),
        progress_callback: Callable[[TransferProgress], Any] | None = None,
    ) -> TransferResult:
        """Sync source to destination (mirror semantics).

        Args:
            source: Source path.
            destination: Destination path.
            delete: Remove files not in source.
            exclude: Patterns to exclude.
            progress_callback: Optional callback for progress updates.

        Returns:
            TransferResult with metrics.
        """
        return self._transfer.sync(
            source=source,
            destination=destination,
            delete=delete,
            exclude=exclude,
            progress_callback=progress_callback,
        )

    def _dry_run(self, spec: TransferSpec, direction: str) -> TransferResult:
        """Execute dry run showing what would happen."""
        import time

        start_ms = int(time.time() * 1000)

        return TransferResult(
            success=True,
            dry_run=True,
            direction=direction,
            timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
            warnings=("Dry run - transfer not executed",),
        )
