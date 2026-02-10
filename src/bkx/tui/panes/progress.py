"""Progress pane - Progress tracking for operations.

Uses mikomn's GlobalProgressBar and StatusBar for progress tracking.
"""

from __future__ import annotations

from mikomn.tui_base.panes.display.progress_pane import ProgressPane as MikomnProgressPane
from mikomn.tui_base.widgets import StatusBar
from mikomn.tui_base.widgets.status import StatusMessage
from textual.app import ComposeResult
from textual.containers import Container, Horizontal
from textual.message import Message
from textual.widgets import Label, Static


class ProgressPane(MikomnProgressPane):
    """Progress pane using mikomn's ProgressPane."""

    class ProgressUpdate(Message):
        """Progress was updated."""

    class ProgressComplete(Message):
        """Progress operation completed."""

        operation: str = ""

        def __init__(self, operation: str = "") -> None:
            super().__init__()
            self.operation = operation

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
            **kwargs,
        )
        self.add_class("bkx-progress-pane")
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
        self.progress_bar.progress = float(total)
        if message:
            self.post_message(StatusMessage(text=message, level="info"))

    def update_progress(
        self,
        current: int,
        message: str = "",
        speed: str | None = None,
        eta: str | None = None,
    ) -> None:
        """Update progress.

        Args:
            current: Current progress value
            message: Status message
            speed: Transfer speed string
            eta: Estimated time remaining
        """
        self.progress_bar.progress = float(current)
        if message:
            self.post_message(StatusMessage(text=message, level="info"))
        # Note: speed and eta would need to be handled differently
        # GlobalProgressBar doesn't have direct set_speed/set_eta methods

    def complete_progress(self, message: str = "Complete") -> None:
        """Complete the progress operation.

        Args:
            message: Completion message
        """
        self.progress_bar.progress = 100.0
        self.post_message(StatusMessage(text=message, level="success"))
        self.post_message(self.ProgressComplete(operation=self._operation_name))

    def error_progress(self, message: str = "Error") -> None:
        """Mark progress as error.

        Args:
            message: Error message
        """
        self.post_message(StatusMessage(text=message, level="error"))
        self.post_message(self.ProgressComplete(operation=self._operation_name))


class StatusBarPane(Container):
    """Status bar pane using mikomn's StatusBar."""

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.add_class("bkx-status-bar-pane")
        self._status_bar = StatusBar()

    def compose(self) -> ComposeResult:
        yield self._status_bar

    def display_status(
        self,
        message: str,
        severity: str = "information",
        icon: str = "ℹ️",
    ) -> None:
        """Display a status message.

        Args:
            message: Status message text
            severity: Message severity (information, success, warning, error)
            icon: Icon to display
        """
        severity_map = {
            "information": "info",
            "success": "success",
            "warning": "warning",
            "error": "error",
        }
        sev = severity_map.get(severity, "info")
        self._status_bar.post_message(StatusMessage(text=f"{icon} {message}", level=sev))

    def clear_status(self) -> None:
        """Clear the status message."""
        self._status_bar.update("")


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
            current=progress.current_file if hasattr(progress, "current_file") else int(progress.percent * 100),
            speed=f"{progress.bytes_current / 1024 / 1024:.1f} MB/s" if hasattr(progress, "bytes_current") else None,
        )

    return callback


class TransferProgressPane(Container):
    """Specialized pane for transfer operations with speed and ETA."""

    class TransferProgressUpdate(Message):
        """Transfer progress update."""

        def __init__(
            self,
            bytes_transferred: float,
            total_bytes: float,
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
        self.add_class("bkx-transfer-progress-pane")
        self._title = title
        self._total_bytes: float = 0
        self._transferred_bytes: float = 0
        self._current_file = ""
        self._speed = 0.0
        self._start_time: float = 0.0

    def compose(self) -> ComposeResult:
        yield Label(self._title, classes="transfer-header")
        yield Label("", classes="transfer-file", id="current-file")
        yield Container(
            Static(classes="transfer-fill", id="transfer-fill"),
            classes="transfer-bar",
        )
        yield Container(
            Horizontal(
                Label("Speed: ", classes="stat-label"),
                Label("", id="speed-value", classes="stat-value"),
            ),
            Horizontal(
                Label("  ETA: ", classes="stat-label"),
                Label("", id="eta-value", classes="stat-value"),
            ),
            Horizontal(
                Label("  ", classes="stat-label"),
                Label("", id="progress-value", classes="stat-value"),
            ),
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
        total_bytes: float,
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
        bytes_transferred: float,
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
        self._fill.styles.width = f"{percent}%"

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

        self.post_message(
            self.TransferProgressUpdate(
                bytes_transferred=bytes_transferred,
                total_bytes=self._total_bytes,
                percent=percent,
                speed=self._speed,
            )
        )

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

    def _format_size(self, size_bytes: float) -> str:
        """Format bytes to human-readable size."""
        size_bytes = int(size_bytes)  # Cast to int for display
        for unit in ["B", "KB", "MB", "GB", "TB"]:
            if size_bytes < 1024:
                return f"{size_bytes:.1f} {unit}"
            size_bytes /= 1024
        return f"{size_bytes:.1f} PB"
