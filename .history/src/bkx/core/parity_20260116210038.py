"""Parity utilities for deterministic spec hashing and verification.

Contract rule:
- Same intent → same Spec → same outcome.
- Stable JSON serialization for reproducible testing.

This module provides utilities for:
- Computing deterministic hashes from specs
- Verifying parity across interfaces (CLI, Interactive, TUI)
- Schema validation for JSON output
"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any, Protocol, runtime_checkable


def compute_spec_hash(spec: dict) -> str:
    """Compute deterministic SHA256 hash from spec dict.
    
    Uses sorted keys for stable serialization across Python versions.
    
    Args:
        spec: Dictionary representation of a Spec
        
    Returns:
        SHA256 hex digest of the deterministic JSON representation
    """
    # Sort keys for deterministic output
    # Use separators without spaces for consistent encoding
    sorted_json = json.dumps(spec, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(sorted_json.encode("utf-8")).hexdigest()


def spec_to_dict(spec: Any) -> dict:
    """Convert any Spec dataclass to dict with stable field ordering.
    
    Uses field order from dataclass definition for consistency.
    
    Args:
        spec: Any Spec dataclass instance
        
    Returns:
        Dictionary representation with ordered fields
    """
    if hasattr(spec, "model_dump"):
        # Pydantic model
        return spec.model_dump()
    elif hasattr(spec, "dict"):
        # Dataclass with dict() method
        return spec.dict()
    elif hasattr(spec, "__dataclass_fields__"):
        # Plain dataclass - extract fields in definition order
        result = {}
        for field_name in spec.__dataclass_fields__:
            value = getattr(spec, field_name)
            result[field_name] = _normalize_for_json(value)
        return result
    else:
        # Fallback - try generic conversion
        return _normalize_for_json(spec)


def _normalize_for_json(value: Any) -> Any:
    """Normalize a value for JSON serialization.
    
    Handles Path objects, tuples, and other non-JSON types.
    
    Args:
        value: Any value to normalize
        
    Returns:
        JSON-serializable representation
    """
    if isinstance(value, Path):
        return str(value)
    elif isinstance(value, tuple):
        return list(value)
    elif isinstance(value, dict):
        return {k: _normalize_for_json(v) for k, v in value.items()}
    elif isinstance(value, list):
        return [_normalize_for_json(item) for item in value]
    elif hasattr(value, "__dict__"):
        return _normalize_for_json(value.__dict__)
    else:
        return value


def verify_parity(*specs: Any, ignore_fields: tuple[str, ...] = ()) -> tuple[bool, list[str]]:
    """Verify that multiple specs produce identical hashes.
    
    Args:
        *specs: Multiple Spec instances to compare
        ignore_fields: Field names to exclude from comparison
        
    Returns:
        Tuple of (is_parity, list of differences)
    """
    if len(specs) < 2:
        return True, []
    
    # Convert all to dicts
    dicts = []
    for spec in specs:
        d = spec_to_dict(spec)
        
        # Remove ignored fields
        for field in ignore_fields:
            d.pop(field, None)
        
        dicts.append(d)
    
    # Compute hashes
    hashes = [compute_spec_hash(d) for d in dicts]
    
    # Check if all equal
    first_hash = hashes[0]
    differences = []
    
    for i, spec_hash in enumerate(hashes[1:], start=1):
        if spec_hash != first_hash:
            differences.append(f"Spec {i} hash differs: {spec_hash[:16]}... vs {first_hash[:16]}...")
    
    return len(differences) == 0, differences


def compute_backup_spec_hash(
    sources: list[str],
    destination: str | None,
    archive_name: str | None,
    excludes: list[str],
    algorithm: str,
    level: int,
    verify: bool,
    dry_run: bool,
) -> str:
    """Compute hash for backup spec with given parameters.
    
    This creates a canonical representation for comparing
    backup specs across interfaces.
    
    Args:
        sources: Source paths as strings
        destination: Destination path or None
        archive_name: Archive name or None
        excludes: Exclude patterns
        algorithm: Compression algorithm
        level: Compression level
        verify: Whether to verify
        dry_run: Whether this is a dry run
        
    Returns:
        Deterministic hash string
    """
    spec_dict = {
        "sources": sorted(sources),
        "destination": destination,
        "archive_name": archive_name,
        "excludes": sorted(excludes) if excludes else [],
        "compression": {
            "algorithm": algorithm,
            "level": level,
        },
        "verify": verify,
        "dry_run": dry_run,
    }
    
    return compute_spec_hash(spec_dict)


def compute_archive_spec_hash(
    sources: list[str],
    output: str | None,
    excludes: list[str],
    algorithm: str,
    level: int,
    verify: bool,
    dry_run: bool,
) -> str:
    """Compute hash for archive spec with given parameters.
    
    Args:
        sources: Source paths as strings
        output: Output path or None
        excludes: Exclude patterns
        algorithm: Compression algorithm
        level: Compression level
        verify: Whether to verify
        dry_run: Whether this is a dry run
        
    Returns:
        Deterministic hash string
    """
    spec_dict = {
        "sources": sorted(sources),
        "output_path": output,
        "excludes": sorted(excludes) if excludes else [],
        "compression": {
            "algorithm": algorithm,
            "level": level,
        },
        "verify": verify,
        "dry_run": dry_run,
    }
    
    return compute_spec_hash(spec_dict)


def compute_restore_spec_hash(
    archive: str,
    destination: str | None,
    paths: list[str],
    overwrite: bool,
    verify: bool,
    dry_run: bool,
    fresh_dir: bool,
) -> str:
    """Compute hash for restore spec with given parameters.
    
    Args:
        archive: Archive path
        destination: Destination path or None
        paths: Specific paths to restore
        overwrite: Whether to overwrite
        verify: Whether to verify
        dry_run: Whether this is a dry run
        fresh_dir: Whether to create fresh directory
        
    Returns:
        Deterministic hash string
    """
    spec_dict = {
        "archive": archive,
        "destination": destination,
        "paths": sorted(paths) if paths else [],
        "overwrite": overwrite,
        "verify": verify,
        "dry_run": dry_run,
        "fresh_dir": fresh_dir,
    }
    
    return compute_spec_hash(spec_dict)


def compute_transfer_spec_hash(
    source: str,
    destination: str,
    direction: str,
    verify: bool,
    dry_run: bool,
    overwrite: bool,
) -> str:
    """Compute hash for transfer spec with given parameters.
    
    Args:
        source: Source path
        destination: Destination path
        direction: Transfer direction (push/pull)
        verify: Whether to verify
        dry_run: Whether this is a dry run
        overwrite: Whether to overwrite
        
    Returns:
        Deterministic hash string
    """
    spec_dict = {
        "sources": [source],
        "destination": destination,
        "direction": direction,
        "verify": verify,
        "dry_run": dry_run,
        "overwrite": overwrite,
    }
    
    return compute_spec_hash(spec_dict)


# =============================================================================
# Schema Validation
# =============================================================================

@runtime_checkable
class SchemaValidatable(Protocol):
    """Protocol for objects that can be validated against a schema."""
    
    def get_schema(self) -> dict:
        """Get the JSON schema for this type."""
        ...


def validate_spec_against_schema(spec: dict, schema: dict) -> tuple[bool, list[str]]:
    """Validate a spec against a JSON schema.
    
    Args:
        spec: Spec dictionary to validate
        schema: JSON schema dictionary
        
    Returns:
        Tuple of (is_valid, list of validation errors)
    """
    errors = []
    
    # Check required fields
    required_fields = schema.get("required", [])
    for field in required_fields:
        if field not in spec:
            errors.append(f"Missing required field: {field}")
    
    # Check field types
    properties = schema.get("properties", {})
    for field, field_schema in properties.items():
        if field in spec:
            value = spec[field]
            expected_type = field_schema.get("type")
            
            if expected_type == "string" and not isinstance(value, str):
                errors.append(f"Field '{field}' should be string, got {type(value).__name__}")
            elif expected_type == "integer" and not isinstance(value, int):
                errors.append(f"Field '{field}' should be integer, got {type(value).__name__}")
            elif expected_type == "boolean" and not isinstance(value, bool):
                errors.append(f"Field '{field}' should be boolean, got {type(value).__name__}")
            elif expected_type == "array" and not isinstance(value, list):
                errors.append(f"Field '{field}' should be array, got {type(value).__name__}")
            elif expected_type == "object" and not isinstance(value, dict):
                errors.append(f"Field '{field}' should be object, got {type(value).__name__}")
    
    return len(errors) == 0, errors


# =============================================================================
# mikomn Integration
# =============================================================================

def load_mikomn_command_schema() -> dict:
    """Load the mikomn command schema pattern.
    
    Returns:
        The mikomn command schema as a dictionary, or empty dict if unavailable.
    """
    try:
        # Try to load from mikomn package
        from pathlib import Path
        import importlib.resources as resources
        
        # Try multiple possible locations for the schema
        possible_paths = [
            Path(__file__).parent.parent.parent / "mikomn" / "command.schema.json",
            Path("/usr/share/mikomn/schemas/command.schema.json"),
            Path.home() / ".mikomn/schemas/command.schema.json",
        ]
        
        for schema_path in possible_paths:
            if schema_path.exists():
                with open(schema_path) as f:
                    return json.load(f)
    except Exception:
        pass
    
    # Return default schema pattern
    return {
        "$schema": "http://json-schema.org/draft-07/schema#",
        "type": "object",
        "properties": {
            "command_id": {"type": "string"},
            "params": {"type": "object"},
        },
        "required": ["command_id", "params"],
    }


# =============================================================================
# Cross-Interface Parity Tests
# =============================================================================

class ParityTestCase:
    """Test case for cross-interface parity verification."""
    
    def __init__(
        self,
        name: str,
        cli_params: dict,
        tui_intent_class: str,
        tui_params: dict,
        spec_builder_func: str,
    ):
        self.name = name
        self.cli_params = cli_params
        self.tui_intent_class = tui_intent_class
        self.tui_params = tui_params
        self.spec_builder_func = spec_builder_func
    
    def get_expected_hash(self) -> str:
        """Get the expected hash for this test case."""
        # This would use the CLI params to compute hash
        # Subclasses implement specific logic
        raise NotImplementedError


def create_intent_parity_test_case(
    name: str,
    intent_class: str,
    cli_params: dict,
    tui_params: dict,
) -> dict:
    """Create a test case for intent parity.
    
    Args:
        name: Descriptive name for the test
        intent_class: Name of the TUI intent class
        cli_params: Parameters for CLI spec builder
        tui_params: Parameters for TUI intent
        
    Returns:
        Dictionary representation of the test case
    """
    return {
        "name": name,
        "intent_class": intent_class,
        "cli_params": cli_params,
        "tui_params": tui_params,
    }
}

