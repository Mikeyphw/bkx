"""Ports (interfaces for IO/tooling).

Contract rule:
- External tools are behind ports.
- Must have a fallback implementation in core/impl/.
"""

from .archiver import SUPPORTED_ARCHIVE_FORMATS, TAR, ZIP, ArchiveFormat, ArchiverPort
from .hasher import MD5, SHA256, SHA512, XXH3, HasherPort
from .run_store import RunMetadata, RunStorePort
from .tool_detector import KNOWN_TOOLS, PREFERRED_TOOLS, ToolDetectorPort, ToolInfo
from .transfer import TransferPort

__all__ = [
    "ArchiverPort",
    "ArchiveFormat",
    "TAR",
    "ZIP",
    "SUPPORTED_ARCHIVE_FORMATS",
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
