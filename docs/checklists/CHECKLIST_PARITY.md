# Checklist: Determinism + parity

- [ ] Same semantic input produces identical Spec across interfaces
- [ ] Same Spec produces identical Result (core)
- [ ] JSON output schemas stable
- [ ] Exit codes stable

Suggested parity tests:
- [ ] CLI args → Spec hash
- [ ] Interactive selection → same Spec hash
- [ ] TUI message payload → same Spec hash

