"""Profile dialog

Dialog for creating and editing profiles.
"""

from __future__ import annotations

from textual.app import ComposeResult
from textual.containers import Horizontal, Vertical
from textual.message import Message
from textual.screen import ModalScreen
from textual.widgets import Button, Input, Label, Select, Static

from bkx.tui.profiles import BkxProfile, get_profile_manager


class ProfileDialog(ModalScreen):
    """Dialog for creating and editing profiles.

    Features:
    - Create new profile
    - Edit existing profile
    - Set source/destination directories
    - Configure compression settings
    """

    class ProfileSaved(Message):
        """Emitted when a profile is saved."""

        def __init__(self, profile: BkxProfile) -> None:
            super().__init__()
            self.profile = profile

    class Cancelled(Message):
        """Emitted when dialog is cancelled."""

    def __init__(
        self,
        edit_profile: str | None = None,
        **kwargs,
    ) -> None:
        """Initialize the profile dialog.

        Args:
            edit_profile: Name of profile to edit, or None for new profile
        """
        super().__init__(**kwargs)
        self._edit_profile = edit_profile
        self._profile_manager = get_profile_manager()

        if edit_profile:
            self._existing_profile = self._profile_manager.get_profile(edit_profile)
        else:
            self._existing_profile = None

    def compose(self) -> ComposeResult:
        is_edit = self._edit_profile is not None
        title = f"Edit Profile: {self._edit_profile}" if is_edit else "New Profile"

        with Vertical(classes="bkx-profile-dialog"):
            # Header
            with Horizontal(classes="dialog-header"):
                yield Label("⚙️", classes="dialog-icon")
                yield Static(title, classes="dialog-title")

            # Form fields
            with Vertical(classes="dialog-form"):
                # Profile name
                with Horizontal(classes="form-row"):
                    yield Label("Name:", classes="form-label")
                    yield Input(
                        value=self._edit_profile or "",
                        placeholder="Profile name",
                        id="profile-name",
                        classes="form-input",
                        disabled=bool(self._edit_profile),  # Can't edit name of existing profile
                    )

                # Source directory
                with Horizontal(classes="form-row"):
                    yield Label("Source:", classes="form-label")
                    yield Input(
                        value=self._existing_profile.source_dir if self._existing_profile else "",
                        placeholder="/path/to/source",
                        id="profile-source",
                        classes="form-input",
                    )

                # Destination directory
                with Horizontal(classes="form-row"):
                    yield Label("Dest:", classes="form-label")
                    yield Input(
                        value=self._existing_profile.destination_dir if self._existing_profile else "",
                        placeholder="/path/to/destination",
                        id="profile-destination",
                        classes="form-input",
                    )

                # Algorithm
                with Horizontal(classes="form-row"):
                    yield Label("Algo:", classes="form-label")
                    yield Select(
                        [
                            ("zstd", "Zstandard (fast, good compression)"),
                            ("gzip", "GZIP (compatible, medium speed)"),
                            ("xz", "XZ (slow, best compression)"),
                            ("lz4", "LZ4 (fastest, lower compression)"),
                        ],
                        prompt="Algorithm",
                        value=self._existing_profile.algorithm if self._existing_profile else "zstd",
                        id="profile-algorithm",
                        classes="form-select",
                    )

                # Compression level
                with Horizontal(classes="form-row"):
                    yield Label("Level:", classes="form-label")
                    yield Select(
                        [
                            ("1", "1 (fastest)"),
                            ("2", "2"),
                            ("3", "3 (default)"),
                            ("4", "4"),
                            ("5", "5"),
                            ("6", "6"),
                            ("7", "7"),
                            ("8", "8"),
                            ("9", "9 (slowest)"),
                        ],
                        prompt="Level",
                        value=str(self._existing_profile.compression_level) if self._existing_profile else "3",
                        id="profile-level",
                        classes="form-select narrow",
                    )

                # Excludes
                with Horizontal(classes="form-row"):
                    yield Label("Excludes:", classes="form-label")
                    excludes = ", ".join(self._existing_profile.excludes) if self._existing_profile else ""
                    yield Input(
                        value=excludes,
                        placeholder="*.pyc, __pycache__, node_modules",
                        id="profile-excludes",
                        classes="form-input",
                    )

                # Verify checkbox (simplified as Select)
                with Horizontal(classes="form-row"):
                    yield Label("Verify:", classes="form-label")
                    yield Select(
                        [
                            ("yes", "Yes"),
                            ("no", "No"),
                        ],
                        prompt="Verify",
                        value="yes" if (self._existing_profile.verify if self._existing_profile else True) else "no",
                        id="profile-verify",
                        classes="form-select narrow",
                    )

            # Actions
            with Horizontal(classes="dialog-actions"):
                yield Button("Cancel", id="btn-cancel", classes="dialog-btn")
                yield Button("Save", id="btn-save", variant="primary", classes="dialog-btn")

    def on_mount(self) -> None:
        """Focus the name input for new profiles."""
        if not self._edit_profile:
            name_input = self.query_one("#profile-name", Input)
            name_input.focus()

    def on_button_pressed(self, event: Button.Pressed) -> None:
        """Handle button presses."""
        if event.button.id == "btn-cancel":
            self.dismiss(self.Cancelled())

        elif event.button.id == "btn-save":
            self._save_profile()

    def _save_profile(self) -> None:
        """Save the profile."""
        # Get form values
        name = self.query_one("#profile-name", Input).value.strip()
        if not name:
            self.app.notify("Profile name is required", severity="error")
            return

        source = self.query_one("#profile-source", Input).value.strip()
        destination = self.query_one("#profile-destination", Input).value.strip()

        # Handle Select value - it can return NoSelection or None
        algo_select = self.query_one("#profile-algorithm", Select)
        raw_algorithm = algo_select.value
        algorithm: str
        if isinstance(raw_algorithm, str) and raw_algorithm:
            algorithm = raw_algorithm
        else:
            algorithm = "zstd"

        level_select = self.query_one("#profile-level", Select)
        raw_level = level_select.value
        if isinstance(raw_level, str) and raw_level:
            level = int(raw_level)
        else:
            level = 3

        excludes_text = self.query_one("#profile-excludes", Input).value.strip()
        excludes = [e.strip() for e in excludes_text.split(",") if e.strip()]

        verify_select = self.query_one("#profile-verify", Select)
        raw_verify = verify_select.value
        verify = isinstance(raw_verify, str) and raw_verify == "yes"

        # Create or update profile
        profile = BkxProfile(
            name=name,
            source_dir=source,
            destination_dir=destination,
            algorithm=algorithm,
            compression_level=level,
            excludes=excludes,
            verify=verify,
        )

        self._profile_manager.update_profile(profile)
        self.dismiss(self.ProfileSaved(profile))


def show_profile_dialog(app, edit_profile: str | None = None) -> None:
    """Show the profile dialog.

    Args:
        app: The Textual app instance
        edit_profile: Name of profile to edit, or None for new
    """
    dialog = ProfileDialog(edit_profile=edit_profile)
    app._push_modal(dialog)
