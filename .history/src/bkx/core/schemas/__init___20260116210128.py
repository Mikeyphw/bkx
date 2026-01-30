"""JSON Schema definitions for BKX specs.

These schemas are used for:
- Validating spec JSON output
- Cross-language compatibility
- Documentation of spec structure
"""

from .backup_spec import BACKUP_SPEC_SCHEMA
from .archive_spec import ARCHIVE_SPEC_SCHEMA
from .transfer_spec import TRANSFER_SPEC_SCHEMA
from .restore_spec import RESTORE_SPEC_SCHEMA

__all__ = [
    "BACKUP_SPEC_SCHEMA",
    "ARCHIVE_SPEC_SCHEMA",
    "TRANSFER_SPEC_SCHEMA",
    "RESTORE_SPEC_SCHEMA",
]

