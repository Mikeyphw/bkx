"""Core typed errors.

Contract rule:
- Core raises typed errors only.
- CLI maps typed errors -> stable exit codes.

Exit code mapping:
- 0: Success (no error)
- 1: Generic error
- 2: Invalid usage / validation
- 3: Not found
- 4: Permission denied
- 5: External/system failure
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class BkxError(Exception):
    """Base error for all BKX core failures.

    All typed errors derive from this base class.
    """

    msg: str
    details: dict[str, Any] = field(default_factory=dict)

    def __str__(self) -> str:
        return self.msg


class ValidationError(BkxError):
    """Invalid input or configuration.

    Exit code: 2 (invalid usage)
    """

    pass


class NotFoundError(BkxError):
    """Requested resource does not exist.

    Exit code: 3 (not found)
    """

    pass


class PermissionDeniedError(BkxError):
    """Insufficient permissions for operation.

    Exit code: 4 (permission denied)
    """

    pass


class ExternalToolError(BkxError):
    """External tool execution failed.

    Exit code: 5 (external/system failure)
    """

    tool_name: str | None = None
    exit_code: int | None = None
    stderr: str | None = None

    def __init__(
        self,
        msg: str,
        tool_name: str | None = None,
        exit_code: int | None = None,
        stderr: str | None = None,
        **kwargs: Any,
    ) -> None:
        super().__init__(msg, **kwargs)
        self.tool_name = tool_name
        self.exit_code = exit_code
        self.stderr = stderr
