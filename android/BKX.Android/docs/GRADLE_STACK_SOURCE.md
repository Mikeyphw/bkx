# Gradle Stack Source

This patched AOV-01/AOV-02 overlay uses the Gradle stack observed in the uploaded `studyflow-20260815-101352.tar.gz` snapshot, migrated for AGP 9 built-in Kotlin instead of applying the old standalone `org.jetbrains.kotlin.android` plugin.

## Adopted versions

- Android Gradle Plugin: `9.3.1`
- Managed Gradle for Devtool validation: `9.5.0`
- AGP built-in Kotlin: enabled by AGP `9.3.1`
- Kotlin Compose compiler plugin: `2.4.10`
- Kotlin serialization plugin: `2.4.10`
- Removed standalone Kotlin Android plugin: `org.jetbrains.kotlin.android`
- Compose BOM: `2026.08.00`
- Activity Compose: `1.13.0`
- AppCompat: `1.8.0`
- AndroidX Core KTX: `1.19.0`
- Lifecycle Compose/ViewModel Compose: `2.11.0`
- Coroutines: `1.11.0`
- kotlinx.serialization JSON: `1.11.0`
- AndroidX test ext JUnit: `1.3.0`
- Espresso: `3.7.0`
- compileSdk / targetSdk: `36` for BKX Android after Android 16 / API 36 became available in the validator environment
- minSdk: `23`
- Java toolchain: `17`

The BKX Android app keeps its own package namespace and thin backend contract. Only the Android Gradle/dependency stack was aligned with StudyFlow. Devtool validation pins managed Gradle 9.5.0 because AGP 9.3.1 requires it; no StudyFlow product code or StudyFlow business logic was imported.


## Built-in Kotlin migration notes

AGP 9.0+ enables built-in Kotlin for Android modules where AGP is applied. This project therefore removes the `kotlin-android` plugin alias from root and app plugin blocks and from the version catalog. The app keeps the Compose and serialization compiler plugins because those are not replaced by AGP built-in Kotlin.
