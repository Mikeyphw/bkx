"""Deterministic Spec serialization, identity hashing, and JSON Schema validation."""

from __future__ import annotations

import dataclasses
import hashlib
import json
from collections.abc import Mapping
from pathlib import PurePath
from typing import Any

from jsonschema.exceptions import SchemaError
from jsonschema.validators import validator_for


def _normalize_for_json(value: Any) -> Any:
    """Recursively convert contract values to a deterministic JSON-safe form."""
    if isinstance(value, PurePath):
        return str(value)
    if dataclasses.is_dataclass(value) and not isinstance(value, type):
        return {
            item.name: _normalize_for_json(getattr(value, item.name))
            for item in dataclasses.fields(value)
            if item.compare and not item.name.startswith("_")
        }
    if isinstance(value, Mapping):
        return {str(key): _normalize_for_json(item) for key, item in value.items()}
    if isinstance(value, tuple):
        if hasattr(value, "_fields"):
            return {name: _normalize_for_json(getattr(value, name)) for name in value._fields}
        return [_normalize_for_json(item) for item in value]
    if isinstance(value, list):
        return [_normalize_for_json(item) for item in value]
    if isinstance(value, (set, frozenset)):
        normalized = [_normalize_for_json(item) for item in value]
        return sorted(normalized, key=lambda item: json.dumps(item, sort_keys=True, default=str))
    return value


def spec_to_dict(spec: Any) -> dict[str, Any]:
    """Serialize only semantic/public fields used by dataclass equality.

    Private/derived ``compare=False`` fields are deliberately excluded. This makes
    object equality and deterministic intent hashing use the same field set.
    """
    if dataclasses.is_dataclass(spec) and not isinstance(spec, type):
        value = _normalize_for_json(spec)
    elif hasattr(spec, "model_dump"):
        value = _normalize_for_json(spec.model_dump())
    elif hasattr(spec, "dict"):
        value = _normalize_for_json(spec.dict())
    elif isinstance(spec, Mapping):
        value = _normalize_for_json(spec)
    else:
        raise TypeError(f"Expected a Spec/dataclass/mapping, got {type(spec).__name__}")
    if not isinstance(value, dict):
        raise TypeError("Spec serialization must produce an object")
    return value


def compute_spec_hash(spec: dict[str, Any]) -> str:
    """Compute a stable SHA-256 hash from a normalized Spec mapping."""
    payload = json.dumps(_normalize_for_json(spec), sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def compute_spec_hash_from_obj(obj: Any) -> str:
    return compute_spec_hash(spec_to_dict(obj))


def compute_backup_spec_hash(spec: Any) -> str:
    return compute_spec_hash_from_obj(spec)


def compute_archive_spec_hash(spec: Any) -> str:
    return compute_spec_hash_from_obj(spec)


def compute_transfer_spec_hash(spec: Any) -> str:
    return compute_spec_hash_from_obj(spec)


def compute_restore_spec_hash(spec: Any) -> str:
    return compute_spec_hash_from_obj(spec)


def verify_parity(*specs: Any, ignore_fields: tuple[str, ...] = ()) -> tuple[bool, list[str]]:
    if len(specs) < 2:
        return True, []
    dicts: list[dict[str, Any]] = []
    for spec in specs:
        data = spec_to_dict(spec)
        for name in ignore_fields:
            data.pop(name, None)
        dicts.append(data)
    first = compute_spec_hash(dicts[0])
    differences: list[str] = []
    for index, data in enumerate(dicts[1:], start=1):
        current = compute_spec_hash(data)
        if current != first:
            differences.append(f"Spec {index} hash differs: {current[:16]}... vs {first[:16]}...")
    return not differences, differences


def validate_spec_against_schema(spec: dict[str, Any], schema: dict[str, Any]) -> tuple[bool, list[str]]:
    """Validate with the standards-compliant validator declared by the schema."""
    try:
        validator_type = validator_for(schema)
        validator_type.check_schema(schema)
    except SchemaError as exc:
        return False, [f"Invalid JSON Schema: {exc.message}"]

    validator = validator_type(schema)
    failures = sorted(validator.iter_errors(spec), key=lambda error: tuple(str(part) for part in error.absolute_path))
    errors: list[str] = []
    for error in failures:
        path = ".".join(str(part) for part in error.absolute_path)
        prefix = f"{path}: " if path else ""
        errors.append(f"{prefix}{error.message}")
    return not errors, errors


def load_mikomn_command_schema() -> dict[str, Any]:
    """Return the small mikomn command envelope schema used by parity tests."""
    return {
        "$schema": "https://json-schema.org/draft/2020-12/schema",
        "type": "object",
        "additionalProperties": False,
        "properties": {"command_id": {"type": "string"}, "params": {"type": "object"}},
        "required": ["command_id", "params"],
    }
