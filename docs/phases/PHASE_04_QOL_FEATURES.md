# Phase 04 — QoL features (daily-driver polish)

## Goal
Make it comfortable: detection, recent runs, browsing, safer restore.

## Deliverables
- [ ] `tools detect` + `tools explain`
- [ ] `run list` + `run show <id>`
- [ ] `archive verify`
- [ ] `archive browse --query`
- [ ] safer restore defaults:
  - [ ] restore into fresh dir by default
  - [ ] confirm/yes for overwrite
- [ ] config layering (flags > env > config > defaults)

## TODOs
- [ ] Add run metadata storage (simple JSON)
- [ ] Implement archive browsing in core
- [ ] Add verify flows

## Acceptance checks
- [ ] run metadata persists between runs
- [ ] tools explain shows decision reasons
- [ ] restore refuses dangerous overwrite unless confirmed

