# TODO: Move inline CSS to TCSS files

## Phase 1: Create CSS folder structure and base files
- [x] Create `/src/bkx/tui/css/panes/` directory
- [x] Create `/src/bkx/tui/css/screens/` directory
- [x] Create `/src/bkx/tui/css/app.tcss` main entry file
- [x] Create `/src/bkx/tui/css/__init__.py` with CSS registry

## Phase 2: Create pane TCSS files
- [x] Create `panes/status_cards.tcss`
- [x] Create `panes/theme_settings.tcss`
- [x] Create `panes/tools_dashboard.tcss`
- [x] Create `panes/empty_state.tcss`
- [x] Create `panes/log.tcss`
- [x] Create `panes/status.tcss`
- [x] Create `panes/progress.tcss`
- [x] Create `panes/status_bar.tcss`
- [x] Create `panes/transfer_progress.tcss`
- [x] Create `panes/archive_list.tcss`
- [x] Create `panes/archive_preview.tcss`
- [x] Create `panes/navigation.tcss`

## Phase 3: Create screen TCSS files
- [x] Create `screens/restore.tcss`
- [x] Create `screens/backup.tcss`
- [x] Create `screens/archive.tcss`
- [x] Create `screens/transfer.tcss`
- [x] Create `screens/tools.tcss`
- [x] Create `screens/logs.tcss`

## Phase 4: Update Python files to use external CSS
- [x] Update `panes/status_cards.py`
- [x] Update `panes/empty_state.py`
- [x] Update `panes/log.py`
- [x] Update `panes/status.py`
- [x] Update `panes/progress.py`
- [x] Update `panes/archive_list.py`
- [x] Update `panes/navigation.py`
- [x] Update `screens/restore.py`
- [x] Update `screens/backup.py`
- [x] Update `screens/archive.py`
- [x] Update `screens/transfer.py`
- [x] Update `screens/tools.py`
- [x] Update `screens/logs.py`

## Phase 5: Update app.py
- [x] Import and call load_css() in BkxTuiApp

## Phase 6: Testing
- [ ] Verify all TCSS files are valid using mikomn validator
- [ ] Test TUI loads correctly

