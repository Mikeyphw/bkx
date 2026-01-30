"""Transfer screen - File transfer form."""

from __future__ import annotations

from pathlib import Path
from textual.app import ComposeResult
from textual.containers import Container
from textual.screen import Screen
from textual.widgets import Button, Checkbox, Input, Label, RadioButton, RadioSet, Static

from bkx.tui.messages import TransferIntent


class TransferScreen(Screen):
    """Transfer operation screen."""
    
    def compose(self) -> ComposeResult:
        yield Static("🔄 Transfer", classes="screen-header")
        
        yield Container(
            # Direction
            Container(
                Label("Direction:", classes="form-label"),
                RadioSet(
                    RadioButton("Push (local → remote)", id="dir-push", value=True),
                    RadioButton("Pull (remote → local)", id="dir-pull"),
                    id="direction-radio",
                ),
                classes="form-row",
            ),
            # Source
            Container(
                Label("Source:", classes="form-label"),
                Input(placeholder="Source path", id="source-input"),
                classes="form-row",
            ),
            # Destination
            Container(
                Label("Destination:", classes="form-label"),
                Input(placeholder="Destination path", id="destination-input"),
                classes="form-row",
            ),
            # Verify
            Container(
                Label("Options:", classes="form-label"),
                Checkbox("Verify after transfer", id="verify-checkbox", value=True),
                classes="form-row",
            ),
            classes="form-container",
        )
        
        yield Container(
            Button("📁 Select Source", id="btn-source"),
            Button("📁 Select Dest", id="btn-dest"),
            Button("🚀 Transfer", id="btn-run"),
            Button("← Back", id="btn-back"),
            classes="button-row",
        )
    
    def on_button_pressed(self, event: Button.Pressed) -> None:
        """Handle button presses."""
        button_id = event.button.id
        
        if button_id in ("btn-source", "btn-dest"):
            self.notify("File picker integration pending", severity="info")
        elif button_id == "btn-run":
            self._on_transfer()
        elif button_id == "btn-back":
            self.app.pop_screen()
    
    def _on_transfer(self) -> None:
        """Handle transfer execution."""
        direction_radio = self.query_one("#direction-radio", RadioSet)
        direction = "push" if direction_radio.value == "dir-push" else "pull"
        
        source_input = self.query_one("#source-input", Input)
        source = Path(source_input.value.strip())
        
        dest_input = self.query_one("#destination-input", Input)
        destination = Path(dest_input.value.strip())
        
        verify_checkbox = self.query_one("#verify-checkbox", Checkbox)
        verify = verify_checkbox.value
        
        intent = TransferIntent(
            source=source,
            destination=destination,
            direction=direction,
            verify=verify,
        )
        
        self.post_message(intent)

