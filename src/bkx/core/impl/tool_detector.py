"""FallbackToolDetector - detects available tools using shutil.

Fallback implementation that uses shutil.which() to detect tools.
"""

from __future__ import annotations

import shutil
from pathlib import Path
from typing import NamedTuple

from ..ports.tool_detector import ToolInfo, KNOWN_TOOLS, PREFERRED_TOOLS


class FallbackToolDetector:
    """Detects available tools using shutil.which().

    This is the fallback implementation when no specialized
    tool detectors are available.
    """

    def __init__(self) -> None:
        """Initialize the detector."""
        self._cache: dict[str, ToolInfo] = {}

    def detect(self, tool_name: str) -> ToolInfo:
        """Detect if a specific tool is available.

        Args:
            tool_name: Name of tool to detect.

        Returns:
            ToolInfo with availability and version.
        """
        if tool_name in self._cache:
            return self._cache[tool_name]

        # Find the tool using which
        tool_path = shutil.which(tool_name)

        if tool_path is None:
            info = ToolInfo(
                name=tool_name,
                path=None,
                version=None,
                available=False,
                capabilities=(),
            )
        else:
            # Get version if possible
            version = self._get_version(tool_name, Path(tool_path))
            capabilities = self._get_capabilities(tool_name)

            info = ToolInfo(
                name=tool_name,
                path=Path(tool_path),
                version=version,
                available=True,
                capabilities=capabilities,
            )

        self._cache[tool_name] = info
        return info

    def detect_all(self, tool_names: tuple[str, ...]) -> dict[str, ToolInfo]:
        """Detect multiple tools at once.

        Args:
            tool_names: Names of tools to detect.

        Returns:
            Dict mapping tool name to ToolInfo.
        """
        return {name: self.detect(name) for name in tool_names}

    def list_available(self) -> tuple[str, ...]:
        """List all available tools in the registry.

        Returns:
            Tuple of available tool names.
        """
        available = []
        for name in KNOWN_TOOLS:
            if self.detect(name).available:
                available.append(name)
        return tuple(available)

    def get_preferred(self, tool_category: str) -> str | None:
        """Get preferred tool for a category.

        Args:
            tool_category: Category (e.g., "archiver", "transfer").

        Returns:
            Name of preferred tool, or None if unavailable.
        """
        preferred = PREFERRED_TOOLS.get(tool_category, ())
        for tool_name in preferred:
            if self.detect(tool_name).available:
                return tool_name
        return None

    def _get_version(self, tool_name: str, tool_path: Path) -> str | None:
        """Try to get tool version.

        Args:
            tool_name: Name of tool.
            tool_path: Path to tool binary.

        Returns:
            Version string or None.
        """
        import subprocess

        for version_flag in ["--version", "-V", "--version"]:
            try:
                result = subprocess.run(
                    [str(tool_path), version_flag],
                    capture_output=True,
                    text=True,
                    timeout=5,
                )
                if result.returncode == 0:
                    # Extract version from output
                    output = result.stdout or result.stderr
                    # Take first line, first word
                    return output.strip().split("\n")[0].split()[0]
            except (subprocess.TimeoutExpired, FileNotFoundError):
                continue
        return None

    def _get_capabilities(self, tool_name: str) -> tuple[str, ...]:
        """Get tool capabilities based on name patterns.

        Args:
            tool_name: Name of tool.

        Returns:
            Tuple of capability identifiers.
        """
        capabilities = []

        # Compression tools
        if tool_name in ("zstd", "pzstd"):
            capabilities.append("zstd")
        if tool_name in ("gzip", "pigz"):
            capabilities.append("gzip")
        if tool_name in ("bzip2", "pbzip2"):
            capabilities.append("bzip2")
        if tool_name in ("xz", "pxz"):
            capabilities.append("xz")

        # Archive tools
        if tool_name == "tar":
            capabilities.append("tar")
        if tool_name in ("7z", "7za"):
            capabilities.append("7z")
        if tool_name == "zip":
            capabilities.append("zip")

        # Transfer tools
        if tool_name == "rsync":
            capabilities.append("rsync")
        if tool_name == "rclone":
            capabilities.append("rclone")
        if tool_name == "scp":
            capabilities.append("scp")

        # Hash tools
        if tool_name.startswith("sha"):
            capabilities.append("sha256" if "256" in tool_name else "sha512")
        if tool_name.startswith("xxh"):
            capabilities.append(tool_name)

        return tuple(capabilities)


def get_tool_detector() -> FallbackToolDetector:
    """Get the default tool detector implementation.
    
    Returns:
        FallbackToolDetector instance
    """
    return FallbackToolDetector()

