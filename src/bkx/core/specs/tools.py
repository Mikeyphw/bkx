"""Specs for tool detection/explanation service operations."""

from __future__ import annotations

from dataclasses import dataclass

from bkx.core.errors import ValidationError


@dataclass(frozen=True, kw_only=True)
class ToolDetectSpec:
    tool_names: tuple[str, ...] = ("zstd", "tar", "rsync", "rclone", "sha256sum")

    def __post_init__(self) -> None:
        if not isinstance(self.tool_names, tuple) or not self.tool_names:
            raise ValidationError(msg="tool_names must be a non-empty tuple")
        normalized = tuple(name.strip() for name in self.tool_names if isinstance(name, str) and name.strip())
        if len(normalized) != len(self.tool_names):
            raise ValidationError(msg="tool_names entries must be non-empty strings")
        object.__setattr__(self, "tool_names", normalized)


@dataclass(frozen=True, kw_only=True)
class ToolExplainSpec:
    categories: tuple[str, ...] = ("archiver", "compression", "hashing", "transfer")

    def __post_init__(self) -> None:
        valid = {"archiver", "compression", "hashing", "transfer"}
        if not isinstance(self.categories, tuple) or not self.categories or any(category not in valid for category in self.categories):
            raise ValidationError(msg="Invalid tool preference category", details={"categories": list(self.categories) if isinstance(self.categories, tuple) else None})
