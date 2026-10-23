# BKX REL-08 — Final evidence index, source promise audit and release gates

**State at October 23, 2026: source implementation + source-level regression tests; real release qualification OPEN.** This document supersedes any optimistic status label inferred solely from installed overlay files. All claims below distinguish implementation behavior from the actual APK/device authorities.

## Promise-by-promise review (REL-00–07)

| Milestone | Explicit promise | Source / test evidence | Determination |
| --- | --- | --- | --- |
| REL-00 | Same package/version policy, approved signer boundary, product/CI contracts and data-safe update rules | `docs/release/DUAL_VARIANT_CONTRACT.md`, source contract JSON, `scripts/verify-android-editions.py` | **PARTIAL:** rules delivered; installed signer / data upgrade evidence OPEN |
| REL-01 | True Embedded/Lite flavors, isolate native rclone, keep Go ELF in both | `app/build.gradle.kts`, edition verifier, 7 source tests | **SOURCE DELIVERED:** real Gradle compile, two APK ZIPs and 16 KiB proof OPEN |
| REL-02 | Fail closed without rclone, truthful capability reporting, separate Skybind client | `internal/rcloneops/runtime.go`, Android `GoNativeGateway.kt`, focused Go and Kotlin contracts | **PARTIAL:** Go-side negative tests, source wiring; physical Lite UI / transfer and Skybind acceptance OPEN |
| REL-03 | Preserve BR35–BR37 regression obligations, separate variant lint and page checks | `scripts/verify-bkx-rel03-gates.py`, 5 mutation tests, Android static seal | **SOURCE DELIVERED:** real Gradle graph, JVM/Android lint and device tests OPEN |
| REL-04 | Reviewed immutable bclone version/commit, locked Go + NDK, no mutable latest during release | `config/bclone-android-source.lock`, installer, source verifier and lock tests | **SOURCE DELIVERED:** actual upstream fetch/compile, native SHA/provenance verification OPEN |
| REL-05 | One GitHub build, both unsigned APKs, strict ACTIONS-10 manifests, no GitHub signer | `.github/workflows/bkx-android-unsigned-dual-release.yml`, producer, 13 adversarial tests + Devtool manifest acceptance | **SOURCE DELIVERED:** actual GitHub run/artifact IDs and APK evidence OPEN |
| REL-06 | Exact two consumer registrations, same existing LIFE key, RM16 import/sign, immutable receipts | `bkx-devtool-life.py`, `bkx-devtool-release.py`, `bkx-audit-rm16-receipts.py`, 7 tests. REL-08 tightens artifact ID and unsigned SHA linkage | **SOURCE DELIVERED WITH FIX:** local enrollment, actual private LIFE signer and signed artifacts OPEN |
| REL-07 | Exact signed pair checks, in-place Embedded→Lite→Embedded preserving state, instrumentation and Skybind campaigns | `bkx-rel07-qualify.py`, 15 tests; `qualify-device.sh` | **PARTIAL WITH FIX:** pre-existing signer/version preflight added; actual on-device tests, instrumentation test signer, all real runtime/Skybind behavior OPEN |
| REL-08 | Reconcile every promise, source, run, artifact, signer and external receipt; never auto-promote | `scripts/bkx-rel08-evidence.py`, `scripts/test-bkx-rel08-evidence.py`, this ledger | **SOURCE DELIVERED:** formal release authorization **OPEN** |

## Fixes found by the cumulative audit

1. **REL-06 provenance linkage:** previous receipt checker required a shared source/run, matching signer and registry ID, but did **not** independently correlate each imported GitHub artifact ID and its unsigned APK SHA with the corresponding LIFE signing receipt. The new checker rejects absent, duplicate, swapped or inconsistent artifact IDs and unsigned candidate hashes; adversarial tests exercise this.
2. **REL-07 upgrade safety:** the swap script previously relied on Android install refusal, without recording the existing installed certificate or comparing versionCode *before* the first update. It now demands an installed BKX package, reads the exact APK through approved root, uses `apksigner`/`aapt` for preflight, refuses certificate mismatch and downgrade, and records original SHA/certificate/version. This means a fresh installation is not incorrectly counted as an in-place upgrade.
3. **REL-06 test environment:** standalone tests importing Devtool require the installed Devtool Python package. They pass with a correct `PYTHONPATH` / installed Devtool environment. Missing Devtool is a prerequisite failure, not proof of an application defect. Do not silently weaken tests to remove the real Devtool parser dependency.

## How to produce a truthful evidence index

```sh
cd ~/Code/bkx
python3 scripts/bkx-rel08-evidence.py --output "$HOME/bkx-rel08-source-index.json"
# After a real GitHub run, import and LIFE sign, optionally attach:
python3 scripts/bkx-rel08-evidence.py \
  --import-json /private/rm16-import.json \
  --sign-json /private/rm16-sign.json \
  --rel07-json /private/rel07-device.json \
  --output "$HOME/bkx-rel08-signed-index.json"
```

An index always preserves exact filenames, SHA256 hashes and gate status without copying bearer tokens or passwords into its payload. Missing evidence is `PENDING`; self-asserted Skybind, Gradle or instrumentation JSON is **SUBMITTED_NEEDS_INDEPENDENT_AUTHORITY**, not an automatic PASS. The index never declares `qualified_release=true`, because an evidence scanner is not an independent release approval authority. Do not manually edit that field to bypass pending gates.

## Remaining externally gated work

The official GitHub source-bound workflow must complete, and its two distinct artifact IDs must pass Devtool's strict ACTIONS-10/RM-16 import, then LIFE sign with the pre-existing certificate. After independently verifying the installed BKX certificate, obtain separate exact signed Embedded and Lite APK inspection receipts, 16 KiB ZIP/ELF proof and guarded in-place swap evidence. A same-signer instrumentation APK and physical device are required for the production Kotlin/Compose/Go/root lane; the debug-only Skybind replay probe cannot be smuggled into a release. Collect both independently approved Skybind R1 read-positive and authorization-negative campaigns, plus real Lite unavailable-feature, local backup/restore, foreground, Tasker, history, and saved-state evidence. The root owner must independently validate its JSON before closure.

**Boundary:** old BR21–BR37 and deferred manual cases remain recorded, with `NOT_RUN` not converted to failed. This source audit does not substitute for their actual external acceptance. The RM16 resumed-dispatch counter bug belongs to Devtool, not BKX; imported run/attempt IDs, not dispatch_count, are authoritative.
