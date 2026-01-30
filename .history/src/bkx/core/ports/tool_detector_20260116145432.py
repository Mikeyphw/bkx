"""ToolDetectorPort - interface for detecting optional external tools.

Contract rule:
- Services accept Specs, use ports for IO.
- Ports are pure interfaces with fallback implementations.
- Optional tools are runtime-detected with full fallbacks.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol


@dataclass(frozen=True)
class ToolInfo:
    """Information about an external tool."""

    name: str
    path: Path | None  # None if not found
    version: str | None
    available: bool
    capabilities: tuple[str, ...]  # e.g., ("zstd", "parallel")


class ToolDetectorPort(Protocol):
    """Protocol for detecting optional external tools.

    Implement this to detect and version optional tools.
    Fallback implementations should report tools as unavailable.
    """

    def detect(self, tool_name: str) -> ToolInfo:
        """Detect if a specific tool is available.

        Args:
            tool_name: Name of tool to detect.

        Returns:
            ToolInfo with availability and version.
        """
        ...

    def detect_all(self, tool_names: tuple[str, ...]) -> dict[str, ToolInfo]:
        """Detect multiple tools at once.

        Args:
            tool_names: Names of tools to detect.

        Returns:
            Dict mapping tool name to ToolInfo.
        """
        ...

    def list_available(self) -> tuple[str, ...]:
        """List all available tools in the registry.

        Returns:
            Tuple of available tool names.
        """
        ...

    def get_preferred(
        self,
        tool_category: str,
    ) -> str | None:
        """Get preferred tool for a category.

        Args:
            tool_category: Category (e.g., "archiver", "transfer").

        Returns:
            Name of preferred tool, or None if unavailable.
        """
        ...


# Registry of known optional tools
KNOWN_TOOLS = {
    # Python (for pure Python fallbacks)
    "python": ("runtime", "Python interpreter"),

    # Archivers
    "zstd": ("compression", "fast compression with zstd"),
    "pigz": ("compression", "parallel gzip"),
    "pbzip2": ("compression", "parallel bzip2"),
    "xz": ("compression", "xz compression"),
    "7z": ("compression", "7-Zip compression"),
    "zip": ("compression", "zip compression"),
    "tar": ("archiving", "tar archiving"),

    # Transfer
    "rsync": ("transfer", "efficient file transfer"),
    "rclone": ("cloud", "cloud storage sync"),
    "scp": ("transfer", "SSH file transfer"),

    # Hashing
    "xxh128": ("hashing", "xxh128 hash"),
    "xxh64": ("hashing", "xxh64 hash"),
    "sha256sum": ("hashing", "SHA-256 hash"),
    "sha512sum": ("hashing", "SHA-512 hash"),
    "md5sum": ("hashing", "MD5 hash"),
}

# Category to tool preference order
PREFERRED_TOOLS = {
    "archiver": ("tar",),
    "compression": ("zstd", "pigz", "xz", "gzip"),
    "transfer": ("rsync", "rclone", "scp", "cp"),
    "hashing": ("xxh128", "xxh64", "sha256sum"),
}

