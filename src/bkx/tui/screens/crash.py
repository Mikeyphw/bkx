"""bkx/src/bkx/tui/screens/crash.py

Crash screen shown when an unhandled exception escapes the app.

feature contract enhancements:
- "Restart app" button with route persistence
- Enhanced "Copy traceback" button
- Enhanced "Export support bundle" button
- "What to do next" guidance line
- Persist last route before crash
"""

from __future__ import annotations

import sys

from textual.app import ComposeResult
from textual.containers import Horizontal, Vertical
from textual.screen import Screen
from textual.widgets import Button, RichLog, Static


class CrashScreen(Screen):
    """Display a traceback and allow exporting debug info.

    Enhanced with restart, guidance, and route persistence.
    """

    BINDINGS = [
        ("q", "quit", "Quit"),
        ("escape", "quit", "Quit"),
        ("r", "restart", "Restart"),
    ]

    def __init__(self, *, title: str, traceback_text: str, last_route: str = "backup") -> None:
        super().__init__()
        self._title = title
        self._traceback_text = traceback_text
        self._last_route = last_route

    def compose(self) -> ComposeResult:
        with Vertical(classes="bkx-crash"):
            yield Static(self._title, classes="bkx-crash-title")

            # What to do next guidance
            yield Static(
                "An unexpected error occurred. You can:\n• Copy the traceback to share for debugging\n• Export a support bundle with system info\n• Restart the app (your last page will be restored)",
                classes="bkx-crash-guidance",
                id="bkx-crash-guidance",
            )

            log = RichLog(
                id="bkx-crash-traceback",
                classes="bkx-crash-traceback",
                highlight=False,
                markup=False,
            )
            yield log

            with Horizontal(classes="bkx-crash-actions"):
                yield Button("📋 Copy traceback", id="bkx-crash-copy", tooltip="Copy traceback to clipboard")
                yield Button("📦 Export bundle", id="bkx-crash-bundle", tooltip="Export support bundle with logs and config")
                yield Button(
                    "🔄 Restart", id="bkx-crash-restart", variant="primary", tooltip=f"Restart app (last page: {self._last_route})"
                )
                yield Button("Quit", id="bkx-crash-quit", variant="error")

    def on_mount(self) -> None:
        self.query_one("#bkx-crash-traceback", RichLog).write(self._traceback_text)
        # Update restart button with route info
        restart_btn = self.query_one("#bkx-crash-restart", Button)
        restart_btn.tooltip = f"Restart and return to {self._last_route} page"

    def action_quit(self) -> None:
        self.app.exit(1)

    def action_restart(self) -> None:
        """Restart the app with the last route."""
        # Persist the route one more time before restart
        try:
            from bkx.tui.state import load_state, save_state

            state = load_state()
            state.last_route = self._last_route
            save_state(state)
        except Exception:
            pass

        # Restart the app by exiting with result 0 and setting run=True
        self.app.exit(result=0)
        # Note: The run=True behavior is handled by the caller checking exit code

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "bkx-crash-copy":
            self._copy_traceback()
            return

        if event.button.id == "bkx-crash-bundle":
            self._export_bundle()
            return

        if event.button.id == "bkx-crash-restart":
            self.action_restart()
            return

        if event.button.id == "bkx-crash-quit":
            self.action_quit()

    def _copy_traceback(self) -> None:
        """Enhanced copy traceback with details."""
        full_text = f"""BKX Crash Report
==============
Time: {self._get_timestamp()}
Last Route: {self._last_route}
Python: {sys.version}

Traceback:
{self._traceback_text}
"""
        try:
            if hasattr(self.app, "copy_to_clipboard"):
                self.app.copy_to_clipboard(full_text)
                self.notify("Crash report copied to clipboard", severity="information")
                return
        except Exception:
            pass

        try:
            from pathlib import Path

            out = Path("/tmp/bkx_crash_report.txt")
            out.write_text(full_text, encoding="utf-8")
            self.app.notify(f"Crash report saved: {out}", severity="information")
        except Exception:
            self.app.notify("Failed to copy crash report", severity="error")

    def _export_bundle(self) -> None:
        """Export support bundle with enhanced info."""
        try:
            # Check if app has the action_export_support_bundle method
            # Using getattr to avoid pyright attribute access issues
            export_fn = getattr(self.app, "action_export_support_bundle", None)
            if callable(export_fn):
                export_fn()
                return
        except Exception:
            pass
        self.app.notify("Failed to export bundle", severity="error")

    def _get_timestamp(self) -> str:
        """Get current timestamp."""
        import datetime

        return datetime.datetime.now().isoformat()
