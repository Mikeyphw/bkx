"""TransferService - Spec-first transfer use cases."""

from __future__ import annotations

from collections.abc import Callable
from typing import TYPE_CHECKING, Any

from ..errors import ExternalToolError, ValidationError, expected_error_boundary
from ..results.transfer import TransferProgress, TransferResult
from .run_tracking import snapshot_spec, track_run

if TYPE_CHECKING:
    from ..ports.hasher import HasherPort
    from ..ports.run_store import RunStorePort
    from ..ports.transfer import TransferPort
    from ..specs.transfer import TransferSpec, TransferSyncSpec


class TransferService:
    def __init__(self, *, transfer: TransferPort, hasher: HasherPort | None = None, run_store: RunStorePort | None = None) -> None:
        self._transfer = transfer
        self._hasher = hasher
        self._run_store = run_store

    def push(self, spec: TransferSpec, *, progress_callback: Callable[[TransferProgress], Any] | None = None) -> TransferResult:
        if spec.direction != "push":
            raise ValidationError(msg="Spec direction must be 'push' for push()", details={"direction": spec.direction})
        spec = spec.normalize()
        if not self._transfer.supports_push:
            raise ExternalToolError(
                msg=f"Transfer backend '{self._transfer.name}' does not support push",
                tool_name=self._transfer.name,
                details={"operation": "push", "backend": self._transfer.name},
            )

        def _execute() -> TransferResult:
            with expected_error_boundary("pushing transfer", resource=str(spec.effective_source), backend=self._transfer.name):
                return self._transfer.push(spec, progress_callback=progress_callback)

        return _track_transfer(self._run_store, "push", spec, _execute)

    def pull(self, spec: TransferSpec, *, progress_callback: Callable[[TransferProgress], Any] | None = None) -> TransferResult:
        if spec.direction != "pull":
            raise ValidationError(msg="Spec direction must be 'pull' for pull()", details={"direction": spec.direction})
        spec = spec.normalize()
        if not self._transfer.supports_pull:
            raise ExternalToolError(
                msg=f"Transfer backend '{self._transfer.name}' does not support pull",
                tool_name=self._transfer.name,
                details={"operation": "pull", "backend": self._transfer.name},
            )

        def _execute() -> TransferResult:
            with expected_error_boundary("pulling transfer", resource=str(spec.effective_source), backend=self._transfer.name):
                return self._transfer.pull(spec, progress_callback=progress_callback)

        return _track_transfer(self._run_store, "pull", spec, _execute)

    def sync(self, spec: TransferSyncSpec, *, progress_callback: Callable[[TransferProgress], Any] | None = None) -> TransferResult:
        spec = spec.normalize()

        def _execute() -> TransferResult:
            with expected_error_boundary("syncing transfer", resource=str(spec.source), backend=self._transfer.name):
                return self._transfer.sync(spec, progress_callback=progress_callback)

        return track_run(
            self._run_store,
            operation="transfer",
            source_paths=(str(spec.source),),
            destination_path=str(spec.destination),
            archive_path=None,
            spec_snapshot=snapshot_spec(spec),
            execute=_execute,
            files_count=lambda result: result.files_transferred,
            bytes_processed=lambda result: result.bytes_transferred,
        )


def _track_transfer(run_store: RunStorePort | None, label: str, spec: TransferSpec, execute: Callable[[], TransferResult]) -> TransferResult:
    del label
    return track_run(
        run_store,
        operation="transfer",
        source_paths=tuple(str(source) for source in spec.effective_sources),
        destination_path=str(spec.effective_destination),
        archive_path=None,
        spec_snapshot=snapshot_spec(spec),
        execute=execute,
        files_count=lambda result: result.files_transferred,
        bytes_processed=lambda result: result.bytes_transferred,
    )
