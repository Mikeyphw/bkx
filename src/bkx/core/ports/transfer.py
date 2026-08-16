"""TransferPort - interface for transferring normalized Specs."""

from __future__ import annotations

from collections.abc import Callable
from typing import Any, Protocol

from ..results.transfer import TransferProgress, TransferResult
from ..specs.transfer import TransferSpec, TransferSyncSpec


class TransferPort(Protocol):
    @property
    def name(self) -> str: ...
    @property
    def supports_push(self) -> bool: ...
    @property
    def supports_pull(self) -> bool: ...

    def push(self, spec: TransferSpec, *, progress_callback: Callable[[TransferProgress], Any] | None = None) -> TransferResult: ...
    def pull(self, spec: TransferSpec, *, progress_callback: Callable[[TransferProgress], Any] | None = None) -> TransferResult: ...
    def sync(self, spec: TransferSyncSpec, *, progress_callback: Callable[[TransferProgress], Any] | None = None) -> TransferResult: ...
