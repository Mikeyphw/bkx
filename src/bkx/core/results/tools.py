"""Structured results for tool inspection services."""

from __future__ import annotations

from dataclasses import dataclass

from ..ports.tool_detector import ToolInfo
from .common import FrozenMapping, frozen_mapping


@dataclass(frozen=True, kw_only=True)
class ToolDetectionResult:
    tools: FrozenMapping

    def __post_init__(self) -> None:
        data = frozen_mapping(dict(self.tools))
        if any(not isinstance(value, ToolInfo) for value in data.values()):
            raise ValueError("tools values must be ToolInfo")
        object.__setattr__(self, "tools", data)


@dataclass(frozen=True, kw_only=True)
class ToolExplainResult:
    preferred: FrozenMapping

    def __post_init__(self) -> None:
        data = frozen_mapping(dict(self.preferred))
        if any(value is not None and not isinstance(value, str) for value in data.values()):
            raise ValueError("preferred tool values must be strings or None")
        object.__setattr__(self, "preferred", data)
