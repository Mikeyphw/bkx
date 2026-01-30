"""Tests for core/errors.py - typed error hierarchy."""

from __future__ import annotations

import pytest

from bkx.core.errors import (
    CoreError,
    ValidationError,
    NotFoundError,
    PermissionDeniedError,
    ExternalToolError,
    CoreErrorMixin,
)


class TestCoreError:
    """Tests for base CoreError class."""

    def test_base_error_is_exception(self):
        """CoreError should be an Exception."""
        error = CoreError(msg="test error")
        assert isinstance(error, Exception)

    def test_base_error_has_msg(self):
        """CoreError should store the message."""
        error = CoreError(msg="test error")
        assert error.msg == "test error"

    def test_base_error_has_no_details(self):
        """CoreError should have empty details by default."""
        error = CoreError(msg="test error")
        assert error.details == {}

    def test_base_error_with_details(self):
        """CoreError should accept details."""
        error = CoreError(msg="test error", details={"key": "value"})
        assert error.details == {"key": "value"}


class TestValidationError:
    """Tests for ValidationError."""

    def test_validation_error_is_core_error(self):
        """ValidationError should be a CoreError."""
        error = ValidationError(msg="invalid input", details={"field": "name"})
        assert isinstance(error, CoreError)

    def test_validation_error_has_category(self):
        """ValidationError should have correct category."""
        error = ValidationError(msg="invalid input")
        assert error.category == "validation"


class TestNotFoundError:
    """Tests for NotFoundError."""

    def test_not_found_error_is_core_error(self):
        """NotFoundError should be a CoreError."""
        error = NotFoundError(msg="file not found", details={"path": "/test"})
        assert isinstance(error, CoreError)

    def test_not_found_error_has_category(self):
        """NotFoundError should have correct category."""
        error = NotFoundError(msg="not found")
        assert error.category == "not_found"


class TestPermissionDeniedError:
    """Tests for PermissionDeniedError."""

    def test_permission_denied_is_core_error(self):
        """PermissionDeniedError should be a CoreError."""
        error = PermissionDeniedError(msg="permission denied", details={"path": "/test"})
        assert isinstance(error, CoreError)

    def test_permission_denied_has_category(self):
        """PermissionDeniedError should have correct category."""
        error = PermissionDeniedError(msg="denied")
        assert error.category == "permission_denied"


class TestExternalToolError:
    """Tests for ExternalToolError."""

    def test_external_tool_error_is_core_error(self):
        """ExternalToolError should be a CoreError."""
        error = ExternalToolError(
            msg="tool failed",
            tool_name="rsync",
            exit_code=1,
        )
        assert isinstance(error, CoreError)

    def test_external_tool_error_has_tool_info(self):
        """ExternalToolError should store tool information."""
        error = ExternalToolError(
            msg="tool failed",
            tool_name="rsync",
            exit_code=1,
        )
        assert error.tool_name == "rsync"
        assert error.exit_code == 1

    def test_external_tool_error_has_category(self):
        """ExternalToolError should have correct category."""
        error = ExternalToolError(msg="tool failed", tool_name="test")
        assert error.category == "external_tool"


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

    def test_unknown_core_error_maps_to_generic(self):
        """Unknown CoreError should map to exit code 1."""
        from bkx.cli.exit_codes import error_to_exit_code, EXIT_GENERIC

        error = CoreError(msg="unknown error")
        assert error_to_exit_code(error) == EXIT_GENERIC

