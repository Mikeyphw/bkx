"""Non-blocking operations utilities - Step 8B

Provides utilities for running heavy operations in thread pool with:
- Throttled progress updates (100ms max frequency)
- Operation cancellation support
- Thread-safe progress callbacks
"""

from __future__ import annotations

import asyncio
import threading
import time
from collections.abc import Callable
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Any

# Maximum time between progress updates (in seconds)
THROTTLE_INTERVAL = 0.1


@dataclass
class ProgressState:
    """Thread-safe progress state for long-running operations."""

    current: int = 0
    total: int = 100
    percent: float = 0.0
    message: str = ""
    is_cancelled: bool = False
    is_completed: bool = False
    error: str | None = None
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def update(
        self,
        current: int | None = None,
        total: int | None = None,
        percent: float | None = None,
        message: str | None = None,
    ) -> None:
        """Update progress state (thread-safe)."""
        with self._lock:
            if current is not None:
                self.current = current
            if total is not None:
                self.total = max(1, total)
            if percent is not None:
                self.percent = max(0.0, min(1.0, percent))
            if message is not None:
                self.message = message
            # Auto-calculate percent if not provided
            if percent is None and self.total > 0:
                self.percent = self.current / self.total

    def cancel(self) -> None:
        """Cancel the operation."""
        with self._lock:
            self.is_cancelled = True

    @property
    def cancelled(self) -> bool:
        """Check if operation was cancelled."""
        with self._lock:
            return self.is_cancelled

    def complete(self, message: str = "Completed") -> None:
        """Mark operation as completed."""
        with self._lock:
            self.is_completed = True
            self.message = message
            self.percent = 1.0

    def fail(self, error: str) -> None:
        """Mark operation as failed."""
        with self._lock:
            self.is_completed = True
            self.error = error


class ThrottledProgressCallback:
    """Thread-safe callback that throttles progress updates.

    Only calls the actual callback at most every THROTTLE_INTERVAL seconds,
    but tracks the latest values internally.
    """

    def __init__(
        self,
        on_progress: Callable[[ProgressState], None],
        throttle_interval: float = THROTTLE_INTERVAL,
    ) -> None:
        self._on_progress = on_progress
        self._throttle_interval = throttle_interval
        self._state = ProgressState()
        self._lock = threading.Lock()
        self._last_update = 0.0
        self._enabled = threading.Event()
        self._enabled.set()

    def update(
        self,
        current: int | None = None,
        total: int | None = None,
        percent: float | None = None,
        message: str | None = None,
    ) -> None:
        """Queue a progress update."""
        with self._lock:
            self._state.update(current=current, total=total, percent=percent, message=message)
            self._maybe_notify()

    def _maybe_notify(self) -> None:
        """Notify if enough time has passed since last update."""
        now = time.monotonic()
        # Always notify on first update (when _last_update is 0.0)
        if self._last_update == 0.0 or now - self._last_update >= self._throttle_interval:
            self._notify()

    def _notify(self) -> None:
        """Actually send the update to the callback."""
        if not self._enabled.is_set():
            return

        with self._lock:
            state = ProgressState(
                current=self._state.current,
                total=self._state.total,
                percent=self._state.percent,
                message=self._state.message,
                is_cancelled=self._state.is_cancelled,
            )
            self._last_update = time.monotonic()

        try:
            self._on_progress(state)
        except Exception:
            pass  # Don't let callback errors propagate

    def flush(self) -> None:
        """Force send any pending updates."""
        with self._lock:
            if self._state.current > 0 or self._state.message:
                self._notify()

    def cancel(self) -> None:
        """Cancel the operation."""
        self._state.cancel()
        self.flush()

    def disable(self) -> None:
        """Disable further updates."""
        self._enabled.clear()

    def enable(self) -> None:
        """Enable updates."""
        self._enabled.set()


def run_with_throttled_progress(
    func: Callable[..., Any],
    on_progress: Callable[[ProgressState], None],
    max_workers: int = 1,
    throttle_interval: float = THROTTLE_INTERVAL,
) -> Callable[..., Future]:
    """Decorator to run a function with throttled progress updates.

    Args:
        func: Function to run (should accept a ProgressState via keyword arg)
        on_progress: Callback for progress updates
        max_workers: Max thread pool workers
        throttle_interval: Min seconds between updates

    Returns:
        Decorated function that returns a Future

    Example:
        @run_with_throttled_progress(my_func, on_progress_callback)
        def long_operation(progress: ProgressState):
            for i in range(100):
                progress.update(current=i, total=100, message=f"Step {i}")
                time.sleep(0.01)
    """

    def wrapper(*args: Any, **kwargs: Any) -> Future:
        executor = ThreadPoolExecutor(max_workers=max_workers)

        def run() -> Any:
            progress = ProgressState()
            throttled = ThrottledProgressCallback(on_progress, throttle_interval)

            # Inject progress into kwargs (copy to avoid mutation)
            run_kwargs = dict(kwargs)
            run_kwargs["_progress"] = progress

            try:
                result = func(*args, **run_kwargs)
                progress.complete()
                throttled.flush()
                return result
            except Exception as e:
                progress.fail(str(e))
                throttled.flush()
                raise

        return executor.submit(run)

    return wrapper


class AsyncThrottledProgress:
    """Async wrapper for throttled progress updates.

    Use with asyncio.to_thread() for non-blocking async operations.
    """

    def __init__(
        self,
        on_progress: Callable[[ProgressState], None],
        throttle_interval: float = THROTTLE_INTERVAL,
    ) -> None:
        self._on_progress = on_progress
        self._throttle_interval = throttle_interval
        self._state = ProgressState()
        self._last_update = 0.0
        self._lock = asyncio.Lock()
        self._enabled = True

    async def update(
        self,
        current: int | None = None,
        total: int | None = None,
        percent: float | None = None,
        message: str | None = None,
    ) -> None:
        """Update progress (async, throttled)."""
        async with self._lock:
            self._state.update(current=current, total=total, percent=percent, message=message)
            now = time.monotonic()
            if now - self._last_update >= self._throttle_interval:
                self._last_update = now
                await self._notify()

    async def _notify(self) -> None:
        """Send progress update."""
        if not self._enabled:
            return
        try:
            state = ProgressState(
                current=self._state.current,
                total=self._state.total,
                percent=self._state.percent,
                message=self._state.message,
                is_cancelled=self._state.is_cancelled,
            )
            self._on_progress(state)
        except Exception:
            pass

    async def flush(self) -> None:
        """Force send any pending updates."""
        async with self._lock:
            if self._state.current > 0 or self._state.message:
                await self._notify()

    def cancel(self) -> None:
        """Cancel the operation."""
        self._state.cancel()

    def disable(self) -> None:
        """Disable further updates."""
        self._enabled = False


# Convenience function for creating throttled callbacks
def create_throttled_progress(
    on_progress: Callable[[ProgressState], None],
    throttle_interval: float = THROTTLE_INTERVAL,
) -> ThrottledProgressCallback:
    """Create a throttled progress callback.

    Args:
        on_progress: Callback for progress updates
        throttle_interval: Min seconds between updates

    Returns:
        ThrottledProgressCallback instance
    """
    return ThrottledProgressCallback(on_progress, throttle_interval)
