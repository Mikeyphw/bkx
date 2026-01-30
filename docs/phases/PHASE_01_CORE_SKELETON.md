# Phase 01 — Core skeleton (Specs → Services → Results)

## Goal
Core architecture exists and is deterministic, with typed errors and ports.

## Deliverables
- [ ] `core/errors.py` typed errors
- [ ] `core/specs/*` Specs + normalization
- [ ] `core/results.py` structured results
- [ ] `core/ports/*` protocols
- [ ] `core/services/*` stub services that raise NotImplementedError until later
- [ ] Tool detection port (even if minimal)

## TODOs
- [ ] Define typed error hierarchy + exit categories
- [ ] Implement initial Specs:
  - [ ] BackupSpec
  - [ ] ArchiveCreateSpec
  - [ ] TransferSpec
  - [ ] RestoreSpec
- [ ] Implement Result types matching Specs
- [ ] Create Port interfaces

## Acceptance checks
- [ ] `pytest tests/core` includes:
  - [ ] Spec normalization unit tests
  - [ ] Service unit test scaffolds

