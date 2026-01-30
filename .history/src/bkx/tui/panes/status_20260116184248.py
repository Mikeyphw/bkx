"""Status footer pane - Status bar with key hints."""

from __future__ import annotations

from textual.app import ComposeResult
from textual.containers import Container
from textual.widgets import Label

from bkx.ui.theme import get_current_theme_name


class StatusFooterPane(Container):
    """Status footer pane with key binding hints and status info.
    
    Features:
    - Theme indicator
    - Key binding hints
    - Operation status
    - Busy indicator
    """
    
    CSS = """
    StatusFooterPane {
        height: 2;
        dock: bottom;
        background: $surface;
        color: $foreground-muted;
    }
    
    .status-section {
        width: 1fr;
        padding: 0 1;
        align: center middle;
    }
    
    .status-left {
        align: left middle;
    }
    
    .status-center {
        align: center middle;
    }
    
    .status-right {
        align: right middle;
    }
    
    .status-indicator {
        color: $success;
    }
    
    .status-indicator.busy {
        color: $warning;
        animation: blink 1s infinite;
    }
    
    .key-hint {
        color: $accent;
        bold: true;
    }
    
    .key-separator {
        color: $foreground-muted;
        margin: 0 0.5;
    }
    
    @keyframes blink {
        0%, 100% { opacity: 1; }
        50% { opacity: 0.5; }
    }
    """
    
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self._status_text = "Ready"
        self._is_busy = False
    
    def compose(self) -> ComposeResult:
        yield Label("Ctrl+K: Commands  |  B: Backup  |  A: Archive  |  R: Restore  |  T: Theme", classes="status-section status-center")
        yield Label(id="status-indicator", classes="status-section status-right")
    
    def on_mount(self) -> None:
        """Update status on mount."""
        self.update_status()
    
    def update_status(self, text: str | None = None, is_busy: bool | None = None) -> None:
        """Update the status indicator.
        
        Args:
            text: Status text to display
            is_busy: Whether operation is in progress
        """
        if text is not None:
            self._status_text = text
        if is_busy is not None:
            self._is_busy = is_busy
        
        indicator = self.query_one("#status-indicator", Label)
        theme = get_current_theme_name()
        
        if self._is_busy:
            indicator.update(f"⏳ {self._status_text} | Theme: {theme}")
            indicator.add_class("busy")
        else:
            indicator.update(f"✅ {self._status_text} | Theme: {theme}")
            indicator.remove_class("busy")
    
    def set_ready(self) -> None:
        """Set status to ready."""
        self.update_status(text="Ready", is_busy=False)
    
    def set_busy(self, message: str = "Processing...") -> None:
        """Set status to busy.
        
        Args:
            message: Status message to display
        """
        self.update_status(text=message, is_busy=True)
    
    def set_success(self, message: str) -> None:
        """Set status to success.
        
        Args:
            message: Success message
        """
        self.update_status(text=message, is_busy=False)
    
    def set_error(self, message: str) -> None:
        """Set status to error.
        
        Args:
            message: Error message
        """
        self.update_status(text=f"Error: {message}", is_busy=False)
    
    def on_update_status(self, event) -> None:
        """Handle external status update messages."""
        self.update_status(text=event.message, is_busy=event.is_busy if hasattr(event, "is_busy") else False)

