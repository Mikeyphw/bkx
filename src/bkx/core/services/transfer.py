"""TransferService - Spec-first transfer use cases."""

from __future__ import annotations

from collections.abc import Callable
from typing import TYPE_CHECKING, Any

from ..errors import ExternalToolError, expected_error_boundary
from ..results.backup import Timing
from ..results.transfer import TransferProgress, TransferResult

if TYPE_CHECKING:
    from ..ports.hasher import HasherPort
    from ..ports.transfer import TransferPort
    from ..specs.transfer import TransferSpec, TransferSyncSpec


class TransferService:
    def __init__(self, *, transfer: TransferPort, hasher: HasherPort | None = None) -> None:
        self._transfer = transfer
        self._hasher = hasher

    def push(self, spec: TransferSpec, *, progress_callback: Callable[[TransferProgress], Any] | None = None) -> TransferResult:
        if spec.direction != "push":
            from ..errors import ValidationError
            raise ValidationError(msg="Spec direction must be 'push' for push()", details={"direction": spec.direction})
        spec = spec.normalize()
        if not self._transfer.supports_push:
            raise ExternalToolError(msg=f"Transfer backend '{self._transfer.name}' does not support push", tool_name=self._transfer.name, details={"operation": "push", "backend": self._transfer.name})
        if spec.dry_run:
            return self._dry_run(spec, "push")
        with expected_error_boundary("pushing transfer", resource=str(spec.effective_source), backend=self._transfer.name):
            return self._transfer.push(spec, progress_callback=progress_callback)

    def pull(self, spec: TransferSpec, *, progress_callback: Callable[[TransferProgress], Any] | None = None) -> TransferResult:
        if spec.direction != "pull":
            from ..errors import ValidationError
            raise ValidationError(msg="Spec direction must be 'pull' for pull()", details={"direction": spec.direction})
        spec = spec.normalize()
        if not self._transfer.supports_pull:
            raise ExternalToolError(msg=f"Transfer backend '{self._transfer.name}' does not support pull", tool_name=self._transfer.name, details={"operation": "pull", "backend": self._transfer.name})
        if spec.dry_run:
            return self._dry_run(spec, "pull")
        with expected_error_boundary("pulling transfer", resource=str(spec.effective_source), backend=self._transfer.name):
            return self._transfer.pull(spec, progress_callback=progress_callback)

    def sync(self, spec: TransferSyncSpec, *, progress_callback: Callable[[TransferProgress], Any] | None = None) -> TransferResult:
        spec = spec.normalize()
        if spec.dry_run:
            now = _now_ms()
            return TransferResult(success=True, dry_run=True, direction="sync", timing=Timing(now, now), source=spec.source, destination=spec.destination, warnings=("Dry run - sync not executed",))
        with expected_error_boundary("syncing transfer", resource=str(spec.source), backend=self._transfer.name):
            return self._transfer.sync(spec, progress_callback=progress_callback)

    def _dry_run(self, spec: TransferSpec, direction: str) -> TransferResult:
        now = _now_ms()
        return TransferResult(
            success=True,
            dry_run=True,
            direction=direction,
            timing=Timing(now, now),
            source=spec.effective_source,
            destination=spec.effective_destination,
            warnings=("Dry run - transfer not executed",),
        )


def _now_ms() -> int:
    import time
    return int(time.time() * 1000)
