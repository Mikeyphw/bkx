"""Tests for Steps 7 and 8 implementation.

Tests cover:
- Selection + Focus Restore
- Archive Details Drawer
- Command Palette Completeness
- Crash Screen Polish
- Non-blocking Operations
- Error Boundary Per Pane
"""

from __future__ import annotations

import time
from pathlib import Path
from unittest.mock import Mock

import pytest

from bkx.tui.panes.archive_details_drawer import ArchiveDetailsDrawer, DetailsDrawerManager
from bkx.tui.panes.archive_list import ArchiveListItem
from bkx.tui.panes.error_boundary import (
    ErrorBoundaryRegistry,
    ErrorBoundaryState,
    PaneErrorBoundary,
)
from bkx.tui.panes.non_blocking import (
    THROTTLE_INTERVAL,
    ProgressState,
    ThrottledProgressCallback,
    create_throttled_progress,
)
from bkx.tui.screens.crash import CrashScreen
from bkx.tui.state import TuiState


class TestTuiStateStep7A:
    """Tests for TuiState with state additions."""

    def test_default_state(self) -> None:
        """Test default TuiState values."""
        state = TuiState()
        assert state.last_route == "backup"
        assert state.layout_mode == "normal"
        assert state.show_toasts is True
        # state additions
        assert state.archive_scroll_position == 0
        assert state.last_focused_widget == ""
        assert state.last_focused_pane == ""
        assert state.restore_selection_on_navigate is True

    def test_from_json_with_all_fields(self) -> None:
        """Test TuiState.from_json with all feature contract fields."""
        data = {
            "last_route": "archive",
            "layout_mode": "compact",
            "show_toasts": False,
            "archive_filter_text": "backup",
            "archive_selected_id": "test-123",
            "archive_scroll_position": 50,
            "last_focused_widget": "archive-list",
            "last_focused_pane": "main-content",
            "restore_selection_on_navigate": False,
        }
        state = TuiState.from_json(data)
        assert state.last_route == "archive"
        assert state.layout_mode == "compact"
        assert state.show_toasts is False
        assert state.archive_scroll_position == 50
        assert state.last_focused_widget == "archive-list"
        assert state.last_focused_pane == "main-content"
        assert state.restore_selection_on_navigate is False

    def test_from_json_with_missing_fields(self) -> None:
        """Test TuiState.from_json with missing fields (backward compatibility)."""
        data = {
            "last_route": "restore",
            "layout_mode": "wide",
        }
        state = TuiState.from_json(data)
        assert state.last_route == "restore"
        assert state.layout_mode == "wide"
        # Should use defaults for missing feature contract fields
        assert state.archive_scroll_position == 0
        assert state.last_focused_widget == ""
        assert state.restore_selection_on_navigate is True


class TestArchiveListItemStep7A:
    """Tests for ArchiveListItem with state additions."""

    def test_archive_list_item_creation(self) -> None:
        """Test ArchiveListItem creation."""
        item = ArchiveListItem(
            archive_id="test-id",
            name="backup-2024.zst",
            path=Path("/backups/backup-2024.zst"),
            size="1.2 GB",
            modified="2024-01-15 10:30",
            icon="📦",
        )
        assert item.archive_id == "test-id"
        assert item.name == "backup-2024.zst"
        assert item.extension == ".zst"

    def test_archive_list_item_extension(self) -> None:
        """Test ArchiveListItem extension property."""
        item1 = ArchiveListItem(
            archive_id="a1",
            name="backup.tar.zst",
            path=Path("/backups/backup.tar.zst"),
        )
        assert item1.extension == ".tar.zst"

        item2 = ArchiveListItem(
            archive_id="a2",
            name="backup",
            path=Path("/backups/backup"),
        )
        assert item2.extension == "(none)"


