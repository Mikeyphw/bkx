from __future__ import annotations

"""bkx.tui.overlays.notifications

Step 4A:
BKX wrapper around mikomn's NotificationCenterScreen.
Adds stable classes and attempts to focus the search input on mount.
"""

# ruff: noqa: E402 - mikomn and textual imports must come after docstring
from mikomn.tui_base.screens.notification_center import NotificationCenterScreen
from textual.widgets import Input


class BkxNotificationCenterScreen(NotificationCenterScreen):
    """BKX-styled notification center."""

    DEFAULT_CLASSES = "notification-center-screen bkx-notification-center-screen"

    def on_mount(self) -> None:
        super().on_mount()
        # Best-effort: focus the search input if present.
        for selector in ("#search-input", "#search", ".search-input"):
            try:
                self.query_one(selector, Input).focus()
                break
            except Exception:
                continue
