# TODO: BKX TUI Patches Implementation

## TCSS Files (12 files)
- [ ] 1. archive_list.tcss - Rename selectors with `.bkx-archive-list-pane` prefix
- [ ] 2. archive_preview.tcss - Rename `ArchivePreviewPane` → `.bkx-archive-preview-pane`
- [ ] 3. empty_state.tcss - Rename `EmptyStatePane` → `.bkx-empty-state-pane`
- [ ] 4. log.tcss - Rename `RunLogPane` → `.bkx-run-log-pane`, `RichLog` → `.bkx-run-log-pane RichLog`
- [ ] 5. navigation.tcss - Rename selectors with `.bkx-navigation-pane` prefix
- [ ] 6. progress.tcss - Rename `ProgressPane` → `.bkx-progress-pane`
- [ ] 7. status.tcss - Rename `StatusFooterPane` → `.bkx-status-footer-pane`
- [ ] 8. status_bar.tcss - Rename `StatusBarPane` → `.bkx-status-bar-pane`
- [ ] 9. status_cards.tcss - Rename `StatusCardsPane` → `.bkx-status-cards-pane`
- [ ] 10. theme_settings.tcss - Rename `ThemeSettingsPane` → `.bkx-theme-settings-pane`
- [ ] 11. tools_dashboard.tcss - Rename `ToolsDashboardPane` → `.bkx-tools-dashboard-pane`
- [ ] 12. transfer_progress.tcss - Rename `TransferProgressPane` → `.bkx-transfer-progress-pane`

## Python Files (8 files)
- [ ] 1. archive_list.py - Add `DEFAULT_CLASSES`/`add_class()` to pane classes
- [ ] 2. empty_state.py - Add `DEFAULT_CLASSES = "bkx-empty-state-pane"`
- [ ] 3. log.py - Add `DEFAULT_CLASSES = "bkx-run-log-pane"`
- [ ] 4. navigation.py - Add `DEFAULT_CLASSES = "bkx-navigation-pane"`
- [ ] 5. progress.py - Add `add_class()` calls in init methods
- [ ] 6. status.py - Add `DEFAULT_CLASSES = "bkx-status-footer-pane"`
- [ ] 7. status_cards.py - Add `DEFAULT_CLASSES` to StatusCardsPane, ThemeSettingsPane, ToolsDashboardPane
- [ ] 8. run_panel.py - Add progress plumbing methods and import GlobalProgressBar
- [ ] 9. app.py - Update progress handlers

## Verification
- [ ] Create test script in /tmp
- [ ] Run ruff on modified/created files
- [ ] Run pyright on modified/created files

