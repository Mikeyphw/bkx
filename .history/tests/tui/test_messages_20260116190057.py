"""Tests for TUI message schema (intents).

Tests focus on:
- Message schema validation
- Intent creation with correct defaults
- to_spec_params() conversion

Contract rule:
- Tests message dispatch behavior, not widget layout.
- No business logic in message tests.
"""

from __future__ import annotations

from pathlib import Path
import pytest

from bkx.tui.messages import (
    BackupIntent,
    ArchiveIntent,
    RestoreIntent,
    TransferIntent,
    NavigateToScreen,
    ShowCommandPalette,
    CycleTheme,
    SetBusy,
    ClearBusy,
    ShowNotification,
    UpdateLog,
    FilesSelected,
    DirectoriesSelected,
    ServiceResult,
    BackupResult,
    ArchiveResult,
    RestoreResult,
    TransferResult,
    ServiceError,
)


class TestBackupIntent:
    """Tests for BackupIntent message."""

    def test_default_values(self) -> None:
        """Test BackupIntent has correct default values."""
        intent = BackupIntent()
        assert intent.sources == []
        assert intent.destination is None
        assert intent.archive_name is None
        assert intent.excludes == []
        assert intent.algorithm == "zstd"
        assert intent.level == 3
        assert intent.verify is True

    def test_with_sources(self) -> None:
        """Test BackupIntent with sources."""
        sources = [Path("/home/user/docs"), Path("/home/user/pictures")]
        intent = BackupIntent(sources=sources)
        assert intent.sources == sources

    def test_with_all_params(self) -> None:
        """Test BackupIntent with all parameters."""
        sources = [Path("/home/user")]
        destination = Path("/backup")
        intent = BackupIntent(
            sources=sources,
            destination=destination,
            archive_name="backup_2024",
            excludes=["*.tmp", "node_modules"],
            algorithm="zstd",
            level=6,
            verify=False,
        )
        assert intent.sources == sources
        assert intent.destination == destination
        assert intent.archive_name == "backup_2024"
        assert intent.excludes == ["*.tmp", "node_modules"]
        assert intent.algorithm == "zstd"
        assert intent.level == 6
        assert intent.verify is False

    def test_to_spec_params(self) -> None:
        """Test to_spec_params() returns correct dict."""
        sources = [Path("/home/user")]
        intent = BackupIntent(sources=sources, algorithm="lz4")
        params = intent.to_spec_params()
        assert params["sources"] == sources
        assert params["algorithm"] == "lz4"
        assert "sources" in params
        assert "algorithm" in params


class TestArchiveIntent:
    """Tests for ArchiveIntent message."""

    def test_default_values(self) -> None:
        """Test ArchiveIntent has correct default values."""
        intent = ArchiveIntent()
        assert intent.sources == []
        assert intent.output is None
        assert intent.excludes == []
        assert intent.algorithm == "zstd"
        assert intent.verify is True

    def test_to_spec_params(self) -> None:
        """Test to_spec_params() returns correct dict."""
        sources = [Path("/data")]
        output = Path("/output/archive.tar.zst")
        intent = ArchiveIntent(sources=sources, output=output)
        params = intent.to_spec_params()
        assert params["sources"] == sources
        assert params["output"] == output


class TestRestoreIntent:
    """Tests for RestoreIntent message."""

    def test_default_values(self) -> None:
        """Test RestoreIntent has correct default values."""
        intent = RestoreIntent(archive=Path("/backup/archive.tar.zst"))
        assert intent.archive == Path("/backup/archive.tar.zst")
        assert intent.destination is None
        assert intent.fresh_dir is False
        assert intent.overwrite is False
        assert intent.verify is True

    def test_to_spec_params(self) -> None:
        """Test to_spec_params() returns correct dict."""
        archive = Path("/backup/archive.tar.zst")
        destination = Path("/restore")
        intent = RestoreIntent(
            archive=archive,
            destination=destination,
            fresh_dir=True,
            overwrite=True,
        )
        params = intent.to_spec_params()
        assert params["archive"] == archive
        assert params["destination"] == destination
        assert params["fresh_dir"] is True
        assert params["overwrite"] is True


class TestTransferIntent:
    """Tests for TransferIntent message."""

    def test_default_values(self) -> None:
        """Test TransferIntent has correct default values."""
        intent = TransferIntent(
            source=Path("/local/file"),
            destination=Path("/remote/file"),
        )
        assert intent.source == Path("/local/file")
        assert intent.destination == Path("/remote/file")
        assert intent.direction == "push"
        assert intent.verify is True

    def test_pull_direction(self) -> None:
        """Test TransferIntent with pull direction."""
        intent = TransferIntent(
            source=Path("/remote/file"),
            destination=Path("/local/file"),
            direction="pull",
        )
        assert intent.direction == "pull"

    def test_to_spec_params(self) -> None:
        """Test to_spec_params() returns correct dict."""
        intent = TransferIntent(
            source=Path("/src"),
            destination=Path("/dst"),
            verify=False,
        )
        params = intent.to_spec_params()
        assert params["source"] == Path("/src")
        assert params["destination"] == Path("/dst")
        assert params["verify"] is False


