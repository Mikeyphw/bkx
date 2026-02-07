from __future__ import annotations

"""bkx.tui.overlays.help_overlay

Step 4B:
Wrapper around mikomn HelpOverlay providing a stable BKX class for styling.
"""

from mikomn.tui_base.overlays import HelpOverlay


class BkxHelpOverlay(HelpOverlay):
    """BKX-styled HelpOverlay."""

    DEFAULT_CLASSES = "help-overlay bkx-help-overlay"
