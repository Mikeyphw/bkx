# REL05-HF01 — GitHub workflow_dispatch HTTP 422 repair

## Observed event and authority

Devtool ACTIONS-ANDROID-BATCH source-bound `release-batch-plan` identified the `bkx-android-unsigned-dual-release.yml` workflow on remote `main` and correctly identified both BKX consumers. An actual `release-batch-run --yes` attempt returned `GitHub rejected workflow ref or input validation` (HTTP 422), with no confirmed GitHub run ID. This is **not** proof that any build started.

## Source defect

The original job-level `env` declared `BKX_ANDROID_SIGNING_PROPERTIES: ${{ runner.temp }}/...`. GitHub allows `github` expressions in `jobs.<job_id>.env`, but the `runner` context is not available there; it is available for step-level `env` and `run` expressions. Thus the workflow definition contains an invalid GitHub expression even though generic YAML parsing succeeds. GitHub may reject manual dispatch of invalid workflows with HTTP 422.

## Minimal fix

Replace `runner.temp` with `github.workspace` for the non-existent signing-disable sentinel path. Existing guards require the sentinel to not exist, and Gradle refuses a personal signing configuration during hosted unsigned release builds. Preserve the exact workflow ID, artifact names, source SHA binding, both variants, and local LIFE signing authority. Add explicit negative tests to catch future job-level runner expression use.

Reference: https://docs.github.com/en/actions/reference/workflows-and-actions/contexts#context-availability

## Devtool improvement ledger addendum (source not changed)

**BKX-DSP-422-01 (P2, OPEN, Devtool `github_actions.py`):** Devtool catches HTTP 422 and replaces the GitHub API's response body with a generic message, preventing diagnosis of invalid expression contexts, missing event triggers, or invalid inputs. Improve bounded, secret-redacted GitHub 422 diagnostics in the existing machine-readable receipt, while preserving redaction of user inputs/tokens and dispatch deduplication safety. Do not treat the failed POST as an accepted run or automatically retry.

## Qualification status

- Source contract/tests: testable offline.
- GitHub workflow index parse: **pending a new commit and push**.
- Real GitHub dispatch, APK compilation, LIFE signing, Android and Skybind qualification: **pending**.
- This hotfix makes no claims about hosted build success, which may expose new runtime/toolchain errors after dispatch is fixed.
