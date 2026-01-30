"""Exit code mapping.

Contract rule:
- Exit codes are public API.
- Core raises typed errors only.
- CLI maps typed errors -> stable exit codes.

Exit code discipline:
| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Generic error |
| 2 | Invalid usage / validation |
| 3 | Not found |
| 4 | Permission denied |
| 5 | External/system failure |
"""

from __future__ import annotations

from bkx.core.errors import (
    BkxError,
    ValidationError,
    NotFoundError,
    PermissionDeniedError,
    ExternalToolError,
)

# Stable exit codes per contract:
EXIT_OK = 0
EXIT_GENERIC = 1
EXIT_USAGE = 2
EXIT_NOT_FOUND = 3
EXIT_PERMISSION = 4
EXIT_EXTERNAL = 5


def error_to_exit_code(error: CoreError) -> int:
    """Map typed error to exit code.

    Args:
        error: A typed error from core.

    Returns:
        Exit code matching the error category.
    """
    if isinstance(error, ValidationError):
        return EXIT_USAGE
    if isinstance(error, NotFoundError):
        return EXIT_NOT_FOUND
    if isinstance(error, PermissionDeniedError):
        return EXIT_PERMISSION
    if isinstance(error, ExternalToolError):
        return EXIT_EXTERNAL
    # Fallback for any other CoreError
    return EXIT_GENERIC
