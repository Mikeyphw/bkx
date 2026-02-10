# Demo TUI App (mikomn `tui_base`) — Diff-like Implementation Plan

This document is a **design + implementation plan** for a small “showcase” TUI app whose only job is to demonstrate the features available in:

`/home/mike/.python_scripts/modules/mikomn/src/mikomn/tui_base/`

It is intentionally **code-agnostic and contract-compliant** (message-driven, removable presentation layer, CSS-owned layout).

---

## Goals

- Provide a runnable demo app that exercises **each major `mikomn.tui_base` capability** in a discoverable way.
- Demonstrate both navigation patterns:
  - **Sidebar shell** (`BaseShellTuiApp` + `SidebarShellScreen` + routed pages)
  - **Tabs shell** (`TabsShellScreen` + footer tabs + `ActiveContentSwitcher`)
- Demonstrate modal stack + overlays:
  - **Help overlay** (`HelpOverlay`)
  - **Command palette** (`CommandPalette`)
  - **Filter sheet** (`FilterSheet`)
  - **Confirm / Error modals** (`ConfirmModal`, `ErrorModal`)
  - **Wizard** (`WizardScreen` / `FormWizardScreen`)
- Demonstrate reusable panes + widgets and their intended message flows (no business logic).
- Demonstrate theme + TCSS cascade behavior:
  - Theme registration/cycling (`BaseTuiApp.action_cycle_theme`)
  - Layout mode toggle & platform files (`expanded` vs `compact`)
  - `extra_css_paths` and `get_css_excludes()`
- Demonstrate utilities:
  - `NotificationHistory` + `NotificationCenterPage`
  - `ScreenManager`, `PageRegistry`, `ScreenRegistry`
  - `SelectionModel`
- Demonstrate instrumentation/debug:
  - Debug overlay (`DebugOverlay`)
  - Debug snapshot dump (`BaseTuiApp.action_dump_debug`)

## Non-goals

- Adding new features to `mikomn.tui_base`.
- Reworking `bkx`’s production TUI; the demo is **separate** and can be removed.

---

## Feature Inventory → Demo Surface Map

This is the “what exists” → “where it’s shown” mapping.

### App + platform

- `BaseTuiApp`:
  - theme cycling (`t`)
  - layout mode toggle (`F7`)
  - sidebar collapse toggle (`F6`)
  - help overlay (`F1`)
  - debug overlay (`Ctrl+D`)
  - debug dump (`Ctrl+Shift+D`)
  - pane focus navigation (`Alt+Arrows`)
  - scroll helpers (`Alt+J/K/U/D/G/Shift+G`)
- `BaseShellTuiApp`:
  - persistent header + sidebar + `ContentSwitcher`
  - `PageRegistry` pages + sorting/grouping
  - “bell” header icon synchronized to `NotificationHistory`
- Layout modes:
  - `MIKOMN_TUI_LAYOUT=expanded|compact` overrides detection
  - `.compact`, `.expanded`, `.termux`, `.desktop` classes

### Screens (navigation targets)

- Shell patterns:
  - `SidebarShellScreen`
  - `TabsShellScreen`
- Core flows:
  - `DashboardScreen`
  - `NotificationCenterPage` (+ details modal + export modal)
  - `ConfirmModal` / typed confirm variants
  - `WizardScreen` / `FormWizardScreen`
  - `ErrorModal`

### Overlays (modal stack)

- `HelpOverlay` (searchable bindings, row selection)
- `CommandPalette` (fuzzy search, grouping, recents, multi-select)
- `FilterSheet` (filter groups/options, apply/cancel)

### Panes (semantic regions)

