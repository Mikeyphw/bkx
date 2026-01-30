"""TransferSpec JSON Schema definition."""

TRANSFER_SPEC_SCHEMA = {
    "$schema": "http://json-schema.org/draft-07/schema#",
    "title": "TransferSpec",
    "description": "Normalized specification for file transfer operations.",
    "type": "object",
    "required": ["sources", "destination", "direction"],
    "properties": {
        "sources": {
            "type": "array",
            "items": {"type": "string"},
            "description": "Source paths to transfer",
        },
        "destination": {
            "type": "string",
            "description": "Destination path",
        },
        "direction": {
            "type": "string",
            "enum": ["push", "pull"],
            "description": "Transfer direction (push to remote, pull from remote)",
        },
        "verify": {
            "type": "boolean",
            "default": True,
            "description": "Verify after transfer",
        },
        "checksum": {
            "type": "string",
            "nullable": True,
            "description": "Expected checksum for verification",
        },
        "dry_run": {
            "type": "boolean",
            "default": False,
            "description": "Just show plan, don't execute",
        },
        "overwrite": {
            "type": "boolean",
            "default": False,
            "description": "Overwrite existing files",
        },
    },
}

