"""Archive screen - Archive creation and browsing.

Provides:
- Archive creation form
- Archive browser with search/filter
- Preview pane for archive details

Uses mikomn's enhanced TUI widgets for polish.
"""

from __future__ import annotations

from pathlib import Path
from textual.app import ComposeResult
from textual.containers import Container, Horizontal
from textual.message import Message
from textual.screen import Screen
from textual.widgets import Button, Checkbox, Input, Label, Select, Static, TabbedContent, Tab

from bkx.core.config import get_config
from bkx.tui.messages import ArchiveIntent, FilterChanged, PreviewRequest
from bkx.tui.panes.archive_list import ArchiveListPane, ArchivePreviewPane
from bkx.tui.panes.empty_state import EmptyStatePresets, EmptyStateManager
from bkx.tui.widgets.file_picker import show_file_picker, show_destination_picker
from mikomn.tui_base.widgets.file_picker import FilterMode


class ArchiveScreen(Screen):
    """Archive creation and browsing screen."""
    
    BINDINGS = [
        ("escape", "pop_screen", "Back"),
        ("ctrl+f", "focus_filter", "Filter"),
    ]
    
    def compose(self) -> ComposeResult:
        yield Static("📦 Archive", classes="screen-header")
        
        yield TabbedContent(
            Tab("Create", id="tab-create"),
            Tab("Browse", id="tab-browse"),
            id="archive-tabs",
        )
        
        # Create tab content
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
                    [
                        ("zstd", "zstd"),
                        ("gzip", "gzip"),
                        ("lz4", "lz4"),
                        ("xz", "xz"),
                        ("bzip2", "bzip2"),
                    ],
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
            id="create-tab-content",
        )
        
        # Browse tab content
        yield Container(
            Horizontal(
                ArchiveListPane(
                    id="archive-list",
                    classes="archive-list-pane",
                ),
                ArchivePreviewPane(
                    id="archive-preview",
                    classes="archive-preview-pane",
                ),
                id="browser-content",
            ),
            Container(
                Button("🔄 Refresh", id="btn-refresh"),
                Button("🗑️ Delete", id="btn-delete"),
                Button("← Back", id="btn-back"),
                classes="browser-toolbar",
            ),
            id="browse-tab-content",
        )
        
        # Action buttons for create tab
        yield Container(
            Button("📁 Select Files", id="btn-select"),
            Button("🚀 Create Archive", id="btn-run"),
            Button("← Back", id="btn-back"),
            classes="button-row",
            id="create-buttons",
        )
        
        # Status bar
        yield Label("Ready", id="archive-status", classes="browser-status")
    
    def on_mount(self) -> None:
        """Initialize form and load archives."""
        # Initialize create tab
        config = get_config()
        verify_checkbox = self.query_one("#verify-checkbox", Checkbox)
        verify_checkbox.value = config.verify_after_operation
        
        # Set up empty state manager
        self._empty_manager = EmptyStateManager("archive-list")
        
        # Load archives
        self._refresh_archive_list()
    
    def _refresh_archive_list(self) -> None:
        """Refresh the archive list."""
        list_pane = self.query_one("#archive-list", ArchiveListPane)
        
        # Get archives from default location
        config = get_config()
        archive_dir = config.archives_dir
        
        if archive_dir and Path(archive_dir).exists():
            # Find archive files
            archive_files = self._find_archive_files(archive_dir)
            if archive_files:
                list_pane.set_archives(archive_files)
                self._update_status(f"Found {len(archive_files)} archives")
            else:
                self._show_empty_state("no-archives")
        else:
            self._show_empty_state("no-archives")
    
    def _find_archive_files(self, directory: str) -> list:
        """Find archive files in directory.
        
        Args:
            directory: Directory to search
            
        Returns:
            List of archive items
        """
        from bkx.tui.panes.archive_list import create_archive_list_items
        from pathlib import Path
        
        archive_dir = Path(directory)
        if not archive_dir.exists():
            return []
        
        # Find common archive patterns
        patterns = ["*.tar.zst", "*.tar.gz", "*.tar.xz", "*.tar.bz2", "*.zip", "*.7z"]
        
        archive_paths = []
        for pattern in patterns:
            archive_paths.extend(archive_dir.glob(pattern))
        
        # Sort by modification time
        archive_paths.sort(key=lambda p: p.stat().st_mtime, reverse=True)
        
        return create_archive_list_items(archive_paths)
    
    def _show_empty_state(self, state_name: str) -> None:
        """Show empty state in the archive list pane."""
        list_pane = self.query_one("#archive-list", ArchiveListPane)
        
        presets = {
            "no-archives": EmptyStatePresets.no_archives,
            "no-selection": EmptyStatePresets.no_files_selected,
        }
        
        preset = presets.get(state_name, EmptyStatePresets.no_archives)
        self._empty_manager.show_preset(list_pane, state_name)
        self._update_status("No archives found")
    
    def _update_status(self, message: str) -> None:
        """Update status bar."""
        status = self.query_one("#archive-status", Label)
        status.update(message)
    
    def on_tabbed_content_changed(self, event: TabbedContent.Changed) -> None:
        """Handle tab switching."""
        if event.tab.id == "tab-create":
            self.query_one("#create-buttons", Container).display = True
            self.query_one("#create-tab-content", Container).display = True
            self.query_one("#browse-tab-content", Container).display = False
        elif event.tab.id == "tab-browse":
            self.query_one("#create-buttons", Container).display = False
            self.query_one("#create-tab-content", Container).display = False
            self.query_one("#browse-tab-content", Container).display = True
            self._refresh_archive_list()
    
    def on_button_pressed(self, event: Button.Pressed) -> None:
        """Handle button presses."""
        button_id = event.button.id
        
        if button_id == "btn-select":
            self.notify("File picker integration pending", severity="info")
        elif button_id == "btn-run":
            self._on_create_archive()
        elif button_id == "btn-back":
            self.app.pop_screen()
        elif button_id == "btn-refresh":
            self._refresh_archive_list()
        elif button_id == "btn-delete":
            self._on_delete_archive()
    
    def on_archive_list_selected(self, event: ArchiveListPane.Selected) -> None:
        """Handle archive selection."""
        preview_pane = self.query_one("#archive-preview", ArchivePreviewPane)
        preview_pane.set_archive(event.item)
        self._update_status(f"Selected: {event.item.name}")
    
    def on_filter_changed(self, event: FilterChanged) -> None:
        """Handle filter changes in archive list."""
        list_pane = self.query_one("#archive-list", ArchiveListPane)
        
        if event.filter_text:
            list_pane.set_filter(event.filter_text)
            self._update_status(f"Filter: {event.filter_text} ({event.filtered_count}/{event.total_count})")
        else:
            self._update_status(f"All archives ({event.total_count})")
    
    def action_focus_filter(self) -> None:
        """Focus the filter input."""
        list_pane = self.query_one("#archive-list", ArchiveListPane)
        list_pane.focus_filter()
    
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
    
    def _on_delete_archive(self) -> None:
        """Handle archive deletion."""
        list_pane = self.query_one("#archive-list", ArchiveListPane)
        selected = list_pane.get_selected()
        
        if selected:
            self.notify(f"Delete {selected.name} - Implementation pending", severity="info")
        else:
            self.notify("Select an archive to delete", severity="warning")

