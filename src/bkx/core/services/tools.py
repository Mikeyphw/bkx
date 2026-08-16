"""Tool inspection service; interfaces never construct detector implementations."""

from __future__ import annotations

from typing import TYPE_CHECKING

from ..results.tools import ToolDetectionResult, ToolExplainResult
from ..specs.tools import ToolDetectSpec, ToolExplainSpec

if TYPE_CHECKING:
    from ..ports.tool_detector import ToolDetectorPort


class ToolService:
    def __init__(self, *, detector: ToolDetectorPort) -> None:
        self._detector = detector

    def detect(self, spec: ToolDetectSpec) -> ToolDetectionResult:
        return ToolDetectionResult(tools=self._detector.detect_all(spec.tool_names))

    def explain(self, spec: ToolExplainSpec) -> ToolExplainResult:
        return ToolExplainResult(preferred={category: self._detector.get_preferred(category) for category in spec.categories})
