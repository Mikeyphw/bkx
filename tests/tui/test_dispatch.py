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
from unittest.mock import Mock, patch
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

    def test_backup_intent_creates_spec(self) -> None:
        """Test that BackupIntent produces correct spec params."""
        intent = BackupIntent(
            sources=[Path("/test")],
            destination=Path("/backup"),
            algorithm="zstd",
        )
        params = intent.to_spec_params()
        assert params["sources"] == [Path("/test")]
        assert params["destination"] == Path("/backup")
        assert params["algorithm"] == "zstd"

    def test_backup_intent_with_verify_false(self) -> None:
        """Test BackupIntent with verify=False."""
        intent = BackupIntent(
            sources=[Path("/test")],
            verify=False,
        )
        params = intent.to_spec_params()
        assert params["verify"] is False


class TestArchiveIntentDispatch:
    """Tests for ArchiveIntent dispatch behavior."""

    def test_archive_intent_creates_spec(self) -> None:
        """Test that ArchiveIntent produces correct spec params."""
        intent = ArchiveIntent(
            sources=[Path("/data")],
            output=Path("/output/archive.tar.zst"),
            algorithm="zstd",
        )
        params = intent.to_spec_params()
        assert params["sources"] == [Path("/data")]
        assert params["output"] == Path("/output/archive.tar.zst")
        assert params["algorithm"] == "zstd"

    def test_archive_intent_with_excludes(self) -> None:
        """Test ArchiveIntent with excludes."""
        intent = ArchiveIntent(
            sources=[Path("/data")],
            output=Path("/output/archive.tar.zst"),
            excludes=["*.log", "*.tmp"],
        )
        params = intent.to_spec_params()
        assert params["excludes"] == ["*.log", "*.tmp"]


class TestRestoreIntentDispatch:
    """Tests for RestoreIntent dispatch behavior."""

    def test_restore_intent_creates_spec(self) -> None:
        """Test that RestoreIntent produces correct spec params."""
        intent = RestoreIntent(
            archive=Path("/backup/archive.tar.zst"),
            destination=Path("/restore"),
            fresh_dir=True,
        )
        params = intent.to_spec_params()
        assert params["archive"] == Path("/backup/archive.tar.zst")
        assert params["destination"] == Path("/restore")
        assert params["fresh_dir"] is True

    def test_restore_intent_with_overwrite(self) -> None:
        """Test RestoreIntent with overwrite=True."""
        intent = RestoreIntent(
            archive=Path("/backup/archive.tar.zst"),
            overwrite=True,
        )
        params = intent.to_spec_params()
        assert params["overwrite"] is True


class TestTransferIntentDispatch:
    """Tests for TransferIntent dispatch behavior."""

    def test_transfer_intent_creates_spec(self) -> None:
        """Test that TransferIntent produces correct spec params."""
        intent = TransferIntent(
            source=Path("/local"),
            destination=Path("/remote"),
            direction="push",
        )
        params = intent.to_spec_params()
        assert params["source"] == Path("/local")
        assert params["destination"] == Path("/remote")
        assert params["direction"] == "push"

    def test_transfer_intent_pull_direction(self) -> None:
        """Test TransferIntent with pull direction."""
        intent = TransferIntent(
            source=Path("/remote"),
            destination=Path("/local"),
            direction="pull",
        )
        params = intent.to_spec_params()
        assert params["direction"] == "pull"


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
            assert isinstance(intent, Message), (
                f"{type(intent).__name__} should be a Message subclass"
            )
