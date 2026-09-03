# GO00 frozen compatibility contracts

`v1/` is the machine-readable baseline for BKX at the start of the Python → Go
migration. It is generated from the production Python implementation and consumed
by both Python drift tests and Go invariant tests.

Contents:

- `cli.json` — Typer/Click command tree, options, arguments, defaults and counts.
- `bridge.json` — protocol-v1 runtime identity, capabilities, handler set, and
  request/result/progress envelope invariants.
- `core.json` — configuration precedence/environment/defaults, public typed-error
  exit codes, run-history persistence, and canonical schema inventory.
- `release.json` — product/backend/Android identity and migration cutover rules.
- `spec_schemas/*.json` — canonical backup/archive/restore/transfer JSON Schemas.
- `manifest.json` — SHA-256 and byte size for every frozen payload above.

Regenerate intentionally with:

```sh
PYTHONPATH=src python3 tools/go_migration/export_contracts.py
```

Verify drift with:

```sh
PYTHONPATH=src python3 tools/go_migration/export_contracts.py --check
go run ./cmd/bkx contract check migration/contracts/v1
```

An intentional contract change must update the Python implementation, regenerated
fixtures, the affected Go parity implementation/tests, and the migration ledger in
the same change. Incidental drift is a failure.
