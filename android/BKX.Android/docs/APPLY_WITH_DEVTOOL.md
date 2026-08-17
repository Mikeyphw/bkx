# Apply AOV-01 + AOV-02 with Devtool

Use the BKX repository root provided for this task:

```bash
ARTIFACT="$HOME/Downloads/bkx_android_aov01_aov02_foundation_termux_bridge_v7.zip"
devtool --copy -y -r "$HOME/Code/bkx" apply-overlay "$ARTIFACT" \
  --validate \
  --task :app:testDebugUnitTest \
  --task :app:lintDebug \
  --task :app:assembleDebug \
  --commit "Create BKX Android companion foundation and Termux bridge"
```

Notes:

- `--copy -y` matches the requested non-interactive copied-archive workflow.
- Each Gradle task has its own `--task` flag.
- `--validate` is on the apply command, so apply, validation, and commit are one Devtool transaction.
- `--commit` supplies the commit message explicitly even though the overlay manifest also requests the same single commit.
- The Devtool Android platform is explicitly pinned to `36`, so validation does not auto-request unavailable/preview SDK packages such as `platforms;android-37`.

## Gradle stack note

The overlay uses the StudyFlow Gradle plugin/dependency versions from `studyflow-20260815-101352.tar.gz`: AGP `9.3.0` with built-in Kotlin enabled, managed Gradle `9.5.0`, Compose/serialization compiler plugins `2.4.10`, Compose BOM `2026.06.00`, min SDK `23`, and Java toolchain `17`. BKX Android intentionally caps compile/target SDK at stable Android 16 / API 36 instead of the StudyFlow snapshot's API 37 value.


## AGP 9 built-in Kotlin

The app intentionally does not apply `org.jetbrains.kotlin.android`; AGP 9.3.0 provides built-in Kotlin support. Keep `org.jetbrains.kotlin.plugin.compose` and `org.jetbrains.kotlin.plugin.serialization` because they are separate compiler plugins.
