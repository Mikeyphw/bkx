## Step 7B — Persist last route/page

diff --git a/src/bkx/tui/state.py b/src/bkx/tui/state.py
+ add field: last_route: str
+ load/save on every route change

diff --git a/src/bkx/tui/app.py b/src/bkx/tui/app.py
+ on_mount(): if state.last_route -> goto
+ hook after _goto_page()
