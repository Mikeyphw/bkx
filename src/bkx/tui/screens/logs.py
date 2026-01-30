"""Logs screen - View run logs."""

from __future__ import annotations

from textual.app import ComposeResult
from textual.containers import Container
from textual.screen import Screen
from textual.widgets import Button, Static

from bkx.tui.panes.log import RunLogPane


class LogsScreen(Screen):
    """Logs screen with full log viewer."""
    
    def compose(self) -> ComposeResult:
        yield Static("📋 Run Logs", classes="screen-header")
        
        yield RunLogPane(
            title="Full Log View",
            max_lines=5000,
            auto_scroll=True,
            id="full-log",
        )
        
        yield Container(
            Button("⏸️ Pause", id="btn-pause"),
            Button("▶️ Resume", id="btn-resume"),
            Button("🗑️ Clear", id="btn-clear"),
            Button("← Back", id="btn-back"),
            classes="button-row",
        )
    
    def on_button_pressed(self, event: Button.Pressed) -> None:
        """Handle button presses."""
        button_id = event.button.id
        
        log_pane = self.query_one("#full-log", RunLogPane)
        
        if button_id == "btn-pause":
            log_pane.toggle_pause()
        elif button_id == "btn-resume":
            log_pane.toggle_pause()
        elif button_id == "btn-clear":
            log_pane.clear()
        elif button_id == "btn-back":
            self.app.pop_screen()

