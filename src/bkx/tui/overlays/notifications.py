from __future__ import annotations

from textual.app import ComposeResult
from textual.screen import ModalScreen
from textual.widgets import Input, Static


class BkxNotificationCenterScreen(ModalScreen):
    """Legacy Textual notification center placeholder."""

    DEFAULT_CLASSES = "notification-center-screen bkx-notification-center-screen"

    def __init__(self, *, title: str = "Notifications", **kwargs) -> None:
        super().__init__(**kwargs)
        self.title = title

    def compose(self) -> ComposeResult:
        yield Static(self.title, classes="notification-center-title")

    def on_mount(self) -> None:
        for selector in ("#search-input", "#search", ".search-input"):
            try:
                self.query_one(selector, Input).focus()
                break
            except Exception:
                continue
