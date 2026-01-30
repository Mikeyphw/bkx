"""ArchiveCreateSpec JSON Schema definition."""

ARCHIVE_SPEC_SCHEMA = {
    "$schema": "http://json-schema.org/draft-07/schema#",
    "title": "ArchiveCreateSpec",
    "description": "Normalized specification for archive creation.",
    "type": "object",
    "required": ["sources", "output_path"],
    "properties": {
        "sources": {
            "type": "array",
            "items": {"type": "string"},
            "description": "Source paths to archive",
        },
        "output_path": {
            "type": "string",
            "description": "Output file path for the archive",
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
    },
}

