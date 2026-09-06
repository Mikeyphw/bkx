#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import subprocess
import tempfile
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--go-binary", required=True)
    args = parser.parse_args()
    binary = Path(args.go_binary)
    if not binary.is_file():
        raise SystemExit(f"missing Go binary: {binary}")

    with tempfile.TemporaryDirectory(prefix="bkx-go13-root-") as td:
        runs = Path(td) / "runs.jsonl"
        proc = subprocess.Popen(
            [str(binary), "android-runtime", "bridge-session", str(runs)],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
        assert proc.stdin and proc.stdout and proc.stderr
        proc.stdin.write(json.dumps({"id": "ready", "entrypoint": "readiness"}, separators=(",", ":")) + "\n")
        proc.stdin.flush()
        reply = json.loads(proc.stdout.readline())
        ready = reply["value"]
        assert ready["runtime"] == "go-native-android", ready
        caps = set(ready["capabilities"])
        assert "filesystem.direct" in caps, caps
        if os.geteuid() == 0:
            assert "root.filesystem" in caps, caps
            assert "storage.termux.private" in caps, caps
            assert "storage.app_private" in caps, caps
        proc.stdin.close()
        rc = proc.wait(timeout=5)
        if rc != 0:
            raise AssertionError(proc.stderr.read())
    print(f"BKX GO13 runtime capability probe valid (euid={os.geteuid()}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
