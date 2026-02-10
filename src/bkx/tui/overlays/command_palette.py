from __future__ import annotations

"""bkx.tui.overlays.command_palette

Step 4A:
Thin wrapper over mikomn's CommandPalette.
Provides a stable BKX class for styling and leaves internal structure intact.
"""

# ruff: noqa: E402 - mikomn imports must come after docstring
from mikomn.tui_base.overlays import CommandPalette


class BkxCommandPalette(CommandPalette):
    """BKX-styled CommandPalette."""

    DEFAULT_CLASSES = "command-palette bkx-command-palette"
