"""Tests for core/errors.py - typed error hierarchy."""

from __future__ import annotations

import pytest

from bkx.core.errors import (
    BkxError,
    ValidationError,
    NotFoundError,
    PermissionDeniedError,
    ExternalToolError,
)


class TestBkxError:
    """Tests for base BkxError class."""

    def test_base_error_is_exception(self):
        """BkxError should be an Exception."""
        error = BkxError(msg="test error")
        assert isinstance(error, Exception)

    def test_base_error_has_msg(self):
        """BkxError should store the message."""
        error = BkxError(msg="test error")
        assert error.msg == "test error"

    def test_base_error_has_no_details(self):
        """BkxError should have empty details by default."""
        error = BkxError(msg="test error")
        assert error.details == {}

    def test_base_error_with_details(self):
        """BkxError should accept details."""
        error = BkxError(msg="test error", details={"key": "value"})
        assert error.details == {"key": "value"}


class TestValidationError:
    """Tests for ValidationError."""

    def test_validation_error_is_bkx_error(self):
        """ValidationError should be a BkxError."""
        error = ValidationError(msg="invalid input", details={"field": "name"})
        assert isinstance(error, BkxError)


class TestNotFoundError:
    """Tests for NotFoundError."""

    def test_not_found_error_is_bkx_error(self):
        """NotFoundError should be a BkxError."""
        error = NotFoundError(msg="file not found", details={"path": "/test"})
        assert isinstance(error, BkxError)


class TestPermissionDeniedError:
    """Tests for PermissionDeniedError."""

    def test_permission_denied_is_bkx_error(self):
        """PermissionDeniedError should be a BkxError."""
        error = PermissionDeniedError(msg="permission denied", details={"path": "/test"})
        assert isinstance(error, BkxError)


class TestExternalToolError:
    """Tests for ExternalToolError."""

    def test_external_tool_error_is_bkx_error(self):
        """ExternalToolError should be a BkxError."""
        error = ExternalToolError(
            msg="tool failed",
            tool_name="rsync",
            exit_code=1,
        )
        assert isinstance(error, BkxError)

    def test_external_tool_error_has_tool_info(self):
        """ExternalToolError should store tool information."""
        error = ExternalToolError(
            msg="tool failed",
            tool_name="rsync",
            exit_code=1,
        )
        assert error.tool_name == "rsync"
        assert error.exit_code == 1


class TestErrorToExitCode:
    """Tests for error to exit code mapping."""

    def test_validation_error_maps_to_usage(self):
        """ValidationError should map to exit code 2."""
        from bkx.cli.exit_codes import error_to_exit_code, EXIT_USAGE

        error = ValidationError(msg="invalid")
        assert error_to_exit_code(error) == EXIT_USAGE

    def test_not_found_error_maps_to_not_found(self):
        """NotFoundError should map to exit code 3."""
        from bkx.cli.exit_codes import error_to_exit_code, EXIT_NOT_FOUND

        error = NotFoundError(msg="not found")
        assert error_to_exit_code(error) == EXIT_NOT_FOUND

    def test_permission_denied_maps_to_permission(self):
        """PermissionDeniedError should map to exit code 4."""
        from bkx.cli.exit_codes import error_to_exit_code, EXIT_PERMISSION

        error = PermissionDeniedError(msg="denied")
        assert error_to_exit_code(error) == EXIT_PERMISSION

    def test_external_tool_maps_to_external(self):
        """ExternalToolError should map to exit code 5."""
        from bkx.cli.exit_codes import error_to_exit_code, EXIT_EXTERNAL

        error = ExternalToolError(msg="tool failed", tool_name="test")
        assert error_to_exit_code(error) == EXIT_EXTERNAL

    def test_unknown_bkx_error_maps_to_generic(self):
        """Unknown BkxError should map to exit code 1."""
        from bkx.cli.exit_codes import error_to_exit_code, EXIT_GENERIC

        error = BkxError(msg="unknown error")
        assert error_to_exit_code(error) == EXIT_GENERIC
