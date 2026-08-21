# Apply BKX Android overlays with Devtool

## Current overlay: AOV-13 through AOV-16

AOV-13→16 cuts the production `BackendPort` over from the Termux bridge to an
embedded CPython/Chaquopy backend while retaining the legacy Termux adapter only
for migration/parity checks.

Apply this mega overlay without full validation. The roadmap reserves the full
release validator for AOV-25→32.

```bash
ARTIFACT="$HOME/Downloads/bkx_android_aov13_aov16_embedded_engine_cutover_v1.zip"
devtool --yes --copy -r "$HOME/Code/bkx" --target bkx_android \
  apply overlay --no-validate "$ARTIFACT"
```

The overlay manifest requests a single commit with message:

```text
Embed canonical BKX engine in Android (AOV-13 through AOV-16)
```

## Embedded build lane

- Android compile/target SDK: 36
- Android min SDK: 24
- Java: 21
- Gradle: 9.7.0 (Devtool-managed lane)
- Android Gradle Plugin: 9.2.1
- Chaquopy: 17.0.0
- Embedded Python: 3.13
- ABIs: arm64-v8a and x86_64

`src/bkx` remains the single canonical Python source tree. The Android module
points Chaquopy at that source tree rather than copying or rewriting BKX in Kotlin.

## AOV-13→16 validation policy

Do not run the full Android release seal during this interim overlay. The overlay
contains focused regression gates (`verifyBkxEmbeddedEngineContract`, Python
Android API parity tests, and Kotlin backend tests), but complete build/lint/
instrumentation/release validation remains deferred to the final mega overlay.

## Legacy bridge status

The Termux adapter remains in source during this cutover only so differential
checks can still compare the old transport contract. `DefaultBackendGraph` no
longer instantiates it. Termux is therefore not required for the production
backend selected by this overlay.
