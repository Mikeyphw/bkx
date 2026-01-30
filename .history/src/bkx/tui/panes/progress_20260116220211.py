"""Progress pane - Progress tracking for operations.

Uses mikomn's GlobalProgressBar and StatusBar for progress tracking.
"""

from __future__ import annotations

from typing import Optional

from textual.app import ComposeResult
from textual.containers import Container, Horizontal, Vertical
from textual.message import Message
from textual.widgets import Label, Static

# mikomn TUI base widgets for enhanced UI
try:
    from mikomn.tui_base.widgets import GlobalProgressBar, StatusBar, StatusMessage
    from mikomn.tui_base.panes import ProgressPane as MikomnProgressPane
    _HAS_MIKOMN = True
except ImportError:
    _HAS_MIKOMN = False


if _HAS_MIKOMN:
    # Use mikomn's progress widgets when available
    class ProgressPane(MikomnProgressPane):
        """Progress pane using mikomn's ProgressPane."""
        
        class ProgressUpdate(Message):
            """Progress was updated."""
        
        class ProgressComplete(Message):
            """Progress operation completed."""
        
        def __init__(
            self,
            title: str = "Progress",
            show_percentage: bool = True,
            show_speed: bool = True,
            show_eta: bool = True,
            **kwargs,
        ):
            super().__init__(
                title=title,
                show_percentage=show_percentage,
                show_speed=show_speed,
                show_eta=show_eta,
                **kwargs
            )
            self._operation_name = ""
        
        def start_progress(
            self,
            operation: str,
            total: int = 100,
            message: str = "",
        ) -> None:
            """Start a progress operation.
            
            Args:
                operation: Name of the operation
                total: Total units for progress
                message: Status message
            """
            self._operation_name = operation
            self.set_total(total)
            if message:
                self.set_status(StatusMessage(text=message, severity="info"))
        
        def update_progress(
            self,
            current: int,
            message: str = "",
            speed: Optional[str] = None,
            eta: Optional[str] = None,
        ) -> None:
            """Update progress.
            
            Args:
                current: Current progress value
                message: Status message
                speed: Transfer speed string
                eta: Estimated time remaining
            """
            self.advance(current)
            if message:
                self.set_status(StatusMessage(text=message, severity="info"))
            if speed:
                self.set_speed(speed)
            if eta:
                self.set_eta(eta)
        
        def complete_progress(self, message: str = "Complete") -> None:
            """Complete the progress operation.
            
            Args:
                message: Completion message
            """
            self.set_total(100)
            self.advance(100)
            self.set_status(StatusMessage(text=message, severity="success"))
            self.post_message(self.ProgressComplete(operation=self._operation_name))
        
        def error_progress(self, message: str = "Error") -> None:
            """Mark progress as error.
            
            Args:
                message: Error message
            """
            self.set_status(StatusMessage(text=message, severity="error"))
            self.post_message(self.ProgressComplete(operation=self._operation_name))
    
    class StatusBarPane(Container):
        """Status bar pane using mikomn's StatusBar."""
        
        def __init__(self, **kwargs):
            super().__init__(**kwargs)
            self._status_bar = StatusBar()
        
        def compose(self) -> ComposeResult:
            yield self._status_bar
        
        def display_status(
            self,
            message: str,
            severity: str = "info",
            icon: str = "ℹ️",
        ) -> None:
            """Display a status message.
            
            Args:
                message: Status message text
                severity: Message severity (info, success, warning, error)
                icon: Icon to display
            """
            severity_map = {
                "info": "info",
                "success": "success",
                "warning": "warning",
                "error": "error",
            }
            sev = severity_map.get(severity, "info")
            self._status_bar.display_status(
                StatusMessage(text=f"{icon} {message}", severity=sev)
            )
        
        def clear_status(self) -> None:
            """Clear the status message."""
            self._status_bar.clear_status()

