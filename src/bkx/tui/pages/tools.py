"""bkx/src/bkx/tui/pages/tools.py

Tools page (dashboard).

Uses BKX panes built on mikomn widgets:
- Status cards overview
- Theme/settings surface (read-only reflection of current config for now)
"""

from __future__ import annotations

from textual.app import ComposeResult
from textual.containers import Container
from textual.widgets import Static

from bkx.core.config import get_config
from bkx.tui.panes.status_cards import ToolsDashboardPane
from bkx.ui.theme import get_current_theme_name


class ToolsPage(Container):
    """Tools and settings page."""

    DEFAULT_CLASSES = "bkx-page bkx-tools-page"

    def compose(self) -> ComposeResult:
        yield Static("🔧 Tools", classes="page-title")
        yield ToolsDashboardPane(id="bkx-tools-dashboard")

    def on_mount(self) -> None:
        cfg = get_config()
        theme = get_current_theme_name()

        try:
            dash = self.query_one("#bkx-tools-dashboard", ToolsDashboardPane)
        except Exception:
            return

        dash.update_setting("theme", theme)
        dash.update_setting("compression", cfg.compression_algorithm)
        dash.update_setting("verify", "Enabled" if cfg.verify_after_operation else "Disabled")
        dash.update_setting("level", str(cfg.compression_level))

        dash.update_status_card("theme", theme, "Current color scheme", "info")
        dash.update_status_card("compression", cfg.compression_algorithm, "Default algorithm", "success")
        dash.update_status_card(
            "verification",
            "Enabled" if cfg.verify_after_operation else "Disabled",
            "Verify after ops",
            "info",
        )
        dash.update_status_card("level", str(cfg.compression_level), "Compression level", "info")
