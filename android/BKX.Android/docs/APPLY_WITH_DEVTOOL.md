# Apply BKX Android Companion overlays with Devtool

Use the BKX repository root provided for this task. For AOV-09/AOV-10 and the remaining interim overlays, validation is intentionally deferred until the final overlay.

```bash
ARTIFACT="$HOME/Downloads/bkx_android_aov09_aov10_operations_rendering_parity_v2.zip"
devtool --copy -y -r "$HOME/Code/bkx" apply-overlay "$ARTIFACT" \
  --no-validate \
  --commit "Add BKX Android operation runtime and result rendering parity"
```

Notes:

- `--copy -y` matches the requested non-interactive copied-archive workflow.
- `--no-validate` is required for interim overlays; final-roadmap validation runs only after the last overlay.
- `--commit` supplies the commit message explicitly even though the overlay manifest also requests the same single commit.
- The Devtool Android platform, `compileSdk`, and `targetSdk` remain pinned to Android 17 / API 37 from the applied AOV-03/AOV-04 warning cleanup.

## Gradle stack note

The project remains on the StudyFlow-style AGP 9 built-in Kotlin layout, with refreshed versions for deterministic warning cleanup: AGP `9.3.1`, managed Gradle `9.5.0`, Kotlin/Compose/serialization compiler plugins `2.4.10`, Compose BOM `2026.08.00`, min SDK `23`, target/compile SDK `37`, and Java toolchain `17`.

## Interim validation policy

Do not add validation-mode or Gradle task flags to interim overlay apply commands. Full Devtool/Gradle validation is reserved for the final roadmap overlay.
