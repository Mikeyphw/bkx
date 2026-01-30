# Phase 08 - Determinism + Parity Suite - TODO List

## Goal
Lock in the big promise: same intent → same Spec → same outcome.
Leverage mikomn schemas for stable JSON serialization and testing.

## Deliverables Status

### 1. Spec Hashing Utility (`src/bkx/core/parity.py`)
- [x] `compute_spec_hash(spec: dict) -> str` - Deterministic SHA256 hash with sorted keys
- [x] `spec_to_dict(spec) -> dict` - Convert any Spec to dict with stable field ordering
- [x] `verify_parity(*specs) -> bool` - Verify multiple specs produce same hash
- [x] `compute_backup_spec_hash()` - Specialized hash for backup specs
- [x] `compute_archive_spec_hash()` - Specialized hash for archive specs
- [x] `compute_restore_spec_hash()` - Specialized hash for restore specs
- [x] `compute_transfer_spec_hash()` - Specialized hash for transfer specs
- [x] `validate_spec_against_schema()` - Schema validation helper
- [x] `load_mikomn_command_schema()` - mikomn schema integration

### 2. JSON Schema Snapshots (`src/bkx/core/schemas/`)
- [x] `backup_spec.py` + `backup_spec.json` - JSON Schema for BackupSpec
- [x] `archive_spec.py` + `archive_spec.json` - JSON Schema for ArchiveCreateSpec
- [x] `transfer_spec.py` + `transfer_spec.json` - JSON Schema for TransferSpec
- [x] `restore_spec.py` + `restore_spec.json` - JSON Schema for RestoreSpec
- [x] `__init__.py` - Module exports and `get_schema_for_spec()` helper

### 3. Extended Parity Tests (`tests/parity/test_parity.py`)
- [x] Cross-interface parity tests (CLI args → Spec mapping)
- [x] **NEW** Cross-interface parity tests (CLI vs TUI intent → same Spec hash)
- [x] Spec normalization determinism tests
- [x] Schema validation tests for JSON output
- [x] **NEW** TUI Intent → Spec parity tests (BackupIntent, ArchiveIntent, RestoreIntent, TransferIntent)
- [x] Property-based testing with hypothesis for edge case coverage
- [x] CLI help parity tests
- [x] Dry-run parity tests

### 4. Test Utilities (`tests/parity/conftest.py`)
- [x] Fixtures for generating equivalent inputs across interfaces
- [x] `strategy_backup_params()` - Hypothesis strategy for backup params
- [x] `strategy_archive_params()` - Hypothesis strategy for archive params
- [x] `strategy_restore_params()` - Hypothesis strategy for restore params
- [x] `strategy_transfer_params()` - Hypothesis strategy for transfer params
- [x] `strategy_exclude_patterns()` - Hypothesis strategy for exclude patterns
- [x] `temp_source_dir` fixture - Create temporary source directories
- [x] `temp_archive_file` fixture - Create temporary archive files
- [x] `compute_cli_backup_hash()` - Helper for computing backup spec hashes
- [x] `verify_backup_parity()` - Helper for verifying backup parity

## Acceptance Checks
- [x] Parity tests catch divergence between interfaces
- [x] Schema validation prevents invalid specs
- [x] Hash verification confirms identical outputs
- [x] Property-based tests cover edge cases

## mikomn Features Used
- [x] `Command` model - Consistent intent representation (reference pattern)
- [x] `ConfigRegistry` - Schema loading and validation patterns
- [x] `command.schema.json` - Schema pattern reference
- [x] Stable JSON serialization patterns (sorted keys, deterministic encoding)

## Files Created/Modified

### New Files
- `src/bkx/core/parity.py` - Spec hashing and parity utilities
- `src/bkx/core/schemas/__init__.py` - Schema module exports
- `src/bkx/core/schemas/backup_spec.py` + `.json` - BackupSpec schema
- `src/bkx/core/schemas/archive_spec.py` + `.json` - ArchiveCreateSpec schema
- `src/bkx/core/schemas/transfer_spec.py` + `.json` - TransferSpec schema
- `src/bkx/core/schemas/restore_spec.py` + `.json` - RestoreSpec schema
- `tests/parity/conftest.py` - Test fixtures and strategies

### Modified Files
- `tests/parity/test_parity.py` - Extended with cross-interface, TUI intent, and property-based tests

## Test Execution

Run parity tests:
```bash
# All parity tests
pytest tests/parity/ -v

# Cross-interface parity only
pytest tests/parity/test_parity.py::TestCrossInterfaceParity -v

# TUI Intent parity
pytest tests/parity/test_parity.py::TestTUIIntentParity -v

# Property-based tests (requires hypothesis)
pytest tests/parity/test_parity.py::TestParityPropertyBased -v
```

## Key Features

1. **Deterministic Hashing**: Uses `json.dumps(..., sort_keys=True, separators=(",", ":"))` for stable SHA256 hashes across Python versions.

2. **Cross-Interface Parity**: Verifies CLI, TUI, and Interactive interfaces produce identical specs from same intent.

3. **Schema Validation**: JSON schemas for all spec types with validation utilities.

4. **Property-Based Testing**: Uses hypothesis to generate hundreds of edge cases automatically.

5. **TUI Intent Support**: Tests that TUI intents (via `to_spec_params()`) produce specs identical to CLI.

