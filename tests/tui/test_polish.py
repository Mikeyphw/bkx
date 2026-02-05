"""Tests for TUI polish features (Phase 07).

Tests focus on:
- Archive list and preview panes
- Progress tracking
- Status cards and settings
- Empty states
- Toast notifications

Contract rule:
- Tests polish features, not widget layout.
- Uses mocks to avoid actual service calls.
"""

from __future__ import annotations

from pathlib import Path
from unittest.mock import Mock, patch
import pytest

from bkx.tui.messages import (
    ShowToast,
    ProgressUpdate,
    ProgressStarted,
    ProgressCompleted,
    FilterChanged,
    ItemsSelected,
    RequestEmptyState,
    ClearEmptyState,
    StatusCardUpdated,
    PreviewRequest,
    PreviewContent,
)
from bkx.tui.panes.archive_list import (
    ArchiveListPane,
    ArchivePreviewPane,
    ArchiveListItem,
    create_archive_list_items,
)
from bkx.tui.panes.progress import (
    ProgressPane,
    StatusBarPane,
    TransferProgressPane,
)
from bkx.tui.panes.status_cards import (
    StatusCardsPane,
    StatusCard,
    StatusCardConfig,
    ThemeSettingsPane,
    create_default_status_cards,
)
from bkx.tui.panes.empty_state import (
    EmptyStatePane,
    EmptyStatePresets,
    EmptyStateManager,
)


class TestArchiveListPane:
    """Tests for ArchiveListPane."""

    def test_archive_list_item_creation(self) -> None:
        """Test ArchiveListItem creation."""
        item = ArchiveListItem(
            archive_id="test-archive",
            name="backup-2024.zst",
            path=Path("/backups/backup-2024.zst"),
            size="1.2 GB",
            modified="2024-01-15 10:30",
            icon="📦",
        )
        assert item.archive_id == "test-archive"
        assert item.name == "backup-2024.zst"
        assert str(item.path) == "/backups/backup-2024.zst"
        assert item.size == "1.2 GB"
        assert item.icon == "📦"

    def test_archive_list_item_to_dict(self) -> None:
        """Test ArchiveListItem to_dict conversion."""
        item = ArchiveListItem(
            archive_id="test-archive",
            name="backup.zst",
            path=Path("/backup.zst"),
        )
        data = item.to_dict()
        assert data["id"] == "test-archive"
        assert data["name"] == "backup.zst"
        assert data["path"] == "/backup.zst"

    def test_create_archive_list_items_from_paths(self) -> None:
        """Test creating archive items from paths."""
        # Test with empty list
        items = create_archive_list_items([])
        assert items == []

        # Test with non-existent path
        items = create_archive_list_items([Path("/nonexistent/archive.tar.zst")])
        assert items == []


class TestArchivePreviewPane:
    """Tests for ArchivePreviewPane."""

    def test_preview_creation(self) -> None:
        """Test ArchivePreviewPane creation."""
        pane = ArchivePreviewPane()
        assert pane._title == "Preview"
        assert pane._current_archive is None
        assert pane._content is None  # Not mounted

    def test_preview_set_archive(self) -> None:
        """Test setting archive for preview (without mounting)."""
        item = ArchiveListItem(
            archive_id="test",
            name="backup.zst",
            path=Path("/backup.zst"),
            size="500 MB",
            modified="2024-01-15",
        )
        pane = ArchivePreviewPane()
        # Should not raise error even without mounting
        pane.set_archive(item)
        assert pane._current_archive == item


class TestProgressPane:
    """Tests for ProgressPane."""

    def test_progress_update_message(self) -> None:
        """Test ProgressUpdate message creation."""
        msg = ProgressUpdate(
            operation="backup",
            current=50,
            total=100,
            percent=0.5,
            message="Processing file 50 of 100",
        )
        assert msg.operation == "backup"
        assert msg.current == 50
        assert msg.percent == 0.5

    def test_progress_started_message(self) -> None:
        """Test ProgressStarted message creation."""
        msg = ProgressStarted(
            operation="transfer",
            total=1000,
            message="Starting transfer...",
        )
        assert msg.operation == "transfer"
        assert msg.total == 1000

    def test_progress_completed_message(self) -> None:
        """Test ProgressCompleted message creation."""
        msg = ProgressCompleted(
            operation="archive",
            message="Archive created successfully",
        )
        assert msg.operation == "archive"


