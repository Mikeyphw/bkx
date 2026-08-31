# Checklist: TUI (optional)

## Removability
- [x] TUI package deletable without touching core/cli
- [x] Installed only via extras (`pip install .[tui]`)
- [x] Missing extra fails cleanly through `bkx ui start --mode tui`

## mikomn Integration
- [x] Uses `TuiApp` as app scaffold (`src/bkx/tui/app.py`)
- [x] Theme cycling works through mikomn/TuiApp services
- [x] Command palette/action registry is provided by mikomn/TuiApp
- [x] Platform layout adapts through mikomn runtime
- [x] Uses mikomn panes/widgets through model-first page factories

## Message-driven
- [x] UI actions collect form payloads only
- [x] App dispatches form payloads through the shared interface Spec adapter
- [x] App calls services with normalized Specs

## No logic in UI
- [x] No business logic in screens/panes/key handlers
- [x] No direct IO/subprocess in widgets
- [x] All operations go through action dispatch and services

## Scroll rules
- [x] Screen does not scroll globally; panes declare scroll intent explicitly

## Tests
- [x] Optional dispatch/parity tests compare semantic Spec hashes

## Remaining polish
- [ ] Additional mikomn FileDirPicker/ConfirmModal polish remains optional enhancement work