- Common/display/input/layout/utility panes:
  - `ListPane`, `SelectableListPane`, `FilterableListPane`, `DeferredListPane`
  - `PreviewPane`, `ReadonlyPane`, `LogPane`, `InspectorPane`
  - `TablePane`, `ResultsTablePane`, `PaginatedResultsTablePane`
  - `StatusPane`, `ProgressPane`
  - `FormPane`, `SearchResultsPane`
  - `SidebarPane`, `BottomActionPane`, `ActionSheetPane`
  - `QuickSwitchPane`, `PathShortcutsPane`, `ClipboardPane`, `SharePane`
  - `JsonExplorerPane`
  - `DashboardPane`, `ErrorPane`, `EmptyStatePane`

### Widgets (presentation components)

- Shell widgets: `AppHeader`, `AppFooter`, `Sidebar`, `KeyHints`
- Status/notifications: `StatusBar`, `StatusMessage`, `Toast`, `ToastManager`, `ToastContainer`
- Layout helpers: `PaneHeader`, `TopBar`, `ActiveContentSwitcher`
- Pickers/inputs: `FileDirPicker`, `SearchBar`, `SegmentedControl`, `MultiSelectList`
- Visual atoms: `Badge`, `BadgeCluster`, `StatusIndicator`, `PriorityBadge`
- Content patterns: `EmptyState`, `CollapsibleSection`, `CollapsibleGroup`
- Dashboards: `StatusCard`, `StatusCards`, `Dashboard`
- Lists: `DeferredListView`, `ListPaneItem`
- Banner: `AppBanner`

---

## Demo UX: “Gallery App” Structure

### Top-level navigation

Provide two entry points (two different “shells”):

1) **Sidebar shell demo** (default)
2) **Tabs shell demo** (optional, but included)

Both shells reuse the same underlying “gallery pages”, so the demo shows:
- `PageRegistry` usage
- the two shell patterns with identical content

### Pages (suggested)

Each page is simple, focuses on one subsystem, and contains “Try it” actions that emit messages:

- `home`: landing + quick tips + layout/theme indicators
- `widgets`: badges, cards, empty states, collapsibles, segmented control, key hints
- `panes_list_preview`: master-detail using `ListPane` + `PreviewPane` + `SidebarPane`
- `tables`: `ResultsTablePane` + `PaginatedResultsTablePane` + selection model
- `forms_wizard`: `FormPane` + open `WizardScreen`
- `files`: `FileDirPicker` flow + breadcrumb demo
- `json`: `JsonExplorerPane` with schema hint fallback + editor toggle
- `overlays`: buttons to open `HelpOverlay`, `CommandPalette`, `FilterSheet`
- `notifications`: seed toasts/status messages; navigate to `NotificationCenterPage`
- `debug`: show debug overlay + dump snapshot + render instrumentation toggle

Each page should run without external IO; any “actions” are simulated.

---

## Architecture Rules (demo-specific)

- **No business logic** in panes/widgets. Use message intents emitted upward.
- Store “demo state” in the Screen (view state) or a tiny in-memory service object.
- Layout is always controlled via TCSS; no geometry/docking from Python.
- `compose()` stays pure: structure only; no branching on runtime state (when possible).

---

## Proposed Code Layout (inside this repo)

This plan keeps the demo independent from the production `bkx.tui` implementation by adding a separate package:

- `src/bkx/tui_showcase/` (new)
  - `app.py` (showcase app; subclasses `BaseShellTuiApp`)
  - `tabs_app.py` (tabs-shell variant; optional)
  - `pages/` (gallery pages; each a `BaseSidebarRoutedScreen` or page widget)
  - `state.py` (demo-only view state + seed data)
  - `commands.json` (command palette config)
  - `css/` (demo-only TCSS; uses `extra_css_paths`)

---

## Diff-like Implementation Steps

The blocks below are **not final code**. They are the “surgical patch plan” showing what to add/modify.

### 1) Add new module skeleton + entrypoint

```diff
diff --git a/src/bkx/tui_showcase/__init__.py b/src/bkx/tui_showcase/__init__.py
new file mode 100644
--- /dev/null
+++ b/src/bkx/tui_showcase/__init__.py
@@
+\"\"\"Demo app showcasing mikomn.tui_base components.\"\"\"
+
+__all__ = []
```