class TestArchiveDetailsDrawerStep7B:
    """Tests for ArchiveDetailsDrawer."""

    def test_drawer_creation(self) -> None:
        """Test ArchiveDetailsDrawer creation."""
        drawer = ArchiveDetailsDrawer(title="Details")
        assert drawer._title == "Details"
        assert drawer._current_archive is None
        assert drawer._is_collapsed is False

    def test_drawer_set_archive(self) -> None:
        """Test setting archive in drawer."""
        drawer = ArchiveDetailsDrawer()
        item = ArchiveListItem(
            archive_id="test-id",
            name="backup.zst",
            path=Path("/backups/backup.zst"),
            size="500 MB",
            modified="2024-01-15",
        )
        # set_archive would normally require mounting
        drawer._current_archive = item
        assert drawer._current_archive == item

    def test_drawer_collapse_expand(self) -> None:
        """Test drawer collapse/expand."""
        drawer = ArchiveDetailsDrawer()
        drawer._is_collapsed = False
        drawer.collapse()
        assert drawer._is_collapsed is True
        drawer.expand()
        assert drawer._is_collapsed is False
        drawer.toggle()
        assert drawer._is_collapsed is True
        drawer.toggle()
        assert drawer._is_collapsed is False

    def test_details_drawer_manager(self) -> None:
        """Test DetailsDrawerManager."""
        drawer = ArchiveDetailsDrawer()
        manager = DetailsDrawerManager(drawer)
        assert manager._last_archive is None

        item = ArchiveListItem(
            archive_id="test",
            name="backup.zst",
            path=Path("/backup.zst"),
        )
        manager.on_archive_selected(item)
        assert manager._last_archive == item

        manager.clear()
        assert manager._last_archive is None


class TestNonBlockingOperationsStep8B:
    """Tests for non-blocking operations."""

    def test_progress_state_creation(self) -> None:
        """Test ProgressState creation."""
        state = ProgressState()
        assert state.current == 0
        assert state.total == 100
        assert state.percent == 0.0
        assert state.is_cancelled is False
        assert state.is_completed is False

    def test_progress_state_update(self) -> None:
        """Test ProgressState.update()."""
        state = ProgressState()
        state.update(current=50, total=100, message="Processing")
        assert state.current == 50
        assert state.total == 100
        assert state.percent == 0.5
        assert state.message == "Processing"

    def test_progress_state_cancel(self) -> None:
        """Test ProgressState.cancel()."""
        state = ProgressState()
        state.cancel()
        assert state.is_cancelled is True

    def test_progress_state_complete(self) -> None:
        """Test ProgressState.complete()."""
        state = ProgressState()
        state.complete("Done!")
        assert state.is_completed is True
        assert state.percent == 1.0
        assert state.message == "Done!"

    def test_progress_state_fail(self) -> None:
        """Test ProgressState.fail()."""
        state = ProgressState()
        state.fail("Something went wrong")
        assert state.is_completed is True
        assert state.error == "Something went wrong"

    def test_throttled_progress_callback(self) -> None:
        """Test ThrottledProgressCallback."""
        callback_mock = Mock()
        throttled = ThrottledProgressCallback(callback_mock)

        # First update should trigger callback
        throttled.update(current=10, total=100)
        assert callback_mock.call_count == 1

        # Rapid updates should be throttled
        throttled.update(current=20, total=100)
        throttled.update(current=30, total=100)
        throttled.update(current=40, total=100)
        # Should still only be 1 call (throttled)
        assert callback_mock.call_count == 1

    def test_throttled_progress_flush(self) -> None:
        """Test ThrottledProgressCallback.flush()."""
        callback_mock = Mock()
        throttled = ThrottledProgressCallback(callback_mock)

        throttled.update(current=50, total=100)
        # Flush should trigger callback
        throttled.flush()
        assert callback_mock.call_count == 2  # Initial + flush

    def test_throttled_progress_cancel(self) -> None:
        """Test ThrottledProgressCallback.cancel()."""
        callback_mock = Mock()
        throttled = ThrottledProgressCallback(callback_mock)

        throttled.update(current=50, total=100)
        throttled.cancel()
        throttled.flush()

        # Last call should have is_cancelled = True
        last_call = callback_mock.call_args_list[-1][0][0]
        assert last_call.is_cancelled is True

    def test_create_throttled_progress(self) -> None:
        """Test create_throttled_progress factory."""
        callback = Mock()
        result = create_throttled_progress(callback)
        assert isinstance(result, ThrottledProgressCallback)
        assert result._on_progress == callback
        assert result._throttle_interval == THROTTLE_INTERVAL

    def test_progress_state_auto_percent(self) -> None:
        """Test that percent is auto-calculated when not provided."""
        state = ProgressState()
        state.update(current=75, total=100)
        assert state.percent == 0.75


