"""Context Switcher Pane - Step 10C

Interactive context bar for profile switching and context management.
"""

from __future__ import annotations

from textual.app import ComposeResult
from textual.containers import Container, Horizontal
from textual.message import Message
from textual.widgets import Button, Label, Select, Static

from bkx.tui.profiles import get_profile_manager


class ContextSwitcherPane(Container):
    """Interactive context switcher pane.

    Features:
    - Profile selection dropdown
    - Source directory quick access
    - Destination directory quick access
    - Operation mode display
    """

    DEFAULT_CLASSES = "bkx-context-switcher"

    class ProfileChanged(Message):
        """Emitted when profile is changed."""

        def __init__(self, profile_name: str) -> None:
            super().__init__()
            self.profile_name = profile_name

    class SourceChanged(Message):
        """Emitted when source is changed."""

        def __init__(self, source_dir: str) -> None:
            super().__init__()
            self.source_dir = source_dir

    class DestinationChanged(Message):
        """Emitted when destination is changed."""

        def __init__(self, destination_dir: str) -> None:
            super().__init__()
            self.destination_dir = destination_dir

    def __init__(self, **kwargs) -> None:
        super().__init__(**kwargs)
        self._profile_manager = get_profile_manager()
        self._current_profile = self._profile_manager.get_current_profile()

    def compose(self) -> ComposeResult:
        with Horizontal(classes="context-switcher-bar"):
            # Profile selector
            with Horizontal(classes="context-item"):
                yield Label("👤", classes="context-icon")
                yield Select(
                    self._get_profile_options(),
                    prompt="Profile",
                    value=self._current_profile.name,
                    id="profile-select",
                    classes="context-select",
                )

            # Source directory
            with Horizontal(classes="context-item"):
                yield Label("📁", classes="context-icon")
                yield Static(
                    self._format_path(self._current_profile.source_dir),
                    classes="context-value",
                    id="context-source",
                )
                yield Button("📂", id="btn-select-source", classes="context-btn", tooltip="Select source directory")

            # Destination directory
            with Horizontal(classes="context-item"):
                yield Label("📂", classes="context-icon")
                yield Static(
                    self._format_path(self._current_profile.destination_dir),
                    classes="context-value",
                    id="context-destination",
                )
                yield Button("📂", id="btn-select-destination", classes="context-btn", tooltip="Select destination directory")

            # Operation mode
            with Horizontal(classes="context-item"):
                yield Label("⚙️", classes="context-icon")
                yield Static(
                    f"{self._current_profile.algorithm}/{self._current_profile.compression_level}",
                    classes="context-value",
                    id="context-mode",
                )

            # Profile actions
            with Horizontal(classes="context-actions"):
                yield Button("+", id="btn-new-profile", classes="context-btn small", tooltip="Create new profile")

    def _get_profile_options(self) -> list[tuple[str, str]]:
        """Get profile options for the dropdown."""
        profiles = self._profile_manager.get_profile_names()
        return [(name, name) for name in profiles]

    def _format_path(self, path: str) -> str:
        """Format a path for display."""
        if not path:
            return "(none)"
        # Show just the last component for brevity
        from pathlib import Path

        return Path(path).name or path

    def on_mount(self) -> None:
        """Initialize after mounting."""
        self._update_display()

    def _update_display(self) -> None:
        """Update the display with current profile values."""
        self._current_profile = self._profile_manager.get_current_profile()

        # Update source
        source_label = self.query_one("#context-source", Static)
        source_label.update(self._format_path(self._current_profile.source_dir))

        # Update destination
        dest_label = self.query_one("#context-destination", Static)
        dest_label.update(self._format_path(self._current_profile.destination_dir))

        # Update mode
        mode_label = self.query_one("#context-mode", Static)
        mode_label.update(f"{self._current_profile.algorithm}/{self._current_profile.compression_level}")

    def on_select_changed(self, event: Select.Changed) -> None:
        """Handle profile selection change."""
        if event.select.id == "profile-select":
            new_profile = str(event.value) if event.value else ""
            if new_profile and self._profile_manager.switch_profile(new_profile):
                self._update_display()
                self.post_message(self.ProfileChanged(new_profile))

    def on_button_pressed(self, event: Button.Pressed) -> None:
        """Handle button presses."""
        if event.button.id == "btn-select-source":
            self._handle_select_source()

        elif event.button.id == "btn-select-destination":
            self._handle_select_destination()

        elif event.button.id == "btn-new-profile":
            self._handle_new_profile()

    def _handle_select_source(self) -> None:
        """Handle source directory selection."""
        # In a full implementation, this would open a file picker
        # For now, we'll just show a notification
        try:
            app = self.app
            app.notify("Source selection: Use profile dialog for full path", severity="information")
        except Exception:
            pass

    def _handle_select_destination(self) -> None:
        """Handle destination directory selection."""
        try:
            app = self.app
            app.notify("Destination selection: Use profile dialog for full path", severity="information")
        except Exception:
            pass

    def _handle_new_profile(self) -> None:
        """Handle new profile creation."""
        try:
            app = self.app
            app.notify("Creating new profile... (Profile dialog)", severity="information")
            # In a full implementation, open the profile dialog
        except Exception:
            pass

    def refresh_profile(self) -> None:
        """Refresh display with current profile state."""
        self._update_display()


class CompactContextBar(Container):
    """Compact context bar showing minimal info."""

    DEFAULT_CLASSES = "bkx-context-bar-compact"

    def compose(self) -> ComposeResult:
        profile = get_profile_manager().get_current_profile()
        yield Static(
            f"👤 {profile.name} | 📁 {self._format_path(profile.source_dir)} | 📂 {self._format_path(profile.destination_dir)}",
            classes="context-bar-text",
            id="context-bar-text",
        )

    def _format_path(self, path: str) -> str:
        """Format a path for display."""
        if not path:
            return "none"
        from pathlib import Path

        return Path(path).name or path

    def refresh(self) -> None:
        """Refresh the display."""
        profile = get_profile_manager().get_current_profile()
        text = f"👤 {profile.name} | 📁 {self._format_path(profile.source_dir)} | 📂 {self._format_path(profile.destination_dir)}"
        try:
            label = self.query_one("#context-bar-text", Static)
            label.update(text)
        except Exception:
            pass
