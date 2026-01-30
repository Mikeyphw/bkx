# Phase 05 — Interactive mode (optional extra)

## Goal
Interactive CLI is a Spec-builder UX layer, parity-safe and removable.

## Deliverables
- [ ] extra: `.[interactive]`
- [ ] `ui start --mode interactive`
- [ ] noun→verb menu
- [ ] two-phase prompts (required → optional)
- [ ] spec/plan preview
- [ ] edit-before-run loop
- [ ] picker fallback chain (fzf → rich → numbered)

## TODOs
- [ ] Build interactive orchestrator that calls shared Spec builders
- [ ] Add tool detection for external pickers
- [ ] Ensure interactive never bypasses validation

## Acceptance checks
- [ ] same selection produces same Spec as CLI
- [ ] interactive works without fzf installed

