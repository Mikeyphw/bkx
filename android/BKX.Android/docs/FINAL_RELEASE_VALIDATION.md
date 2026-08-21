# BKX Android final release validation

AOV-21→24 is an interim product-UI milestone. Full release validation remains reserved for AOV-25→32; interim overlays are applied with `--no-validate` by project policy.

The final release seal must include at least:

- `:app:testDebugUnitTest`
- `:app:lintDebug`
- `:app:assembleDebug`
- `:app:assembleRelease`
- `:app:connectedDebugAndroidTest`
- `:app:verifyBkxAndroidSdk36DependencyLane`
- `:app:verifyBkxEmbeddedEngineContract`
- `:app:verifyBkxRootStorageContract`
- `:app:verifyBkxProductUiContract`
- `:app:bkxAndroidReleaseSeal`

Physical-device qualification must prove root and fallback lanes, All files access, Termux-private path access under root, direct-path backup/archive/restore/local-transfer behavior, phone/tablet navigation, dark/light/dynamic color, large fonts, process recreation, notifications, operation recovery, and release performance.
