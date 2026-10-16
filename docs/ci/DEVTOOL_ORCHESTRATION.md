# Devtool orchestration convergence: bkx

This overlay uses explicit MP03 dependency DAGs, target-local jobs, safe parallel scheduling, and the native EXO run ledger. It is intentionally layered: `./devtoolw` -> canonical Devtool `wrapper exec` -> local target jobs/workflows -> EXO. Remote GitHub Actions remains `devtool actions` (not an unsupported `wrapper devtool="actions"`).

## Local repository operations

```bash
./devtoolw --help
./devtoolw ci-preflight    # read-only CI workflow presence; EXO run evidence
./devtoolw ci-local        # local tests/contract, not a GitHub run
./devtoolw build           # canonical target build

devtool --repo "$PWD" workflows --json
devtool --repo "$PWD" jobs --json
devtool --repo "$PWD" workflows --json   # Native DAG graph + execution-wave schedule
devtool --repo "$PWD" invoke ci-preflight --target bkx  # Optional direct invocation
devtool --repo "$PWD" runs --help
```

## Remote GitHub Actions

```bash
devtool --repo "$PWD" actions diagnose --json
devtool --repo "$PWD" actions workflows --json
```

Run `devtool --repo "$PWD" actions plan WORKFLOW.yml --ref BRANCH --json` first, then `actions run WORKFLOW.yml --ref BRANCH --yes --json` to dispatch. Inspect `actions runs`, `actions jobs`, `actions watch`, and `actions artifacts`; use `actions resume --request-id ID` for uncertain dispatches and do not re-dispatch blindly. Github success is not release or device qualification. Keep artifact and source-SHA checks before acceptance.

Expected workflow files: .github/workflows/ci.yml, .github/workflows/devtool-android-build.yml, .github/workflows/devtool-bkx-go-build.yml.

## Safety

- `ci-preflight` is a read-only workflow *presence* check only, not a GitHub status or security approval.
- `ci-local` executes canonical local tests and cannot build or sign a release implicitly.
- Wrapper uses only Devtool-supported native actions or named `invoke` workflows. **No nested Devtool actions dispatch inside an EXO job.**
- Existing target/job DAGs, Gradle/Go runners, device gates and release authority are preserved.
- Source-bound update requires the exact uploaded configuration variant, a clean Git worktree, and refuses unexpected existing files.

Existing `docs/ci/bkx-devtool-*-pipeline.json` remain the remote orchestration authority and can still be planned/run through Devtool actions pipeline; they are not executed by `./devtoolw ci-local`.
