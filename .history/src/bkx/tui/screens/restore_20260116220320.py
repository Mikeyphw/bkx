"""Restore screen - Archive restore form."""

from __future__ import annotations

from pathlib import Path
from textual.app import ComposeResult
from textual.containers import Container
from textual.screen import Screen
from textual.widgets import Button, Checkbox, Input, Label, Static

from bkx.tui.messages import RestoreIntent


class RestoreScreen(Screen):
    """Restore operation screen."""
    
    def compose(self) -> ComposeResult:
        yield Static("📥 Restore", classes="screen-header")
        
        yield Container(
            # Archive path
            Container(
                Label("Archive:", classes="form-label"),
                Input(placeholder="Archive path to restore from", id="archive-input"),
                classes="form-row",
            ),
            # Destination
            Container(
                Label("Destination:", classes="form-label"),
                Input(placeholder="Destination path (creates dir if empty)", id="destination-input"),
                classes="form-row",
            ),
            # Fresh dir
            Container(
                Label("Options:", classes="form-label"),
                Checkbox("Create fresh directory with timestamp", id="fresh-checkbox", value=True),
                classes="form-row",
            ),
            # Overwrite
            Container(
                Label("", classes="form-label"),
                Checkbox("Allow overwriting existing files", id="overwrite-checkbox"),
                classes="form-row",
            ),
            # Verify
            Container(
                Label("", classes="form-label"),
                Checkbox("Verify archive before restore", id="verify-checkbox", value=True),
                classes="form-row",
            ),
            classes="form-container",
        )
        
        yield Container(
            Button("📁 Select Archive", id="btn-select"),
            Button("🚀 Restore", id="btn-run"),
            Button("← Back", id="btn-back"),
            classes="button-row",
        )
    
    def on_button_pressed(self, event: Button.Pressed) -> None:
        """Handle button presses."""
        button_id = event.button.id
        
        if button_id == "btn-select":
            self.notify("Archive picker integration pending", severity="info")
        elif button_id == "btn-run":
            self._on_restore()
        elif button_id == "btn-back":
            self.app.pop_screen()
    
    def _on_restore(self) -> None:
        """Handle restore execution."""
        archive_input = self.query_one("#archive-input", Input)
        archive = Path(archive_input.value.strip())
        
        destination_input = self.query_one("#destination-input", Input)
        destination = Path(destination_input.value.strip()) if destination_input.value.strip() else None
        
        fresh_checkbox = self.query_one("#fresh-checkbox", Checkbox)
        fresh_dir = fresh_checkbox.value
        
        overwrite_checkbox = self.query_one("#overwrite-checkbox", Checkbox)
        overwrite = overwrite_checkbox.value
        
        verify_checkbox = self.query_one("#verify-checkbox", Checkbox)
        verify = verify_checkbox.value
        
        intent = RestoreIntent(
            archive=archive,
            destination=destination,
            fresh_dir=fresh_dir,
            overwrite=overwrite,
            verify=verify,
        )
        
        self.post_message(intent)

