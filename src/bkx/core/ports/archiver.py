"""ArchiverPort - framework-free archive IO boundary."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Protocol

from ..results.archive import ArchiveCreateResult, ArchiveEntry, ArchiveInfoResult, ArchiveVerifyResult
from ..specs.archive import ArchiveCreateSpec, ArchiveInfoSpec, ArchiveListSpec, ArchiveVerifySpec
from ..specs.restore import RestoreSpec


class ArchiverPort(Protocol):
    @property
    def name(self) -> str: ...

    @property
    def supported_formats(self) -> tuple[str, ...]: ...

    def create(
        self,
        spec: ArchiveCreateSpec,
        *,
        progress_callback: Callable[[int, int], Any] | None = None,
    ) -> ArchiveCreateResult: ...

    def list(self, spec: ArchiveListSpec) -> tuple[ArchiveEntry, ...]: ...
    def info(self, spec: ArchiveInfoSpec) -> ArchiveInfoResult: ...
    def verify(
        self,
        spec: ArchiveVerifySpec,
        *,
        progress_callback: Callable[[int, int], Any] | None = None,
    ) -> ArchiveVerifyResult: ...
    def extract(
        self,
        spec: RestoreSpec,
        *,
        progress_callback: Callable[[int, int], Any] | None = None,
    ) -> tuple[Path, ...]: ...


@dataclass(frozen=True)
class ArchiveFormat:
    """Capability descriptor for a format actually exposed by the fallback port."""

    identifier: str
    extension: str
    mime_type: str
    supports_compression: bool = True
    supports_splitting: bool = False


# The fallback is currently the only archive adapter. Do not advertise formats or
# split capabilities that it cannot execute; later adapter-selection work may add
# new descriptors alongside real implementations.
TAR = ArchiveFormat("tar", ".tar", "application/x-tar", supports_compression=False, supports_splitting=False)
ZIP = ArchiveFormat("zip", ".zip", "application/zip", supports_compression=True, supports_splitting=False)
SUPPORTED_ARCHIVE_FORMATS = (TAR, ZIP)
