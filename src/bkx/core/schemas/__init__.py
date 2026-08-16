"""Standards-compliant JSON Schemas for canonical BKX Specs."""

from __future__ import annotations

from copy import deepcopy

from bkx.core.errors import ValidationError

from .archive_spec import ARCHIVE_SPEC_SCHEMA
from .backup_spec import BACKUP_SPEC_SCHEMA
from .restore_spec import RESTORE_SPEC_SCHEMA
from .transfer_spec import TRANSFER_SPEC_SCHEMA

__all__ = [
    "BACKUP_SPEC_SCHEMA",
    "ARCHIVE_SPEC_SCHEMA",
    "TRANSFER_SPEC_SCHEMA",
    "RESTORE_SPEC_SCHEMA",
    "get_schema_for_spec",
]

_SCHEMA_MAP = {
    "backup": BACKUP_SPEC_SCHEMA,
    "archive": ARCHIVE_SPEC_SCHEMA,
    "transfer": TRANSFER_SPEC_SCHEMA,
    "restore": RESTORE_SPEC_SCHEMA,
}


def get_schema_for_spec(spec_type: str) -> dict:
    """Return a defensive copy of a known schema or fail closed."""
    try:
        schema = _SCHEMA_MAP[spec_type]
    except KeyError as exc:
        raise ValidationError(msg=f"Unknown Spec schema: {spec_type}", details={"spec_type": spec_type, "valid": sorted(_SCHEMA_MAP)}) from exc
    return deepcopy(schema)
