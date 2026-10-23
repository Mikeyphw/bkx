# REL-03 — BR35–BR37 dual-edition regression gate migration

**Status:** Source implemented; real Gradle task enumeration, Android lint/unit runs, APK inspection and device acceptance **not yet verified**. No BR21–BR37 historical gate has been removed.

| Promise | Concrete enforcement | State |
|---|---|---|
| BR21–BR34 functional source seals | All original verification tasks remain registered in `build-logic/android-verification.gradle.kts`; preflight and behavioral suite retain these dependencies | Preserved at source level; Gradle execution pending |
| BR35 release signing placeholders and versionCode | Original static signing and version contracts retained, unchanged production signing authority | Preserved; installed certificate check open |
| BR35 release lint | `lintEmbeddedRelease` and `lintLiteRelease` required by release package aggregate and CI | Source implemented; actual lint pending |
| BR35 merged-manifest proof | `verifyBkxReleaseMergedManifestContract` reads both AGP merged release manifests | Source implemented; real merged manifests pending |
| BR35 16 KiB ELF | `verifyBkxEmbeddedRelease16Kb` and `verifyBkxLiteRelease16Kb` independently inspect exact release ZIP APKs; legacy aggregate requires both | Source implemented; actual APKs pending |
| BR35 package-member truth | `verifyBkxEditionApkContents` rejects rclone in Lite, requires Go in both, rclone and provenance in Embedded | Source implemented; actual APKs pending |
| BR36 behavior and Android unit tests | `bkxBr37BehavioralUnitSeal` requires Embedded and Lite JVM suites, retains startup/navigation/UI behavioral seals | Source implemented; actual suite pending |
| BR36 R1 instrumentation | `connectedEmbeddedDebugAndroidTest` with variant-matched test APK in dedicated workflow | Source implemented; live test pending; Lite device evidence separate |
| BR37 fast preflight | static list does not invoke full JVM, native packages, lint or instrumentation; Devtool references static aggregate | Source contract and mutations pass; Gradle dry-run pending |
| BR37 no repeated heavy work | package and behavioral lanes compose Gradle tasks with deduplicated dependencies; duplicate debug lint removed | Source implemented; Gradle graph pending |
| Historical OV5/OV6 contracts | Active source assertion uses edition-aware guide markers; Embedded cloud/rclone acceptance not incorrectly claimed for Lite | Source implemented; cloud/device validation pending |
| Go and Skybind transport isolation | Go runtime in both; separate R1 socket reader remains unchanged from REL-02 | Preserved source-level; real device evidence pending |

No overlay validation may claim release-ready merely because `verify-bkx-rel03-gates.py` passed. Run the full BR35/36/37 Gradle task graph in the BKX Android toolchain and publish actual test XML, lint diagnostics, exact APK ZIP checks and 16 KiB proofs before closing REL-03.

## External acceptance commands (after applying source overlay)

From BKX repository root, using an Android SDK/NDK-capable Gradle environment:

```sh
cd android/BKX.Android
./gradlew --no-daemon :app:tasks --all
./gradlew --no-daemon --stacktrace :app:bkxBr37BehavioralUnitSeal \
  :app:bkxBr37PackageAndReleaseLintSeal :app:verifyBkxGradleTaskGraphOptimizationSeal
```

Run `:app:connectedEmbeddedDebugAndroidTest` only with a qualified device. Full BKX/Skybind device acceptance and local LIFE signing are later gates. REL-04 locks bclone inputs; REL-05 introduces GitHub unsigned producer.
