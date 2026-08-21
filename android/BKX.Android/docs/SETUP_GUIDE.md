# BKX Android Setup Guide — Embedded Engine Lane

## BKX backend

The default Android backend is now embedded in the APK:

```text
Compose UI -> ViewModel -> BackendPort -> EmbeddedBkxBackend -> Chaquopy/CPython -> bkx.android_api -> bkx.core
```

A separate Termux installation is **not required** for the production backend.
The legacy Termux adapter remains in source temporarily for AOV-13→16 parity and
will be removed by the standalone-runtime mega overlay.

## Embedded Python

Chaquopy starts Python once per Android process from `BkxAndroidApplication`.
Kotlin calls only the JSON functions exported by `bkx.android_api`; it does not
invoke the BKX CLI, Textual TUI, arbitrary Python expressions, or shell commands.

## Android file access

User-selected `content://` documents are still represented by the existing SAF
and staging contracts. BKX receives ordinary staged filesystem paths. AOV-17→20
finishes the native SAF staging/index/runtime so this boundary no longer carries
legacy bridge-file assumptions.

## Notifications and active operations

The current foreground-service and reconnect contracts remain in place. AOV-17→20
will replace polling/bridge-file runtime assumptions with the in-process operation
manager while preserving request IDs and lifecycle recovery.

## Validation

AOV-13→16 is an interim mega overlay and is applied with `--no-validate`. The
final fail-closed Devtool validation remains reserved for AOV-25→32.
