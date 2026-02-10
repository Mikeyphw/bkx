"""Loading Spinner Widget - Step 11B

Provides animated loading indicators for async operations.
"""

from __future__ import annotations

from textual.app import ComposeResult
from textual.containers import Container, Horizontal
from textual.widgets import Label


class LoadingSpinner(Container):
    """Animated loading spinner widget.

    Usage:
        LoadingSpinner(message="Processing...", spinner_type="dots")
        LoadingSpinner(message="Loading...", variant="primary")
    """

    DEFAULT_CLASSES = "bkx-loading-spinner"

    # Spinner animation frames
    SPINNER_FRAMES = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"]
    DOTS_FRAMES = ["   ", ".  ", ".. ", "...", "   "]
    BAR_FRAMES = ["   ", "▌ ", "▌█", "▌██", "▌███", "████", " ███", "  ██", "   █", "    "]

    def __init__(
        self,
        message: str = "Loading...",
        spinner_type: str = "spinner",
        variant: str = "default",
        **kwargs,
    ) -> None:
        """Initialize the loading spinner.

        Args:
            message: Text to display alongside spinner
            spinner_type: Type of animation (spinner, dots, bar)
            variant: Color variant (default, primary, success, warning, error)
        """
        super().__init__(**kwargs)
        self._message = message
        self._spinner_type = spinner_type
        self._variant = variant
        self._frame_index = 0
        self._frame_count = len(self.SPINNER_FRAMES)

    def compose(self) -> ComposeResult:
        """Compose the loading spinner."""
        with Horizontal(classes="bkx-spinner-container"):
            yield Label(self.SPINNER_FRAMES[0], classes="bkx-spinner-icon", id="spinner-icon")
            yield Label(self._message, classes="bkx-spinner-message", id="spinner-message")

    def on_mount(self) -> None:
        """Start animation on mount."""
        self._update_spinner()

    def _update_spinner(self) -> None:
        """Update spinner frame."""
        try:
            icon = self.query_one("#spinner-icon", Label)
            icon.update(self._get_current_frame())
            self._frame_index = (self._frame_index + 1) % self._frame_count
            self.set_timer(0.1, self._update_spinner)
        except Exception:
            pass

    def _get_current_frame(self) -> str:
        """Get the current animation frame."""
        frames = getattr(self, f"{self._spinner_type.upper()}_FRAMES", self.SPINNER_FRAMES)
        return frames[self._frame_index % len(frames)]

    @property
    def message(self) -> str:
        """Get the spinner message."""
        return self._message

    @message.setter
    def message(self, value: str) -> None:
        """Set the spinner message."""
        self._message = value
        try:
            msg_label = self.query_one("#spinner-message", Label)
            msg_label.update(value)
        except Exception:
            pass


class ProgressDots(Container):
    """Animated progress dots indicator.

    Usage:
        ProgressDots(message="Working")
    """

    DEFAULT_CLASSES = "bkx-progress-dots"

    def __init__(self, message: str = "...", **kwargs) -> None:
        super().__init__(**kwargs)
        self._message = message
        self._state = 0

    def compose(self) -> ComposeResult:
        yield Label(f"[dim]{self._message}[/]", classes="bkx-dots-text", id="dots-text")

    def on_mount(self) -> None:
        """Start animation on mount."""
        self._animate()

    def _animate(self) -> None:
        """Animate the dots."""
        try:
            text = self.query_one("#dots-text", Label)
            dots = ["   ", ".  ", ".. ", "...", ".. ", ".  "]
            text.update(f"[dim]{self._message}{dots[self._state % len(dots)]}[/]")
            self._state = (self._state + 1) % len(dots)
            self.set_timer(0.3, self._animate)
        except Exception:
            pass


class SkeletonLoader(Container):
    """Skeleton loading placeholder.

    Shows a shimmering placeholder while content loads.

    Usage:
        SkeletonLoader(width=40, height=3)
    """

    DEFAULT_CLASSES = "bkx-skeleton-loader"

    def __init__(
        self,
        width: int = 20,
        height: int = 1,
        **kwargs,
    ) -> None:
        super().__init__(**kwargs)
        self._width = width
        self._height = height

    def compose(self) -> ComposeResult:
        for _ in range(self._height):
            yield Label(
                "░" * self._width,
                classes="bkx-skeleton-line",
            )


class StatusIndicator(Container):
    """Status indicator with animated feedback.

    Shows success/error/warning states with optional animation.
    """

    DEFAULT_CLASSES = "bkx-status-indicator"

    ICONS = {
        "success": "✓",
        "error": "✕",
        "warning": "⚠",
        "info": "ℹ",
        "loading": "⟳",
    }

    def __init__(
        self,
        status: str = "info",
        message: str = "",
        animate: bool = False,
        **kwargs,
    ) -> None:
        super().__init__(**kwargs)
        self._status = status
        self._message = message
        self._should_animate = animate  # Renamed to avoid conflict with Textual's animator
        self._icon = self.ICONS.get(status, self.ICONS["info"])

    def compose(self) -> ComposeResult:
        with Horizontal(classes="bkx-status-content"):
            yield Label(self._icon, classes=f"bkx-status-icon bkx-status-{self._status}", id="status-icon")
            if self._message:
                yield Label(self._message, classes="bkx-status-message", id="status-message")

    def on_mount(self) -> None:
        """Start animation if enabled."""
        if self._should_animate and self._status == "loading":
            self._animate_spin()

    def _animate_spin(self) -> None:
        """Animate loading indicator."""
        try:
            icon = self.query_one("#status-icon", Label)
            frames = ["⟳", "⟲"]
            idx = 0
            for _ in range(20):  # Spin for 2 seconds
                icon.update(frames[idx % 2])
                idx += 1
                import time

                time.sleep(0.1)
            # Reset to static icon
            icon.update(self._icon)
        except Exception:
            pass

    def set_status(self, status: str, message: str = "") -> None:
        """Update status and message."""
        self._status = status
        self._message = message
        self._icon = self.ICONS.get(status, self.ICONS["info"])
        try:
            icon = self.query_one("#status-icon", Label)
            icon.update(self._icon)
            icon.remove_class(f"bkx-status-{self._status}")
            icon.add_class(f"bkx-status-{status}")
            if message:
                msg = self.query_one("#status-message", Label)
                msg.update(message)
        except Exception:
            pass


def show_loading(app, message: str = "Loading...") -> LoadingSpinner:
    """Show a loading spinner overlay.

    Args:
        app: The Textual app instance
        message: Message to display

    Returns:
        The LoadingSpinner widget
    """
    spinner = LoadingSpinner(message=message, classes="bkx-loading-overlay")
    app.mount(spinner)
    return spinner


def hide_loading(spinner: LoadingSpinner) -> None:
    """Hide a loading spinner with fade out.

    Args:
        spinner: The LoadingSpinner to hide
    """
    try:
        spinner.remove_class("bkx-animate-fade-in")
        spinner.add_class("bkx-animate-fade-out")
        spinner.set_timer(0.3, spinner.remove)
    except Exception:
        try:
            spinner.remove()
        except Exception:
            pass
