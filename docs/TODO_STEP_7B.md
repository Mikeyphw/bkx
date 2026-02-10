# STEP_7B Implementation Plan

## Goal
Persist last route/page by adding a hook after `_goto_page()` to save the route.

## Files to Create

1. `/home/mike/.python_scripts/modules/bkx/docs/STEP_7B.toml` - TOML spec
2. `/home/mike/.python_scripts/modules/bkx/docs/STEP_7B.patch` - Patch file with the diff

## Changes

### STEP_7B.patch
```diff
diff --git a/src/bkx/tui/app.py b/src/bkx/tui/app.py
index 1234567..abcdefg 100644
--- a/src/bkx/tui/app.py
+++ b/src/bkx/tui/app.py
@@ -214,6 +214,8 @@ class BkxTuiApp(BaseTuiApp):
 
     def _goto_page(self, route: str) -> None:
         """Navigate to a shell page route."""
+        self._last_route = route
+        self._persist_tui_state()
         try:
             self.query_one(BkxShell).goto_page(route)
```

### STEP_7B.toml
```toml
tcss_validate_ignore = ["**/mikomn/**/tokens.tcss"]
```

## Status
- [x] Create STEP_7B.patch
- [x] Create STEP_7B.toml
- [ ] Verify implementation

