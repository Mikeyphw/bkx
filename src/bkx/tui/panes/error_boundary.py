"""Error boundary widget - Step 8C

Provides per-pane error boundaries that:
- Catch exceptions within wrapped widgets
- Show localized error messages
- Provide recovery options
- Keep the app usable when one pane crashes
"""

from __future__ import annotations

from typing import Any

from textual.app import ComposeResult
from textual.containers import Container
from textual.message import Message
from textual.widgets import Button, Label


class ErrorBoundaryState:
    """State for error boundary."""

    def __init__(
        self,
        pane_id: str = "",
        error_message: str = "",
        error_details: str = "",
        is_error: bool = False,
        error_count: int = 0,
    ) -> None:
        self.pane_id = pane_id
        self.error_message = error_message
        self.error_details = error_details
        self.is_error = is_error
        self.error_count = error_count

    def set_error(self, message: str, details: str = "") -> None:
        """Set error state."""
        self.error_message = message
        self.error_details = details
        self.is_error = True
        self.error_count += 1

    def clear_error(self) -> None:
        """Clear error state."""
        self.error_message = ""
        self.error_details = ""
        self.is_error = False

    def to_dict(self) -> dict:
        """Convert to dictionary."""
        return {
            "pane_id": self.pane_id,
            "error_message": self.error_message,
            "error_details": self.error_details,
            "is_error": self.is_error,
            "error_count": self.error_count,
        }


class PaneErrorBoundary(Container):
    """Container that catches and displays errors from child widgets.

    Usage:
        with PaneErrorBoundary(id="my-pane", fallback_message="Panel failed"):
            # Your widgets here
            MyWidget()

    Step 8C features:
    - Localized error display
    - Recovery options (retry, dismiss)
    - Error counting
    - App stays usable
    """

    DEFAULT_CLASSES = "bkx-error-boundary"

    class ErrorOccurred(Message):
        """Emitted when an error is caught."""

        def __init__(
            self,
            pane_id: str,
            error_message: str,
            error_details: str,
            error_count: int,
        ) -> None:
            super().__init__()
            self.pane_id = pane_id
            self.error_message = error_message
            self.error_details = error_details
            self.error_count = error_count

    class RetryRequested(Message):
        """Emitted when retry button is clicked."""

        def __init__(self, pane_id: str) -> None:
            super().__init__()
            self.pane_id = pane_id

    class DismissRequested(Message):
        """Emitted when dismiss button is clicked."""

        def __init__(self, pane_id: str) -> None:
            super().__init__()
            self.pane_id = pane_id

    def __init__(
        self,
        *children: Any,
        id: str | None = None,
        classes: str | None = None,
        pane_name: str = "Panel",
        fallback_message: str = "This panel encountered an error",
        show_details: bool = True,
        **kwargs: Any,
    ) -> None:
        super().__init__(*children, id=id, classes=classes, **kwargs)
        self._pane_name = pane_name
        self._fallback_message = fallback_message
        self._show_details = show_details
        self._state = ErrorBoundaryState(pane_id=id or "")

    @property
    def state(self) -> ErrorBoundaryState:
        """Get error boundary state."""
        return self._state

    def compose(self) -> ComposeResult:
        # Error display (hidden by default)
        with Container(id="bkx-error-container", classes="error-container hidden"):
            yield Label(f"[bold red]⚠️ {self._pane_name} Error[/]", classes="error-title")
            yield Label(self._fallback_message, id="bkx-error-message", classes="error-message")
            yield Label("", id="bkx-error-details", classes="error-details")
            with Container(classes="error-actions"):
                yield Button("🔄 Retry", id="bkx-error-retry", classes="error-btn retry")
                yield Button("✕ Dismiss", id="bkx-error-dismiss", classes="error-btn dismiss")

    def on_mount(self) -> None:
        self._error_container = self.query_one("#bkx-error-container", Container)
        self._error_message = self.query_one("#bkx-error-message", Label)
        self._error_details = self.query_one("#bkx-error-details", Label)
        self._retry_btn = self.query_one("#bkx-error-retry", Button)
        self._dismiss_btn = self.query_one("#bkx-error-dismiss", Button)

    def _show_error(self, message: str, details: str = "") -> None:
        """Show error UI."""
        self._state.set_error(message, details)

        # Update error display
        self._error_message.update(f"[dim]{message}[/]")

        if details and self._show_details:
            self._error_details.update(f"[dim][small]{details}[/][/]")
            self._error_details.display = True
        else:
            self._error_details.display = False

        # Show error container, hide children
        self._error_container.display = True
        for child in self.query("*"):
            if child != self._error_container:
                try:
                    child.display = False
                except Exception:
                    pass

        # Post error message
        self.post_message(
            self.ErrorOccurred(
                pane_id=self._state.pane_id,
                error_message=message,
                error_details=details,
                error_count=self._state.error_count,
            )
        )

    def _hide_error(self) -> None:
        """Hide error UI and show children again."""
        self._state.clear_error()

        # Hide error container
        error_container = getattr(self, "_error_container", None)
        if error_container is None:
            return
        error_container.display = False

        # Show children again
        for child in self.query("*"):
            if child != error_container:
                try:
                    child.display = True
                except Exception:
                    pass

    def on_button_pressed(self, event: Button.Pressed) -> None:
        """Handle button presses."""
        if event.button.id == "bkx-error-retry":
            self.post_message(self.RetryRequested(self._state.pane_id))
            self._hide_error()

        elif event.button.id == "bkx-error-dismiss":
            self.post_message(self.DismissRequested(self._state.pane_id))
            self._hide_error()

    def catch(self, error: Exception) -> bool:
        """Catch an exception and display error UI.

        Call this method in a try/except block within child widgets.

        Args:
            error: The exception that was caught

        Returns:
            True if error was handled, False otherwise
        """
        import traceback

        message = str(error) or "Unknown error"
        details = traceback.format_exc()

        # Truncate details if too long
        if len(details) > 500:
            details = details[:500] + "..."

        self._show_error(message, details)
        return True

    def reset(self) -> None:
        """Reset the error boundary to initial state."""
        self._state = ErrorBoundaryState(pane_id=self._state.pane_id)
        self._hide_error()


class ErrorBoundaryRegistry:
    """Registry for managing multiple error boundaries."""

    def __init__(self) -> None:
        self._boundaries: dict[str, PaneErrorBoundary] = {}

    def register(self, boundary: PaneErrorBoundary) -> str:
        """Register an error boundary.

        Returns:
            The boundary's ID
        """
        boundary_id = boundary.id or f"boundary-{len(self._boundaries)}"
        self._boundaries[boundary_id] = boundary
        return boundary_id

    def unregister(self, boundary_id: str) -> None:
        """Unregister an error boundary."""
        self._boundaries.pop(boundary_id, None)

    def get(self, boundary_id: str) -> PaneErrorBoundary | None:
        """Get an error boundary by ID."""
        return self._boundaries.get(boundary_id)

    def dismiss_all(self) -> None:
        """Dismiss all errors."""
        for boundary in self._boundaries.values():
            boundary.reset()

    def get_error_summary(self) -> list[dict]:
        """Get summary of all current errors."""
        return [b.state.to_dict() for b in self._boundaries.values() if b.state.is_error]

    def has_errors(self) -> bool:
        """Check if any boundary has an active error."""
        return any(b.state.is_error for b in self._boundaries.values())
