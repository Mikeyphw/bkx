"""Bounded fallback optional-tool detection."""

from __future__ import annotations

import os
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping

from bkx.core.errors import ValidationError

from ..ports.tool_detector import KNOWN_TOOLS, PREFERRED_TOOLS, ToolInfo

_CATEGORY_ALIASES = {"hasher": "hashing", "compressor": "compression"}
_VERSION_RE = re.compile(r"(?P<version>\d+(?:\.\d+)+(?:[-+._~A-Za-z0-9]*)?)")


@dataclass(frozen=True)
class ToolProbeRecord:
    """Cached detection plus the environment fingerprint it was derived from."""

    info: ToolInfo
    fingerprint: tuple[str, str | None]


class FallbackToolDetector:
    """Detect optional command-line tools using bounded probes.

    The detector intentionally considers only registry entries and executable
    files, parses real version-like tokens from command output, and invalidates
    its cache when ``PATH`` changes.
    """

    def __init__(
        self,
        preferences: dict[str, str | None] | None = None,
        *,
        environ: Mapping[str, str] | None = None,
        timeout_seconds: float = 2.0,
    ) -> None:
        if timeout_seconds <= 0:
            raise ValidationError(msg="tool probe timeout must be positive", details={"timeout_seconds": timeout_seconds})
        self._cache: dict[str, ToolProbeRecord] = {}
        self._preferences = {_canonical_category(k): v for k, v in dict(preferences or {}).items() if v}
        self._environ = os.environ if environ is None else environ
        self._timeout_seconds = timeout_seconds

    @property
    def timeout_seconds(self) -> float:
        return self._timeout_seconds

    def clear_cache(self) -> None:
        """Explicitly invalidate all cached probe results."""
        self._cache.clear()

    def detect(self, tool_name: str) -> ToolInfo:
        """Detect one registered tool using a bounded subprocess probe."""
        name = _normalize_tool_name(tool_name)
        fingerprint = self._fingerprint(name)
        cached = self._cache.get(name)
        if cached is not None and cached.fingerprint == fingerprint:
            return cached.info

        tool_path = _which(name, self._environ.get("PATH"))
        if tool_path is None:
            info = ToolInfo(name=name, path=None, version=None, available=False, capabilities=())
        else:
            path = Path(tool_path)
            if not path.is_file() or not os.access(path, os.X_OK):
                info = ToolInfo(name=name, path=None, version=None, available=False, capabilities=())
            else:
                info = ToolInfo(
                    name=name,
                    path=path,
                    version=self._get_version(name, path),
                    available=True,
                    capabilities=_capabilities(name),
                )
        self._cache[name] = ToolProbeRecord(info=info, fingerprint=fingerprint)
        return info

    def detect_all(self, tool_names: tuple[str, ...]) -> dict[str, ToolInfo]:
        return {name: self.detect(name) for name in tool_names}

    def list_available(self) -> tuple[str, ...]:
        return tuple(name for name in sorted(KNOWN_TOOLS) if self.detect(name).available)

    def get_preferred(self, tool_category: str) -> str | None:
        category = _canonical_category(tool_category)
        if category not in PREFERRED_TOOLS:
            raise ValidationError(msg="Unknown tool category", details={"category": tool_category})
        configured = self._preferences.get(category)
        if configured:
            info = self.detect(configured)
            return configured if info.available and _tool_matches_category(configured, category) else None
        for tool_name in PREFERRED_TOOLS[category]:
            info = self.detect(tool_name)
            if info.available and _tool_matches_category(tool_name, category):
                return tool_name
        return None

    def _fingerprint(self, tool_name: str) -> tuple[str, str | None]:
        return (self._environ.get("PATH", ""), _which(tool_name, self._environ.get("PATH")))

    def _get_version(self, tool_name: str, tool_path: Path) -> str | None:
        for version_flag in ("--version", "-V", "version"):
            try:
                result = subprocess.run(
                    [str(tool_path), version_flag],
                    capture_output=True,
                    text=True,
                    timeout=self._timeout_seconds,
                    env=dict(self._environ),
                    check=False,
                )
            except (subprocess.TimeoutExpired, FileNotFoundError, PermissionError, OSError):
                continue
            output = f"{result.stdout}\n{result.stderr}".strip()
            if not output:
                continue
            match = _VERSION_RE.search(output)
            if match:
                return match.group("version")
            first = output.splitlines()[0].strip()
            return first or None
        return None


def _normalize_tool_name(tool_name: str) -> str:
    if not isinstance(tool_name, str) or not tool_name.strip():
        raise ValidationError(msg="tool_name must be a non-empty string")
    return tool_name.strip()


def _which(tool_name: str, path: str | None) -> str | None:
    try:
        return shutil.which(tool_name, path=path)
    except TypeError:
        # Some tests monkeypatch shutil.which with the legacy one-argument shape.
        return shutil.which(tool_name)


def _canonical_category(category: str) -> str:
    return _CATEGORY_ALIASES.get(category, category)


def _capabilities(tool_name: str) -> tuple[str, ...]:
    capabilities: list[str] = []
    if tool_name in ("zstd",):
        capabilities.extend(("compression", "zstd"))
    if tool_name in ("gzip", "pigz"):
        capabilities.extend(("compression", "gzip"))
    if tool_name in ("bzip2", "pbzip2"):
        capabilities.extend(("compression", "bzip2"))
    if tool_name in ("xz",):
        capabilities.extend(("compression", "xz"))
    if tool_name in ("tar",):
        capabilities.extend(("archiver", "tar"))
    if tool_name in ("7z", "7za"):
        capabilities.extend(("archiver", "7z"))
    if tool_name == "zip":
        capabilities.extend(("archiver", "zip"))
    if tool_name in ("rsync", "rclone", "scp", "cp"):
        capabilities.extend(("transfer", tool_name))
    if tool_name.startswith("sha") or tool_name.startswith("md5") or tool_name.startswith("xxh"):
        capabilities.append("hashing")
        if "256" in tool_name:
            capabilities.append("sha256")
        elif "512" in tool_name:
            capabilities.append("sha512")
        elif tool_name.startswith("md5"):
            capabilities.append("md5")
        else:
            capabilities.append(tool_name)
    if tool_name == "python":
        capabilities.append("runtime")
    return tuple(dict.fromkeys(capabilities))


def _tool_matches_category(tool_name: str, category: str) -> bool:
    if tool_name not in KNOWN_TOOLS:
        # A user-configured executable is accepted for its configured category;
        # the service/factory still falls back when it cannot adapt it.
        return True
    known_category = KNOWN_TOOLS[tool_name][0]
    if category == known_category:
        return True
    capabilities = _capabilities(tool_name)
    return category in capabilities or (category == "archiver" and "archiver" in capabilities)


def get_tool_detector() -> FallbackToolDetector:
    """Get the default detector with live BKX configuration preferences."""
    from ..config import get_config

    return FallbackToolDetector(preferences=get_config().get_tool_preferences())
