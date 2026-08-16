"""Core typed errors and expected backend-error translation."""

from __future__ import annotations

import tarfile
import zipfile
from contextlib import contextmanager
from dataclasses import dataclass, field
from typing import Any, Iterator


class FrozenList(list[Any]):
    """JSON-serializable list that preserves list equality but rejects mutation."""

    def _immutable(self, *_args: Any, **_kwargs: Any) -> None:
        raise TypeError("error details are immutable")

    __setitem__ = _immutable
    __delitem__ = _immutable
    __iadd__ = _immutable
    __imul__ = _immutable
    append = _immutable
    clear = _immutable
    extend = _immutable
    insert = _immutable
    pop = _immutable
    remove = _immutable
    reverse = _immutable
    sort = _immutable


def _freeze_detail(value: Any) -> Any:
    if isinstance(value, FrozenDetails | FrozenList):
        return value
    if isinstance(value, dict):
        return FrozenDetails({key: _freeze_detail(item) for key, item in value.items()})
    if isinstance(value, list):
        return FrozenList(_freeze_detail(item) for item in value)
    if isinstance(value, tuple):
        return tuple(_freeze_detail(item) for item in value)
    return value


class FrozenDetails(dict[str, Any]):
    """JSON-serializable mapping that is recursively immutable after construction."""

    def __init__(self, values: dict[str, Any] | None = None, **kwargs: Any) -> None:
        data = dict(values or {})
        data.update(kwargs)
        dict.__init__(self, {key: _freeze_detail(value) for key, value in data.items()})

    def _immutable(self, *_args: Any, **_kwargs: Any) -> None:
        raise TypeError("error details are immutable")

    __setitem__ = _immutable
    __delitem__ = _immutable
    clear = _immutable
    pop = _immutable
    popitem = _immutable
    setdefault = _immutable
    update = _immutable
    __ior__ = _immutable


@dataclass
class BkxError(Exception):
    """Base error with immutable contract metadata and normal Exception internals.

    Python must be able to assign ``__traceback__``/cause/context while an
    exception propagates. Public error metadata is locked after dataclass init.
    """

    msg: str
    details: dict[str, Any] = field(default_factory=dict)
    _contract_locked: bool = field(default=False, init=False, repr=False, compare=False)

    _RUNTIME_EXCEPTION_ATTRS = frozenset({"__traceback__", "__cause__", "__context__", "__suppress_context__", "args"})

    def __post_init__(self) -> None:
        self.details = FrozenDetails(self.details)
        self._contract_locked = True

    def __setattr__(self, name: str, value: Any) -> None:
        if getattr(self, "_contract_locked", False) and name not in self._RUNTIME_EXCEPTION_ATTRS and name != "_contract_locked":
            raise AttributeError(f"{type(self).__name__} contract metadata is immutable: {name}")
        object.__setattr__(self, name, value)

    def __str__(self) -> str:
        return self.msg


@dataclass
class ValidationError(BkxError):
    """Invalid input or configuration. Exit code 2."""


@dataclass
class NotFoundError(BkxError):
    """Requested resource does not exist. Exit code 3."""


@dataclass
class PermissionDeniedError(BkxError):
    """Insufficient permissions for operation. Exit code 4."""


@dataclass
class ExternalToolError(BkxError):
    """External/system/backend operation failed. Exit code 5."""

    tool_name: str | None = None
    exit_code: int | None = None
    stderr: str | None = None


@dataclass
class OptionalFeatureError(ExternalToolError):
    """An optional BKX interface was requested without its declared extra."""

    feature: str | None = None
    extra: str | None = None
    dependency: str | None = None


@contextmanager
def expected_error_boundary(
    operation: str,
    *,
    resource: str | None = None,
    backend: str | None = None,
) -> Iterator[None]:
    """Translate expected OS/backend failures into the public typed hierarchy.

    Programming errors are deliberately not caught. Only failure families expected
    from filesystem access and stdlib/external backends are translated.
    """

    details = {"operation": operation}
    if resource is not None:
        details["resource"] = resource
    if backend is not None:
        details["backend"] = backend

    try:
        yield
    except BkxError:
        raise
    except PermissionError as exc:
        raise PermissionDeniedError(
            msg=f"Permission denied while {operation}: {resource or exc.filename or ''}".rstrip(),
            details={**details, "errno": exc.errno},
        ) from exc
    except FileNotFoundError as exc:
        raise NotFoundError(
            msg=f"Resource not found while {operation}: {resource or exc.filename or ''}".rstrip(),
            details={**details, "errno": exc.errno},
        ) from exc
    except NotImplementedError as exc:
        raise ExternalToolError(
            msg=f"Unsupported backend capability while {operation}: {exc}",
            tool_name=backend,
            details={**details, "reason": str(exc)},
        ) from exc
    except (tarfile.TarError, zipfile.BadZipFile) as exc:
        raise ExternalToolError(
            msg=f"Archive backend failed while {operation}: {exc}",
            tool_name=backend or "archive",
            details={**details, "reason": str(exc)},
        ) from exc
    except OSError as exc:
        raise ExternalToolError(
            msg=f"System operation failed while {operation}: {exc}",
            tool_name=backend,
            details={**details, "errno": exc.errno, "reason": str(exc)},
        ) from exc
