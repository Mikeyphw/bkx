"""Rendering layer (stdout only).

Contract rule:
- No print() scattered.
- All user output goes through renderer.
- Logs -> stderr.

Theme integration:
- Uses mikomn's unified theme system for consistent styling
- Rich markup styles applied based on active theme
"""

from __future__ import annotations

from .renderer import (
    JSONRenderer,
    TextRenderer,
    get_renderer,
    render_result,
)

__all__ = [
    "JSONRenderer",
    "TextRenderer",
    "get_renderer",
    "render_result",
]
