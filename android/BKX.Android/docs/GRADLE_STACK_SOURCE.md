# Android toolchain contract

BKX Android uses a stable API-36 lane. The canonical dependency versions live in `gradle/libs.versions.toml`; the Gradle release guard rejects accidental drift.

Current pinned lane:

- Android Gradle Plugin: `9.2.1`
- Devtool-managed Gradle: `9.7.1+`
- Kotlin / Compose compiler / serialization plugin: `2.4.10`
- Chaquopy: `17.0.0`
- Compose BOM: `2026.06.01`
- Activity Compose: `1.12.4`
- AppCompat: `1.7.1`
- AndroidX Core KTX: `1.17.0`
- Lifecycle Compose: `2.10.0`
- libsu: `6.0.0` through a JitPack group filter
- compileSdk / targetSdk: `36`
- minSdk: `24`
- Java: `21`

AGP built-in Kotlin is used instead of the standalone Android Kotlin plugin. Do not accept dependency-version lint suggestions independently of the SDK/validator lane.
