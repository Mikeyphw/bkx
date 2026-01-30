"""BackupSpec JSON Schema definition."""

BACKUP_SPEC_SCHEMA = {
    "$schema": "http://json-schema.org/draft-07/schema#",
    "title": "BackupSpec",
    "description": "Normalized specification for a backup operation.",
    "type": "object",
    "required": ["sources"],
    "properties": {
        "sources": {
            "type": "array",
            "items": {"type": "string"},
            "description": "Source paths to back up",
        },
        "destination": {
            "type": "string",
            "nullable": True,
            "description": "Destination path for archive storage",
        },
        "archive_name": {
            "type": "string",
            "nullable": True,
            "description": "Explicit archive name (auto-generated if not set)",
        },
        "excludes": {
            "type": "array",
            "items": {"type": "string"},
            "default": [],
            "description": "Glob patterns to exclude",
        },
        "includes": {
            "type": "array",
            "items": {"type": "string"},
            "default": [],
            "description": "Glob patterns to include",
        },
        "split_size": {
            "type": "integer",
            "nullable": True,
            "description": "Chunk size for split archives (bytes)",
        },
        "compression": {
            "type": "object",
            "required": ["algorithm"],
            "properties": {
                "algorithm": {
                    "type": "string",
                    "enum": ["zstd", "gzip", "lz4", "xz", "bzip2", "none"],
                    "description": "Compression algorithm",
                },
                "level": {"type": "integer", "description": "Compression level"},
            },
        },
        "verify": {
            "type": "boolean",
            "default": True,
            "description": "Verify archive after creation",
        },
        "dry_run": {
            "type": "boolean",
            "default": False,
            "description": "Just show plan, don't execute",
        },
        "_resolved_destination": {
            "type": "string",
            "nullable": True,
            "description": "Internal: resolved destination path",
        },
        "_archive_path": {
            "type": "string",
            "nullable": True,
            "description": "Internal: full archive path",
        },
    },
}

