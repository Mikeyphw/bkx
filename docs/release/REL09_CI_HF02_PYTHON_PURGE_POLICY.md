# REL09-CI-HF02 — Historical Python application purge versus active Android release tooling

## Observed CI failure

- GitHub CI run: `40106302489`, source `81b9b11d7ad9c9170d3db2247a2189975201e8dd`.
- `android-release-packaging`: **SUCCESS** (the prior Kotlin contract repair is qualified).
- `go-production`: **FAILURE** at `TestRepositoryPythonPurgeSeal` after `gofmt` and the edition Go tests passed.
- The 31 reviewed scripts under `scripts/*.py` were misclassified as retired Python application code, and `scripts/build-go-android-runtime.sh` was rejected for correctly invoking `scripts/verify-bkx-go-android-executable.py`.

## Repair scope and invariant

The GO20 migration retired the Python **BKX application/runtime**, not source-review, Android verification, CI validation, and private Devtool RM16 release tooling. It is unsafe to delete supported tools to make a historical application-purge test green.

1. Preserve forbiddance of historical `src/bkx/`, `tests/`, and `tools/go_migration/` paths and retired Python project configuration.
2. Explicitly allow only the 31 reviewed, current BKX Python files under `scripts/`; unknown `.py` files remain blocked, and `.pyc` outside ignored caches remains blocked. This must be revisited intentionally when adding new reviewed tooling.
3. Keep Python dependency and `PYTHONPATH` prohibitions in the production Go release builder, Gradle build logic, CI, and `.devtool.toml`.
4. Accept **only** the exact single fail-closed Python ELF validator call in the Android runtime builder. Extra or altered interpreter invocations are rejected.
5. Keep existing .gitignore/cache traversal skips, Go target assertion, and format/canonical source tests.

## Qualification

The scope tests are stdlib-only and can run without downloading Go modules by passing explicit files to `go test`. The full repository Go suite and live Android CI still require the GitHub runner. The signed APKs from the previous source commit are preserved; this change makes a new source revision, not a qualified release.
