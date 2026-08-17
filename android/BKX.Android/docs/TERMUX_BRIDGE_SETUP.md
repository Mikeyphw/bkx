# Termux Bridge Setup

The first BKX Android backend strategy uses Termux RUN_COMMAND with a fixed wrapper. The Android app never builds a shell command from UI input.

## Required Termux side setup

1. BKX must be installed and repaired inside Termux.
2. The approved wrapper must exist at:

```text
/data/data/com.termux/files/usr/bin/bkx-android-bridge
```

3. Termux must allow external app execution:

```text
~/.termux/termux.properties
allow-external-apps=true
```

4. Android must grant BKX Companion the Termux custom permission:

```text
com.termux.permission.RUN_COMMAND
```

## Invocation contract

The app invokes only the approved wrapper path, sends only fixed arguments, and passes the one structured bridge request JSON document through Termux `RUN_COMMAND_STDIN` instead of embedding operation payload in shell text:

```text
--protocol-version 1
--request-id <request-id>
--request <request.json>
--result <result.json>
--progress <progress.jsonl>
--cancel <cancel.json>
```

The wrapper must consume exactly one request JSON document from stdin, may mirror it to the `request.json` path, must write progress as JSONL, and must write exactly one final JSON result document for each request id.
