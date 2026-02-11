from __future__ import annotations

"""bkx.tui.overlays.filter_sheet

Step 4B:
Wrapper around mikomn FilterSheet providing a stable BKX class for styling.
"""

# ruff: noqa: E402 - mikomn imports must come after docstring
from mikomn_tui.base.overlays import FilterSheet


class BkxFilterSheet(FilterSheet):
    """BKX-styled FilterSheet."""

    DEFAULT_CLASSES = "filter-sheet bkx-filter-sheet"
