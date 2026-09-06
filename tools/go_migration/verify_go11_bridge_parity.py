#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import subprocess
import tempfile
from pathlib import Path
from typing import Any


def request(request_id: str, operation: str, payload: dict[str, Any]) -> dict[str, Any]:
    return {
        "protocol_version": 1,
        "request_id": request_id,
        "operation": operation,
        "payload": payload,
    }


def without_duration(document: dict[str, Any]) -> dict[str, Any]:
    result = json.loads(json.dumps(document))
    metrics = result.get("metrics")
    if isinstance(metrics, dict):
        assert isinstance(metrics.get("duration_ms"), int) and metrics["duration_ms"] >= 0, result
        metrics["duration_ms"] = "<duration>"
    return result


class GoSession:
    def __init__(self, binary: Path, runs: str = "-") -> None:
        self.proc = subprocess.Popen(
            [str(binary), "migration", "bridge", "session", runs],
            text=True,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=1,
        )
        assert self.proc.stdin is not None and self.proc.stdout is not None

    def call(self, entrypoint: str, *, request_id: str = "", request_doc: Any = None) -> Any:
        command: dict[str, Any] = {"entrypoint": entrypoint}
        if request_id:
            command["request_id"] = request_id
        if request_doc is not None:
            command["request"] = request_doc
        self.proc.stdin.write(json.dumps(command, separators=(",", ":")) + "\n")
        self.proc.stdin.flush()
        line = self.proc.stdout.readline()
        assert line, self.proc.stderr.read() if self.proc.stderr else "bridge session ended"
        return json.loads(line)

    def close(self) -> None:
        if self.proc.stdin:
            self.proc.stdin.close()
        rc = self.proc.wait(timeout=10)
        assert rc == 0, self.proc.stderr.read() if self.proc.stderr else rc


def assert_error_shape_parity(py: dict[str, Any], go: dict[str, Any], *, exact_summary: bool = True) -> None:
    assert py["protocol_version"] == go["protocol_version"] == 1
    assert py["request_id"] == go["request_id"]
    assert py["status"] == go["status"] == "failure"
    assert py["artifacts"] == go["artifacts"] == []
    assert len(py["errors"]) == len(go["errors"]) == 1
    for key in ("code", "action"):
        assert py["errors"][0][key] == go["errors"][0][key], (key, py, go)
    if exact_summary:
        assert py["summary"] == go["summary"], (py, go)
        assert py["errors"][0]["message"] == go["errors"][0]["message"]
    for doc in (py, go):
        assert isinstance(doc["metrics"]["duration_ms"], int) and doc["metrics"]["duration_ms"] >= 0


