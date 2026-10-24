# BKX REL05-HF04 — edition-aware APK native verifier

## Observed authority

GitHub Actions workflow run [40044773493](https://github.com/Mikeyphw/bkx/actions/runs/40044773493), attempt 1, source `5983906959cfae0ef4c63b489c56f57630b793fe`, 2026-10-24 UTC: both `assembleEmbeddedRelease` and `assembleLiteRelease` completed; APK content verifier passed; Embedded 16 KiB verification passed. `:app:verifyBkxLiteRelease16Kb` failed because `scripts/verify-native-page-size.go apk` unconditionally required `libbkxrclone_exec.so`. Lite correctly excludes it. Both ACTIONS-10 artifact uploads were skipped.

## Repair promises / evidence

1. The Go APK verifier requires explicit `--edition embedded|lite` for **exactly one** APK. This avoids accidentally treating a Lite APK as Embedded or the converse.
2. In both editions, it requires the exact `lib/arm64-v8a/libbkxgo.so` and verifies PT_LOAD 16 KiB alignment for every packaged native `.so` and uncompressed ZIP entry offsets.
3. Embedded additionally requires `lib/arm64-v8a/libbkxrclone_exec.so` and `assets/bkx-rclone-runtime.properties`; Lite explicitly **forbids** both. Any unsupported native ABI or duplicate ZIP member is rejected.
4. Both AGP 16 KiB Gradle tasks pass their immutable expected variant to the Go verifier, after metadata-based exact APK location.
5. The unsigned producer makes the same independent edition-specific verifier call, after validating the producer contract. Signing is never done in GitHub.
6. Go fixture tests include valid Embedded/Lite plus missing Go, missing/prohibited rclone, missing provenance, malformed flags, unsupported ABI, duplicate ZIP entry, and misaligned ELF negative cases. A Python source contract verifies Gradle and producer CLI bindings.

## Qualification boundary

Source tests and native Devtool overlay installation are **not** GitHub-run success receipts. New GitHub dispatch must produce two valid ACTIONS-10 artifacts, then LIFE local signing and Android/Skybind tests must furnish external evidence. No release is qualified by this patch.