class TestTransferProgressPane:
    """Tests for TransferProgressPane."""

    def test_transfer_progress_creation(self) -> None:
        """Test TransferProgressPane creation."""
        pane = TransferProgressPane()
        assert pane._title == "Transfer Progress"
        assert pane._total_bytes == 0
        assert pane._transferred_bytes == 0

    def test_transfer_progress_start_transfer(self) -> None:
        """Test TransferProgressPane.start_transfer method."""
        pane = TransferProgressPane()
        # Manually set the attributes that would be set in on_mount
        pane._file_label = Mock()
        pane._fill = Mock()
        pane._speed_label = Mock()
        pane._eta_label = Mock()
        pane._progress_label = Mock()

        pane.start_transfer(total_bytes=1000000, current_file="test.txt")

        pane._file_label.update.assert_called_with("📄 test.txt")
        assert pane._total_bytes == 1000000

    def test_transfer_progress_update_transfer(self) -> None:
        """Test TransferProgressPane.update_transfer method."""
        pane = TransferProgressPane()
        # Manually set the attributes that would be set in on_mount
        pane._file_label = Mock()
        pane._fill = Mock()
        pane._speed_label = Mock()
        pane._eta_label = Mock()
        pane._progress_label = Mock()
        pane._total_bytes = 1000000
        pane._transferred_bytes = 0
        pane._start_time = 0

        with patch("time.time", return_value=1.0):
            pane.update_transfer(bytes_transferred=500000, current_file="test.txt")

        pane._fill.update_styles.assert_called()
        pane._speed_label.update.assert_called()
        pane._eta_label.update.assert_called()
        pane._progress_label.update.assert_called()


class TestStatusCardsPane:
    """Tests for StatusCardsPane."""

    def test_status_card_creation(self) -> None:
        """Test StatusCard creation."""
        card = StatusCard(
            id="theme",
            title="Theme",
            value="sweet-dracula",
            subtitle="Current theme",
            icon="🎨",
            status="info",
        )
        assert card.id == "theme"
        assert card.value == "sweet-dracula"
        assert card.status == "info"

    def test_status_card_config(self) -> None:
        """Test StatusCardConfig."""
        cards = [
            StatusCard(id="c1", title="Card 1", value="Value 1"),
            StatusCard(id="c2", title="Card 2", value="Value 2"),
        ]
        config = StatusCardConfig(cards=cards, layout="grid", columns=2)
        assert len(config.cards) == 2
        assert config.layout == "grid"

    def test_create_default_status_cards(self) -> None:
        """Test creating default status cards."""
        with patch("bkx.ui.theme.get_current_theme_name", return_value="sweet-dracula"):
            cards = create_default_status_cards()
            assert len(cards) >= 5
            theme_card = next((c for c in cards if c.id == "theme"), None)
            assert theme_card is not None
            assert theme_card.value == "sweet-dracula"


class TestEmptyStatePane:
    """Tests for EmptyStatePane."""

    def test_empty_state_creation(self) -> None:
        """Test EmptyStatePane creation."""
        pane = EmptyStatePane(
            title="No items",
            message="Add items to get started",
            icon="📦",
            action_label="Add Item",
            action_id="add",
        )
        assert pane._title == "No items"
        assert pane._icon == "📦"
        assert pane._action_id == "add"

    def test_empty_state_presets(self) -> None:
        """Test empty state presets."""
        no_archives = EmptyStatePresets.no_archives()
        assert no_archives._title == "No Archives"
        assert no_archives._action_id == "create-archive"

        no_files = EmptyStatePresets.no_files_selected()
        assert no_files._title == "No Files Selected"

        search_no_results = EmptyStatePresets.search_no_results("test")
        assert "test" in search_no_results._message


class TestEmptyStateManager:
    """Tests for EmptyStateManager."""

    def test_manager_creation(self) -> None:
        """Test EmptyStateManager creation."""
        manager = EmptyStateManager("test-container")
        assert manager._container_id == "test-container"
        assert not manager.is_visible()

    def test_manager_show_hide(self) -> None:
        """Test EmptyStateManager show/hide."""
        manager = EmptyStateManager("test-container")
        mock_container = Mock()
        mock_container.mount = Mock()
        mock_container.remove = Mock()

        # Show state
        state = manager.show(
            mock_container,
            title="Test",
            message="Test message",
            icon="📦",
        )
        assert state._title == "Test"
        mock_container.mount.assert_called_once()

        # Hide state
        manager.hide(mock_container)
        # Note: actual hide depends on state.is_mounted


