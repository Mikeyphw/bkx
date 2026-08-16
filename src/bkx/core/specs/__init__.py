"""Canonical normalized Specs used at every interface/service boundary."""

from .archive import ArchiveBrowseSpec, ArchiveCreateSpec, ArchiveInfoSpec, ArchiveListSpec, ArchiveVerifySpec
from .backup import DEFAULT_COMPRESSION, BackupSpec, CompressionProfile
from .restore import RestoreSpec
from .tools import ToolDetectSpec, ToolExplainSpec
from .transfer import TransferSpec, TransferSyncSpec

__all__ = [
    "BackupSpec",
    "CompressionProfile",
    "DEFAULT_COMPRESSION",
    "ArchiveCreateSpec",
    "ArchiveListSpec",
    "ArchiveInfoSpec",
    "ArchiveVerifySpec",
    "ArchiveBrowseSpec",
    "TransferSpec",
    "TransferSyncSpec",
    "RestoreSpec",
    "ToolDetectSpec",
    "ToolExplainSpec",
]
