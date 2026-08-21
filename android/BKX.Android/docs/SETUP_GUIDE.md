# BKX Android setup

BKX Android is standalone. Termux is not an execution dependency.

## Recommended personal-device setup

1. Install and open BKX.
2. If your root manager prompts, grant BKX root access when you want private-path backups.
3. In **More → Storage access**, grant **All files access** for direct shared-storage operation.
4. Return to Home and use **Check** if you want to refresh the engine/root status.
5. Open **Backup** and choose a source/destination path. Root-only paths become usable when the Home badge says **ROOT READY**.

## Termux data

With root granted, BKX can operate directly on Termux's private filesystem, including:

- `/data/data/com.termux/files`
- `/data/data/com.termux/files/home`
- `/data/data/com.termux/files/home/storage`
- `/data/data/com.termux/files/usr`

The Backup and More screens expose Termux home/prefix shortcuts when root is active. Termux does not need `allow-external-apps`, `RUN_COMMAND`, or a BKX wrapper; BKX executes its own embedded Python engine.

## Without root

BKX still runs through its normal embedded Python runtime. All files access provides broad shared-storage access. Root-only areas such as other apps' private data and Termux's private `$HOME` remain unavailable.

SAF remains a fallback for document providers which do not expose normal filesystem paths.
