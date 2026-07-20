"""Core services factory functions.

Contract rule:
- No printing here.
- No UI imports.
- Deterministic behavior: identical Spec -> identical Result.
"""

from __future__ import annotations

from ..impl.archiver import get_archiver
from ..impl.hasher import get_hasher
from ..impl.run_store import get_run_store
from ..impl.tool_detector import get_tool_detector
from ..impl.transfer import get_transfer
from .archive import ArchiveService
from .backup import BackupService
from .restore import RestoreService
from .run import RunService
from .transfer import TransferService


def get_backup_service() -> BackupService:
    """Get a BackupService with default port implementations."""
    return BackupService(
        archiver=get_archiver(),
        transfer=get_transfer(),
        hasher=get_hasher(),
        tool_detector=get_tool_detector(),
    )


def get_archive_service() -> ArchiveService:
    """Get an ArchiveService with default port implementation."""
    return ArchiveService(
        archiver=get_archiver(),
    )


def get_transfer_service() -> TransferService:
    """Get a TransferService with default port implementations."""
    return TransferService(
        transfer=get_transfer(),
        hasher=get_hasher(),
    )


def get_restore_service() -> RestoreService:
    """Get a RestoreService with default port implementation."""
    return RestoreService(
        archiver=get_archiver(),
    )


def get_run_service() -> RunService:
    """Get a RunService with default port implementation."""
    return RunService(
        run_store=get_run_store(),
    )


__all__ = [
    "get_backup_service",
    "get_archive_service",
    "get_transfer_service",
    "get_restore_service",
    "get_run_service",
    "BackupService",
    "ArchiveService",
    "TransferService",
    "RestoreService",
    "RunService",
]