```diff
diff --git a/src/bkx/tui_showcase/__main__.py b/src/bkx/tui_showcase/__main__.py
new file mode 100644
--- /dev/null
+++ b/src/bkx/tui_showcase/__main__.py
@@
+from bkx.tui_showcase.app import ShowcaseApp
+
+if __name__ == \"__main__\":
+    ShowcaseApp().run()
```

### 2) Implement `ShowcaseApp` (sidebar-shell by default)

Key responsibilities:
- subclass `BaseShellTuiApp`
- `register_pages()` provides gallery pages (`PageSpec` objects with grouping + ordering)
- `register_modal_routes()` registers modal routes in `self.nav` (wizard, confirm, etc.)
- implement:
  - `action_filter()` → opens `FilterSheet` with demo `FilterGroup`s
  - `action_command_palette()` (bind to `ctrl+p`) → opens `CommandPalette`
  - `on_command_selected()` → routes to the target page or triggers a demo action
- seed demo notifications (toasts + status) and demo command list on mount

```diff
diff --git a/src/bkx/tui_showcase/app.py b/src/bkx/tui_showcase/app.py
new file mode 100644
--- /dev/null
+++ b/src/bkx/tui_showcase/app.py
@@
+from mikomn.tui_base import BaseShellTuiApp
+from mikomn.tui_base.models import Command, FilterGroup, FilterOption, ToastData, HelpBinding
+from mikomn.tui_base.overlays import CommandPalette, CommandSelected, FilterSheet, FilterApplied
+from mikomn.tui_base.notifications import NotificationHistory
+from mikomn.tui_base.utils.page_registry import PageSpec
+from bkx.tui_showcase.pages import (
+    HomePage, WidgetsPage, PanesListPreviewPage, TablesPage, FormsWizardPage,
+    FilesPage, JsonPage, OverlaysPage, NotificationsSeedPage, DebugPage,
+ )
+
+class ShowcaseApp(BaseShellTuiApp):
+    TITLE = \"tui_base Showcase\"
+    SUB_TITLE = \"mikomn\"
+    BINDINGS = BaseShellTuiApp.BINDINGS + [
+        (\"ctrl+p\", \"command_palette\", \"Commands\"),
+        (\"/\", \"command_palette\", \"Commands\"),
+        (\"f\", \"filter\", \"Filter\"),
+    ]
+
+    def __init__(self, **kwargs):
+        super().__init__(extra_css_paths=[...], command_config_path=..., **kwargs)
+
+    def register_pages(self):
+        return [
+            PageSpec(route=\"home\", label=\"Home\", icon=\"🏠\", key=\"h\", factory=lambda: HomePage(id=\"home\")),
+            ...
+        ]
+
+    def register_modal_routes(self):
+        self.nav.register(\"wizard\", lambda: ..., cache=False)
+        self.nav.register(\"confirm\", lambda: ..., cache=False)
+
+    def action_command_palette(self):
+        commands = self.commands or self._fallback_commands()
+        self.push_screen(CommandPalette(commands=commands))
+
+    def action_filter(self):
+        groups = [...]
+        self.push_screen(FilterSheet(groups=groups))
+
+    def on_command_selected(self, msg: CommandSelected):
+        ... # navigate or run demo action
```

### 3) Add a tabs-shell variant (optional, but recommended)

Implement `TabsShowcaseApp` that mounts `TabsShellScreen` instead of sidebar shell.
This demonstrates:
- footer tabs + dynamic hints
- `ActiveContentSwitcher`
- reuse of the same `PageRegistry`

