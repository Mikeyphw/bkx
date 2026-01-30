"""Tests for TUI message dispatch behavior.

Tests focus on:
- Message → dispatch behavior (not widget layout)
- Intent handlers receiving and processing messages correctly
- Spec building from intents

Contract rule:
- Tests message dispatch behavior, not widget layout.
- Uses mocks to avoid actual service calls.
- No business logic in dispatch tests.
"""

from __future__ import annotations

from pathlib import Path
from unittest.mock import Mock, patch, MagicMock
import pytest

from bkx.tui.messages import (
    BackupIntent,
    ArchiveIntent,
    RestoreIntent,
    TransferIntent,
    NavigateToScreen,
    SetBusy,
    ClearBusy,
    UpdateLog,
    ShowNotification,
)


class TestBackupIntentDispatch:
    """Tests for BackupIntent dispatch behavior."""

    @pytest.fixture
    def mock_spec_builder(self) -> Mock:
        """Mock spec builder for backup."""
        with patch("bkx.cli.spec_builders.build_backup_spec") as mock:
            mock_spec = Mock()
            mock_spec.sources = (Path("/test"),)
            mock.return_value = mock_spec
            yield mock

    @pytest.fixture
    def mock_service(self) -> Mock:
        """Mock backup service."""
        with patch("bkx.tui.app.get_backup_service") as mock:
            mock_service = Mock()
            mock_result = Mock()
            mock_result.archive_path = Path("/backup/test.tar.zst")
            mock_result.files_count = 10
            mock_result.bytes_total = 102400
            mock_service.create.return_value = mock_result
            mock.return_value = mock_service
            yield mock

    def test_backup_intent_creates_spec(
        self, mock_spec_builder: Mock, mock_service: Mock
    ) -> None:
        """Test that BackupIntent triggers spec building."""
        intent = BackupIntent(
            sources=[Path("/test")],
            destination=Path("/backup"),
            algorithm="zstd",
        )
        params = intent.to_spec_params()
        mock_spec_builder.assert_called_once_with(**params)

    def test_backup_intent_with_verify_false(self, mock_spec_builder: Mock) -> None:
        """Test BackupIntent with verify=False."""
        intent = BackupIntent(
            sources=[Path("/test")],
            verify=False,
        )
        params = intent.to_spec_params()
        assert params["verify"] is False
        mock_spec_builder.assert_called_once_with(**params)


class TestArchiveIntentDispatch:
    """Tests for ArchiveIntent dispatch behavior."""

    @pytest.fixture
    def mock_spec_builder(self) -> Mock:
        """Mock spec builder for archive."""
        with patch("bkx.tui.app.build_archive_spec") as mock:
            mock.return_value = Mock()
            yield mock

    def test_archive_intent_creates_spec(
        self, mock_spec_builder: Mock
    ) -> None:
        """Test that ArchiveIntent triggers spec building."""
        intent = ArchiveIntent(
            sources=[Path("/data")],
            output=Path("/output/archive.tar.zst"),
            algorithm="zstd",
        )
        params = intent.to_spec_params()
        mock_spec_builder.assert_called_once_with(**params)

    def test_archive_intent_with_excludes(self, mock_spec_builder: Mock) -> None:
        """Test ArchiveIntent with excludes."""
        intent = ArchiveIntent(
            sources=[Path("/data")],
            output=Path("/output/archive.tar.zst"),
            excludes=["*.log", "*.tmp"],
        )
        params = intent.to_spec_params()
        assert params["excludes"] == ["*.log", "*.tmp"]
        mock_spec_builder.assert_called_once_with(**params)


class TestRestoreIntentDispatch:
    """Tests for RestoreIntent dispatch behavior."""

    @pytest.fixture
    def mock_spec_builder(self) -> Mock:
        """Mock spec builder for restore."""
        with patch("bkx.tui.app.build_restore_spec") as mock:
            mock.return_value = Mock()
            yield mock

    def test_restore_intent_creates_spec(
        self, mock_spec_builder: Mock
    ) -> None:
        """Test that RestoreIntent triggers spec building."""
        intent = RestoreIntent(
            archive=Path("/backup/archive.tar.zst"),
            destination=Path("/restore"),
            fresh_dir=True,
        )
        params = intent.to_spec_params()
        mock_spec_builder.assert_called_once_with(**params)

    def test_restore_intent_with_overwrite(self, mock_spec_builder: Mock) -> None:
        """Test RestoreIntent with overwrite=True."""
        intent = RestoreIntent(
            archive=Path("/backup/archive.tar.zst"),
            overwrite=True,
        )
        params = intent.to_spec_params()
        assert params["overwrite"] is True
        mock_spec_builder.assert_called_once_with(**params)


