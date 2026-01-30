"""Exit code mapping.

Contract rule:
- Exit codes are public API.
- Core raises typed errors only.
- CLI maps typed errors -> stable exit codes.

Implementation starts in Phase 2.
"""

from __future__ import annotations

# Stable exit codes per contract:
EXIT_OK = 0
EXIT_GENERIC = 1
EXIT_USAGE = 2
EXIT_NOT_FOUND = 3
EXIT_PERMISSION = 4
EXIT_EXTERNAL = 5

# TODO(Phase 2): map bkx.core.errors.* to these exit codes.
