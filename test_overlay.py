#!/usr/bin/env python3
"""Temporary script to validate the bkx overlay implementation."""

import sys
from pathlib import Path

BKX_REPO = Path(__file__).parent

def check_file_exists(path: Path, description: str) -> bool:
    """Check if a file exists and report status."""
    if path.exists():
        print(f"✓ {description}: {path}")
        return True
    else:
        print(f"✗ {description}: {path} NOT FOUND")
        return False

def check_import(module_path: str) -> bool:
    """Check if a module can be imported."""
    try:
        __import__(module_path)
        print(f"✓ Import successful: {module_path}")
        return True
    except ImportError as e:
        print(f"✗ Import failed: {module_path} - {e}")
        return False

def main():
    """Validate overlay implementation."""
    print("=" * 60)
    print("BKX Overlay Implementation Validation")
    print("=" * 60)
    print(f"Repository: {BKX_REPO}")
    print()

    all_passed = True

    # Check files exist
    print("--- Checking Files ---")
    files_to_check = [
        (BKX_REPO / "src/bkx/tui/widgets/run_panel.py", "Run Panel Widget"),
        (BKX_REPO / "src/bkx/tui/widgets/shell.py", "Shell Widget"),
        (BKX_REPO / "src/bkx/tui/pages/registry.py", "Page Registry"),
        (BKX_REPO / "src/bkx/tui/pages/__init__.py", "Pages Init"),
        (BKX_REPO / "src/bkx/tui/pages/backup.py", "Backup Page"),
        (BKX_REPO / "src/bkx/tui/pages/archive.py", "Archive Page"),
        (BKX_REPO / "src/bkx/tui/pages/restore.py", "Restore Page"),
        (BKX_REPO / "src/bkx/tui/pages/transfer.py", "Transfer Page"),
        (BKX_REPO / "src/bkx/tui/pages/tools.py", "Tools Page"),
        (BKX_REPO / "src/bkx/tui/pages/logs.py", "Logs Page"),
        (BKX_REPO / "src/bkx/tui/css/shell.tcss", "Shell TCSS"),
        (BKX_REPO / "src/bkx/tui/css/__init__.py", "CSS Init (modified)"),
        (BKX_REPO / "src/bkx/tui/app.py", "App (modified)"),
    ]

    for path, desc in files_to_check:
        if not check_file_exists(path, desc):
            all_passed = False

    print()

    # Check shell.tcss content
    print("--- Checking shell.tcss ---")
    shell_tcss = BKX_REPO / "src/bkx/tui/css/shell.tcss"
    if shell_tcss.exists():
        content = shell_tcss.read_text()
        required_selectors = [
            ".bkx-shell-layout",
            ".bkx-sidebar-region", 
            ".bkx-content-region",
            ".bkx-run-panel-region",
            ".bkx-page",
            ".expanded .bkx-footer-tabs",
            ".compact .bkx-sidebar-region",
        ]
        for selector in required_selectors:
            if selector in content:
                print(f"✓ Found selector: {selector}")
            else:
                print(f"✗ Missing selector: {selector}")
                all_passed = False

    print()

    # Check css/__init__.py imports shell.tcss
    print("--- Checking css/__init__.py ---")
    css_init = BKX_REPO / "src/bkx/tui/css/__init__.py"
    if css_init.exists():
        content = css_init.read_text()
        if "shell.tcss" in content:
            print("✓ shell.tcss referenced in css/__init__.py")
        else:
            print("✗ shell.tcss NOT referenced in css/__init__.py")
            all_passed = False

    print()

    # Check app.py imports
    print("--- Checking app.py ---")
    app_py = BKX_REPO / "src/bkx/tui/app.py"
    if app_py.exists():
        content = app_py.read_text()
        
        if "from bkx.tui.widgets.shell import BkxShell" in content:
            print("✓ BkxShell imported")
        else:
            print("✗ BkxShell NOT imported")
            all_passed = False
            
        if "from bkx.tui.pages import create_page_registry" in content:
            print("✓ create_page_registry imported")
        else:
            print("✗ create_page_registry NOT imported")
            all_passed = False
            
        if "yield BkxShell(pages=create_page_registry())" in content:
            print("✓ BkxShell used in compose()")
        else:
            print("✗ BkxShell NOT used in compose()")
            all_passed = False

    print()

    # Summary
    print("=" * 60)
    if all_passed:
        print("✓ All checks passed!")
        return 0
    else:
        print("✗ Some checks failed!")
        return 1

if __name__ == "__main__":
    sys.exit(main())

