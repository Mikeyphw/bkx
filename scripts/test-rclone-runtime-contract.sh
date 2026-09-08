#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
# shellcheck source=scripts/rclone-runtime-contract.sh
source "$script_dir/rclone-runtime-contract.sh"

pass_build_info=$'path\tgithub.com/rclone/rclone\nbuild\tGOOS=android\nbuild\tGOARCH=arm64\nbuild\tCGO_ENABLED=1'
bkx_rclone_validate_build_info "$pass_build_info"

for bad in \
    $'path\texample.invalid/not-rclone\nbuild\tGOOS=android\nbuild\tGOARCH=arm64' \
    $'path\tgithub.com/rclone/rclone\nbuild\tGOOS=linux\nbuild\tGOARCH=arm64' \
    $'path\tgithub.com/rclone/rclone\nbuild\tGOOS=android\nbuild\tGOARCH=amd64'
do
    if bkx_rclone_validate_build_info "$bad" >/dev/null 2>&1; then
        echo 'negative Go build-info identity fixture unexpectedly passed' >&2
        exit 1
    fi
done

pass_version=$'rclone v1.75.1\n- os/version: android 16\n- os/kernel: 6.1\n- os/type: android\n- os/arch: arm64\n- go/version: go1.27.0'
bkx_rclone_validate_version_output "$pass_version" '1.75.1'

for bad in \
    $'rclone v1.75.0\n- os/type: android\n- os/arch: arm64' \
    $'rclone v1.75.1\n- os/type: linux\n- os/arch: arm64' \
    $'rclone v1.75.1\n- os/type: android\n- os/arch: amd64'
do
    if bkx_rclone_validate_version_output "$bad" '1.75.1' >/dev/null 2>&1; then
        echo 'negative rclone version-output identity fixture unexpectedly passed' >&2
        exit 1
    fi
done

installer="$script_dir/install-rclone-android-runtime.sh"
grep -Fq 'embedded version marker' "$installer"
grep -Fq 'bkx_rclone_validate_build_info' "$installer"
grep -Fq 'bkx_rclone_validate_version_output' "$installer"
if grep -Fq 'strings "$tmp_target" | grep -Fq "rclone v${RCLONE_VERSION}"' "$installer"; then
    echo 'installer regressed to the brittle formatted raw-string version check' >&2
    exit 1
fi

printf 'rclone Android runtime identity contract tests: PASS\n'