else:
    # Fallback implementation without mikomn
    class ProgressPane(Container):
        """Progress pane with custom progress bar implementation."""
        
        class ProgressUpdate(Message):
            """Progress was updated."""
        
        class ProgressComplete(Message):
            """Progress operation completed."""
        
        def __init__(
            self,
            title: str = "Progress",
            **kwargs,
        ):
            super().__init__(**kwargs)
            self._operation_name = ""
            self._total = 100
            self._current = 0
            self._message = ""
            self._speed = ""
            self._eta = ""
        
        def compose(self) -> ComposeResult:
            yield Container(
                Label("", classes="progress-operation", id="operation-name"),
                Label("", classes="progress-message", id="message"),
                classes="progress-header",
            )
            yield Container(
                Static(classes="progress-bar-fill", id="progress-fill"),
                classes="progress-bar-container",
            )
            yield Container(
                Label("", id="progress-details", classes="progress-stats progress-details"),
                Label("0%", id="percentage", classes="progress-stats progress-percentage"),
                classes="progress-stats",
            )
        
        def on_mount(self) -> None:
            self._operation_label = self.query_one("#operation-name", Label)
            self._message_label = self.query_one("#message", Label)
            self._progress_fill = self.query_one("#progress-fill", Static)
            self._details_label = self.query_one("#progress-details", Label)
            self._percentage_label = self.query_one("#percentage", Label)
        
        def start_progress(
            self,
            operation: str,
            total: int = 100,
            message: str = "",
        ) -> None:
            """Start a progress operation."""
            self._operation_name = operation
            self._total = total
            self._current = 0
            self._message = message
            self._operation_label.update(operation)
            self._message_label.update(message)
            self._update_progress_ui()
            self._progress_fill.add_class("progress-active")
        
        def update_progress(
            self,
            current: int,
            message: str = "",
            speed: str = "",
            eta: str = "",
        ) -> None:
            """Update progress."""
            self._current = current
            if message:
                self._message = message
                self._message_label.update(message)
            if speed:
                self._speed = speed
            if eta:
                self._eta = eta
            self._update_progress_ui()
            self.post_message(self.ProgressUpdate(
                operation=self._operation_name,
                current=current,
                total=self._total,
                percent=current / self._total if self._total > 0 else 0,
            ))
        
        def _update_progress_ui(self) -> None:
            """Update the progress UI."""
            percent = (self._current / self._total * 100) if self._total > 0 else 0
            self._progress_fill.update_styles(width=f"{percent}%")
            self._percentage_label.update(f"{percent:.1f}%")
            
            details = []
            if self._speed:
                details.append(f"Speed: {self._speed}")
            if self._eta:
                details.append(f"ETA: {self._eta}")
            if details:
                self._details_label.update(" | ".join(details))
        
        def complete_progress(self, message: str = "Complete") -> None:
            """Complete the progress operation."""
            self._current = self._total
            self._update_progress_ui()
            self._message_label.update(message)
            self._progress_fill.remove_class("progress-active")
            self.post_message(self.ProgressComplete(operation=self._operation_name))
        
        def error_progress(self, message: str = "Error") -> None:
            """Mark progress as error."""
            self._message_label.update(message)
            self._progress_fill.add_class("error")
            self._progress_fill.remove_class("progress-active")
            self.post_message(self.ProgressComplete(operation=self._operation_name))
    
    class StatusBarPane(Container):
        """Simple status bar pane."""
        
        CSS = """
        StatusBarPane {
            height: 2;
            dock: bottom;
            background: $surface-darken-1;
            color: $foreground-muted;
        }
        
        .status-left {
            width: 1fr;
            align: left middle;
        }
        
        .status-right {
            width: 30;
            align: right middle;
        }
        """
        
        def __init__(self, **kwargs):
            super().__init__(**kwargs)
            self._current_status = ""
        
        def compose(self) -> ComposeResult:
            yield Label(id="status-left", classes="status-left")
            yield Label(id="status-right", classes="status-right")
        
        def on_mount(self) -> None:
            self._left_label = self.query_one("#status-left", Label)
            self._right_label = self.query_one("#status-right", Label)
        
        def display_status(
            self,
            message: str,
            severity: str = "info",
            icon: str = "ℹ️",
        ) -> None:
            """Display a status message."""
            icon_map = {
                "info": "ℹ️",
                "success": "✅",
                "warning": "⚠️",
                "error": "❌",
            }
            display_icon = icon_map.get(severity, icon)
            self._current_status = f"{display_icon} {message}"
            self._left_label.update(self._current_status)
        
        def set_right_content(self, content: str) -> None:
            """Set content on the right side."""
            self._right_label.update(content)
        
        def clear_status(self) -> None:
            """Clear the status message."""
            self._current_status = ""
            self._left_label.update("")


# Convenience function to create progress tracking callbacks
def create_progress_callback(progress_pane: ProgressPane, operation: str):
    """Create a progress callback function for service operations.
    
    Args:
        progress_pane: ProgressPane instance
        operation: Operation name
        
    Returns:
        Callback function for progress updates
    """
    def callback(progress):
        progress_pane.update_progress(
            current=progress.current_file if hasattr(progress, 'current_file') else progress.percent * 100,
            speed=f"{progress.bytes_current / 1024 / 1024:.1f} MB/s" if hasattr(progress, 'bytes_current') else None,
        )
    return callback


