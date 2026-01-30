# Phase 03 — Use-cases v1 (end-to-end workflows)

## Goal
Core becomes useful: archive/transfer/restore/backup services work.

## Deliverables
- [ ] ArchiveService:
  - [ ] create
  - [ ] list
  - [ ] info
- [ ] TransferService:
  - [ ] push
  - [ ] pull
- [ ] RestoreService:
  - [ ] extract
- [ ] BackupService:
  - [ ] execute (archive → transfer → verify)

## TODOs
- [ ] Implement tool selection via ports
- [ ] Add fallback implementations
- [ ] Add ExecutionPlan object + `--plan` rendering

## Acceptance checks
- [ ] Base workflows operate with fallback implementations
- [ ] Determinism: same Spec yields same Result