```diff
diff --git a/src/bkx/tui_showcase/tabs_app.py b/src/bkx/tui_showcase/tabs_app.py
new file mode 100644
--- /dev/null
+++ b/src/bkx/tui_showcase/tabs_app.py
@@
+from mikomn.tui_base import BaseTuiApp
+from mikomn.tui_base.screens.tabs_shell import TabsShellScreen
+from mikomn.tui_base.utils.page_registry import PageRegistry
+
+class TabsShowcaseApp(BaseTuiApp):
+    def register_routes(self):
+        pages = PageRegistry()
+        pages.register_many([...])
+        self.nav.register(\"shell\", lambda: TabsShellScreen(pages=pages, header=..., default_page=\"home\"), cache=True)
+
+    def on_mount(self):
+        super().on_mount()
+        self.nav.goto(\"shell\")
```

### 4) Add pages: keep each page small and message-driven

#### 4.1 Home page

Shows:
- current theme name + theme cycling (`t`)
- layout mode status + toggle (`F7`)
- quick action hints (`KeyHints`)
- navigation instructions

```diff
diff --git a/src/bkx/tui_showcase/pages/home.py b/src/bkx/tui_showcase/pages/home.py
new file mode 100644
--- /dev/null
+++ b/src/bkx/tui_showcase/pages/home.py
@@
+from mikomn.tui_base.screens.sidebar_routed import BaseSidebarRoutedScreen
+from mikomn.tui_base.widgets import PaneHeader, KeyHints, AppBanner
+...
```

#### 4.2 Widgets page

Shows:
- `Badge`, `BadgeCluster`, `StatusIndicator`, `PriorityBadge`
- `StatusCards` / `Dashboard`
- `EmptyState` variants + action callback
- `CollapsibleSection`/`CollapsibleGroup`
- `SegmentedControl` events
- `ToastManager` triggered locally

#### 4.3 Panes: list + preview + sidebar

Build a master-detail layout with:
- `MasterDetailLayout` (or `SidebarSwitcherLayout`)
- `ListPane` / `SelectableListPane` (selection)
- `PreviewPane` (details)
- `InspectorPane` (copy/open/share intents)
- `SidebarPane` or `BottomActionPane` for “Try it” actions

#### 4.4 Tables page

Shows:
- `ResultsTablePane` for simple table
- `PaginatedResultsTablePane` for paging
- `SelectionModel` for range/toggle selection

#### 4.5 Forms + Wizard page

Shows:
- `FormPane` with inputs
- open a `WizardScreen` modal
- validate + emit `WizardCompleted` / `WizardValidationError`

#### 4.6 Files page

Shows:
- `FileDirPicker` (files-only/dirs-only/none)
- `Breadcrumb` display
- `PathsSelected` / `PickerCancelled` messages

#### 4.7 JSON Explorer page

Shows:
- `JsonExplorerPane` with sample JSON
- schema hints (exact vs nearest parent fallback)
- edit mode toggle + format/save/cancel behaviors

#### 4.8 Overlays page

Buttons that open:
- `HelpOverlay` (bindings built from app + page BINDINGS)
- `CommandPalette` (use commands loaded from `commands.json`)
- `FilterSheet` (demo groups/options)
- `ConfirmModal` variants
- `ErrorModal`

#### 4.9 Notifications page

Actions:
- seed `NotificationHistory.record_toast(ToastData(...))`
- seed `NotificationHistory.record_status(...)`
- navigate to `NotificationCenterPage`

#### 4.10 Debug page

Actions:
- open debug overlay (`Ctrl+D` / `action_toggle_debug`)
- dump snapshot (`Ctrl+Shift+D` / `action_dump_debug`)
- show “where files land” (relative to `$MIKOMN_DEBUG_DUMP_FOLDER`)

### 5) Add command palette config (`commands.json`)

Use `BaseTuiApp(command_config_path=...)` + `load_commands_from_config()` so the palette demo also validates schema.