class TestNavigationMessages:
    """Tests for navigation messages."""

    def test_navigate_to_screen(self) -> None:
        """Test NavigateToScreen message."""
        intent = NavigateToScreen(screen_name="backup")
        assert intent.screen_name == "backup"

    def test_show_command_palette(self) -> None:
        """Test ShowCommandPalette message."""
        intent = ShowCommandPalette()
        assert isinstance(intent, NavigateToScreen.__bases__[0])

    def test_cycle_theme(self) -> None:
        """Test CycleTheme message."""
        intent = CycleTheme()
        assert isinstance(intent, NavigateToScreen.__bases__[0])


class TestUIStateMessages:
    """Tests for UI state messages."""

    def test_set_busy_default(self) -> None:
        """Test SetBusy with default message."""
        intent = SetBusy()
        assert intent.message == "Processing..."

    def test_set_busy_custom(self) -> None:
        """Test SetBusy with custom message."""
        intent = SetBusy(message="Backing up files...")
        assert intent.message == "Backing up files..."

    def test_clear_busy(self) -> None:
        """Test ClearBusy message."""
        intent = ClearBusy()
        assert isinstance(intent, NavigateToScreen.__bases__[0])

    def test_show_notification(self) -> None:
        """Test ShowNotification message."""
        intent = ShowNotification(message="Backup complete!", level="success")
        assert intent.message == "Backup complete!"
        assert intent.level == "success"

    def test_update_log(self) -> None:
        """Test UpdateLog message."""
        intent = UpdateLog(message="Starting backup", level="info")
        assert intent.message == "Starting backup"
        assert intent.level == "info"

    def test_update_log_debug(self) -> None:
        """Test UpdateLog with debug level."""
        intent = UpdateLog(message="Debug info", level="debug")
        assert intent.level == "debug"


class TestFilePickerMessages:
    """Tests for file picker messages."""

    def test_files_selected(self) -> None:
        """Test FilesSelected message."""
        paths = [Path("/file1.txt"), Path("/file2.txt")]
        intent = FilesSelected(paths=paths, picker_for="backup")
        assert intent.paths == paths
        assert intent.picker_for == "backup"

    def test_directories_selected(self) -> None:
        """Test DirectoriesSelected message."""
        paths = [Path("/dir1"), Path("/dir2")]
        intent = DirectoriesSelected(paths=paths, picker_for="archive")
        assert intent.paths == paths
        assert intent.picker_for == "archive"


class TestServiceResultMessages:
    """Tests for service result messages."""

    def test_service_result_default(self) -> None:
        """Test ServiceResult with defaults."""
        result = ServiceResult()
        assert result.success is False
        assert result.message == ""
        assert result.details == {}

    def test_backup_result(self) -> None:
        """Test BackupResult message."""
        result = BackupResult(
            success=True,
            message="Backup complete",
            archive_path=Path("/backup/archive.tar.zst"),
            files_count=100,
            bytes_total=1024000,
        )
        assert result.success is True
        assert result.archive_path == Path("/backup/archive.tar.zst")
        assert result.files_count == 100

    def test_archive_result(self) -> None:
        """Test ArchiveResult message."""
        result = ArchiveResult(
            success=True,
            archive_path=Path("/output/archive.tar.zst"),
            files_count=50,
        )
        assert result.archive_path == Path("/output/archive.tar.zst")
        assert result.files_count == 50

    def test_restore_result(self) -> None:
        """Test RestoreResult message."""
        result = RestoreResult(
            success=True,
            destination_path=Path("/restore"),
            files_count=100,
        )
        assert result.destination_path == Path("/restore")
        assert result.files_count == 100

    def test_transfer_result(self) -> None:
        """Test TransferResult message."""
        result = TransferResult(
            success=True,
            source_path=Path("/source"),
            destination_path=Path("/dest"),
        )
        assert result.source_path == Path("/source")
        assert result.destination_path == Path("/dest")

    def test_service_error(self) -> None:
        """Test ServiceError message."""
        result = ServiceError(
            success=False,
            message="Backup failed",
            error_code=10,
            error_details={"reason": "disk full"},
        )
        assert result.success is False
        assert result.error_code == 10
        assert result.error_details["reason"] == "disk full"

