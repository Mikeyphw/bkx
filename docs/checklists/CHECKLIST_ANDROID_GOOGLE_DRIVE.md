# Android Google Drive acceptance checklist

- [ ] Google Play services AuthorizationClient is used; legacy GoogleSignIn API is not introduced.
- [ ] Requested scope is exactly `drive.file`, not full `drive`.
- [ ] Managed remote name is reserved as `bkx-google-drive` and imported configs cannot replace it.
- [ ] Access token exists only in app-private rclone configuration and is absent from UI models/history/diagnostics.
- [ ] Connect, consent resolution, silent refresh, failure, and revoke/disconnect states are represented in the product UI.
- [ ] `android.permission.INTERNET` is present because the packaged rclone process runs in the BKX app sandbox.
- [ ] Backup accepts the managed Drive root and advertises/validates `backup.remote`.
- [ ] Cloud publication refuses overwrite, rolls back partial objects, and verifies the downloaded logical archive when verification is requested.
- [ ] Split archives publish sidecars first and the logical base last.
- [ ] Archive list/info/browse/verify accept Drive locations and clean temporary materialization.
- [ ] Missing fresh `BKX` folder is treated as an empty archive library rather than a fatal error.
- [ ] Restore from Drive goes through the existing restore preview/verification/publication safety path.
- [ ] `archive.remote`, `restore.remote`, and `cloud.google_drive` capabilities are advertised only when the rclone runtime is present.
- [ ] Google Drive product contracts, remote Go integration tests, unit tests, lint, and release seal pass through Devtool.
