"""TransferPort - interface for transferring artifacts.

Contract rule:
- Services accept Specs, use ports for IO.
- Ports are pure interfaces with fallback implementations.
"""

from __future__ import annotations

from collections.abc import Callable
from pathlib import Path
from typing import Any, Protocol

from ..results.transfer import TransferProgress, TransferResult
from ..specs.transfer import TransferSpec


class TransferPort(Protocol):
    """Protocol for transfer operations.

    Implement this to support different transfer mechanisms.
    Each implementation must have a fallback in core/impl/.
    """

    @property
    def name(self) -> str:
        """Human-readable name of this transfer method."""
        ...

    @property
    def supports_push(self) -> bool:
        """Whether push operations are supported."""
        ...

    @property
    def supports_pull(self) -> bool:
        """Whether pull operations are supported."""
        ...

    def push(
        self,
        spec: TransferSpec,
        *,
        progress_callback: Callable[[TransferProgress], Any] | None = None,
    ) -> TransferResult:
        """Transfer files from local to destination.

        Args:
            spec: Normalized transfer spec (direction must be "push").
            progress_callback: Optional callback for progress updates.

        Returns:
            Transfer result with metrics.
        """
        ...

    def pull(
        self,
        spec: TransferSpec,
        *,
        progress_callback: Callable[[TransferProgress], Any] | None = None,
    ) -> TransferResult:
        """Transfer files from destination to local.

        Args:
            spec: Normalized transfer spec (direction must be "pull").
            progress_callback: Optional callback for progress updates.

        Returns:
            Transfer result with metrics.
        """
        ...

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
            Transfer result with metrics.
        """
        ...
