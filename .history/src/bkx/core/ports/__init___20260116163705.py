"""Ports (interfaces for IO/tooling).

Contract rule:
- External tools are behind ports.
- Must have a fallback implementation in core/impl/.
"""

from .archiver import ArchiverPort, ArchiveFormat, TAR_ZSTD, TAR_GZIP, TAR, ZIP
from .transfer import TransferPort
from .hasher import HasherPort, SHA256, SHA512, XXH3, MD5
from .tool_detector import ToolDetectorPort, ToolInfo, KNOWN_TOOLS, PREFERRED_TOOLS
from .run_store import RunStorePort, RunMetadata

__all__ = [
    "ArchiverPort",
    "ArchiveFormat",
    "TAR_ZSTD",
    "TAR_GZIP",
    "TAR",
    "ZIP",
    "TransferPort",
    "HasherPort",
    "SHA256",
    "SHA512",
    "XXH3",
    "MD5",
    "ToolDetectorPort",
    "ToolInfo",
    "KNOWN_TOOLS",
    "PREFERRED_TOOLS",
    "RunStorePort",
    "RunMetadata",
]
