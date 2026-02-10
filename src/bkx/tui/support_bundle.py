"""Support bundle generator - Step 9A

Creates comprehensive support bundles for debugging.
Includes logs, notifications, UI state, intents, configs, and system info.
"""

from __future__ import annotations

import json
import os
import platform
import sys
import time
import zipfile
from dataclasses import asdict
from datetime import datetime
from pathlib import Path
from typing import Any

from bkx.tui.intent_history import get_intent_history
from bkx.tui.state import get_state_file, load_state

# Bundle content configuration
BUNDLE_CONFIG = {
    "max_log_lines": 500,
    "max_notifications": 200,
    "max_intents": 100,
}


def get_bkx_version() -> str:
    """Get BKX version if available."""
    try:
        from importlib.metadata import version

        return version("bkx")
    except Exception:
        try:
            import bkx

            return getattr(bkx, "__version__", "unknown")
        except Exception:
            return "unknown"


def get_platform_info() -> dict[str, Any]:
    """Gather platform and system information."""
    # Import bkx locally for package path detection
    try:
        import bkx as _bkx_module

        bkx_pkg_path = str(Path(_bkx_module.__file__).parent)
    except Exception:
        bkx_pkg_path = "N/A"

    return {
        "timestamp": datetime.now().isoformat(),
        "platform": {
            "system": platform.system(),
            "release": platform.release(),
            "version": platform.version(),
            "machine": platform.machine(),
            "processor": platform.processor(),
            "python_version": sys.version,
            "python_executable": sys.executable,
            "python_implementation": platform.python_implementation(),
        },
        "bkx": {
            "version": get_bkx_version(),
            "package_path": bkx_pkg_path,
        },
    }


def get_tui_state_info() -> dict[str, Any]:
    """Get persisted TUI state."""
    try:
        state = load_state()
        return asdict(state)
    except Exception as e:
        return {"error": str(e)}


def get_run_logs(max_lines: int = 500) -> str:
    """Get recent run logs.

    Tries multiple sources:
    1. RunPanel widget (if available)
    2. Log file (if exists)
    """
    logs: list[str] = []

    # Try to get logs from RunPanel
    try:
        from bkx.tui.widgets.shell import BkxShell

        app = getattr(BkxShell, "_app", None) or getattr(BkxShell, "app", None)
        if app:
            run_panel = None
            try:
                shell = app.query_one(BkxShell)
                run_panel = shell.run_panel
            except Exception:
                pass

            if run_panel and hasattr(run_panel, "get_log_text"):
                log_text = run_panel.get_log_text()
                if log_text:
                    lines = log_text.split("\n")
                    logs.extend(lines[-max_lines:])
                    return "\n".join(logs)
    except Exception:
        pass

    # Check for log file
    log_paths = [
        Path.home() / ".config" / "bkx" / "logs" / "bkx.log",
        Path("/tmp/bkx.log"),
        Path("/tmp/bkx_run.log"),
    ]

    for log_path in log_paths:
        if log_path.exists():
            try:
                lines = log_path.read_text(encoding="utf-8").split("\n")
                logs.extend(lines[-max_lines:])
                return "\n".join(logs)
            except Exception:
                continue

    return "\n".join(logs) if logs else "No run logs available"


def get_notification_history(max_entries: int = 200) -> list[dict[str, Any]]:
    """Get notification history."""
    try:
        from mikomn.tui_base.notifications import NotificationHistory

        entries = NotificationHistory.events()
        try:
            recent = list(entries)[-max_entries:]
            return [
                {
                    "timestamp": getattr(e, "timestamp", ""),
                    "message": getattr(e, "message", ""),
                    "level": getattr(e, "level", "info"),
                    "source": getattr(e, "source", "bkx"),
                }
                for e in recent
            ]
        except Exception:
            pass
    except Exception:
        pass

    return []


def get_intents_history(max_entries: int = 100) -> list[dict[str, Any]]:
    """Get intent history from the tracking system."""
    try:
        history = get_intent_history()
        return history.to_list()
    except Exception:
        return []


def get_config_files() -> dict[str, str]:
    """Collect relevant config file contents."""
    configs: dict[str, str] = {}

    config_home = os.environ.get("XDG_CONFIG_HOME", "") or str(Path.home() / ".config")
    bkx_config_dir = Path(config_home) / "bkx"

    # Known config files
    config_patterns = [
        "bkx.yaml",
        "bkx.yml",
        "config.yaml",
        "config.yml",
        "tui_state.json",
        "profiles.json",
    ]

    for pattern in config_patterns:
        config_path = bkx_config_dir / pattern
        if config_path.exists():
            try:
                content = config_path.read_text(encoding="utf-8")
                # Truncate very large files
                if len(content) > 10000:
                    content = content[:10000] + "\n... (truncated)"
                configs[str(config_path)] = content
            except Exception:
                pass

    return configs


