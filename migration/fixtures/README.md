# Frozen compatibility fixtures

GO17 converts the accepted Python↔Go differential evidence into immutable, Go-owned fixtures.

`v1/reference_oracle.json` freezes the 14 JSON and 3 usage cases that passed the GO15 public CLI differential gate, plus protocol-v1 Android readiness/registry expectations. The fixture data is deterministic and the Go test `internal/compatfixtures/oracle_test.go` executes it without importing or launching Python.

The historical Python implementation remains available only as the deterministic archive in `migration/archive/` until the migration audit is complete. Normal CI, Devtool validation, release building, Android validation, and compatibility validation do not execute it from GO17 onward.