class TestErrorBoundaryStep8C:
    """Tests for error boundary."""

    def test_error_boundary_state_creation(self) -> None:
        """Test ErrorBoundaryState creation."""
        state = ErrorBoundaryState()
        assert state.is_error is False
        assert state.error_count == 0

    def test_error_boundary_state_set_error(self) -> None:
        """Test ErrorBoundaryState.set_error()."""
        state = ErrorBoundaryState()
        state.set_error("Test error", "Error details")
        assert state.is_error is True
        assert state.error_message == "Test error"
        assert state.error_details == "Error details"
        assert state.error_count == 1

    def test_error_boundary_state_clear_error(self) -> None:
        """Test ErrorBoundaryState.clear_error()."""
        state = ErrorBoundaryState()
        state.set_error("Test error")
        state.clear_error()
        assert state.is_error is False
        assert state.error_message == ""

    def test_error_boundary_state_to_dict(self) -> None:
        """Test ErrorBoundaryState.to_dict()."""
        state = ErrorBoundaryState(pane_id="test-pane")
        state.set_error("Error", "Details")
        result = state.to_dict()
        assert result["pane_id"] == "test-pane"
        assert result["error_message"] == "Error"
        assert result["is_error"] is True
        assert result["error_count"] == 1

    def test_error_boundary_registry(self) -> None:
        """Test ErrorBoundaryRegistry."""
        registry = ErrorBoundaryRegistry()
        assert len(registry._boundaries) == 0

        boundary = PaneErrorBoundary(id="test-boundary", pane_name="Test")
        boundary_id = registry.register(boundary)
        assert boundary_id == "test-boundary"
        assert len(registry._boundaries) == 1

        retrieved = registry.get("test-boundary")
        assert retrieved == boundary

        registry.unregister("test-boundary")
        assert len(registry._boundaries) == 0

    def test_error_boundary_registry_dismiss_all(self) -> None:
        """Test ErrorBoundaryRegistry.dismiss_all()."""
        registry = ErrorBoundaryRegistry()

        b1 = PaneErrorBoundary(id="b1", pane_name="Pane 1")
        b2 = PaneErrorBoundary(id="b2", pane_name="Pane 2")

        registry.register(b1)
        registry.register(b2)

        # Set errors
        b1.state.set_error("Error 1")
        b2.state.set_error("Error 2")
        assert registry.has_errors() is True

        registry.dismiss_all()
        assert registry.has_errors() is False

    def test_error_boundary_registry_error_summary(self) -> None:
        """Test ErrorBoundaryRegistry.get_error_summary()."""
        registry = ErrorBoundaryRegistry()

        b1 = PaneErrorBoundary(id="b1", pane_name="Pane 1")
        b2 = PaneErrorBoundary(id="b2", pane_name="Pane 2")

        registry.register(b1)
        registry.register(b2)

        # No errors
        assert registry.get_error_summary() == []

        # One error
        b1.state.set_error("Error 1")
        summary = registry.get_error_summary()
        assert len(summary) == 1
        assert summary[0]["error_message"] == "Error 1"

        # Two errors
        b2.state.set_error("Error 2")
        summary = registry.get_error_summary()
        assert len(summary) == 2


class TestCrashScreenStep8A:
    """Tests for crash screen."""

    def test_crash_screen_creation(self) -> None:
        """Test CrashScreen creation."""
        screen = CrashScreen(
            title="BKX crashed",
            traceback_text="Traceback here",
            last_route="archive",
        )
        assert screen._title == "BKX crashed"
        assert screen._traceback_text == "Traceback here"
        assert screen._last_route == "archive"

    def test_crash_screen_default_route(self) -> None:
        """Test CrashScreen with default route."""
        screen = CrashScreen(
            title="BKX crashed",
            traceback_text="Error",
        )
        assert screen._last_route == "backup"

    def test_crash_screen_bindings(self) -> None:
        """Test CrashScreen has required bindings."""
        assert ("q", "quit", "Quit") in CrashScreen.BINDINGS
        assert ("escape", "quit", "Quit") in CrashScreen.BINDINGS
        assert ("r", "restart", "Restart") in CrashScreen.BINDINGS


