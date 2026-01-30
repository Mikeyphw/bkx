"""Tools screen - Utility tools."""

from __future__ import annotations

from textual.app import ComposeResult
from textual.containers import Container, Grid
from textual.screen import Screen
from textual.widgets import Button, Label, Static


class ToolsScreen(Screen):
    """Tools screen with utility operations."""
    
    def compose(self) -> ComposeResult:
        yield Static("🔧 Tools", classes="screen-header")
        
        yield Grid(
            # Verify tool
            Button(
                Static("🔍", classes="tool-icon") + 
                Static("Verify", classes="tool-name") + 
                Static("Verify archive integrity", classes="tool-desc"),
                id="tool-verify",
                classes="tool-button",
            ),
            # List tool
            Button(
                Static("📋", classes="tool-icon") + 
                Static("List", classes="tool-name") + 
                Static("List archive contents", classes="tool-desc"),
                id="tool-list",
                classes="tool-button",
            ),
            # Info tool
            Button(
                Static("ℹ️", classes="tool-icon") + 
                Static("Info", classes="tool-name") + 
                Static("Show archive metadata", classes="tool-desc"),
                id="tool-info",
                classes="tool-button",
            ),
            # Config tool
            Button(
                Static("⚙️", classes="tool-icon") + 
                Static("Config", classes="tool-name") + 
                Static("Manage configuration", classes="tool-desc"),
                id="tool-config",
                classes="tool-button",
            ),
            classes="tools-grid",
        )
        
        yield Container(
            Button("← Back", id="btn-back"),
        )
    
    def on_button_pressed(self, event: Button.Pressed) -> None:
        """Handle button presses."""
        button_id = event.button.id
        
        if button_id == "btn-back":
            self.app.pop_screen()
        elif button_id.startswith("tool-"):
            tool = button_id.replace("tool-", "")
            self.notify(f"Tool '{tool}' - Implementation pending", severity="info")

