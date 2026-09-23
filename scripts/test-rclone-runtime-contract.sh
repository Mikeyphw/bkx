#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
# shellcheck source=scripts/rclone-runtime-contract.sh
source "$script_dir/rclone-runtime-contract.sh"

fixture_revision=0123456789abcdef0123456789abcdef01234567
pass_build_info=$'path\tgithub.com/rclone/rclone\nbuild\tGOOS=android\nbuild\tGOARCH=arm64\nbuild\tCGO_ENABLED=1\nbuild\tvcs.revision='"$fixture_revision"$'\nbuild\tvcs.modified=false'
bkx_rclone_validate_build_info "$pass_build_info" "$fixture_revision"

for bad in \
    $'path\texample.invalid/not-rclone\nbuild\tGOOS=android\nbuild\tGOARCH=arm64\nbuild\tCGO_ENABLED=1' \
    $'path\tgithub.com/rclone/rclone\nbuild\tGOOS=linux\nbuild\tGOARCH=arm64\nbuild\tCGO_ENABLED=1' \
    $'path\tgithub.com/rclone/rclone\nbuild\tGOOS=android\nbuild\tGOARCH=amd64\nbuild\tCGO_ENABLED=1' \
    $'path\tgithub.com/rclone/rclone\nbuild\tGOOS=android\nbuild\tGOARCH=arm64\nbuild\tCGO_ENABLED=0'
do
    if bkx_rclone_validate_build_info "$bad" >/dev/null 2>&1; then
        echo 'negative Go build-info identity fixture unexpectedly passed' >&2
        exit 1
    fi
done

modified_build_info=$'path\tgithub.com/rclone/rclone\nbuild\tGOOS=android\nbuild\tGOARCH=arm64\nbuild\tCGO_ENABLED=1\nbuild\tvcs.revision='"$fixture_revision"$'\nbuild\tvcs.modified=true'
if bkx_rclone_validate_build_info "$modified_build_info" "$fixture_revision" >/dev/null 2>&1; then
    echo 'modified bclone source fixture unexpectedly passed provenance validation' >&2
    exit 1
fi

[[ "$(bkx_bclone_extract_release_tag 'https://github.com/BenjiThatFoxGuy/bclone/releases/tag/v9.99.7')" == 'v9.99.7' ]]
[[ "$(bkx_bclone_extract_release_tag '<title>Release v9.99.7 · BenjiThatFoxGuy/bclone · GitHub</title>')" == 'v9.99.7' ]]
if bkx_bclone_extract_release_tag 'https://github.com/BenjiThatFoxGuy/bclone/releases/latest' >/dev/null 2>&1; then
    echo 'unresolved latest-release URL unexpectedly produced a version tag' >&2
    exit 1
fi

pass_version=$'rclone v9.99.7\n- os/version: android 16\n- os/kernel: 6.1\n- os/type: android\n- os/arch: arm64\n- go/version: go1.26.7'
bkx_rclone_validate_version_output "$pass_version" '9.99.7'

for bad in \
    $'rclone v9.99.6\n- os/type: android\n- os/arch: arm64' \
    $'rclone v9.99.7\n- os/type: linux\n- os/arch: arm64' \
    $'rclone v9.99.7\n- os/type: android\n- os/arch: amd64'
do
    if bkx_rclone_validate_version_output "$bad" '9.99.7' >/dev/null 2>&1; then
        echo 'negative rclone version-output identity fixture unexpectedly passed' >&2
        exit 1
    fi
done

installer="$script_dir/install-rclone-android-runtime.sh"
native_page_verifier="$script_dir/verify-native-page-size.go"
for file in "$installer" "$native_page_verifier"; do
    [[ -f "$file" ]] || { echo "missing runtime verifier: $file" >&2; exit 1; }
done

