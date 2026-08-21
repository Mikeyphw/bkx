# BKX Android setup

BKX Android 1.0 is standalone. Termux is not an execution dependency.

## Personal rooted-device setup

1. Install and open BKX.
2. In your root manager, grant BKX root when prompted.
3. In **More → Storage access**, enable **All files access**.
4. Return to BKX and refresh engine status. **ROOT READY** means the embedded BKX Python engine can run as UID 0.
5. Use normal absolute paths in Backup, Archives/Restore, and Transfer.

Root is preferred by default. **More → Root access** can change the policy to Require root or No root; the next operation uses the new policy without restarting the app.

## Termux private data

With root granted, BKX can directly access:

- `/data/data/com.termux/files`
- `/data/data/com.termux/files/home`
- `/data/data/com.termux/files/home/storage`
- `/data/data/com.termux/files/usr`

Termux does not need `allow-external-apps`, `RUN_COMMAND`, or a BKX wrapper. BKX executes its own embedded `bkx.core` engine.

For backup, symbolic links are skipped instead of followed. This is intentional: Termux's `~/storage` links must not make a home-directory backup escape into unrelated shared-storage trees.

## Restore safety

Every restore requires an explicit destination path.

- **New folder**: choose a path which does not already exist.
- **Existing folder**: choose a real existing directory.
- Root-sensitive app/private paths require a root engine and exact `RESTORE ROOT` confirmation.
- Protected Android system roots are blocked even when root is available.

## Without root

BKX still uses its embedded Python runtime. All files access covers broad shared/removable storage. Other apps' private data and Termux's private `$HOME` remain unavailable without root.

SAF remains a fallback for document providers which do not expose normal filesystem paths.
