"""Normalized Specs.

Contract rule:
- Interfaces build Specs.
- Services accept Specs.
- Specs are deterministic and hashable (parity tests later).
"""

from .archive import ArchiveCreateSpec
from .backup import DEFAULT_COMPRESSION, BackupSpec, CompressionProfile
from .restore import RestoreSpec
from .transfer import TransferSpec

__all__ = [
    "BackupSpec",
    "CompressionProfile",
    "DEFAULT_COMPRESSION",
    "ArchiveCreateSpec",
    "TransferSpec",
    "RestoreSpec",
]
