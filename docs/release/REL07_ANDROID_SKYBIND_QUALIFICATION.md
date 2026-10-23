# BKX REL-07 — signed Android + Skybind acceptance (operator-bound)

**Status:** source implementation delivered; real GitHub, LIFE signing, rooted-device, instrumentation, and Skybind evidence **PENDING**. None of the source checks qualify an APK for release. This document is the REL-07 acceptance contract; REL-08 final closure remains separate.

## Artifacts and two distinct acceptance lanes

Use exactly the **real local-LIFE-signed** `embeddedRelease` and `liteRelease` APKs from the same GitHub run/attempt, plus immutable REL-06 import/sign JSON receipts. Never substitute hosted debug builds. Both variants have package `com.mikeyphw.bkx.android` and the same authorized signer; they replace each other rather than co-installing. `scripts/bkx-rel07-qualify.py` verifies both signed ZIPs against the REL-06 receipts and APK `aapt`, `apksigner`, `zipalign -P 16`, all packaged ARM64 ELF PT_LOAD segments, and exact edition required/forbidden members. It does **not** guess the operator's keystore or reproduce a signature.

**Source-only preflight (read-only):**

```sh
python3 scripts/bkx-rel07-qualify.py \
  --embedded-apk /private/path/embedded-signed.apk \
  --lite-apk /private/path/lite-signed.apk \
  --import-json /private/path/rm16-import.json \
  --sign-json /private/path/rm16-sign.json \
  --evidence-json /private/path/rel07-preflight.json
```

Run on Termux with Android SDK build-tools (`apksigner`, `zipalign`, `aapt`) on PATH; override executable paths with `--apksigner`, `--zipalign`, and `--aapt` if installed elsewhere. An absent tool or receipt is a blocker. A preflight success leaves `device_qualification=NOT_RUN`, `skybind_qualification=NOT_RUN`, and `qualified_release=false`.

**Explicit rooted-device install/swap (mutating, opt-in):**

```sh
python3 scripts/bkx-rel07-qualify.py \
  --embedded-apk /private/path/embedded-signed.apk \
  --lite-apk /private/path/lite-signed.apk \
  --import-json /private/path/rm16-import.json \
  --sign-json /private/path/rm16-sign.json \
  --serial EXACT_ADB_SERIAL --execute --yes \
  --evidence-json /private/path/rel07-device.json
```

* The operator must deliberately select the device and authorize root before beginning. No `adb uninstall`, `pm clear`, `install -d`, signing reset, or untrusted package installation is used.
* The harness installs **Embedded → Lite → Embedded** with `adb install -r`, checks exact installed `/data/app/.../base.apk` SHA256 using approved `su`, checks the installed package UID remains unchanged, and writes a **temporary random marker only** under app-private files to verify in-place retention. It removes the marker at completion. The marker is not a substitute for testing real saved jobs, backup history, grants or settings. The operator must separately verify preservation of real BKX state. A failure leaves an explicitly FAILED JSON receipt, with all release gates unqualified. Run additional final Lite installation if the intended installed edition is Lite.
* APK signature verification and package/version checks happen **before** any installation. Before the **first** installation the harness also reads the exact pre-existing
base APK via authorized root, verifies its certificate with Android build-tools,
checks its package ID and rejects downgrades. Missing pre-existing BKX is not an
in-place migration proof and is rejected rather than silently installed. Android
`install -r` supplies a second certificate/permission enforcement boundary. Do not uninstall the installed app to get around it.
* This is **partial** device evidence only. Successful installation/retention does not prove Go execution, storage permissions, real backups, Tasker actions, foreground notifications, user-visible unavailable-feature behavior or any Skybind broker operation.

## Separate production instrumentation lane

`android/BKX.Android/scripts/qualify-device.sh` now refuses a signed release APK without an **explicit same-signer** instrumentation APK. It verifies both signers before installation, runs only the release/root/Go/Compose qualification classes, insists on the positive AndroidJUnit terminal result and obtains five screenshots through the approved root interface (production APKs are not debuggable; `run-as` is not an appropriate prerequisite). Keep the captured instrumentation text, screens and package information private. A debug-signed `bkx-r1-androidtest-source-bound-pair` is **not** a matching release test APK.

A matching release instrumentation APK is not produced by REL-05 and is not automatically signed by RM-16; it requires a separately authorized local test-APK signing workflow using the **same certificate**. Never install an unmatched test APK or disable Android signature enforcement. The full release instrumentation lane therefore remains **PENDING** until such a candidate and real test receipt exist.

## Separate Skybind R1 root-gateway lane

For **each actual installed edition**, run the trusted installed Skybind root-operator `acceptance-bkx REMOTE [PATH]` gateway, with a fresh process-scoped authorized grant and compatible instrumentation package. Preserve the root-private, immutable `skybind-r1-bkx-device-acceptance-v1` receipt independently of the local BKX device receipt. Require the five allowed typed calls (`system.capabilities`, `rclone.version`, `rclone.listremotes`, `rclone.lsjson`, `rclone.size`), genuine `not_authorized` denials on no token / wrong operation / wrong remote / wrong path / wrong process / revocation / expiry, and no fallback to local rclone on a denied Skybind call. Receipts must demonstrate matching running BKX package/UID/PID, version, signer, and scope. Do not publish bearer tokens or raw remote listings.

**Important distinction:** the existing wrong-process replay probe is **debug-only**. The Kotlin instrumentation is now source-compilable against release without shipping that probe in production, but the 12-case `wrong_process` phase still fails closed if the probe is absent. A passing disposable/debug Skybind campaign cannot be relabeled as release-process negative evidence. A separately reviewed production-safe replay test mechanism or equivalent independently witnessed device authority is still required before this specific signed-release obligation can be closed. No release socket endpoint is added just to make a test green.

## Evidence ledger and REL-08 merge window

| Promise | Evidence | State at source delivery |
|---|---|---|
| Exact two signed APKs, same signer/version, correct package and 16KiB/ELF | REL-07 read-only preflight + RM-16 batch JSON + independent Android tools | SOURCE IMPLEMENTED / REAL APK PENDING |
| Both in-place update directions and same app UID | Three-step guarded rooted-device JSON | SOURCE IMPLEMENTED / REAL DEVICE PENDING |
| App-private retention marker | Device JSON (synthetic marker only) | SOURCE IMPLEMENTED / REAL DEVICE PENDING |
| Rooted Go runtime and screenshot/navigation instrumentation | Explicit same-signer test APK + successful AndroidJUnit and artifacts | PENDING MATCHING TEST APK/DEVICE |
| Real backup/restore, Tasker, foreground, Lite unavailable-state UI | Appropriate BKX runtime instrumentation and JSON evidence | OPEN |
| Five Skybind positive calls + all seven negative cases for **each** signed edition | Independent root-private Skybind gateway receipts | OPEN |
| App grants reissued and old PID tokens invalid after update | Signed-release device/gateway evidence | OPEN |
| Full release qualification and final frozen evidence index | REL-08 | OPEN — must not be automatically promoted |

Manual deferred tests remain deferred (not automatically FAILED); preserve previously recorded outcomes without substituting them for fresh automated evidence. Avoid treating absence of signer/device as an implementation defect, but do not call a source-only receipt a device PASS.
