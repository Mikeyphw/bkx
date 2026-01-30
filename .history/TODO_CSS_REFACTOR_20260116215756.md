# TODO: Move inline CSS to TCSS files

## Phase 1: Create CSS folder structure and base files
- [ ] Create `/src/bkx/tui/css/panes/` directory
- [ ] Create `/src/bkx/tui/css/screens/` directory
- [ ] Create `/src/bkx/tui/css/tokens.tcss` with TCSS variables
- [ ] Create `/src/bkx/tui/css/app.tcss` main entry file

## Phase 2: Create pane TCSS files
- [ ] Create `panes/status_cards.tcss`
- [ ] Create `panes/empty_state.tcss`
- [ ] Create `panes/log.tcss`
- [ ] Create `panes/status.tcss`
- [ ] Create `panes/progress.tcss`
- [ ] Create `panes/archive_list.tcss`
- [ ] Create `panes/navigation.tcss`

## Phase 3: Create screen TCSS files
- [ ] Create `screens/restore.tcss`
- [ ] Create `screens/backup.tcss`
- [ ] Create `screens/archive.tcss`
- [ ] Create `screens/transfer.tcss`
- [ ] Create `screens/tools.tcss`
- [ ] Create `screens/logs.tcss`

## Phase 4: Create CSS registry
- [ ] Create `/src/bkx/tui/css/__init__.py` with get_css_paths(), load_css(), reload_css()

## Phase 5: Update Python files to use external CSS
- [ ] Update `panes/status_cards.py`
- [ ] Update `panes/empty_state.py`
- [ ] Update `panes/log.py`
- [ ] Update `panes/status.py`
- [ ] Update `panes/progress.py`
- [ ] Update `panes/archive_list.py`
- [ ] Update `panes/navigation.py`
- [ ] Update `screens/restore.py`
- [ ] Update `screens/backup.py`
- [ ] Update `screens/archive.py`
- [ ] Update `screens/transfer.py`
- [ ] Update `screens/tools.py`
- [ ] Update `screens/logs.py`

## Phase 6: Update app.py
- [ ] Import and call load_css() in BkxTuiApp

## Phase 7: Testing
- [ ] Verify all TCSS files are valid using mikomn validator
- [ ] Test TUI loads correctly