class TestTransferIntentDispatch:
    """Tests for TransferIntent dispatch behavior."""

    @pytest.fixture
    def mock_spec_builder(self) -> Mock:
        """Mock spec builder for transfer."""
        with patch("bkx.tui.app.build_transfer_spec") as mock:
            mock.return_value = Mock()
            yield mock

    def test_transfer_intent_creates_spec(
        self, mock_spec_builder: Mock
    ) -> None:
        """Test that TransferIntent triggers spec building."""
        intent = TransferIntent(
            source=Path("/local"),
            destination=Path("/remote"),
            direction="push",
        )
        params = intent.to_spec_params()
        mock_spec_builder.assert_called_once_with(**params)

    def test_transfer_intent_pull_direction(self, mock_spec_builder: Mock) -> None:
        """Test TransferIntent with pull direction."""
        intent = TransferIntent(
            source=Path("/remote"),
            destination=Path("/local"),
            direction="pull",
        )
        params = intent.to_spec_params()
        assert params["direction"] == "pull"
        mock_spec_builder.assert_called_once_with(**params)


class TestNavigationDispatch:
    """Tests for navigation message dispatch."""

    def test_navigate_to_backup_screen(self) -> None:
        """Test NavigateToScreen with backup."""
        intent = NavigateToScreen(screen_name="backup")
        assert intent.screen_name == "backup"

    def test_navigate_to_archive_screen(self) -> None:
        """Test NavigateToScreen with archive."""
        intent = NavigateToScreen(screen_name="archive")
        assert intent.screen_name == "archive"

    def test_navigate_to_restore_screen(self) -> None:
        """Test NavigateToScreen with restore."""
        intent = NavigateToScreen(screen_name="restore")
        assert intent.screen_name == "restore"

    def test_navigate_to_transfer_screen(self) -> None:
        """Test NavigateToScreen with transfer."""
        intent = NavigateToScreen(screen_name="transfer")
        assert intent.screen_name == "transfer"

    def test_navigate_to_logs_screen(self) -> None:
        """Test NavigateToScreen with logs."""
        intent = NavigateToScreen(screen_name="logs")
        assert intent.screen_name == "logs"

    def test_navigate_to_tools_screen(self) -> None:
        """Test NavigateToScreen with tools."""
        intent = NavigateToScreen(screen_name="tools")
        assert intent.screen_name == "tools"

    def test_navigate_to_unknown_screen(self) -> None:
        """Test NavigateToScreen with unknown screen."""
        intent = NavigateToScreen(screen_name="unknown")
        assert intent.screen_name == "unknown"


class TestUIStateDispatch:
    """Tests for UI state message dispatch."""

    def test_set_busy_message(self) -> None:
        """Test SetBusy message contains correct message."""
        intent = SetBusy(message="Processing backup...")
        assert intent.message == "Processing backup..."

    def test_clear_busy_message(self) -> None:
        """Test ClearBusy message structure."""
        intent = ClearBusy()
        assert isinstance(intent, ClearBusy)

    def test_update_log_message(self) -> None:
        """Test UpdateLog message contains correct data."""
        intent = UpdateLog(message="Backup started", level="info")
        assert intent.message == "Backup started"
        assert intent.level == "info"

    def test_update_log_error_level(self) -> None:
        """Test UpdateLog with error level."""
        intent = UpdateLog(message="Backup failed", level="error")
        assert intent.level == "error"

    def test_show_notification_info(self) -> None:
        """Test ShowNotification with info level."""
        intent = ShowNotification(message="Task complete", level="info")
        assert intent.message == "Task complete"
        assert intent.level == "info"

    def test_show_notification_success(self) -> None:
        """Test ShowNotification with success level."""
        intent = ShowNotification(message="Backup successful", level="success")
        assert intent.level == "success"

    def test_show_notification_warning(self) -> None:
        """Test ShowNotification with warning level."""
        intent = ShowNotification(message="Low disk space", level="warning")
        assert intent.level == "warning"

    def test_show_notification_error(self) -> None:
        """Test ShowNotification with error level."""
        intent = ShowNotification(message="Operation failed", level="error")
        assert intent.level == "error"


class TestIntentHandlerProtocol:
    """Tests for IntentHandler protocol compliance."""

    def test_intent_subclasses_message(self) -> None:
        """Test all intents are Message subclasses."""
        from textual.message import Message

        intents = [
            BackupIntent(sources=[Path("/test")]),
            ArchiveIntent(sources=[Path("/test")], output=Path("/out.tar.zst")),
            RestoreIntent(archive=Path("/archive.tar.zst")),
            TransferIntent(source=Path("/src"), destination=Path("/dst")),
            NavigateToScreen(screen_name="backup"),
            SetBusy(),
            ClearBusy(),
            UpdateLog(message="test"),
            ShowNotification(message="test"),
        ]

        for intent in intents:
            assert isinstance(
                intent, Message
            ), f"{type(intent).__name__} should be a Message subclass"

