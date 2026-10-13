# BKX: remote GitHub Actions builds controlled by local Devtool

Devtool stays on the local machine/Termux; its `actions` integration dispatches
checked-in workflows on GitHub. The remote jobs use GitHub-hosted Ubuntu, not
Termux/chroot. Always commit and push changes to the branch used by `--ref`.
Devtool's Android build planner **requires a clean local Git working tree**
and remote ref pointing to exactly that commit.

## Android debug APK / AAB

The dispatchable `.github/workflows/devtool-android-build.yml` accepts the four
**exact** ACTIONS-05 inputs (`project_dir`, `module`, `variant`, `format`). This
repository supports `android/BKX.Android`, `app`, `debug`, and `apk` or `aab`.
The Gradle launcher at `android/BKX.Android/gradlew` bootstraps checksum-pinned
Gradle 9.7.1. The GitHub job installs Java 21, an up-to-date stable Go toolchain,
Android API 36, Build Tools 36.0.0 and NDK 29.0.14206865. Gradle builds
BKX's own Go ARM64 runtime and current stable bclone ARM64 runtime and then
packages the requested debug artifact.

```sh
REPO="$HOME/Code/bkx"
# Local Git origin must point to the GitHub repository, or use --github-repo.
git -C "$REPO" push origin main

devtool -r "$REPO" actions diagnose --github-repo Mikeyphw/bkx --json
devtool -r "$REPO" actions android discover --project-dir android/BKX.Android --json
devtool -r "$REPO" actions android plan --project-dir android/BKX.Android --module app --variant debug --format apk --ref main --json
devtool -r "$REPO" actions android run --project-dir android/BKX.Android --module app --variant debug --format apk --ref main --yes --json
```

If the dispatch returns a verified `run_id`, use that ID (not the latest
unrelated run). Dispatch acceptance alone is not build success.

```sh
devtool -r "$REPO" actions watch --run-id RUN_ID --max-checks 30 --interval 10 --json
devtool -r "$REPO" actions artifacts --run-id RUN_ID --json
devtool -r "$REPO" actions android collect --run-id RUN_ID --artifact-id ARTIFACT_ID --format apk --variant debug --expected-package com.mikeyphw.bkx.android --download --json
```

Local APK collection requires Android `apksigner` and `aapt`/`aapt2`. Devtool
checks the GitHub run, archive digest, package identity and debug signing.
For AAB change `--format aab` in plan/run/collect; collection also requires
Devtool's bundletool setup. The GitHub workflow validates that the BKX JNI
runtimes and rclone provenance are included; for APK it also verifies native
16-KiB page-size alignment. **Neither upload nor collection is a qualified
signed release.**

## Go CLI builds

```sh
devtool -r "$REPO" actions plan devtool-bkx-go-build.yml --ref main --json
devtool -r "$REPO" actions run devtool-bkx-go-build.yml --ref main --yes --json
devtool -r "$REPO" actions watch --run-id RUN_ID --max-checks 30 --interval 10 --json
devtool -r "$REPO" actions artifacts --run-id RUN_ID --json
```

The Go job builds the `linux/amd64` CLI (Ubuntu runner native target) plus
Android ARM64 `libbkxgo.so`, validates hashes and uploads a ZIP artifact. The
Go artifact is **not** the complete Android APK.

## Signing, source trust, and limits

GitHub debug packages use GitHub's disposable Android debug certificate;
**they cannot update an installed BKX signed with the permanent personal key**.
BKX's existing Devtool `build`, `release`, and `package` target operations
still require the user's personal signing identity; this workflow does not
remove or replace those gates or export private keystores to GitHub.

Release variants are not dispatchable here. A separately designed local
LIFE/personal-signing workflow is required for distributable or updateable
releases. Device instrumentation, root access and on-device acceptance are
**not** performed by the Linux runner.

The bclone installer intentionally resolves GitHub's latest stable release at
build time. It pins the resolved tag to a commit and records provenance, but
successive runs from the same BKX commit can contain different bclone binaries
if that upstream release changes. The workflow therefore does **not** claim
fully reproducible builds across time.

If Devtool returns a GitHub 404, check `git remote -v`, the exact `--github-repo`
value and token Actions read/write permissions. Use `gh auth login` or the
supported `GH_TOKEN`/`GITHUB_TOKEN` environment; never put tokens in workflow
inputs. Devtool's `actions run` requires explicit `--yes`.
