# Phase 08 — Determinism + parity suite

## Goal
Lock in the big promise: same intent → same Spec → same outcome.
Leverage mikomn schemas for stable JSON serialization and testing.

## Dependencies
- **mikomn**: Schema validation, command models, stable serialization

## mikomn Schema Integration

### Spec Hashing Strategy
Use mikomn's command schema pattern for stable Spec serialization:
```python
from mikomn.tui.actions import ActionSpec
from mikomn.tui.app import TuiApp

# Stable JSON serialization with sorted keys
def compute_spec_hash(spec: dict) -> str:
    """Compute deterministic hash from spec dict."""
    import hashlib
    import json
    
    # Sort keys for deterministic output
    sorted_spec = json.dumps(spec, sort_keys=True, separators=(',', ':'))
    return hashlib.sha256(sorted_spec.encode()).hexdigest()

# Verify parity across interfaces
def verify_parity(cli_spec: dict, interactive_spec: dict, tui_spec: dict) -> bool:
    """Verify all interfaces produce equivalent specs."""
    return (
        compute_spec_hash(cli_spec) ==
        compute_spec_hash(interactive_spec) ==
        compute_spec_hash(tui_spec)
    )
```

### Schema Snapshots
Use mikomn ConfigRegistry for schema validation:
```python
from mikomn.tui.app import TuiApp
from pathlib import Path

# Load and validate spec against schema
spec = {"sources": ["/home"], "destination": "/backup"}
config_path = Path("specs/backup.schema.json")

# Use mikomn's config loading for validation
validated = ConfigRegistry.load_commands(config_path, schema_path=config_path)
```

### Command Model for Intent
Use mikomn's Command model for consistent intent representation:
```python
from mikomn.tui.actions import ActionSpec

# Define spec intent using Command model
backup_command = Command(
    id="backup",
    title="Backup",
    description="Create a backup archive",
    keywords=["archive", "save", "backup"],
    group="Operations",
)

# TUI intent payload maps to Command
intent_payload = {
    "command_id": "backup",
    "params": {
        "sources": ["/home/user/docs"],
        "destination": "/backups/docs-2024.zst",
        "algorithm": "zstd",
    }
}
```

### JSON Schema Tests
Use mikomn's schema patterns for JSON output validation:
```python
import json
from jsonschema import validate

# Spec schema (mirrors mikomn command.schema.json)
SPEC_SCHEMA = {
    "type": "object",
    "properties": {
        "operation": {"type": "string"},
        "sources": {"type": "array", "items": {"type": "string"}},
        "destination": {"type": "string"},
    },
    "required": ["operation", "sources"],
}

# Validate CLI output
cli_spec = {"operation": "backup", "sources": ["/home"]}
validate(instance=cli_spec, schema=SPEC_SCHEMA)

# Validate TUI intent
tui_intent = {"command_id": "backup", "params": cli_spec}
validate(instance=tui_intent, schema=INTENT_SCHEMA)
```

### Parity Test Framework
```python
import pytest
from hypothesis import given, settings
from hypothesis import strategies as st

# Property-based testing for parity
@given(
    sources=st.lists(st.text(min_size=1), min_size=1),
    algorithm=st.sampled_from(["zstd", "gzip", "lz4"]),
)
@settings(max_examples=100)
def test_parity(sources, algorithm):
    """Same inputs across CLI, interactive, TUI produce same spec."""
    from bkx.cli.spec_builders import build_backup_spec
    
    # CLI-style input
    cli_spec = build_backup_spec(
        sources=[Path(s) for s in sources],
        algorithm=algorithm,
    )
    
    # Interactive-style input (simulated)
    interactive_spec = build_backup_spec(
        sources=[Path(s) for s in sources],
        algorithm=algorithm,
    )
    
    # TUI-style input (simulated)
    tui_spec = build_backup_spec(
        sources=[Path(s) for s in sources],
        algorithm=algorithm,
    )
    
    # All should produce identical spec hash
    assert compute_spec_hash(cli_spec.dict()) == \
           compute_spec_hash(interactive_spec.dict()) == \
           compute_spec_hash(tui_spec.dict())
```

## Deliverables
- [ ] parity tests:
  - [ ] CLI args → Spec hash
  - [ ] interactive selections → Spec hash
  - [ ] TUI intent payload → Spec hash
- [ ] stable JSON schemas tests (using mikomn patterns)
- [ ] schema snapshot tests for JSON output
- [ ] property-based testing for edge cases

## mikomn Features Used
- `Command` model - Consistent intent representation
- `ConfigRegistry` - Schema loading and validation
- `command.schema.json` - Schema pattern reference
- Stable JSON serialization patterns

## TODOs
- [ ] Add Spec hashing strategy (stable field ordering)
- [ ] Add schema snapshots for JSON output
- [ ] Implement property-based parity tests
- [ ] Add command schema validation

## Acceptance checks
- [ ] parity tests catch divergence
- [ ] Schema validation prevents invalid specs
- [ ] Hash verification confirms identical outputs
- [ ] Property-based tests cover edge cases

