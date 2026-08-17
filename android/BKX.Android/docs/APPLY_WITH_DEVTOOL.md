# Apply BKX Android AOV-03 + AOV-04 with Devtool

Use the BKX repository root provided for this task:

```bash
ARTIFACT="$HOME/Downloads/bkx_android_aov03_aov04_saf_runs_ui_warnings_v1.zip"
devtool --copy -y -r "$HOME/Code/bkx" apply-overlay "$ARTIFACT" \
  --validate \
  --task :app:testDebugUnitTest \
  --task :app:lintDebug \
  --task :app:assembleDebug \
  --commit "Add BKX Android SAF, runs, tools, settings, and warning cleanup"
```

Notes:

- `--copy -y` matches the requested non-interactive copied-archive workflow.
- Each Gradle task has its own `--task` flag.
- `--validate` is on the apply command, so apply, validation, and commit are one Devtool transaction.
- `--commit` supplies the commit message explicitly even though the overlay manifest also requests the same single commit.
- The Devtool Android platform, `compileSdk`, and `targetSdk` are pinned to Android 17 / API 37 to remove the deterministic old-target/newer-SDK warnings reported by the v7 validator.

## Gradle stack note

The project remains on the StudyFlow-style AGP 9 built-in Kotlin layout, with refreshed versions for deterministic warning cleanup: AGP `9.3.1`, managed Gradle `9.5.0`, Kotlin/Compose/serialization compiler plugins `2.4.10`, Compose BOM `2026.08.00`, min SDK `23`, target/compile SDK `37`, and Java toolchain `17`.


## Warning policy

AOV-03/AOV-04 also carries Devtool-side Gradle passthrough for the custom-AAPT2 experimental-option warning and uses one Gradle worker in the constrained Termux validator. These settings keep the successful Termux AAPT2 strategy while reducing deterministic diagnostics.