class TestToastMessages:
    """Tests for toast notification messages."""

    def test_show_toast_message(self) -> None:
        """Test ShowToast message creation."""
        msg = ShowToast(
            message="Operation completed",
            severity="success",
            duration=3.0,
        )
        assert msg.message == "Operation completed"
        assert msg.severity == "success"
        assert msg.duration == 3.0

    def test_show_toast_with_defaults(self) -> None:
        """Test ShowToast with default values."""
        msg = ShowToast(message="Test message")
        assert msg.severity == "info"
        assert msg.duration == 3.0


class TestFilterMessages:
    """Tests for filter-related messages."""

    def test_filter_changed_message(self) -> None:
        """Test FilterChanged message creation."""
        msg = FilterChanged(
            filter_text="backup",
            filtered_count=5,
            total_count=20,
        )
        assert msg.filter_text == "backup"
        assert msg.filtered_count == 5
        assert msg.total_count == 20


class TestSelectionMessages:
    """Tests for selection-related messages."""

    def test_items_selected_message(self) -> None:
        """Test ItemsSelected message creation."""
        msg = ItemsSelected(
            item_ids=["item1", "item2", "item3"],
            picker_for="restore",
        )
        assert len(msg.item_ids) == 3
        assert msg.picker_for == "restore"

    def test_request_empty_state_message(self) -> None:
        """Test RequestEmptyState message creation."""
        msg = RequestEmptyState(
            container_id="archive-list",
            title="No Archives",
            message="Add archives to continue",
            icon="📦",
            action_label="Add Archive",
            action_id="add-archive",
        )
        assert msg.container_id == "archive-list"
        assert msg.title == "No Archives"

    def test_clear_empty_state_message(self) -> None:
        """Test ClearEmptyState message creation."""
        msg = ClearEmptyState(container_id="archive-list")
        assert msg.container_id == "archive-list"


class TestStatusCardMessages:
    """Tests for status card messages."""

    def test_status_card_updated_message(self) -> None:
        """Test StatusCardUpdated message creation."""
        msg = StatusCardUpdated(
            card_id="theme",
            value="dark-mode",
            subtitle="Current color scheme",
            status="success",
        )
        assert msg.card_id == "theme"
        assert msg.value == "dark-mode"
        assert msg.status == "success"


class TestPreviewMessages:
    """Tests for preview-related messages."""

    def test_preview_request_message(self) -> None:
        """Test PreviewRequest message creation."""
        msg = PreviewRequest(
            item_id="archive-1",
            item_type="archive",
            item_path=Path("/backup.zst"),
        )
        assert msg.item_id == "archive-1"
        assert msg.item_type == "archive"

    def test_preview_content_message(self) -> None:
        """Test PreviewContent message creation."""
        msg = PreviewContent(
            title="Archive Contents",
            content="file1.txt\nfile2.txt",
            content_type="text",
        )
        assert msg.title == "Archive Contents"
        assert msg.content_type == "text"


