"""JSON Schema definitions for BKX specs.

These schemas are used for:
- Validating spec JSON output
- Cross-language compatibility
- Documentation of spec structure
"""

from .archive_spec import ARCHIVE_SPEC_SCHEMA
from .backup_spec import BACKUP_SPEC_SCHEMA
from .restore_spec import RESTORE_SPEC_SCHEMA
from .transfer_spec import TRANSFER_SPEC_SCHEMA

__all__ = [
    "BACKUP_SPEC_SCHEMA",
    "ARCHIVE_SPEC_SCHEMA",
    "TRANSFER_SPEC_SCHEMA",
    "RESTORE_SPEC_SCHEMA",
]


def get_schema_for_spec(spec_type: str) -> dict:
    """Get the JSON schema for a specific spec type.

    Args:
        spec_type: Type name (backup, archive, transfer, restore)

    Returns:
        JSON schema dictionary
    """
    schema_map = {
        "backup": BACKUP_SPEC_SCHEMA,
        "archive": ARCHIVE_SPEC_SCHEMA,
        "transfer": TRANSFER_SPEC_SCHEMA,
        "restore": RESTORE_SPEC_SCHEMA,
    }
    return schema_map.get(spec_type, {})
