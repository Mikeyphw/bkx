"""BKX Textual TUI Package.

Provides TUI experience using Textual.
"""

from __future__ import annotations

try:
    from bkx.tui.app import BkxTuiApp  # noqa: F401
except Exception:
    # Optional import; keep package importable even when Textual isn't installed.
    pass
