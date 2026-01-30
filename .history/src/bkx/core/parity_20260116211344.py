"""Parity utilities for deterministic spec hashing and verification.

Contract rule:
- Same intent -> same Spec -> same outcome.
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
from typing import Any


def _normalize_for_json(value: Any) -> Any:
    """Normalize a value for JSON serialization.

    Handles Path objects, tuples, and other non-JSON types.
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
    elif value is None:
        return None
    else:
        return value


def spec_to_dict(spec: Any) -> dict:
    """Convert any Spec dataclass to dict with stable field ordering.

    Uses field order from dataclass definition for consistency.
    All values are normalized for JSON serialization.

    Args:
        spec: Any Spec dataclass instance

    Returns:
        Dictionary representation with ordered fields, JSON-safe
    """
    if hasattr(spec, "model_dump"):
        raw = spec.model_dump()
    elif hasattr(spec, "dict"):
        raw = spec.dict()
    elif hasattr(spec, "__dataclass_fields__"):
        raw = {}
        for field_name in spec.__dataclass_fields__:
            value = getattr(spec, field_name)
            raw[field_name] = value
    elif hasattr(spec, "__dict__"):
        raw = spec.__dict__
    else:
        raw = spec

    return _normalize_for_json(raw)


def compute_spec_hash(spec: dict) -> str:
    """Compute deterministic SHA256 hash from spec dict.

    Uses sorted keys for stable serialization across Python versions.

    Args:
        spec: Dictionary representation of a Spec (already normalized)

    Returns:
        SHA256 hex digest of the deterministic JSON representation
    """
    sorted_json = json.dumps(spec, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(sorted_json.encode("utf-8")).hexdigest()


def compute_spec_hash_from_obj(obj: Any) -> str:
    """Compute deterministic SHA256 hash from any spec object.

    Handles conversion and normalization automatically.

    Args:
        obj: Any Spec object

    Returns:
        SHA256 hex digest of the deterministic JSON representation
    """
    spec_dict = spec_to_dict(obj)
    return compute_spec_hash(spec_dict)


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

    dicts = []
    for spec in specs:
        d = spec_to_dict(spec)
        for field in ignore_fields:
            d.pop(field, None)
        dicts.append(d)

    hashes = [compute_spec_hash(d) for d in dicts]
    first_hash = hashes[0]
    differences = []

    for i, spec_hash in enumerate(hashes[1:], start=1):
        if spec_hash != first_hash:
            differences.append(f"Spec {i} hash differs: {spec_hash[:16]}... vs {first_hash[:16]}...")

    return len(differences) == 0, differences


def validate_spec_against_schema(spec: dict, schema: dict) -> tuple[bool, list[str]]:
    """Validate a spec against a JSON schema.

    Args:
        spec: Spec dictionary to validate
        schema: JSON schema dictionary

    Returns:
        Tuple of (is_valid, list of validation errors)
    """
    errors = []

    required_fields = schema.get("required", [])
    for field in required_fields:
        if field not in spec or spec.get(field) is None:
            errors.append(f"Missing required field: {field}")

    properties = schema.get("properties", {})
    for field, field_schema in properties.items():
        if field in spec and spec.get(field) is not None:
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


def load_mikomn_command_schema() -> dict:
    """Load the mikomn command schema pattern.

    Returns:
        The mikomn command schema as a dictionary, or empty dict if unavailable.
    """
    return {
        "$schema": "http://json-schema.org/draft-07/schema#",
        "type": "object",
        "properties": {
            "command_id": {"type": "string"},
            "params": {"type": "object"},
        },
        "required": ["command_id", "params"],
    }
