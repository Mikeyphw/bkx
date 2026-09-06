#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import subprocess
import tempfile
import threading
import time
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--go-binary", required=True)
    args = parser.parse_args()
    binary = Path(args.go_binary)
    if not binary.is_file():
        raise SystemExit(f"missing Go binary: {binary}")

    with tempfile.TemporaryDirectory(prefix="bkx-go12-runtime-") as td:
        root = Path(td)
        runs = root / "runs.jsonl"
        proc = subprocess.Popen(
            [str(binary), "android-runtime", "bridge-session", str(runs)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            bufsize=1,
        )
        assert proc.stdin and proc.stdout and proc.stderr
        replies: dict[str, object] = {}
        condition = threading.Condition()
        errors: list[str] = []

        def reader() -> None:
            try:
                for line in proc.stdout:
                    if not line.strip():
                        continue
                    doc = json.loads(line)
                    with condition:
                        replies[str(doc.get("id"))] = doc.get("value")
                        condition.notify_all()
            except Exception as exc:  # pragma: no cover - diagnostic path
                errors.append(f"stdout reader: {exc}")
                with condition:
                    condition.notify_all()

        thread = threading.Thread(target=reader, daemon=True)
        thread.start()

        def send(command_id: str, entrypoint: str, *, request_id: str = "", request: dict | None = None) -> None:
            doc: dict[str, object] = {"id": command_id, "entrypoint": entrypoint}
            if request_id:
                doc["request_id"] = request_id
            if request is not None:
                doc["request"] = request
            proc.stdin.write(json.dumps(doc, separators=(",", ":")) + "\n")
            proc.stdin.flush()

        def await_reply(command_id: str, timeout: float = 10.0):
            deadline = time.monotonic() + timeout
            with condition:
                while command_id not in replies and time.monotonic() < deadline and not errors:
                    condition.wait(timeout=max(0.01, deadline - time.monotonic()))
                if errors:
                    raise AssertionError(errors[0])
                if command_id not in replies:
                    stderr = proc.stderr.read() if proc.poll() is not None else ""
                    raise AssertionError(f"timed out waiting for {command_id}; stderr={stderr!r}")
                return replies.pop(command_id)

        send("ready", "readiness")
        ready = await_reply("ready")
        assert ready["protocol_version"] == 1, ready
        assert ready["runtime"] == "go-native-android", ready
        assert ready["python_version"] == "none", ready
        assert "backup.plan" in ready["capabilities"], ready

        request = {
            "protocol_version": 1,
            "request_id": "storage-1",
            "operation": "storage.locations",
            "payload": {},
        }
        send("execute-storage", "execute", request=request)
        storage = await_reply("execute-storage")
        assert storage["request_id"] == "storage-1" and storage["status"] == "success", storage
        send("progress-storage", "progress", request_id="storage-1")
        progress = await_reply("progress-storage")
        assert progress["request_id"] == "storage-1" and progress["phase"] == "success", progress

        # Prove the Android session is genuinely concurrent: start a large local transfer,
        # then issue progress and cancel commands before execute returns. A sequential
        # session would deadlock these replies behind the long execute call.
        source = root / "large.bin"
        with source.open("wb") as fh:
            fh.truncate(256 * 1024 * 1024)
        destination = root / "large-copy.bin"
        transfer_request = {
            "protocol_version": 1,
            "request_id": "transfer-concurrent",
            "operation": "transfer.run",
            "payload": {
                "mode": "push",
                "direction": "push",
                "sources": [{"source_id": "s", "label": "large", "location": str(source), "remote": False}],
                "destination": {"destination_id": "d", "label": "destination", "location": str(destination), "remote": False},
                "verify": False,
                "checksum": None,
                "dry_run": False,
                "overwrite": False,
                "delete_extraneous_on_sync": False,
            },
        }
        send("execute-transfer", "execute", request=transfer_request)
        time.sleep(0.02)
        send("progress-transfer", "progress", request_id="transfer-concurrent")
        send("cancel-transfer", "cancel", request_id="transfer-concurrent")
        cancel = await_reply("cancel-transfer", timeout=5)
        assert cancel["status"] == "success" and cancel["request_id"] == "transfer-concurrent", cancel
        concurrent_progress = await_reply("progress-transfer", timeout=5)
        if concurrent_progress is not None:
            assert concurrent_progress["request_id"] == "transfer-concurrent", concurrent_progress
        result = await_reply("execute-transfer", timeout=30)
        assert result["status"] == "cancelled", result
        assert not destination.exists(), "cancelled transfer published destination"

        proc.stdin.close()
        rc = proc.wait(timeout=5)
        stderr = proc.stderr.read()
        if rc != 0:
            raise AssertionError(f"Android runtime session exited {rc}: {stderr}")

    print("BKX GO12 concurrent Android runtime protocol valid.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
