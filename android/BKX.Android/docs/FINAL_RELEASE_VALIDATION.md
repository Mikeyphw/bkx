# BKX Android final release validation

AOV-17→20 is an interim mega milestone. Full release validation remains reserved for AOV-25→32; interim overlays are applied with `--no-validate` by project policy.

The final release seal must include at least:

- `:app:testDebugUnitTest`
- `:app:lintDebug`
- `:app:assembleDebug`
- `:app:assembleRelease`
- `:app:connectedDebugAndroidTest`
- `:app:verifyBkxAndroidSdk36DependencyLane`
- `:app:verifyBkxEmbeddedEngineContract`
- `:app:verifyBkxRootStorageContract`
- `:app:bkxAndroidReleaseSeal`

Physical-device qualification must prove both root and fallback lanes, All files access, Termux-private path access under root, backup/archive/restore/local-transfer behavior, cancellation/failure behavior, process recreation, notifications, and operation recovery.