def create_support_bundle(
    output_dir: Path | None = None,
    include_logs: bool = True,
    include_notifications: bool = True,
    include_intents: bool = True,
    include_configs: bool = True,
    include_system_info: bool = True,
) -> Path:
    """Create a support bundle zip file.

    Args:
        output_dir: Directory for output file (default: /tmp)
        include_logs: Include run logs
        include_notifications: Include notification history
        include_intents: Include intent history
        include_configs: Include config file contents
        include_system_info: Include system/platform info

    Returns:
        Path to the created bundle zip file
    """
    timestamp = time.strftime("%Y%m%d_%H%M%S")
    output_path = output_dir or Path("/tmp")
    bundle_path = output_path / f"bkx_support_{timestamp}.zip"

    with zipfile.ZipFile(bundle_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        # manifest.json - Bundle metadata
        manifest = {
            "created_at": datetime.now().isoformat(),
            "bkx_version": get_bkx_version(),
            "bundle_version": "1.0",
            "contents": [],
        }

        # system_info.txt
        if include_system_info:
            system_info = get_platform_info()
            info_text = _format_system_info(system_info)
            zf.writestr("system_info.txt", info_text)
            manifest["contents"].append("system_info.txt")

        # tui_state.json
        try:
            state_file = get_state_file()
            if state_file.exists():
                zf.write(state_file, arcname="tui_state.json")
                manifest["contents"].append("tui_state.json")
        except Exception:
            pass

        # run_logs.txt
        if include_logs:
            logs = get_run_logs(BUNDLE_CONFIG["max_log_lines"])
            zf.writestr("run_logs.txt", logs)
            manifest["contents"].append("run_logs.txt")

        # notification_history.json
        if include_notifications:
            notifications = get_notification_history(BUNDLE_CONFIG["max_notifications"])
            zf.writestr(
                "notification_history.json",
                json.dumps(notifications, indent=2, default=str),
            )
            manifest["contents"].append("notification_history.json")

        # intents_history.json
        if include_intents:
            intents = get_intents_history(BUNDLE_CONFIG["max_intents"])
            zf.writestr(
                "intents_history.json",
                json.dumps(intents, indent=2, default=str),
            )
            manifest["contents"].append("intents_history.json")

        # config files
        if include_configs:
            configs = get_config_files()
            for rel_path, content in configs.items():
                arcname = f"config/{Path(rel_path).name}"
                zf.writestr(arcname, content)
                manifest["contents"].append(arcname)

        # Write manifest last
        zf.writestr("manifest.json", json.dumps(manifest, indent=2))

    return bundle_path


def _format_system_info(info: dict[str, Any]) -> str:
    """Format system info as readable text."""
    lines = ["BKX Support Bundle - System Information", "=" * 50, ""]

    if "timestamp" in info:
        lines.append(f"Generated: {info['timestamp']}")

    if "bkx" in info:
        lines.append("")
        lines.append("BKX Information:")
        for key, value in info["bkx"].items():
            lines.append(f"  {key}: {value}")

    if "platform" in info:
        lines.append("")
        lines.append("Platform Information:")
        for key, value in info["platform"].items():
            lines.append(f"  {key}: {value}")

    return "\n".join(lines)


def get_bundle_path() -> Path:
    """Get the most recent support bundle path."""
    tmp_dir = Path("/tmp")
    bundles = sorted(
        (p for p in tmp_dir.glob("bkx_support_*.zip") if p.is_file()),
        reverse=True,
    )
    return bundles[0] if bundles else Path("/tmp/bkx_support_latest.zip")


def export_support_bundle() -> tuple[Path, str]:
    """Export support bundle and return path with message.

    Returns:
        Tuple of (bundle_path, message)
    """
    try:
        bundle_path = create_support_bundle()
        message = f"Support bundle saved: {bundle_path}"
        return bundle_path, message
    except PermissionError:
        # Try alternate location
        try:
            bundle_path = create_support_bundle(output_dir=Path.home())
            return bundle_path, f"Support bundle saved: {bundle_path}"
        except Exception as e:
            return Path(""), f"Failed to create support bundle: {e}"
    except Exception as e:
        return Path(""), f"Failed to create support bundle: {e}"