def check(binary: Path) -> None:
    with tempfile.TemporaryDirectory(prefix="bkx-go11-bridge-") as td:
        root = Path(td)
        home = root / "home"
        home.mkdir()
        os.environ["HOME"] = str(home)
        os.environ["TZ"] = "UTC"

        from bkx.android_api import engine

        # Readiness keeps the current protocol/capability seam while declaring
        # that this is migration-only Go, not the still-production Python runtime.
        py_ready = json.loads(engine.readiness_json())
        go_ready = json.loads(
            subprocess.check_output([str(binary), "migration", "bridge", "readiness"], text=True)
        )
        assert py_ready["protocol_version"] == go_ready["protocol_version"] == 1
        assert py_ready["capabilities"] == go_ready["capabilities"]
        assert py_ready["runtime"] == "embedded-cpython"
        assert go_ready["runtime"] == "go-native-migration"
        assert go_ready["backend_version"] == "1.0.0"
        assert go_ready["python_version"] == "none"

        sample = root / "sample.txt"
        sample.write_text("hello\n", encoding="utf-8")
        epoch_ns = 1_704_164_646_123_000_000
        os.utime(sample, ns=(epoch_ns, epoch_ns))

        # Successful request/result envelope and final progress are exact except
        # elapsed time, which is runtime-specific by definition.
        req = request("bridge-stat", "filesystem.stat", {"path": str(sample)})
        py_result = json.loads(engine.execute_json(json.dumps(req, separators=(",", ":"))))
        py_progress = json.loads(engine.progress_json("bridge-stat"))
        session = GoSession(binary)
        try:
            go_result = session.call("execute", request_doc=req)
            go_progress = session.call("progress", request_id="bridge-stat")
        finally:
            session.close()
        assert without_duration(py_result) == without_duration(go_result), (py_result, go_result)
        assert py_progress == go_progress, (py_progress, go_progress)

        # Strict request validation and typed boundary error mapping.
        malformed_cases: list[tuple[str, str, bool]] = [
            ("{}", "empty-object", True),
            ("[]", "array-root", True),
            ('{"protocol_version":2,"request_id":"x","operation":"filesystem.stat","payload":{}}', "protocol", True),
            ('{"protocol_version":1,"request_id":"bad id","operation":"filesystem.stat","payload":{}}', "request-id", True),
            ('{"protocol_version":1,"request_id":"x","operation":"filesystem.stat","payload":[]}', "payload", True),
            ("{bad", "json-syntax", False),
        ]
        for raw, label, exact in malformed_cases:
            py = json.loads(engine.execute_json(raw))
            go = json.loads(
                subprocess.check_output([str(binary), "migration", "bridge", "execute", raw, "-"], text=True)
            )
            assert_error_shape_parity(py, go, exact_summary=exact), label

        unsupported = request("no-shell", "shell.exec", {})
        py = json.loads(engine.execute_json(json.dumps(unsupported)))
        go = json.loads(
            subprocess.check_output(
                [str(binary), "migration", "bridge", "execute", json.dumps(unsupported), "-"], text=True
            )
        )
        assert_error_shape_parity(py, go)

        missing = request("missing", "filesystem.stat", {"path": str(root / "missing")})
        py = json.loads(engine.execute_json(json.dumps(missing)))
        go = json.loads(
            subprocess.check_output(
                [str(binary), "migration", "bridge", "execute", json.dumps(missing), "-"], text=True
            )
        )
        assert_error_shape_parity(py, go)

        # cancel_json acknowledgement, queued cooperative cancellation, and the
        # final cancelled progress document must match in one persistent runtime.
        cancel_id = "queued-go11"
        py_ack = json.loads(engine.cancel_json(cancel_id))
        py_cancelled = json.loads(engine.execute_json(json.dumps(request(cancel_id, "storage.locations", {}))))
        py_cancel_progress = json.loads(engine.progress_json(cancel_id))
        session = GoSession(binary)
        try:
            go_ack = session.call("cancel", request_id=cancel_id)
            go_cancelled = session.call("execute", request_doc=request(cancel_id, "storage.locations", {}))
            go_cancel_progress = session.call("progress", request_id=cancel_id)
        finally:
            session.close()
        assert py_ack == go_ack, (py_ack, go_ack)
        assert without_duration(py_cancelled) == without_duration(go_cancelled), (py_cancelled, go_cancelled)
        assert py_cancel_progress == go_cancel_progress, (py_cancel_progress, go_cancel_progress)

        # run.cancel is a handler, but not an advertised operation.
        target = "go11-target"
        cancel_command = request("go11-cancel-command", "run.cancel", {"target_request_id": target})
        py_command = json.loads(engine.execute_json(json.dumps(cancel_command)))
        py_target = json.loads(engine.execute_json(json.dumps(request(target, "storage.locations", {}))))
        session = GoSession(binary)
        try:
            go_command = session.call("execute", request_doc=cancel_command)
            go_target = session.call("execute", request_doc=request(target, "storage.locations", {}))
        finally:
            session.close()
        assert without_duration(py_command) == without_duration(go_command), (py_command, go_command)
        assert without_duration(py_target) == without_duration(go_target), (py_target, go_target)

        advertised = list(engine.supported_operations())
        frozen = json.loads(Path("migration/contracts/v1/bridge.json").read_text())
        assert advertised == frozen["advertisedOperations"]
        assert "run.cancel" not in advertised
        assert "run.cancel" in frozen["handlers"]

    print("BKX GO11 protocol-v1 bridge envelope/error/progress/cancellation parity passed.")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--go-binary", type=Path, required=True)
    args = parser.parse_args()
    check(args.go_binary.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
