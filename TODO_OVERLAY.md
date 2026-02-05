# BKX Overlay Apply Plan

## Files Created:

### 1. Widgets
- [x] `src/bkx/tui/widgets/run_panel.py` - Persistent Run Panel using mikomn panes
- [x] `src/bkx/tui/widgets/shell.py` - Hybrid shell widget (replaces current screens-based layout)

### 2. Pages
- [x] `src/bkx/tui/pages/registry.py` - Page registry for navigation
- [x] `src/bkx/tui/pages/__init__.py` - Exports create_page_registry
- [x] `src/bkx/tui/pages/backup.py` - Backup page
- [x] `src/bkx/tui/pages/archive.py` - Archive page
- [x] `src/bkx/tui/pages/restore.py` - Restore page
- [x] `src/bkx/tui/pages/transfer.py` - Transfer page
- [x] `src/bkx/tui/pages/tools.py` - Tools page (placeholder)
- [x] `src/bkx/tui/pages/logs.py` - Logs page (placeholder)

### 3. CSS
- [x] `src/bkx/tui/css/shell.tcss` - TCSS styles for the new shell layout
- [x] Update `src/bkx/tui/css/__init__.py` - Add shell.tcss to CSS_FILES

### 4. App Patch
- [x] Update `src/bkx/tui/app.py` - Replace compose() to mount BkxShell instead of current layout

## Key Changes from apply.sh:
1. Uses mikomn's HeaderBodyFooterLayout for responsive layout
2. Uses mikomn's Sidebar/SidebarRegion for sidebar navigation
3. Uses mikomn's AppFooter for compact mode tabs
4. Uses mikomn's StatusPane, ProgressPane, LogPane in RunPanel
5. Page-based navigation with ContentSwitcher


