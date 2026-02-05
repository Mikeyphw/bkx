"""RestoreSpec JSON Schema definition."""

RESTORE_SPEC_SCHEMA = {
    "$schema": "http://json-schema.org/draft-07/schema#",
    "title": "RestoreSpec",
    "description": "Normalized specification for archive restore operations.",
    "type": "object",
    "required": ["archive"],
    "properties": {
        "archive": {
            "type": "string",
            "description": "Path to archive to restore",
        },
        "destination": {
            "type": "string",
            "nullable": True,
            "description": "Destination path for restore",
        },
        "paths": {
            "type": "array",
            "items": {"type": "string"},
            "default": [],
            "description": "Specific paths to restore (empty = all)",
        },
        "excludes": {
            "type": "array",
            "items": {"type": "string"},
            "default": [],
            "description": "Glob patterns to exclude",
        },
        "overwrite": {
            "type": "boolean",
            "default": False,
            "description": "Overwrite existing files",
        },
        "verify": {
            "type": "boolean",
            "default": True,
            "description": "Verify archive integrity before restore",
        },
        "dry_run": {
            "type": "boolean",
            "default": False,
            "description": "Just show plan, don't execute",
        },
        "fresh_dir": {
            "type": "boolean",
            "default": False,
            "description": "Create fresh directory for restore",
        },
        "confirm_overwrite": {
            "type": "boolean",
            "default": True,
            "description": "Require confirmation for overwrite",
        },
    },
}
