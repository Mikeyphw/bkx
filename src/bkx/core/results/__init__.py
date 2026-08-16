"""Structured Result objects returned by services.

Contract rule:
- Services return Results only, never strings.
- Results include timings, counts, produced paths, and warnings.
"""

from .archive import (
    ArchiveBrowseResult,
    ArchiveCreateResult,
    ArchiveEntry,
    ArchiveInfoResult,
    ArchiveListResult,
    ArchiveVerifyResult,
)
from .backup import BackupResult, BackupStepResult, Timing
from .execution_plan import (
    ExecutionPlan,
    ExecutionStep,
    PlanFromSpecResult,
    generate_archive_plan,
    generate_backup_plan,
    generate_restore_plan,
    generate_transfer_plan,
    plan_for_spec,
    execution_plan_to_dict,
)
from .restore import RestoreFileInfo, RestorePreviewResult, RestoreResult
from .run import RunListResult, RunShowResult, RunSummary
from .transfer import TransferProgress, TransferResult
from .tools import ToolDetectionResult, ToolExplainResult

__all__ = [
    "Timing",
    "BackupResult",
    "BackupStepResult",
    "ArchiveCreateResult",
    "ArchiveListResult",
    "ArchiveEntry",
    "ArchiveInfoResult",
    "ArchiveVerifyResult",
    "ArchiveBrowseResult",
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
    "plan_for_spec",
    "execution_plan_to_dict",
    "RunListResult",
    "RunShowResult",
    "RunSummary",
    "ToolDetectionResult",
    "ToolExplainResult",
]
