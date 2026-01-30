"""Core typed errors.

Contract rule:
- Core raises typed errors only.
- CLI maps typed errors -> stable exit codes.

Implementation starts in Phase 1.
"""

from __future__ import annotations


class BkxError(Exception):
    """Base error for all BKX core failures."""


# TODO(Phase 1): Add small, stable set of typed errors with optional `.details` payload.
