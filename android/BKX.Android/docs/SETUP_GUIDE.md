# BKX Android setup

BKX Android 1.0 is standalone. Termux is not an execution dependency. The Android product uses the packaged Go protocol-v1 runtime for both app-privilege and libsu root execution.

## Personal rooted-device setup

1. Install and open BKX.
2. In your root manager, grant BKX root when prompted.
3. In **More → Storage access**, enable **All files access**.
4. Return to BKX and refresh engine status. **ROOT READY** means the packaged Go BKX runtime can execute through the libsu UID-0 route.
5. Use the picker-first Backup, Archives/Restore, and Transfer workflows. Manual absolute paths remain available under Advanced.

Root is preferred by default. **More → Root access** can change the policy to Require root or No root; the next operation uses the new policy without restarting the app.

## Termux private data

With root granted, BKX can directly access:

- `/data/data/com.termux/files`
- `/data/data/com.termux/files/home`
- `/data/data/com.termux/files/home/storage`
- `/data/data/com.termux/files/usr`

Termux does not need `allow-external-apps`, `RUN_COMMAND`, or a BKX wrapper. BKX executes its own packaged Go engine.

For backup, symbolic links are skipped instead of followed. This is intentional: Termux's `~/storage` links must not make a home-directory backup escape into unrelated shared-storage trees.

## Google Drive setup

Google Drive is optional and local backup/archive/restore remains fully usable without it.

For a personal Google Drive build:

1. Run `bash ./tools/setup-personal-signing.sh` once. Keep the generated `~/.config/bkx/bkx-personal.p12` and `~/.config/bkx/android-signing.properties` permanently and back them up privately.
2. Run `gradle :app:bkxSigningStatus` and record the SHA-1 printed by the setup helper. With personal signing configured, debug and release APKs use the same certificate.
3. Enable the Google Drive API in the Google Cloud project used by the APK.
4. Create one Android OAuth client for package `com.mikeyphw.bkx.android` and register the SHA-1 fingerprint of the permanent BKX certificate.
5. Build/install with `gradle :app:assemblePersonalDebug` or `gradle :app:assemblePersonalRelease`. The first permanent-key install may require uninstalling an older APK that was signed with Android's generated debug key.
6. Open **More → Google Drive**, choose **Connect Google Drive**, and complete Google's authorization UI.
7. BKX requests only `https://www.googleapis.com/auth/drive.file`; it does not request blanket Drive access.
8. Use **Back up to Google Drive** or select Google Drive from the Backup destination picker. Drive archives then appear in the normal Archives workflow for browse, verify, and restore.

The managed cloud location is `rclone://bkx-google-drive/BKX`. OAuth access tokens are stored only in BKX's owner-only app-private rclone configuration. BKX refreshes Google authorization before a managed Drive operation; if interactive approval is required, the operation waits for explicit user consent rather than starting unattended.

Generic rclone remotes remain available through **More → Remote storage**. Their credential-bearing option values are never projected into the UI model.

## Restore safety

Every restore requires an explicit destination path.

- **New folder**: choose a path which does not already exist.
- **Existing folder**: choose a real existing directory.
- Root-sensitive app/private paths require a root engine and exact `RESTORE ROOT` confirmation.
- Protected Android system roots are blocked even when root is available.
- Remote archives are downloaded into private temporary staging first, then passed through the same local traversal/collision/root safety engine.

## Without root

BKX still uses its packaged Go runtime. All files access covers broad shared/removable storage. Other apps' private data and Termux's private `$HOME` remain unavailable without root.

SAF remains a fallback for document providers which do not expose normal filesystem paths. Google Drive and generic rclone remotes do not require root.
