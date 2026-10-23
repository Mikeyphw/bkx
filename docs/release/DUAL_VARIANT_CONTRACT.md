# BKX Android Embedded and Lite — contract REL-00–02

Source base: BKX `f450deccfd26faa27482607fad7750cabaa36c83` (2026-10-22 snapshot).

- One `edition` dimension with `embedded` and `lite`; build types debug/release.
- Exact Android applicationId: `com.mikeyphw.bkx.android`; neither edition uses an applicationIdSuffix, and both retain their shared versionCode from `internal/version/version.go`. They replace one another, **not** side-by-side installation.
- ARM64 only, Android minSdk 24, compile/target 36, extracted `libbkxgo.so` for both.
- Embedded release **requires** `lib/arm64-v8a/libbkxrclone_exec.so` and `assets/bkx-rclone-runtime.properties`; Lite release **forbids** both. No local `rclone` binary search/fallback on missing path.
- Rclone remote transfer, Drive and remote backup/restore require a real native executable. Lite retains local Go operations and data; remote features report unavailable through readiness and UI.
- Skybind R1 is a **separate**, app-process-only, explicitly approved **read-only** provider: `system.capabilities`, `rclone.version`, `rclone.listremotes`, `rclone.lsjson`, `rclone.size`. Its grants are per PID/scope, and denial never falls back to BKX rclone.
- Personal signing identity lives outside the repo. GitHub produces unsigned release candidates only (REL-05). Devtool's authorized local LIFE signer signs each after strict manifest/certificate review (REL-06); compare signer with current installed app before an upgrade.
- No real APK, Gradle, signing, installed-app, Skybind or GitHub acceptance evidence is created by this source-only milestone. Source package presence alone is not release qualification.

## Task graph responsibilities

BR37 static preflight does not compile native assets. Behavioral tests are edition-scoped (`testEmbeddedDebugUnitTest`, `testLiteDebugUnitTest`); package/lint seal includes both release editions, both merged manifests, both release lints, ZIP contents and 16 KiB ELF members; on-device instrumentation remains external qualification. Do not replace these gates with markers or mock APKs for production.

## Local authorization / upgrade checks (deferred external gate)

Before installation, run the approved read-only Devtool identity/fingerprint checks, compare with installed BKX signer, and verify data/versionCode policy. Never uninstall/clear data to bypass a signing mismatch. An older installed build may not be in-place upgradable until cert alignment is proven.
