"""Backup screen - Backup operation form."""

from __future__ import annotations

from pathlib import Path
from textual.app import ComposeResult
from textual.containers import Container, Vertical
from textual.screen import Screen
from textual.widgets import (
    Button,
    Checkbox,
    Input,
    Label,
    RichLog,
    Select,
    Static,
)

from bkx.core.config import get_config
from bkx.tui.messages import BackupIntent, FilesSelected


class BackupScreen(Screen):
    """Backup operation screen with form inputs.
    
    Features:
    - Source path selection
    - Destination path input
    - Archive name input
    - Exclude patterns
    - Compression settings
    - Verify option
    """
    
    CSS = """
    BackupScreen {
        background: $background;
    }
    
    .screen-header {
        height: 3;
        dock: top;
        background: $primary;
        content-align: center middle;
    }
    
    .screen-title {
        color: $foreground;
        font-size: 1.5;
        font-weight: bold;
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
    
    .form-input {
        width: 1fr;
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
    
    def __init__(self):
        super().__init__()
        self._sources: list[Path] = []
    
    def compose(self) -> ComposeResult:
        yield Static("💾 Backup", classes="screen-header")
        
        yield Container(
            # Source path
            Container(
                Label("Sources:", classes="form-label"),
                Input(placeholder="Paths to back up (space-separated)", classes="form-input", id="sources-input"),
                classes="form-row",
            ),
            # Destination path
            Container(
                Label("Destination:", classes="form-label"),
                Input(placeholder="Output path (auto-generated if empty)", classes="form-input", id="destination-input"),
                classes="form-row",
            ),
            # Archive name
            Container(
                Label("Name:", classes="form-label"),
                Input(placeholder="Archive name (auto-generated if empty)", classes="form-input", id="name-input"),
                classes="form-row",
            ),
            # Excludes
            Container(
                Label("Excludes:", classes="form-label"),
                Input(placeholder="*.pyc __pycache__ .git", classes="form-input", id="excludes-input"),
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
            # Level
            Container(
                Label("Level:", classes="form-label"),
                Input(placeholder="Compression level", classes="form-input", id="level-input"),
                classes="form-row",
            ),
            # Verify checkbox
            Container(
                Label("Options:", classes="form-label"),
                Checkbox("Verify archive after creation", id="verify-checkbox", value=True),
                classes="form-row",
            ),
            classes="form-container",
        )
        
        yield Container(
            Button("📁 Select Sources", id="btn-select-sources"),
            Button("🚀 Run Backup", id="btn-run"),
            Button("← Back", id="btn-back"),
            classes="button-row",
        )
    
    def on_mount(self) -> None:
        """Initialize form with defaults."""
        config = get_config()
        
        # Set default algorithm
        algorithm_select = self.query_one("#algorithm-select", Select)
        # Note: Select value setting depends on implementation
        
        # Set default level
        level_input = self.query_one("#level-input", Input)
        level_input.value = str(config.compression_level)
        
        # Set default verify
        verify_checkbox = self.query_one("#verify-checkbox", Checkbox)
        verify_checkbox.value = config.verify_after_operation
    
    def on_button_pressed(self, event: Button.Pressed) -> None:
        """Handle button presses."""
        button_id = event.button.id
        
        if button_id == "btn-select-sources":
            self._on_select_sources()
        elif button_id == "btn-run":
            self._on_run_backup()
        elif button_id == "btn-back":
            self.app.pop_screen()
    
    def _on_select_sources(self) -> None:
        """Handle source selection."""
        # Use mikomn's FileDirPicker via overlay
        self.notify("File picker integration pending", severity="info")
    
    def _on_run_backup(self) -> None:
        """Handle backup execution."""
        # Collect form data
        sources_input = self.query_one("#sources-input", Input)
        sources_text = sources_input.value.strip()
        
        if sources_text:
            self._sources = [Path(p.strip()) for p in sources_text.split() if p.strip()]
        else:
            self._sources = [Path(".")]
        
        destination_input = self.query_one("#destination-input", Input)
        destination = Path(destination_input.value.strip()) if destination_input.value.strip() else None
        
        name_input = self.query_one("#name-input", Input)
        archive_name = name_input.value.strip() if name_input.value.strip() else None
        
        excludes_input = self.query_one("#excludes-input", Input)
        excludes = excludes_input.value.strip().split() if excludes_input.value.strip() else []
        
        algorithm_select = self.query_one("#algorithm-select", Select)
        algorithm = algorithm_select.value or "zstd"
        
        level_input = self.query_one("#level-input", Input)
        try:
            level = int(level_input.value) if level_input.value else 3
        except ValueError:
            level = 3
        
        verify_checkbox = self.query_one("#verify-checkbox", Checkbox)
        verify = verify_checkbox.value
        
        # Create intent and dispatch
        intent = BackupIntent(
            sources=self._sources,
            destination=destination,
            archive_name=archive_name,
            excludes=excludes,
            algorithm=algorithm,
            level=level,
            verify=verify,
        )
        
        self.post_message(intent)

