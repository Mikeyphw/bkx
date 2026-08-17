
# BKX Android Companion Setup Guide

## BKX backend

Install and repair BKX in Termux first. The Android app is a thin interface and must not replace BKX core logic.

The supported bridge path is:

```text
Compose UI -> ViewModel -> BackendPort -> TermuxBridgeAdapter -> fixed bkx-android-bridge wrapper -> BKX engine
```

## Termux RUN_COMMAND

The companion requires Termux to be installed and externally callable. Termux-side setup must include:

```text
allow-external-apps=true
```

in Termux properties, plus the fixed `bkx-android-bridge` wrapper installed at the expected location. Android UI input is never converted into arbitrary shell commands.

## Android file access

Pick user-visible files and folders through Android's Storage Access Framework. `content://` URIs are not POSIX paths; the app records grants and stages files before passing normalized paths to BKX.

## Notifications and active operations

Grant notification permission when Android asks. Active BKX operations use request ids, progress files, status polling, and notification/reconnect state so the UI can recover after rotation or process recreation.

## Final validation

Use `docs/FINAL_RELEASE_VALIDATION.md`. Do not use raw Gradle as the primary release gate; use Devtool with one `--task` flag per Gradle task.
