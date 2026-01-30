"""Tests for CLI layer - exit codes and rendering."""

from __future__ import annotations

import pytest
from typer.testing import CliRunner

from bkx.cli.app import app
from bkx.cli.exit_codes import (
    EXIT_OK,
    EXIT_GENERIC,
    EXIT_USAGE,
    EXIT_NOT_FOUND,
    EXIT_PERMISSION,
    EXIT_EXTERNAL,
)


class TestCLIExitCodes:
    """Tests for CLI exit codes."""

    def test_help_exits_zero(self):
        """Help should exit with code 0."""
        runner = CliRunner()
        result = runner.invoke(app, ["--help"])

        assert result.exit_code == EXIT_OK

    def test_backup_help_exits_zero(self):
        """Backup help should exit with code 0."""
        runner = CliRunner()
        result = runner.invoke(app, ["backup", "--help"])

        assert result.exit_code == EXIT_OK

    def test_archive_help_exits_zero(self):
        """Archive help should exit with code 0."""
        runner = CliRunner()
        result = runner.invoke(app, ["archive", "--help"])

        assert result.exit_code == EXIT_OK

    def test_transfer_help_exits_zero(self):
        """Transfer help should exit with code 0."""
        runner = CliRunner()
        result = runner.invoke(app, ["transfer", "--help"])

        assert result.exit_code == EXIT_OK

    def test_restore_help_exits_zero(self):
        """Restore help should exit with code 0."""
        runner = CliRunner()
        result = runner.invoke(app, ["restore", "--help"])

        assert result.exit_code == EXIT_OK


class TestCLIRendering:
    """Tests for CLI output rendering."""

    def test_json_flag_exists(self):
        """JSON flag should be available."""
        runner = CliRunner()
        result = runner.invoke(app, ["--help"])

        assert "--json" in result.output

    def test_dry_run_flag_exists(self):
        """Dry-run flag should be available."""
        runner = CliRunner()
        result = runner.invoke(app, ["--help"])

        assert "--dry-run" in result.output or "--plan" in result.output


class TestToolsCommand:
    """Tests for tools command."""

    def test_tools_detect_exists(self):
        """Tools detect command should exist."""
        runner = CliRunner()
        result = runner.invoke(app, ["tools", "--help"])

        assert result.exit_code == EXIT_OK
        assert "detect" in result.output

