# OV5 + OV6 Google Drive and roadmap release acceptance

OV5 adds first-class Google Drive to the Android product. OV6 seals the complete post-migration Android roadmap.

The Android acceptance lane must run at least:

- `:app:installBkxRcloneAndroidRuntime`
- `:app:verifyBkxRcloneRemoteTransportContract`
- `:app:verifyBkxGoogleDriveProductContract`
- `:app:verifyBkxCloudProductReleaseSeal`
- `:app:testDebugUnitTest`
- `:app:lintDebug`

The full `bkxAndroidReleaseSeal` remains the final package/release gate and includes the Drive/cloud contracts.

A connected device is optional for ordinary overlay validation. Physical-device qualification remains the separate final instrumentation lane documented in `RELEASE_VALIDATION.md`.
