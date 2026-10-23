# OV5 + OV6 Google Drive and roadmap release acceptance

OV5 adds first-class Google Drive to the Android product. OV6 seals the complete post-migration Android roadmap.

The active edition-aware Android acceptance lane must run at least:

- `:app:installBkxRcloneAndroidRuntime` (Embedded producer only)
- `:app:verifyBkxRcloneRemoteTransportContract` (Embedded feature contract)
- `:app:verifyBkxGoogleDriveProductContract` (Embedded feature contract)
- `:app:verifyBkxCloudProductReleaseSeal`
- `:app:testEmbeddedDebugUnitTest`
- `:app:testLiteDebugUnitTest`
- `:app:lintEmbeddedDebug`
- `:app:lintLiteDebug`
- `:app:lintEmbeddedRelease`
- `:app:lintLiteRelease`
- `:app:verifyBkxEditionApkContents`

The full `bkxAndroidReleaseSeal` remains the final package/release gate and includes the Drive/cloud contracts.

A connected device is optional for ordinary overlay validation. Physical-device qualification remains the separate final instrumentation lane documented in `RELEASE_VALIDATION.md`.

## Scope and evidence

The historical remote/Drive integration applies to **Embedded** only. Lite must
report rclone-dependent remote operations as unavailable, while keeping its
independent, authenticated Skybind R1 read-only client. The required Go runtime
must remain packaged in both editions. Real Android APK and Skybind acceptance
remain separate from these source-text contracts.
