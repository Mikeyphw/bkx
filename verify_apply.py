from __future__ import annotations

import py_compile
from pathlib import Path

# Compile all relevant Python files to check for syntax errors
for p in [
    "src/bkx/tui/app.py",
    "src/bkx/tui/widgets/shell.py",
    "src/bkx/tui/widgets/key_hints.py",
]:
    py_compile.compile(p, doraise=True)

# Verify app.py has the new bindings
app_py = Path("src/bkx/tui/app.py").read_text(encoding="utf-8")
assert "jump_last_run" in app_py, "Missing jump_last_run in app.py"
assert '("g l", "jump_last_run"' in app_py or '("g l","jump_last_run"' in app_py, "Missing g l binding"
assert '("?", "help"' in app_py, "Missing ? help binding"

# Verify shell.py imports and uses KeyHintsBar
shell = Path("src/bkx/tui/widgets/shell.py").read_text(encoding="utf-8")
assert "KeyHintsBar" in shell, "Missing KeyHintsBar import in shell.py"
assert "set_key_hints" in shell, "Missing set_key_hints method in shell.py"
assert "key_hints" in shell, "Missing key_hints property in shell.py"

# Verify key_hints.py exists and is valid
key_hints = Path("src/bkx/tui/widgets/key_hints.py").read_text(encoding="utf-8")
assert "class KeyHintsBar" in key_hints, "Missing KeyHintsBar class"
assert "set_text" in key_hints, "Missing set_text method"

# Verify shell.tcss has key hints styles
tcss = Path("src/bkx/tui/css/shell.tcss").read_text(encoding="utf-8")
assert ".bkx-key-hints" in tcss, "Missing .bkx-key-hints CSS class"

print("All verification checks passed!")

