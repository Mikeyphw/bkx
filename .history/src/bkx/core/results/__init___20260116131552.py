"""Structured Result objects returned by services.

Contract rule:
- Services return Results only, never strings.
- Results include timings, counts, produced paths, and warnings.
"""

from .backup import BackupResult, BackupStepResult, Timing
from .archive import (
    ArchiveCreateResult,
    ArchiveListResult,
    ArchiveEntry,
    ArchiveInfoResult,
    ArchiveVerifyResult,
)
from .transfer import TransferResult, TransferProgress
from .restore import RestoreResult, RestorePreviewResult, RestoreFileInfo

__all__ = [
    "Timing",
    "BackupResult",
    "BackupStepResult",
    "ArchiveCreateResult",
    "ArchiveListResult",
    "ArchiveEntry",
    "ArchiveInfoResult",
    "ArchiveVerifyResult",
    "TransferResult",
    "TransferProgress",
    "RestoreResult",
    "RestorePreviewResult",
    "RestoreFileInfo",
]
