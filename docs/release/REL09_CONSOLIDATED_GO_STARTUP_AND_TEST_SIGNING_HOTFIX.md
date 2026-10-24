# BKX REL09: consolidated Go-startup and release instrumentation repair

## Provenance and user-observed symptoms

Source: `ab24cf21b25e17391e287513da25f0ca8730ddc2` (the exact snapshot provided). Prior real GitHub run `40045942016` produced unsigned Embedded + Lite, Devtool import/sign receipts verified both with the same LIFE SHA256 `5601d49884487a5d8f378b7665e37fff8b6808e7a1a038a465de1d07c9a5ef8b`. Rooted device upgrade Embedded→Lite→Embedded passed with stable UID 10341, using temporary REL07 termux/path compatibility. A separate instrumentation APK was present but signed with *different* signer `b721ad9d96b208f15ce20921dd5fdb468d02aabcb81137a33f42f9869e168ea9`.

**New real device symptom:** screenshot shows BKX setup fails at `ProcessBuilder` direct exec of `.../lib/arm64/libbkxgo.so`: `error=2, No such file or directory`, running with working directory `.../files/bkx/go-local/home`. `error=2` is **not proof** that the Go ELF is absent from the APK: missing PT_INTERP, absent native installation, and deleted working directory can all cause ENOENT. The current `isAvailable` check tests file existence/executability and does not prove execve can load it. No device native-directory dump was provided, so the exact live cause remains OPEN.

## Repairs included in one source-bound overlay

1. **Go launch:** check extracted native executable and working directory with explicit diagnostics. If Android reports ENOENT despite both existing, retry through the fixed `/system/bin/linker64` with the *same* argv/env/cwd. No shell, PATH lookup, root escalation, chmod in app data, arbitrary executable or download fallback; if this also fails, fail closed and preserve both causes. A live device launch is still required to validate it.
2. **Go build:** request Android PIE build, statically verify ELF64/AArch64 ET_DYN, `/system/bin/linker64` PT_INTERP, and every PT_LOAD >= 16 KiB. Build fails before publishing APKs if violated. Preserve extracted native libs (`useLegacyPackaging = true`), Go-in-both/embedded-rclone-only product rules. Do not move executable into writable app storage (Android W^X).
3. **Termux 0.9 + Android installed path:** subsume previously *unapplied* REL07 HF01 and HF02: accept Termux `V3.0 Signer: certificate SHA-256 digest`, require unique certificate and verify signature, accept sanitized `/data/app/~~.../base.apk` for existing/install hash checks. Both remain fail-closed.
4. **Production instrumentation:** install a source-bound `scripts/bkx-rel07-build-matching-instrumentation.py` which derives source SHA, artifact identity and authorized LIFE certificate from the **locally audited RM16 import/sign `data` receipts**. Builds release-targeting androidTest APK variants via private temporary Gradle init script and signs them using Devtool's existing local LIFE signer. Checks signing, Android test package, instrumentation target, APK alignment, and Git HEAD cleanliness. No CI signing, no new identity, no automatic installation; never overwrite artifacts.
5. **Device evidence:** provide read-only native-directory diagnostic; improve Go instrumentation failure text to distinguish not-extracted native ELF from launch failure. All pre-existing device/Skybind gates remain open until real evidence.

## Commands AFTER applying/pushing this overlay and completing a NEW successful unsigned GitHub run

Since the overlay advances BKX HEAD, **do not reuse** the old `ab24cf...` signed pair as proof for this new source. Rerun `bkx-devtool-release.py plan/run`, check with exact new run/artifact IDs, import and sign through RM16. Save the inner `data` JSON objects into `~/bkx-rm16-import.json` and `~/bkx-rm16-sign.json` matching **new** source. An old pair is retained, never overwritten. The existing local LIFE identity/certificate remains unchanged.

```
cd ~/Code/bkx
python3 scripts/bkx-rel07-build-matching-instrumentation.py --self-test
python3 scripts/bkx-rel07-build-matching-instrumentation.py
python3 scripts/bkx-rel07-build-matching-instrumentation.py --editions both --execute
bash scripts/bkx-rel09-device-runtime-probe.sh emulator-5554
```

Use `android/BKX.Android/scripts/qualify-device.sh` with the appropriately signed release and test APK; it refuses mismatched signing identities. The existing mismatched test APK must not be counted as release instrumentation. There is no automatic uninstall or data clearing. Keep `qualified_release=false` until independently reviewed runtime, instrumentation, Skybind and app functionality checks pass.

## Promise-by-promise audit

| Promise | Source/test result | Current authority |
| --- | --- | --- |
| Do not weaken signer certificate parsing | Supported two output formats, reject multi signer and mismatches | SOURCE TESTED; real test APK pending |
| Trusted Android APK paths | Both pre-existing and installed sample paths checked | SOURCE TESTED; prior temp device pass |
| Android PIE Go ELF with linker and 16 KiB | New executable inspection and fixtures | SOURCE TESTED; hosted Go build pending |
| Real app Go startup (screenshot issue) | ENOENT fallback + cwd/native checks | PARTIAL; **not yet observed fixed on device** |
| Product edition semantics | Go required both; rclone only Embedded | PRESERVED; runtime testing pending |
| Matching LIFE-signed instrumentation | In-repo local builder tied to true audited receipts | SOURCE DELIVERED; real Gradle/sign pending |
| Data-safe device operations | No automatic install/uninstall in overlay or builder | PRESERVED; new-source swap pending |
| Root runtime, UI, Tasker, Skybind R1 | No fake pass receipt | OPEN external qualification |
| Final qualified release | Never self-promote | OPEN |

## Regression evidence

`python3 scripts/test-bkx-go-android-executable.py` validates interpreter/ELF/ABI/16K negatives. `python3 scripts/test-bkx-rel09-consolidated.py` verifies cross-component boundaries. Older `test-bkx-rel07-hf01-termux-signer.py`, `test-bkx-rel07-hf02-modern-package-path.py`, `test-bkx-rel07-qualification.py` and source verifiers still run. They are **not** substitutes for Android build, installed `execve`, Java/Kotlin instrumentation and independent Skybind.
