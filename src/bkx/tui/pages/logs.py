"""bkx/src/bkx/tui/pages/logs.py

Logs page.

The persistent Run Panel contains the live log. This page provides an expanded
log view (mirrors UpdateLog, toasts, and status notifications).
"""

from __future__ import annotations

from textual.app import ComposeResult
from textual.containers import Container
from textual.widgets import Static

from bkx.tui.panes.log import RunLogPane


class LogsPage(Container):
    """Logs page with an expanded log view."""

    DEFAULT_CLASSES = "bkx-page bkx-logs-page"

    def compose(self) -> ComposeResult:
        yield Static("📋 Logs", classes="page-title")
        yield RunLogPane(title="Logs", id="bkx-logs-log")
