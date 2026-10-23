# REL05-HF02 — Hosted dual-APK packaging memory hotfix

## Evidence

- GitHub run: `Mikeyphw/bkx` Actions run **40037123461**, attempt 1.
- Source: `ec099e083d6219a71b9857dec30d5cdc5e05b532`.
- Step: `Compile Embedded and Lite together, never signing either`.
- Failing Gradle task: `:app:packageEmbeddedRelease`.
- Root failure: `java.lang.OutOfMemoryError: Java heap space` inside Android Gradle Plugin Zipflinger (`Compressor.deflate`, `BytesSource.build`) after it started `:app:minifyLiteReleaseWithR8`.
- The default Gradle daemon heap in `android/BKX.Android/gradle.properties` is `1152m`, optimized for local Termux, not the memory-heavy GitHub packaging lane.
- The hosted run *did* pass immutable checkout and reviewed bclone locking, compiler checks, native 16 KiB ELF checks, Java/Go/NDK setup, and most Android compile tasks. Neither unsigned artifact was uploaded; no signing occurred.

## Repair contract

- Keep **one** GitHub workflow and **one** Gradle build invocation.
- Set a hosted-only Gradle heap override to `-Xmx4096m -XX:MaxMetaspaceSize=768m`.
- Disable parallel project execution and limit workers to 1 for the combined Android APK build, preventing concurrent resource-intensive Android packaging/minification workers.
- Do not modify `android/BKX.Android/gradle.properties`; preserve Termux local defaults.
- Preserve native executable extraction, source lock, unsigned-only enforcement, both edition contents and 16 KiB checks, and two distinct ACTIONS-10 artifact manifests.

## Acceptance and limits

Source tests reject 1152m regressions and parallel packaging, but cannot prove the memory pressure is fully resolved. A **new GitHub run** must complete both APKs and artifact manifests. If OOM recurs, inspect the runner memory metrics, native ELF size and the exact Zipflinger input; do not silently weaken native packaging or remove the bundled rclone variant.

## Related Devtool ledger

RM-16 dispatch, receipts and local signing do not need source modifications for this failure. The previous P2 improvement to surface GitHub's HTTP 422 validation response remains unrelated to this build-stage OOM. The hosted Gradle memory override is a BKX consumer-specific constraint, not a Devtool global default.
