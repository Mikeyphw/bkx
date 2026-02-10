## Step 8B — Restart into last route after crash

diff --git a/src/bkx/tui/app.py b/src/bkx/tui/app.py
+ wrap main run loop
+ on crash: persist last_route, show CrashScreen
