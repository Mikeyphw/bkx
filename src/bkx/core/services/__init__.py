"""Core services (use-cases).

Contract rule:
- No printing here.
- No UI imports.
- Deterministic behavior: identical Spec -> identical Result.
"""

from __future__ import annotations

from .archive import ArchiveService
from .backup import BackupService
from .factory import (
    get_archive_service,
    get_backup_service,
    get_restore_service,
    get_run_service,
    get_transfer_service,
)
from .restore import RestoreService
from .run import RunService
from .transfer import TransferService

__all__ = [
    "BackupService",
    "ArchiveService",
    "TransferService",
    "RestoreService",
    "RunService",
    "get_backup_service",
    "get_archive_service",
    "get_transfer_service",
    "get_restore_service",
    "get_run_service",
]

