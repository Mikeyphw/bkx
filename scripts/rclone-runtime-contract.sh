#!/usr/bin/env bash
# Shared identity checks for BKX's Android rclone-compatible runtime.
# The runtime source is the latest stable GitHub release from the bclone fork,
# while the produced binary preserves rclone's module/CLI identity for BKX.

bkx_bclone_extract_release_tag() {
    local release_text=${1:-}
    local tag

    # Preferred input is curl's effective /releases/tag/vX.Y.Z URL. The title
    # fallback keeps wget usable when only the redirected HTML body is available.
    tag=$(grep -Eo '/releases/tag/v[0-9]+\.[0-9]+\.[0-9]+' <<<"$release_text" \
        | sed 's#^.*/##' | head -n1)
    if [[ -z "$tag" ]]; then
        tag=$(grep -Eo 'Release[[:space:]]+v[0-9]+\.[0-9]+\.[0-9]+' <<<"$release_text" \
            | awk '{print $2}' | head -n1)
    fi
    [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || return 1
    printf '%s\n' "$tag"
}

bkx_rclone_validate_build_info() {
    local build_info=${1:-}
    local expected_revision=${2:-}
    local failed=0
    if ! grep -Fq 'github.com/rclone/rclone' <<<"$build_info"; then
        printf 'missing rclone main-module identity in Go build metadata\n' >&2
        failed=1
    fi
    if ! grep -Fq 'GOOS=android' <<<"$build_info"; then
        printf 'missing GOOS=android in Go build metadata\n' >&2
        failed=1
    fi
    if ! grep -Fq 'GOARCH=arm64' <<<"$build_info"; then
        printf 'missing GOARCH=arm64 in Go build metadata\n' >&2
        failed=1
    fi
    if ! grep -Fq 'CGO_ENABLED=1' <<<"$build_info"; then
        printf 'missing CGO_ENABLED=1 in Go build metadata\n' >&2
        failed=1
    fi
    if [[ -n "$expected_revision" ]]; then
        if ! grep -Fq "vcs.revision=$expected_revision" <<<"$build_info"; then
            printf 'missing expected bclone Git revision in Go build metadata: %s\n' "$expected_revision" >&2
            failed=1
        fi
        if ! grep -Fq 'vcs.modified=false' <<<"$build_info"; then
            printf 'bclone build metadata does not prove an unmodified source checkout\n' >&2
            failed=1
        fi
    fi
    [[ $failed -eq 0 ]]
}

bkx_rclone_validate_version_output() {
    local version_output=${1:-}
    local expected_version=${2:-}
    local failed=0
    if ! grep -Fqx "rclone v${expected_version}" <<<"$version_output"; then
        printf 'rclone version output does not contain the exact expected version line: rclone v%s\n' "$expected_version" >&2
        failed=1
    fi
    if ! grep -Eq '(^|[[:space:]])os/type:[[:space:]]*android([[:space:]]|$)' <<<"$version_output"; then
        printf 'rclone version output does not report os/type: android\n' >&2
        failed=1
    fi
    if ! grep -Eq '(^|[[:space:]])os/arch:[[:space:]]*arm64([[:space:]]|$)' <<<"$version_output"; then
        printf 'rclone version output does not report os/arch: arm64\n' >&2
        failed=1
    fi
    [[ $failed -eq 0 ]]
}
