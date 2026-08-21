"""Stable in-process Android boundary for the canonical BKX engine.

This module deliberately exposes JSON-only functions so the Kotlin/Chaquopy
boundary cannot depend on Python object identity or CLI/TUI implementation
 details. Android owns platform integration; :mod:`bkx.core` owns BKX semantics.
"""

from .engine import cancel_json, execute_json, readiness_json, supported_operations

__all__ = ["cancel_json", "execute_json", "readiness_json", "supported_operations"]