class TransferProgressPane(Container):
    """Specialized pane for transfer operations with speed and ETA."""
    
    CSS = """
    TransferProgressPane {
        height: 10;
        dock: bottom;
        background: $surface;
        border: solid $accent;
    }
    
    .transfer-header {
        height: 3;
        background: $primary;
        color: $foreground;
        content-align: center middle;
    }
    
    .transfer-file {
        height: 2;
        dock: top;
        color: $foreground;
        padding: 0 1;
    }
    
    .transfer-bar {
        height: 4;
        dock: top;
        background: $surface-darken-2;
    }
    
    .transfer-fill {
        height: 100%;
        background: $accent;
        width: 0%;
    }
    
    .transfer-stats {
        height: 2;
        dock: bottom;
        background: $surface-darken-1;
        color: $foreground-muted;
    }
    
    .stat-label {
        color: $foreground-muted;
    }
    
    .stat-value {
        color: $foreground;
    }
    """
    
    class TransferProgressUpdate(Message):
        """Transfer progress update."""
        
        def __init__(
            self,
            bytes_transferred: int,
            total_bytes: int,
            percent: float,
            speed: float = 0.0,
        ):
            super().__init__()
            self.bytes_transferred = bytes_transferred
            self.total_bytes = total_bytes
            self.percent = percent
            self.speed = speed
    
    def __init__(self, title: str = "Transfer Progress", **kwargs):
        super().__init__(**kwargs)
        self._title = title
        self._total_bytes = 0
        self._transferred_bytes = 0
        self._current_file = ""
        self._speed = 0.0
        self._start_time = 0
    
    def compose(self) -> ComposeResult:
        yield Label(self._title, classes="transfer-header")
        yield Label("", classes="transfer-file", id="current-file")
        yield Container(
            Static(classes="transfer-fill", id="transfer-fill"),
            classes="transfer-bar",
        )
        yield Container(
            Label("Speed: ", classes="stat-label") +
            Label("", id="speed-value", classes="stat-value") +
            Label("  ETA: ", classes="stat-label") +
            Label("", id="eta-value", classes="stat-value") +
            Label("  ", classes="stat-label") +
            Label("", id="progress-value", classes="stat-value"),
            classes="transfer-stats",
        )
    
    def on_mount(self) -> None:
        import time
        self._file_label = self.query_one("#current-file", Label)
        self._fill = self.query_one("#transfer-fill", Static)
        self._speed_label = self.query_one("#speed-value", Label)
        self._eta_label = self.query_one("#eta-value", Label)
        self._progress_label = self.query_one("#progress-value", Label)
        self._start_time = time.time()
    
    def start_transfer(
        self,
        total_bytes: int,
        current_file: str = "",
    ) -> None:
        """Start transfer progress tracking.
        
        Args:
            total_bytes: Total bytes to transfer
            current_file: Name of current file being transferred
        """
        import time
        self._total_bytes = total_bytes
        self._transferred_bytes = 0
        self._current_file = current_file
        self._start_time = time.time()
        self._file_label.update(f"📄 {current_file}")
    
    def update_transfer(
        self,
        bytes_transferred: int,
        current_file: str = "",
    ) -> None:
        """Update transfer progress.
        
        Args:
            bytes_transferred: Bytes transferred so far
            current_file: Name of current file
        """
        import time
        self._transferred_bytes = bytes_transferred
        if current_file:
            self._current_file = current_file
            self._file_label.update(f"📄 {current_file}")
        
        # Calculate speed
        elapsed = time.time() - self._start_time
        if elapsed > 0:
            self._speed = bytes_transferred / elapsed
        
        # Update UI
        percent = (bytes_transferred / self._total_bytes * 100) if self._total_bytes > 0 else 0
        self._fill.update_styles(width=f"{percent}%")
        
        # Speed
        speed_str = self._format_speed(self._speed)
        self._speed_label.update(speed_str)
        
        # ETA
        if self._speed > 0:
            remaining = self._total_bytes - bytes_transferred
            eta = remaining / self._speed
            eta_str = self._format_time(eta)
        else:
            eta_str = "--:--"
        self._eta_label.update(eta_str)
        
        # Progress
        self._progress_label.update(f"{self._format_size(bytes_transferred)} / {self._format_size(self._total_bytes)}")
        
        self.post_message(self.TransferProgressUpdate(
            bytes_transferred=bytes_transferred,
            total_bytes=self._total_bytes,
            percent=percent,
            speed=self._speed,
        ))
    
    def complete_transfer(self, message: str = "Transfer complete") -> None:
        """Complete transfer progress."""
        self.update_transfer(self._total_bytes)
        self._file_label.update(f"✅ {message}")
    
    def error_transfer(self, message: str = "Transfer failed") -> None:
        """Mark transfer as error."""
        self._fill.styles.background = "$error"
        self._file_label.update(f"❌ {message}")
    
    def _format_speed(self, bytes_per_second: float) -> str:
        """Format speed to human-readable string."""
        return f"{self._format_size(bytes_per_second)}/s"
    
    def _format_time(self, seconds: float) -> str:
        """Format seconds to human-readable time string."""
        if seconds < 60:
            return f"{int(seconds)}s"
        elif seconds < 3600:
            return f"{int(seconds // 60)}m {int(seconds % 60)}s"
        else:
            hours = int(seconds // 3600)
            minutes = int((seconds % 3600) // 60)
            return f"{hours}h {minutes}m"
    
    def _format_size(self, size_bytes: int) -> str:
        """Format bytes to human-readable size."""
        for unit in ["B", "KB", "MB", "GB", "TB"]:
            if size_bytes < 1024:
                return f"{size_bytes:.1f} {unit}"
            size_bytes /= 1024
        return f"{size_bytes:.1f} PB"

