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
from .execution_plan import (
    ExecutionPlan,
    ExecutionStep,
    PlanFromSpecResult,
    generate_backup_plan,
    generate_archive_plan,
    generate_transfer_plan,
    generate_restore_plan,
)
from .run import RunListResult, RunShowResult, RunSummary

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
    "ExecutionPlan",
    "ExecutionStep",
    "PlanFromSpecResult",
    "generate_backup_plan",
    "generate_archive_plan",
    "generate_transfer_plan",
    "generate_restore_plan",
    "RunListResult",
    "RunShowResult",
    "RunSummary",
]
