"""Core services (use-cases).

Contract rule:
- No printing here.
- No UI imports.
- Deterministic behavior: identical Spec -> identical Result.
"""

from .backup import BackupService
from .archive import ArchiveService
from .transfer import TransferService
from .restore import RestoreService
from ..impl.tool_detector import get_tool_detector
from ..impl.archiver import get_archiver
from ..impl.transfer import get_transfer
from ..impl.hasher import get_hasher

__all__ = [
    "BackupService",
    "ArchiveService",
    "TransferService",
    "RestoreService",
    "get_backup_service",
    "get_archive_service",
    "get_transfer_service",
    "get_restore_service",
]


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
