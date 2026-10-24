# BKX REL05-HF03 — AGP unsigned release output binding

**Incident:** [GitHub Actions run 40043082497](https://github.com/Mikeyphw/bkx/actions/runs/40043082497), attempted source `952d0686aaa82c23f73d986b59a0e73c49b39a07`, job `unsigned-dual` / `120718430016`.

## Direct evidence and cause

The hosted memory-repaired build successfully completed `:app:assembleEmbeddedRelease` and `:app:assembleLiteRelease`. `:app:verifyBkxEditionApkContents` then failed:

`Missing embedded release APK: .../outputs/apk/embedded/release/app-embedded-release.apk`

The hard-coded APK filenames in the BR35/BR37 verification and 16 KiB tasks assumed a signed-style filename. The intentionally unsigned hosted release build emits AGP-declared unsigned output names (normally `app-embedded-release-unsigned.apk` and `app-lite-release-unsigned.apk`). The existing Python ACTIONS-10 producer already resolves the actual `outputFile` from AGP `output-metadata.json`, making the separate Gradle hard-coded assumptions inconsistent.

**This is a BKX validation/path defect, not a missing runtime, a signing failure, or a Devtool dispatch failure.** GitHub artifact upload, local LIFE signing and device acceptance did not execute.

## Repair and contracts

- A single `BkxReleaseApkOutputs.locate()` helper resolves each release APK using `output-metadata.json` under the proper `embedded/release` or `lite/release` output directory.
- Fail-closed checks preserve expected variant, fixed BKX package ID, exactly one output element, safe basename, ordinary metadata/APK files, and precisely one actual APK. No fallback to whichever filename happens to exist.
- Both independently named 16 KiB verification tasks and the Embedded/Lite ZIP member verifier use the same locator. Existing `assemble*Release` dependencies remain intact.
- The BR35 Gradle source seal, REL-03 source checks and Kotlin native-release contract tests now assert dynamic AGP metadata binding instead of impossible signed filenames.
- The ACTIONS-10 producer remains unchanged: two unsigned APKs, immutable GitHub provenance, strict per-edition `manifest.json`, separate GitHub artifacts, and local LIFE signing only. GitHub CI heap/worker fixes remain unchanged.

## Acceptance status

- **Source and fixture tests:** require all REL05-HF03 tests, mutation tests, prior release-source checks and Devtool atomic overlay validation to pass.
- **Hosted Gradle acceptance:** OPEN until a new commit completes both metadata-backed APK verifiers, independent 16 KiB gates, the candidate producer and both `upload-artifact` steps.
- **LIFE signing, installed swap and Skybind:** OPEN. No source check authorizes release qualification.

No signing certificate, keystore, access token or secret is read or shipped in this hotfix.
