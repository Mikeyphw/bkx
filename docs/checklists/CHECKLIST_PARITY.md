# Checklist: Determinism + parity

- [x] Same semantic input produces identical Spec across CLI/shared interface dispatch
- [x] Same Spec produces identical Result (core service boundary)
- [x] JSON output schemas stable through the shared renderer
- [x] Exit codes stable, including `--trace`

Suggested parity tests:
- [x] CLI args → Spec hash
- [x] Interactive selection mapping → same Spec hash through shared adapter
- [x] TUI form payload mapping → same Spec hash through shared adapter


## Release seal adversarial families

- [ ] JSON output snapshots parse as exactly one JSON document.
- [ ] JSON schema negative cases reject invalid enums, items, and selectors.
- [ ] Path traversal probes are blocked in archive and restore flows.
- [ ] Symlink probes are blocked in hash, restore, and transfer flows.
- [ ] FIFO/special-file probes are rejected before archive or transfer writes.
- [ ] Overlap/collision probes are rejected before destructive writes.
- [ ] Partial-write regressions leave no published partial artifact.
- [ ] Corruption probes fail verification and cannot report success.
- [ ] Service failure paths surface typed failures instead of false success.
