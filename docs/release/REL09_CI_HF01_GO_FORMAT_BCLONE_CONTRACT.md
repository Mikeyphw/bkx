# REL09-CI-HF01 — Go formatting and bclone reviewed-lock JVM regression

## Observed GitHub authority

- BKX source: `bb64485fcc5c149c3b8586c04017fbe88d9852b7`.
- GitHub dual unsigned release run `40101199926` **PASS**; two immutable unsigned artifact uploads. This source hotfix does not invalidate that run's historical result, but it advances the source and requires a new hosted release if the hotfix version is to be installed and qualified.
- GitHub CI run `40101115883` **FAIL** with separate Go and JVM jobs.
  - `go-production` failed `scripts/validate.sh` on two unformatted files: `internal/bridge/edition_readiness_test.go` and `internal/rcloneops/edition_test.go`.
  - `android-release-packaging` failed the JVM test `RcloneRemoteTransportContractTest.androidBuildsLatestStableBcloneAsRcloneCompatibleRuntimeAndWiresItIntoGoSession` on an obsolete Gradle label.

## Root-cause repair and promise audit

| Promise | Source remedy | Evidence / state |
|---|---|---|
| Full Go code formatting | Apply canonical `gofmt` to both new Go tests without changing their logic | `gofmt -l cmd internal` empty; CI rerun pending |
| Correct release bclone provenance | JVM contract now checks reviewed-source Gradle label and exact lock-file + validator inputs | Kotlin execution of all five class methods PASS; CI rerun pending |
| Preserve reviewed immutable bclone lock | Assert schema, repository and stable semver + full SHA; require lock selection and source verification in installer | Source mutation tests; no edits to lock or installer |
| Honest development fallback | Assert development-latest selection still exists without mistaking it for release qualification | Source validation; release review remains locked |
| Accurate Python dependency check | Require `python3 "$validator" "$lock"` used by reviewed-lock verifier instead of falsely asserting installer contains no Python | Source tests; CI rerun pending |
| Atomic source-bound delivery | SHA-256 of all three modified files, preflight exact parent commit + clean worktree, mandatory source validation, single commit | Native Devtool testing, see independent run receipt |

## Verification boundary

Offline source tests include seven cases with negative mutations, the entire repository `gofmt` inventory and a standalone Kotlin/JVM execution of **all five** `RcloneRemoteTransportContractTest` methods. The full Go suite was attempted but could not run in the isolated offline environment due to unavailable `github.com/klauspost/compress@v1.18.4` download. The Android Gradle task cannot be executed here without its complete Android SDK/dependency context. Do not claim full CI closure until a new source-specific GitHub CI run succeeds.

This overlay modifies tests only and does not alter the app, Go runtime, bclone binaries, producer workflow, keystore, signing identity or Android device. Original REL09 Go startup symptom requires separate real-device testing of the release built from a source-matching post-hotfix commit.
