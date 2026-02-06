"""BKX Textual TUI Package.

Provides TUI experience using Textual.

Keep this package import-safe even when optional UI dependencies (mikomn/textual)
aren't installed. Tests and tooling may import message types without needing the
full app.
"""

try:
    from bkx.tui import app  # noqa: F401
except Exception:
    # Optional dependency missing or UI-only import failure.
    pass
