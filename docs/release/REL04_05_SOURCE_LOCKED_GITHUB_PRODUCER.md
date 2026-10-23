# BKX REL-04 + REL-05 — Reviewed bclone source and dual unsigned GitHub producer

**Stage:** Source-implemented, **not** an accepted hosted Android release. This document never authorizes local signing, install or Skybind device qualification.

## Source contract and rationale

- The full reviewed bclone source identity is `v1.75.3` / `7b63b1a4cc9d13465721ebbfc96f2f9bd810f600` from `BenjiThatFoxGuy/bclone`. GitHub must verify that the tag still resolves to that commit before cached reuse or compilation.
- `config/bclone-android-source.lock` also pins the hosted Go compiler (`go1.27.2`), Android NDK (`29.0.14206865`), API 24, arm64, and 16 KiB page-size contract. Updating any of these values requires a reviewed source change and new hosted evidence. Pinning inputs is not the same as proving byte-for-byte reproducibility.
- The installer retains the legacy latest-stable resolver for *unqualified developer use*, but hosted CI exports `BKX_RCLONE_REQUIRE_LOCK=1` and refuses missing, malformed or changed tags, incorrect local toolchains, incomplete cache provenance or 4 KiB native ELF segments.
- Both variants share BKX's native Go producer. Only Embedded depends on building bclone; Lite must neither fetch, cache nor package bclone. The release packaging validators from REL-03 still check both actual APKs independently.
- `.github/workflows/bkx-android-unsigned-dual-release.yml` uses a single dispatch, one Gradle command, Java 21, Gradle 9.7.1, SDK 36, build tools 36.0.0 and NDK r29. It forces missing personal signing credentials. No keystore, cert or private tokens belong in GitHub source or workflow.
- The strict uploader `scripts/produce-bkx-unsigned-candidates.py` reads AGP output metadata rather than guessing APK filenames, checks source/workflow/run identity, app ID, version and native packaging, rejects a signed/debuggable APK, checks zipalign and 16 KiB ELF alignment, then produces two isolated artifact roots, **each only** `manifest.json` and the registered unsigned APK.
- `config/bkx-android-unsigned-artifacts.json` fixes two producer artifact names, files and required/forbidden members. REL-06 registers their corresponding signer/cert in Devtool with local LIFE authority. **Do not commit a signer fingerprint guessed from a previous install.**

## Evidence matrix

| Roadmap promise | Concrete source evidence | Assessment |
|---|---|---|
| Bclone stable tag and full SHA pinned | `config/bclone-android-source.lock`, `verify-bclone-source-lock.py`, installer pin/tag verification | Source implemented; remote Git proof pending |
| Hosted CI enforces lock and exact toolchains | Workflow environment and preflight, installer toolchain checks, version-bearing cache key and provenance | Source implemented; real compiler/NDK run pending |
| Development latest remains available | Installer explicit reviewed/legacy branches; original OV4/BR35 assertions migrated without dropping historical contracts | Targeted source tests passed |
| Only Embedded builds and packages rclone | REL-00–03 Gradle generated-source selection, present/forbidden file lists and two-APK producer validation | Source implemented; real APK check pending |
| One GitHub run, one shared Gradle invocation | Workflow one job, `assembleEmbeddedRelease` and `assembleLiteRelease` in same command | Source implemented; Actions run pending |
| Signed or debuggable GitHub APK cannot be published | Producer checks aapt/apksigner, APK signing blocks and legacy JAR-signatures | Negative fixtures passed; real APK pending |
| Exact ACTIONS-10 manifest and pair integrity | Local config + `produce-bkx-unsigned-candidates.py`; both fixture archives parsed by actual Devtool `actions_unsigned._manifest` | Source/interoperability fixtures passed; real import pending |
| Same app ID/version on both candidates | Cross-APK/AGP metadata verification | Source tests passed; release artifacts pending |
| Existing permanent local signing stays private | `BKX_UNSIGNED_CI_RELEASE` Gradle refusal plus workflow dummy properties path | Source implemented; LIFE signing deferred REL-06 |
| 16 KiB native segments | REL-03 two variant native page tasks and producer Go verifier, plus final ZIP alignment | Source wired; real APK pending |
| Real signed BKX and Skybind compatibility | No device operations or signing performed | **OPEN** REL-06–08 |

## Offline tests performed

- Producer behavior: 13 Python unit and negative tests (two candidates, signing leakage, wrong edition members, mixed Lite, missing Embedded, wrong AGP variant/version, malformed lock, missing source/ref authority) pass using small test APK fixtures and stub SDK executables. These are **not** real Android APK tests.
- Both fixture archives with their generated `manifest.json` were accepted by the exact Devtool ACTIONS-10 `_manifest` parser; separate negative ZIP/ref/id mismatches remain Devtool's authority.
- BKX REL-00–03 source tests, historical OV4 source contract, repository static Android release preflight, focused Go Devtool/rcloneops tests, and Devtool/Skybind source contracts passed.
- Strict missing/invalid lock tests fail closed before network resolution; Bash syntax and workflow YAML parse passed.
- **Not run:** actual upstream `git ls-remote` (container has no DNS access), complete Bclone compile, Gradle task enumeration, hosted GitHub Actions build, APK ZIP inspection from real AGP, authorized LIFE signing, physical Android/Skybind acceptance.

## Operator next steps

1. Apply this source-bound overlay after REL-03 with a clean BKX Git tree.
2. With Devtool, dispatch the new workflow on the exact committed BKX revision; verify its GitHub SHA and inspect both artifact IDs. If upstream source/toolchain pins fail, treat that as a real blocker; do not relax the lock or use the debug workflow.
3. REL-06: register both consumer contracts and **existing local** LIFE identity/certificate in Devtool; perform RM-16 batch check/import/sign with full JSON receipts. Sign nothing in GitHub.
4. REL-07/08: qualify both APKs and Skybind on the actual approved device, retaining any earlier deferred manual evidence unchanged.