grep -Fq 'BCLONE_REPOSITORY="https://github.com/BenjiThatFoxGuy/bclone.git"' "$installer"
grep -Fq 'BCLONE_LATEST_RELEASE_URL="https://github.com/BenjiThatFoxGuy/bclone/releases/latest"' "$installer"
grep -Fq -- "--write-out '%{url_effective}'" "$installer"
grep -Fq 'git ls-remote --tags "$BCLONE_REPOSITORY"' "$installer"
grep -Fq 'refs/tags/$BCLONE_TAG' "$installer"
grep -Fq 'git -C "$source_dir" checkout --quiet --detach "$BCLONE_COMMIT"' "$installer"
grep -Fq "grep -Fqx 'module github.com/rclone/rclone'" "$installer"
grep -Fq 'go mod download' "$installer"
grep -Fq 'go mod verify' "$installer"
grep -Fq 'go build -mod=readonly' "$installer"
grep -Fq -- '-buildvcs=true' "$installer"
grep -Fq 'vcs.revision=' "$script_dir/rclone-runtime-contract.sh"
grep -Fq 'vcs.modified=false' "$script_dir/rclone-runtime-contract.sh"
grep -Fq "printf 'provider=bclone\\n'" "$installer"
grep -Fq "printf 'resolution=github-latest-stable-release\\n'" "$installer"
grep -Fq "printf 'commit=%s\\n'" "$installer"
grep -Fq "printf 'tree=%s\\n'" "$installer"
grep -Fq 'bkx-rclone-runtime.properties' "$installer"
grep -Fq 'max-page-size=${BKX_ANDROID_PAGE_SIZE}' "$installer"
grep -Fq 'common-page-size=${BKX_ANDROID_PAGE_SIZE}' "$installer"
grep -Fq 'CGO_ENABLED=1' "$installer"
grep -Fq 'env -u LD_PRELOAD' "$installer"
grep -Fq 'ndk-termux-bash-shim' "$installer"
grep -Fq 'rejected by Go/cgo + 16 KiB probe; trying next candidate' "$installer"
grep -Fq 'termux-native-clang-ndk-sysroot' "$installer"
grep -Fq 'make_termux_ndk_sysroot_shim' "$installer"
grep -Fq -- '-tags "android noselfupdate"' "$installer"
grep -Fq 'CC_FOR_TARGET="$cc"' "$installer"
grep -Fq -- '--target=%q --sysroot=%q' "$installer"
grep -Fq 'select_toolchain || fail 69 toolchain' "$installer"
grep -Fq 'verify-native-page-size.go' "$installer"

for forbidden in \
    'RCLONE_VERSION="1.75.1"' \
    'RCLONE_SOURCE_SHA256=' \
    'RCLONE_VENDOR_SHA256=' \
    'downloads.rclone.org/' \
    'beta.rclone.org' \
    'linux-arm64'
do
    if grep -Fq "$forbidden" "$installer"; then
        echo "installer contains forbidden pinned/upstream runtime token: $forbidden" >&2
        exit 1
    fi
done

# Reproduce the cgo/Termux shebang failure independently of termux-exec. A
# compiler wrapper with a missing interpreter is ENOENT when executed directly,
# but the BKX-style shim remains executable because it has an absolute bash
# shebang and feeds the original wrapper to bash as data.
shim_tmp=$(mktemp -d "${TMPDIR:-/tmp}/bkx-rclone-cgo-shim.XXXXXX")
cat > "$shim_tmp/ndk-wrapper" <<'BAD'
#!/definitely/missing/bash
if [[ "${1:-}" == "--version" ]]; then echo 'fixture clang'; exit 0; fi
exit 0
BAD
chmod 0700 "$shim_tmp/ndk-wrapper"
if "$shim_tmp/ndk-wrapper" --version >/dev/null 2>&1; then
    echo 'bad-shebang fixture unexpectedly executed directly' >&2
    exit 1
fi
bash_bin=$(command -v bash)
cat > "$shim_tmp/cc-shim" <<SHIM
#!$bash_bin
exec "$bash_bin" "$shim_tmp/ndk-wrapper" "\$@"
SHIM
chmod 0700 "$shim_tmp/cc-shim"
env -u LD_PRELOAD "$shim_tmp/cc-shim" --version | grep -Fq 'fixture clang'
rm -rf "$shim_tmp"

# Exercise the Go ELF parser without requiring a compiler: the verifier itself
# emits two deterministic ELF64 fixtures with opposite alignment outcomes.
tmp=$(mktemp -d "${TMPDIR:-/tmp}/bkx-rclone-elf-contract.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
env GOTOOLCHAIN=local go run "$native_page_verifier" fixture --good "$tmp/good.elf" --bad "$tmp/bad.elf"
env GOTOOLCHAIN=local go run "$native_page_verifier" elf --min-page-size 16384 "$tmp/good.elf" >/dev/null
if env GOTOOLCHAIN=local go run "$native_page_verifier" elf --min-page-size 16384 "$tmp/bad.elf" >/dev/null 2>&1; then
    echo '4 KiB ELF fixture unexpectedly passed the 16 KiB gate' >&2
    exit 1
fi
printf 'bclone latest-release Android runtime identity + 16 KiB contract tests: PASS\n'