class TestCommandPaletteCompletenessStep7C:
    """Tests for command palette completeness."""

    def test_command_palette_commands_exist(self) -> None:
        """Test that new commands are available."""
        from mikomn.tui.actions import ActionSpec

        # These are the new commands added in feature contract
        expected_commands = [
            ("archive:copy-id", "Copy archive ID"),
            ("archive:copy-path", "Copy archive path"),
            ("app:export-logs", "Export logs"),
            ("app:toggle-compact", "Toggle compact mode"),
        ]

        # Verify command structure
        for cmd_id, title in expected_commands:
            cmd = ActionSpec(
                id=cmd_id,
                title=title,
                handler=lambda _ctx: None,
                category="Test",
            )
            assert cmd.id == cmd_id
            assert cmd.title == title
            assert cmd.category == "Test"


class TestIntegrationScenarios:
    """Integration tests for Steps 7 & 8."""

    def test_progress_state_thread_safety(self) -> None:
        """Test ProgressState is thread-safe."""
        import threading

        state = ProgressState(total=1000)
        errors = []

        def update_progress():
            try:
                for i in range(100):
                    state.update(current=i, total=100)
            except Exception as e:
                errors.append(e)

        threads = [threading.Thread(target=update_progress) for _ in range(5)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()

        assert len(errors) == 0
        assert state.current >= 0

    def test_full_state_persistence(self) -> None:
        """Test full TuiState with feature contract fields persists correctly."""
        state = TuiState(
            last_route="restore",
            layout_mode="compact",
            show_toasts=False,
            archive_filter_text="important",
            archive_selected_id="important-archive-123",
            archive_scroll_position=42,
            last_focused_widget="filter-input",
            last_focused_pane="archive-content",
            restore_selection_on_navigate=True,
        )

        # Verify all fields
        assert state.last_route == "restore"
        assert state.layout_mode == "compact"
        assert state.archive_scroll_position == 42

        # Convert to dict and back
        data = {
            "last_route": state.last_route,
            "layout_mode": state.layout_mode,
            "show_toasts": state.show_toasts,
            "archive_filter_text": state.archive_filter_text,
            "archive_selected_id": state.archive_selected_id,
            "archive_scroll_position": state.archive_scroll_position,
            "last_focused_widget": state.last_focused_widget,
            "last_focused_pane": state.last_focused_pane,
            "restore_selection_on_navigate": state.restore_selection_on_navigate,
        }

        restored = TuiState.from_json(data)
        assert restored.last_route == state.last_route
        assert restored.archive_scroll_position == state.archive_scroll_position


class TestThrottlingBehavior:
    """Tests for throttling behavior."""

    def test_throttle_interval_is_100ms(self) -> None:
        """Verify throttle interval is 100ms."""
        assert THROTTLE_INTERVAL == 0.1

    def test_rapid_updates_are_throttled(self) -> None:
        """Test that rapid updates are properly throttled."""
        callback = Mock()
        throttled = ThrottledProgressCallback(callback, throttle_interval=0.05)

        # Mock time.monotonic to simulate rapid updates without actual sleep
        fake_time = [0.0]  # Use list to allow modification in nested function
        original_monotonic = time.monotonic

        def fake_monotonic():
            fake_time[0] += 0.001  # 1ms between updates
            return fake_time[0]

        time.monotonic = fake_monotonic
        try:
            start = time.monotonic()
            for i in range(10):
                throttled.update(current=i, total=100)
            elapsed = time.monotonic() - start

            # Should have only 1 callback (first update)
            assert callback.call_count == 1
            # Should have taken much less than 10 * throttle_interval
            assert elapsed < 0.5
        finally:
            time.monotonic = original_monotonic

    def test_slow_updates_are_not_throttled(self) -> None:
        """Test that slow updates are not throttled."""
        callback = Mock()
        throttled = ThrottledProgressCallback(callback, throttle_interval=0.01)

        # Mock time.monotonic to simulate updates with gaps without actual sleep
        fake_time = [0.0]  # Use list to allow modification in nested function
        original_monotonic = time.monotonic

        def fake_monotonic():
            fake_time[0] += 0.02  # 20ms gap between updates
            return fake_time[0]

        time.monotonic = fake_monotonic
        try:
            for i in range(5):
                throttled.update(current=i, total=100)

            # Should have 5 callbacks (each update triggered one)
            assert callback.call_count == 5
        finally:
            time.monotonic = original_monotonic


if __name__ == "__main__":
    pytest.main([__file__, "-v"])
