"""Empty state pane - Reusable empty state component.

Provides empty state components for BKX TUI.
"""

from __future__ import annotations

from typing import Optional

from textual.app import ComposeResult
from textual.containers import Container
from textual.message import Message
from textual.widgets import Button, Label, Static


class EmptyStatePane(Container):
    """Empty state pane with custom implementation.
    
    Features:
    - Icon display
    - Title and message
    - Optional action button
    - Text wrapping for long messages
    """
    
    class ActionClicked(Message):
        """Action button was clicked."""
    
    def __init__(
        self,
        title: str = "No items",
        message: str = "",
        icon: str = "📭",
        action_label: str = "",
        action_id: str = "",
        **kwargs,
    ):
        super().__init__(**kwargs)
        self._title = title
        self._message = message
        self._icon = icon
        self._action_label = action_label
        self._action_id = action_id
    
    def compose(self) -> ComposeResult:
        yield Static(self._icon, classes="empty-icon")
        yield Label(self._title, classes="empty-title")
        yield Label(
            self._message,
            classes="empty-message",
            id="empty-message",
        )
        
        if self._action_label:
            yield Button(
                self._action_label,
                id=f"empty-action-{self._action_id}",
                classes="empty-action",
            )
    
    def on_mount(self) -> None:
        if self._message:
            message_label = self.query_one("#empty-message", Label)
            wrapped = self._wrap_message(self._message, width=50)
            message_label.update(wrapped)
    
    def _wrap_message(self, message: str, width: int = 50) -> str:
        """Wrap message text."""
        import textwrap
        return "\n".join(textwrap.wrap(message, width=width))
    
    def on_button_pressed(self, event: Button.Pressed) -> None:
        """Handle button press."""
        if event.button.id.startswith("empty-action-"):
            self.post_message(self.ActionClicked(action_id=self._action_id))
    
    def update(
        self,
        title: Optional[str] = None,
        message: Optional[str] = None,
        icon: Optional[str] = None,
        action_label: Optional[str] = None,
        action_id: Optional[str] = None,
    ) -> None:
        """Update empty state content."""
        if title is not None:
            self._title = title
            title_label = self.query_one(".empty-title", Label)
            if title_label:
                title_label.update(title)
        
        if message is not None:
            self._message = message
            message_label = self.query_one("#empty-message", Label)
            if message_label:
                wrapped = self._wrap_message(message, width=50)
                message_label.update(wrapped)
        
        if icon is not None:
            self._icon = icon
            icon_label = self.query_one(".empty-icon", Static)
            if icon_label:
                icon_label.update(icon)
        
        if action_label is not None:
            self._action_label = action_label
        
        if action_id is not None:
            self._action_id = action_id


# Common empty state presets
class EmptyStatePresets:
    """Preset empty states for common scenarios."""
    
    @staticmethod
    def no_archives() -> EmptyStatePane:
        """Empty state for no archives."""
        return EmptyStatePane(
            title="No Archives",
            message="No archives found in the specified directory. Create an archive to get started.",
            icon="📦",
            action_label="Create Archive",
            action_id="create-archive",
        )
    
    @staticmethod
    def no_files_selected() -> EmptyStatePane:
        """Empty state for no files selected."""
        return EmptyStatePane(
            title="No Files Selected",
            message="Select files or directories to include in the archive.",
            icon="📁",
            action_label="Browse Files",
            action_id="browse-files",
        )
    
    @staticmethod
    def no_backup_sources() -> EmptyStatePane:
        """Empty state for no backup sources."""
        return EmptyStatePane(
            title="No Sources Specified",
            message="Add source paths to back up. You can type paths or use the file picker.",
            icon="💾",
            action_label="Select Sources",
            action_id="select-sources",
        )
    
    @staticmethod
    def search_no_results(query: str) -> EmptyStatePane:
        """Empty state for search with no results."""
        return EmptyStatePane(
            title="No Results",
            message=f'No items match your search "{query}". Try a different query.',
            icon="🔍",
            action_label="Clear Search",
            action_id="clear-search",
        )
    
    @staticmethod
    def restore_no_archive() -> EmptyStatePane:
        """Empty state for restore with no archive selected."""
        return EmptyStatePane(
            title="No Archive Selected",
            message="Select an archive to restore. Enter a path or use the file picker.",
            icon="📥",
            action_label="Select Archive",
            action_id="select-archive",
        )
    
    @staticmethod
    def transfer_no_source() -> EmptyStatePane:
        """Empty state for transfer with no source."""
        return EmptyStatePane(
            title="No Source Specified",
            message="Enter a source path to transfer. You can push to remote or pull from remote.",
            icon="🔄",
            action_label="Select Source",
            action_id="select-source",
        )
    
    @staticmethod
    def logs_empty() -> EmptyStatePane:
        """Empty state for empty logs."""
        return EmptyStatePane(
            title="No Logs Yet",
            message="Run operations to see logs here. Logs will appear as you perform backup, archive, restore, and transfer operations.",
            icon="📋",
        )
    
    @staticmethod
    def tools_empty() -> EmptyStatePane:
        """Empty state for tools."""
        return EmptyStatePane(
            title="No Tools Available",
            message="Utility tools will appear here. Use the buttons above to access verification, listing, and configuration tools.",
            icon="🔧",
        )


# Helper to create and manage empty states in containers
class EmptyStateManager:
    """Manager for handling empty states in containers."""
    
    def __init__(self, container_id: str):
        self._container_id = container_id
        self._current_state: Optional[EmptyStatePane] = None
    
    def show(
        self,
        container: Container,
        title: str,
        message: str = "",
        icon: str = "📭",
        action_label: str = "",
        action_id: str = "",
    ) -> EmptyStatePane:
        """Show an empty state in the container.
        
        Args:
            container: Parent container
            title: Title text
            message: Message text
            icon: Icon emoji
            action_label: Action button label
            action_id: Action button ID
            
        Returns:
            EmptyStatePane instance
        """
        # Remove existing state
        self.hide(container)
        
        # Create and mount new state
        state = EmptyStatePane(
            title=title,
            message=message,
            icon=icon,
            action_label=action_label,
            action_id=action_id,
            id=f"empty-state-{self._container_id}",
        )
        
        container.mount(state)
        self._current_state = state
        return state
    
    def show_preset(self, container: Container, preset_name: str) -> EmptyStatePane:
        """Show a preset empty state.
        
        Args:
            container: Parent container
            preset_name: Name of preset to show
            
        Returns:
            EmptyStatePane instance
        """
        preset_method = getattr(EmptyStatePresets, preset_name, None)
        if preset_method:
            state = preset_method()
            self.show(
                container,
                title=state._title,
                message=state._message,
                icon=state._icon,
                action_label=state._action_label,
                action_id=state._action_id,
            )
            return state
        else:
            return self.show(container, "No items", icon="📭")
    
    def hide(self, container: Container) -> None:
        """Hide the current empty state."""
        if self._current_state:
            try:
                self._current_state.remove()
            except Exception:
                pass
            self._current_state = None
    
    def is_visible(self) -> bool:
        """Check if an empty state is currently visible."""
        return self._current_state is not None and self._current_state.is_mounted

