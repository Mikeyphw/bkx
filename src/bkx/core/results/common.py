"""Shared immutable Result helpers."""

from __future__ import annotations

from typing import Any


class FrozenMapping(dict[Any, Any]):
    """A recursively immutable, dict-compatible mapping for Result payloads."""

    def __init__(self, values: dict[Any, Any] | None = None) -> None:
        dict.__init__(self, {key: freeze_value(value) for key, value in dict(values or {}).items()})

    def _immutable(self, *_args: Any, **_kwargs: Any) -> None:
        raise TypeError("result mappings are immutable")

    __setitem__ = _immutable
    __delitem__ = _immutable
    clear = _immutable
    pop = _immutable
    popitem = _immutable
    setdefault = _immutable
    update = _immutable
    __ior__ = _immutable


def freeze_value(value: Any) -> Any:
    if isinstance(value, FrozenMapping):
        return value
    if isinstance(value, dict):
        return FrozenMapping(value)
    if isinstance(value, list):
        return tuple(freeze_value(item) for item in value)
    if isinstance(value, tuple):
        return tuple(freeze_value(item) for item in value)
    if isinstance(value, set):
        return frozenset(freeze_value(item) for item in value)
    return value


def frozen_mapping(value: dict[Any, Any] | None) -> FrozenMapping:
    return FrozenMapping(value)


def require_nonnegative(**values: int | float) -> None:
    for name, value in values.items():
        if isinstance(value, bool) or not isinstance(value, (int, float)) or value < 0:
            raise ValueError(f"{name} must be non-negative")
