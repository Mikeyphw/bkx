# BKX — Devtool-controlled GitHub Actions (Go + Android)

## Already present in the supplied snapshot

BKX **already has** three GitHub workflows: `.github/workflows/ci.yml` runs
push/PR Go and Android CI; `devtool-bkx-go-build.yml` dispatches a Go CLI +
Android ARM64 runtime build; `devtool-android-build.yml` dispatches Android
debug APK/AAB builds. This overlay **does not replace** or weaken any of these.
It adds two ACTIONS-06 orchestration manifests and a short Termux helper.

## Install and commit

Apply the source-bound overlay using Devtool, inspect `git diff` and `git status`,
then commit/push the new files to `origin/main`. The pipeline will not execute
with an uncommitted worktree or when GitHub main does not resolve to local HEAD.
For non-main branches, edit **both manifests' `ref` fields** to your branch,
commit and push those edits, then run the plan again. No ref substitution
or dirty-file bypass is performed by the helper.

```bash
cd ~/Code/bkx
bash scripts/devtool-bkx-actions.sh diagnose
bash scripts/devtool-bkx-actions.sh plan apk
bash scripts/devtool-bkx-actions.sh run apk
```

AAB is supported separately:

```bash
bash scripts/devtool-bkx-actions.sh plan aab
bash scripts/devtool-bkx-actions.sh run aab
```

Pipeline `run` performs a bounded foreground pass, *not* a blocking wait until
all remote jobs complete. Record `data.pipeline_id` from Devtool's JSON output,
then query/resume the **same** journal after remote jobs finish:

```bash
bash scripts/devtool-bkx-actions.sh status PIPELINE_ID
bash scripts/devtool-bkx-actions.sh resume PIPELINE_ID
```

The plan rejects dirty local Git source or a GitHub ref mismatch. The pipeline
serializes local journal state while allowing parallel independent GitHub Go and
Android dispatch. The nodes perform source identity checking, both remote
runs, successful remote-result observation, and SHA-256-verified ZIP artifact
registration. A green pipeline registers archives; **it does not automatically
independently verify package internals or qualify a release**.

To retrieve and verify the *debug APK itself*, find the Android run/artifact IDs
from the pipeline journal / `devtool actions artifacts --run-id RUN_ID --json`:

```bash
bash scripts/devtool-bkx-actions.sh android-collect RUN_ID ARTIFACT_ID apk
```

That invokes Devtool's ACTIONS-05 Android collector (APK signature + package
identity). The AAB path (`aab`) requires bundletool and JDK. Devtool's local
Android verification utilities must be installed (`apksigner`, `aapt`).

## Safety and caveats

- GitHub Actions produces **debug** Android artifacts only. The personal BKX
  signing keystore and release certificate stay on-device. Debug builds cannot
  replace an installed copy signed with the permanent personal identity.
- No instrumentation/device acceptance is completed by these pipelines.
- The existing `.github/workflows/ci.yml` remains the default automatic CI
  on push and PR; manual pipeline dispatches are additional jobs and incur
  GitHub Actions runner usage. The helper **does not dispatch anything on plan**.
- Devtool may return `uncertain` if dispatch was accepted but no unambiguous
  `run_id` can be established. In that situation inspect Devtool's saved
  request and corresponding GitHub run; **do not blindly run a second pipeline**.
- BKX resolves the latest stable bclone/rclone-compatible runtime at Android
  build time. Its resolved provenance is recorded but repeated builds from a
  single BKX commit are not guaranteed bit-identical across upstream releases.
- Source checks and artifact archive hashes do **not** prove installation,
  device correctness, package identity, native runtime functionality, or
  qualified release status.

See existing `docs/guide/DEVTOOL_GITHUB_ACTIONS.md` for independent single-run
planning, Android collection and Go workflows. The original CI/workflows and
signing scripts remain untouched by this overlay.
