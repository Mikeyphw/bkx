"""Intent history tracking - Step 9B

Provides a circular buffer for tracking user intents/events.
Used by debug overlay to show last N user actions.
"""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass
from datetime import datetime
from typing import Any


@dataclass(slots=True)
class IntentRecord:
    """A single intent/event record."""

    intent_type: str  # Class name of intent
    timestamp: float  # Unix timestamp
    timestamp_str: str  # Human-readable timestamp
    message: str  # Summary message
    params: dict[str, Any]  # Sanitized parameters (no sensitive data)

    @classmethod
    def from_intent(cls, intent: Any, message: str = "") -> IntentRecord:
        """Create a record from an intent object."""
        now = time.time()
        return cls(
            intent_type=type(intent).__name__,
            timestamp=now,
            timestamp_str=datetime.fromtimestamp(now).strftime("%H:%M:%S.%f")[:-3],
            message=message or f"{type(intent).__name__} intent",
            params=_sanitize_params(intent),
        )


def _sanitize_params(intent: Any) -> dict[str, Any]:
    """Extract and sanitize intent parameters.

    Removes or masks sensitive data like paths with passwords.
    """
    params: dict[str, Any] = {}

    # Get all attributes from the intent
    for key in dir(intent):
        if key.startswith("_"):
            continue
        if key in ("_parent", "parent"):
            continue
        try:
            value = getattr(intent, key)
        except Exception:
            continue

        # Skip private/method attributes
        if callable(value) and not isinstance(value, type):
            continue

        # Handle known sensitive fields
        if key in ("password", "secret", "token", "api_key"):
            params[key] = "***REDACTED***"
            continue

        # Handle Path objects - convert to string
        if hasattr(value, "__fspath__"):
            params[key] = str(value)
            continue

        # Handle lists of paths
        if isinstance(value, list):
            sanitized = []
            for item in value:
                if hasattr(item, "__fspath__"):
                    sanitized.append(str(item))
                else:
                    sanitized.append(item)
            params[key] = sanitized
            continue

        # Handle dataclasses - extract fields
        if hasattr(value, "__dataclass_fields__"):
            continue  # Skip nested dataclasses for brevity

        # Prune very long strings
        if isinstance(value, str) and len(value) > 200:
            params[key] = value[:200] + "...(truncated)"
            continue

        params[key] = value

    return params


class IntentHistory:
    """Circular buffer for tracking recent user intents.

    Thread-safe implementation for use across the app.
    """

    def __init__(self, max_size: int = 50) -> None:
        """Initialize the intent history buffer.

        Args:
            max_size: Maximum number of records to keep (default: 50)
        """
        self._max_size = max_size
        self._records: list[IntentRecord] = []
        self._lock = threading.Lock()

    @property
    def max_size(self) -> int:
        """Get the maximum buffer size."""
        return self._max_size

    def record(self, intent: Any, message: str = "") -> IntentRecord:
        """Record an intent.

        Args:
            intent: The intent object to record
            message: Optional custom message (auto-generated if empty)

        Returns:
            The created IntentRecord
        """
        record = IntentRecord.from_intent(intent, message)

        with self._lock:
            self._records.append(record)

            # Circular buffer: remove oldest if over capacity
            while len(self._records) > self._max_size:
                self._records.pop(0)

        return record

    def record_message(self, intent_type: str, message: str, params: dict[str, Any] | None = None) -> IntentRecord:
        """Record a synthetic intent/event (not from message bus).

        Args:
            intent_type: Type identifier for the event
            message: Human-readable message
            params: Optional parameters

        Returns:
            The created IntentRecord
        """
        now = time.time()
        record = IntentRecord(
            intent_type=intent_type,
            timestamp=now,
            timestamp_str=datetime.fromtimestamp(now).strftime("%H:%M:%S.%f")[:-3],
            message=message,
            params=params or {},
        )

        with self._lock:
            self._records.append(record)
            while len(self._records) > self._max_size:
                self._records.pop(0)

        return record

    def get_recent(self, count: int | None = None) -> list[IntentRecord]:
        """Get the most recent intents.

        Args:
            count: Number of records to return (default: all)

        Returns:
            List of IntentRecord objects, newest first
        """
        with self._lock:
            if count is None:
                return list(reversed(self._records))
            return list(reversed(self._records[-count:]))

    def get_all(self) -> list[IntentRecord]:
        """Get all recorded intents, newest first."""
        return self.get_recent(None)

    def clear(self) -> None:
        """Clear all recorded intents."""
        with self._lock:
            self._records.clear()

    def count(self) -> int:
        """Get the number of recorded intents."""
        with self._lock:
            return len(self._records)

    def to_list(self) -> list[dict[str, Any]]:
        """Export all records as a list of dictionaries.

        Useful for serialization to support bundle.
        """
        with self._lock:
            return [
                {
                    "intent_type": r.intent_type,
                    "timestamp": r.timestamp,
                    "timestamp_str": r.timestamp_str,
                    "message": r.message,
                    "params": r.params,
                }
                for r in self._records
            ]

    def __len__(self) -> int:
        """Return the number of recorded intents."""
        return self.count()

    def __iter__(self):
        """Iterate over records, newest first."""
        return iter(self.get_all())


# Global intent history instance
_intent_history: IntentHistory | None = None


def get_intent_history(max_size: int = 50) -> IntentHistory:
    """Get the global intent history instance.

    Args:
        max_size: Maximum records for new instance (ignored if exists)

    Returns:
        IntentHistory singleton
    """
    global _intent_history
    if _intent_history is None:
        _intent_history = IntentHistory(max_size)
    return _intent_history


def reset_intent_history(max_size: int = 50) -> IntentHistory:
    """Reset and return a new intent history instance.

    Useful for testing.

    Args:
        max_size: Maximum records for the new instance

    Returns:
        New IntentHistory instance
    """
    global _intent_history
    _intent_history = IntentHistory(max_size)
    return _intent_history


# Convenience function for quick recording
def record_intent(intent: Any, message: str = "") -> IntentRecord:
    """Quick function to record an intent to global history.

    Args:
        intent: The intent object
        message: Optional custom message

    Returns:
        The created IntentRecord
    """
    return get_intent_history().record(intent, message)