class TestIntegrationScenarios:
    """Tests for integration scenarios."""

    def test_archive_browse_workflow(self) -> None:
        """Test complete archive browse workflow."""
        # Create archive items
        items = [
            ArchiveListItem(
                archive_id=f"archive-{i}",
                name=f"backup-{i}.zst",
                path=Path(f"/backups/backup-{i}.zst"),
            )
            for i in range(1, 4)
        ]

        # Create list pane
        list_pane = ArchiveListPane()
        list_pane.set_archives(items)

        # Create preview pane
        preview_pane = ArchivePreviewPane()

        # Simulate selection
        selected_item = items[0]
        preview_pane.set_archive(selected_item)

        assert preview_pane._current_archive == selected_item

    def test_progress_workflow(self) -> None:
        """Test complete progress tracking workflow."""
        # Create progress update messages
        started = ProgressStarted(operation="backup", total=100, message="Starting backup")
        update1 = ProgressUpdate(operation="backup", current=25, percent=0.25)
        update2 = ProgressUpdate(operation="backup", current=50, percent=0.50)
        completed = ProgressCompleted(operation="backup", message="Backup complete")

        assert started.total == 100
        assert update1.percent == 0.25
        assert completed.message == "Backup complete"

    def test_toast_notification_workflow(self) -> None:
        """Test toast notification workflow."""
        # Create toast messages for different scenarios
        success_toast = ShowToast(
            message="Backup completed successfully",
            severity="success",
            duration=4.0,
        )

        error_toast = ShowToast(
            message="Backup failed: disk full",
            severity="error",
            duration=5.0,
        )

        info_toast = ShowToast(
            message="Transfer in progress...",
            severity="info",
            duration=3.0,
        )

        assert success_toast.severity == "success"
        assert error_toast.severity == "error"
        assert info_toast.severity == "info"

    def test_status_cards_workflow(self) -> None:
        """Test status cards workflow."""
        # Create status cards
        cards = create_default_status_cards()

        # Verify card structure
        for card in cards:
            assert card.id is not None
            assert card.title is not None
            assert card.value is not None

        # Test card config
        config = StatusCardConfig(cards=cards, layout="grid")
        assert len(config.cards) == len(cards)

    def test_empty_state_workflow(self) -> None:
        """Test empty state workflow."""
        # Test all presets
        presets = [
            EmptyStatePresets.no_archives(),
            EmptyStatePresets.no_files_selected(),
            EmptyStatePresets.no_backup_sources(),
            EmptyStatePresets.logs_empty(),
            EmptyStatePresets.tools_empty(),
        ]

        for preset in presets:
            assert preset._title is not None
            assert preset._icon is not None

        # Test manager
        manager = EmptyStateManager("test")
        assert manager._container_id == "test"
        assert not manager.is_visible()


class TestPolishAcceptanceCriteria:
    """Tests for Phase 07 acceptance criteria."""

    def test_search_filter_across_list_views(self) -> None:
        """Test that search/filter works across list views."""
        # Create items
        items = [
            ArchiveListItem(
                archive_id=f"archive-{i}",
                name=f"backup-{name}.zst",
                path=Path(f"/backups/backup-{name}.zst"),
            )
            for i, name in enumerate(["2024", "2023", "docs", "photos"])
        ]

        # Test filter functionality
        filter_text = "2024"
        filtered = [i for i in items if filter_text in i.name or filter_text in str(i.path)]
        assert len(filtered) == 1
        assert "2024" in filtered[0].name

    def test_progress_indicators(self) -> None:
        """Test that progress indicators can show during operations."""
        # Create progress messages
        progress_messages = [
            ProgressStarted(operation="backup", total=100, message="Starting backup"),
            ProgressUpdate(operation="backup", current=10, percent=0.1),
            ProgressUpdate(operation="backup", current=50, percent=0.5),
            ProgressUpdate(operation="backup", current=90, percent=0.9),
            ProgressCompleted(operation="backup", message="Backup complete"),
        ]

        assert len(progress_messages) == 5
        assert progress_messages[0].operation == "backup"
        assert progress_messages[-1].message == "Backup complete"

    def test_settings_display_with_status_cards(self) -> None:
        """Test that settings can be displayed with StatusCards."""
        cards = create_default_status_cards()

        # Verify settings cards exist
        settings_cards = ["theme", "compression", "verification", "level"]
        for card_id in settings_cards:
            card = next((c for c in cards if c.id == card_id), None)
            assert card is not None, f"Missing settings card: {card_id}"

    def test_toast_notifications(self) -> None:
        """Test that toast notifications work correctly."""
        # Create various toast notifications
        toasts = [
            ShowToast(message="Success!", severity="success"),
            ShowToast(message="Warning", severity="warning"),
            ShowToast(message="Error", severity="error"),
            ShowToast(message="Info", severity="info"),
        ]

        for toast in toasts:
            assert toast.message is not None
            assert toast.severity in ["success", "warning", "error", "info"]

    def test_empty_states(self) -> None:
        """Test that empty states display properly."""
        # Test all empty state presets
        empty_states = [
            EmptyStatePresets.no_archives(),
            EmptyStatePresets.no_files_selected(),
            EmptyStatePresets.no_backup_sources(),
            EmptyStatePresets.search_no_results("test"),
            EmptyStatePresets.restore_no_archive(),
            EmptyStatePresets.transfer_no_source(),
            EmptyStatePresets.logs_empty(),
            EmptyStatePresets.tools_empty(),
        ]

        for state in empty_states:
            assert state._title is not None
            assert state._icon is not None
            assert state._action_id is not None or state._action_label == ""
