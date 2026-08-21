# BKX Android setup

BKX Android is standalone. Termux is not an execution dependency.

## Recommended personal-device setup

1. Install the BKX APK.
2. Open BKX and allow the root request in Magisk, KernelSU, APatch, or another libsu-compatible root manager if root-backed operation is desired.
3. Enable **All files access** when BKX opens Android's special access screen. This gives the non-root fallback direct shared-storage access.
4. Return to BKX and re-check the engine. A root-ready engine advertises `root.filesystem`.

## Termux data

With root granted, BKX can operate directly on Termux's private filesystem, including:

- `/data/data/com.termux/files`
- `/data/data/com.termux/files/home`
- `/data/data/com.termux/files/home/storage`
- `/data/data/com.termux/files/usr`

Termux does not need `allow-external-apps`, `RUN_COMMAND`, or a BKX wrapper. BKX executes its own embedded Python engine.

## Without root

BKX still works through its normal embedded Python runtime. On Android 11+ enable All files access for broad shared-storage access. Root-only areas such as other apps' private data and Termux's private `$HOME` remain unavailable.

SAF remains a fallback for document providers which do not expose normal filesystem paths.
