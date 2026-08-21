"""Differential helpers for the Android bridge-shaped request contract."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from bkx.core.results.execution_plan import plan_for_spec

from .engine import _backup_spec, _restore_spec, _token, _transfer_spec, execute_json


def direct_plan_token(operation: str, payload: dict[str, Any]) -> str:
    """Compute the canonical core plan token without crossing the JSON boundary."""
    if operation == "backup.plan":
        spec = _backup_spec(payload, dry_run=True)
    elif operation == "restore.preview":
        spec = _restore_spec(payload, dry_run=True)
    elif operation == "transfer.plan":
        spec = _transfer_spec(payload, dry_run=True)
    else:
        raise ValueError(f"No direct planning parity contract for {operation}")
    return _token(plan_for_spec(spec, command=operation))


def embedded_plan_token(operation: str, request_id: str, payload: dict[str, Any]) -> str:
    response = json.loads(
        execute_json(
            json.dumps(
                {
                    "protocol_version": 1,
                    "request_id": request_id,
                    "operation": operation,
                    "payload": payload,
                }
            )
        )
    )
    if response["status"] != "success":
        raise AssertionError(response)
    label = {
        "backup.plan": "backup_plan",
        "restore.preview": "restore_plan",
        "transfer.plan": "transfer_plan",
    }[operation]
    return next(item["sha256"] for item in response["artifacts"] if item["label"] == label)