```diff
diff --git a/src/bkx/tui_showcase/commands.json b/src/bkx/tui_showcase/commands.json
new file mode 100644
--- /dev/null
+++ b/src/bkx/tui_showcase/commands.json
@@
+{
+  \"version\": \"1.0.0\",
+  \"commands\": [
+    {\"id\": \"nav.home\", \"title\": \"Go: Home\", \"keywords\": [\"home\"], \"group\": \"Navigate\", \"meta\": {\"route\": \"home\"}},
+    {\"id\": \"nav.widgets\", \"title\": \"Go: Widgets\", \"keywords\": [\"badges\", \"cards\"], \"group\": \"Navigate\", \"meta\": {\"route\": \"widgets\"}},
+    {\"id\": \"demo.toast\", \"title\": \"Demo: Toast\", \"keywords\": [\"notify\"], \"group\": \"Demo\", \"meta\": {\"action\": \"toast\"}},
+    {\"id\": \"demo.status\", \"title\": \"Demo: Status Message\", \"keywords\": [\"status\"], \"group\": \"Demo\", \"meta\": {\"action\": \"status\"}}
+  ]
+}
```

### 6) Add TCSS for the demo pages

Keep demo CSS separate, and *append* it via `extra_css_paths`:

- `src/bkx/tui_showcase/css/app.tcss` (page-specific utilities)
- `src/bkx/tui_showcase/css/pages/*.tcss`

The base cascade still comes from `mikomn/tui_base/css/*`.

```diff
diff --git a/src/bkx/tui_showcase/css/app.tcss b/src/bkx/tui_showcase/css/app.tcss
new file mode 100644
--- /dev/null
+++ b/src/bkx/tui_showcase/css/app.tcss
@@
+/* Demo-only styling; base cascade remains mikomn.tui_base */
+ShowcaseApp .showcase-page { ... }
```

### 7) Wire an executable entry point (optional)

If you want `python -m bkx.tui_showcase` only, `__main__.py` is enough.

If you want a console script, add an entrypoint in packaging config (example shown; adjust to this repo’s conventions):

```diff
diff --git a/pyproject.toml b/pyproject.toml
@@
 [project.scripts]
+bkx-tui-showcase = \"bkx.tui_showcase.__main__:main\"
```

---

## Demo Checklist (feature-by-feature)

Use this as a QA list once the demo app exists.

- [ ] Theme cycle works (`t`) and visibly changes tokens/styles
- [ ] Layout mode toggle works (`F7`) and switches platform TCSS
- [ ] Sidebar collapse works (`F6`) and sidebar widget syncs
- [ ] Help overlay opens (`F1`) and filters/searches; selecting row triggers action when runnable
- [ ] Command palette opens (`Ctrl+P`), fuzzy search works, groups show, recents tracked, multi-select works
- [ ] Filter sheet opens and returns `FilterApplied` with correct selections
- [ ] Wizard opens, step indicator updates, validation errors surface, completion returns data
- [ ] Confirm + Error modals show and return results
- [ ] File picker returns `PathsSelected` / `PickerCancelled`
- [ ] JSON explorer loads JSON, search works, preview updates, editor saves/cancels
- [ ] Toast + status messages record into `NotificationHistory`
- [ ] Notification center shows history, filters/search work, export/details modals work
- [ ] Debug overlay opens and targets the current screen
- [ ] Debug dump writes snapshot and surfaces path in a visible notification

---

## Notes / Gotchas (based on `tui_base` behavior)

- `BaseTuiApp` loads its **own base TCSS cascade** and then appends `extra_css_paths`; use `get_css_excludes()` if the demo needs to hide a base component style for clarity.
- `SidebarShellScreen` depends on CSS classes (`.sidebar-collapsed`, `.sidebar-force-expanded`, `.compact`) for collapse behavior; the demo should toggle via `BaseTuiApp.action_toggle_sidebar()`.
- The help overlay is driven by `BINDINGS` introspection across the MRO; pages should define small, meaningful `BINDINGS` to make the overlay interesting.
- Notification “unread” indicator in `BaseShellTuiApp`’s header icon is synced from `NotificationHistory.unread_count()`.

