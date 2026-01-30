"""Normalized Specs.

Contract rule:
- Interfaces build Specs.
- Services accept Specs.
- Specs are deterministic and hashable (parity tests later).
"""

from .backup import BackupSpec, CompressionProfile, DEFAULT_COMPRESSION
from .archive import ArchiveCreateSpec
from .transfer import TransferSpec
from .restore import RestoreSpec

__all__ = [
    "BackupSpec",
    "CompressionProfile",
    "DEFAULT_COMPRESSION",
    "ArchiveCreateSpec",
    "TransferSpec",
    "RestoreSpec",
]
