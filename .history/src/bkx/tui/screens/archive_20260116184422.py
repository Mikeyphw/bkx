"""Archive screen - Archive creation form."""

from __future__ import annotations

from pathlib import Path
from textual.app import ComposeResult
from textual.containers import Container
from textual.screen import Screen
from textual.widgets import Button, Checkbox, Input, Label, Select, Static

from bkx.core.config import get_config
from bkx.tui.messages import ArchiveIntent


class ArchiveScreen(Screen):
    """Archive creation screen with form inputs."""
    
    CSS = """
    ArchiveScreen {
        background: $background;
    }
    
    .screen-header {
        height: 3;
        dock: top;
        background: $primary;
        content-align: center middle;
    }
    
    .form-container {
        width: 60;
        height: auto;
        dock: center;
        background: $surface;
        border: solid $secondary;
        padding: 1;
        margin: 1;
    }
    
    .form-row {
        height: auto;
        width: 1fr;
        margin-bottom: 1;
    }
    
    .form-label {
        color: $foreground;
        width: 20;
        text-align: right;
        margin-right: 1;
    }
    
    .button-row {
        height: 3;
        dock: bottom;
        align: center middle;
    }
    
    .button-row Button {
        margin: 0 1;
        min-width: 15;
    }
    """
    
    def compose(self) -> ComposeResult:
        yield Static("📦 Archive", classes="screen-header")
        
        yield Container(
            # Source path
            Container(
                Label("Sources:", classes="form-label"),
                Input(placeholder="Paths to archive (space-separated)", id="sources-input"),
                classes="form-row",
            ),
            # Output path
            Container(
                Label("Output:", classes="form-label"),
                Input(placeholder="Output archive path (required)", id="output-input"),
                classes="form-row",
            ),
            # Excludes
            Container(
                Label("Excludes:", classes="form-label"),
                Input(placeholder="*.pyc __pycache__ .git", id="excludes-input"),
                classes="form-row",
            ),
            # Algorithm
            Container(
                Label("Algorithm:", classes="form-label"),
                Select(
                    ("zstd", "zstd"),
                    ("gzip", "gzip"),
                    ("lz4", "lz4"),
                    ("xz", "xz"),
                    ("bzip2", "bzip2"),
                    prompt="Compression algorithm",
                    id="algorithm-select",
                ),
                classes="form-row",
            ),
            # Verify
            Container(
                Label("Options:", classes="form-label"),
                Checkbox("Verify archive after creation", id="verify-checkbox", value=True),
                classes="form-row",
            ),
            classes="form-container",
        )
        
        yield Container(
            Button("📁 Select Files", id="btn-select"),
            Button("🚀 Create Archive", id="btn-run"),
            Button("← Back", id="btn-back"),
            classes="button-row",
        )
    
    def on_mount(self) -> None:
        """Initialize form with defaults."""
        config = get_config()
        
        verify_checkbox = self.query_one("#verify-checkbox", Checkbox)
        verify_checkbox.value = config.verify_after_operation
    
    def on_button_pressed(self, event: Button.Pressed) -> None:
        """Handle button presses."""
        button_id = event.button.id
        
        if button_id == "btn-select":
            self.notify("File picker integration pending", severity="info")
        elif button_id == "btn-run":
            self._on_create_archive()
        elif button_id == "btn-back":
            self.app.pop_screen()
    
    def _on_create_archive(self) -> None:
        """Handle archive creation."""
        sources_input = self.query_one("#sources-input", Input)
        sources_text = sources_input.value.strip()
        
        sources = [Path(p.strip()) for p in sources_text.split()] if sources_text else [Path(".")]
        
        output_input = self.query_one("#output-input", Input)
        output = Path(output_input.value.strip()) if output_input.value.strip() else None
        
        excludes_input = self.query_one("#excludes-input", Input)
        excludes = excludes_input.value.strip().split() if excludes_input.value.strip() else []
        
        algorithm_select = self.query_one("#algorithm-select", Select)
        algorithm = algorithm_select.value or "zstd"
        
        verify_checkbox = self.query_one("#verify-checkbox", Checkbox)
        verify = verify_checkbox.value
        
        intent = ArchiveIntent(
            sources=sources,
            output=output,
            excludes=excludes,
            algorithm=algorithm,
            verify=verify,
        )
        
        self.post_message(intent)

