#!/usr/bin/env bash
# BKX Devtool Actions helper. Dispatching is opt-in, never implicit.
set -euo pipefail
repo="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
usage() {
  cat <<'USAGE'
Usage: bash scripts/devtool-bkx-actions.sh COMMAND [ARGUMENTS]
  diagnose                        Verify Devtool GitHub authentication/permissions
  plan apk|aab                    Validate full Go + Android pipeline against GitHub
  run apk|aab                     Explicitly dispatch both GitHub workflows
  status PIPELINE_ID              Inspect saved pipeline journal
  resume PIPELINE_ID              Resume observation/collection (not repeat dispatch)
  android-collect RUN_ID ARTIFACT_ID apk|aab
                                  Independently verify the Android output via Devtool

Pipeline commits are pinned to ref 'main' in the checked-in manifests.
Push your committed HEAD to origin/main before using plan or run.
No remote release signing, installation, or device qualification is performed.
USAGE
}
command -v devtool >/dev/null || { echo 'devtool is not in PATH' >&2; exit 127; }
(( $# > 0 )) || { usage; exit 2; }
action="$1"; shift
case "$action" in
  diagnose)
    (( $# == 0 )) || { usage >&2; exit 2; }
    exec devtool --no-hud -r "$repo" actions diagnose --json ;;
  plan|run)
    (( $# == 1 )) || { usage >&2; exit 2; }
    case "$1" in
      apk|aab) pipeline="docs/ci/bkx-devtool-$1-pipeline.json" ;;
      *) usage >&2; exit 2 ;;
    esac
    if [[ "$action" == 'run' ]]; then
      exec devtool --no-hud -r "$repo" actions pipeline run --file "$pipeline" --yes --json
    else
      exec devtool --no-hud -r "$repo" actions pipeline plan --file "$pipeline" --json
    fi ;;
  status|resume)
    (( $# == 1 )) || { usage >&2; exit 2; }
    [[ "$1" =~ ^[0-9a-f]{32}$ ]] || { echo 'Expected 32-digit hexadecimal pipeline ID' >&2; exit 2; }
    if [[ "$action" == 'resume' ]]; then
      exec devtool --no-hud -r "$repo" actions pipeline resume --pipeline-id "$1" --yes --json
    else
      exec devtool --no-hud -r "$repo" actions pipeline status --pipeline-id "$1" --json
    fi ;;
  android-collect)
    (( $# == 3 )) || { usage >&2; exit 2; }
    [[ "$1" =~ ^[1-9][0-9]*$ && "$2" =~ ^[1-9][0-9]*$ ]] || { echo 'Expected positive run/artifact IDs' >&2; exit 2; }
    case "$3" in apk|aab) ;; *) usage >&2; exit 2 ;; esac
    exec devtool --no-hud -r "$repo" actions android collect \
      --run-id "$1" --artifact-id "$2" --format "$3" --variant debug \
      --expected-package com.mikeyphw.bkx.android --download --json ;;
  -h|--help|help) usage ;;
  *) usage >&2; exit 2 ;;
esac
