#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
# shellcheck source=scripts/rclone-runtime-contract.sh
source "$script_dir/rclone-runtime-contract.sh"

pass_build_info=$'path\tgithub.com/rclone/rclone\nbuild\tGOOS=android\nbuild\tGOARCH=arm64\nbuild\tCGO_ENABLED=1'
bkx_rclone_validate_build_info "$pass_build_info"

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

pass_version=$'rclone v1.75.1\n- os/version: android 16\n- os/kernel: 6.1\n- os/type: android\n- os/arch: arm64\n- go/version: go1.26.7'
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
native_page_verifier="$script_dir/verify-native-page-size.go"
for file in "$installer" "$native_page_verifier"; do
    [[ -f "$file" ]] || { echo "missing runtime verifier: $file" >&2; exit 1; }
done

grep -Fq 'RCLONE_SOURCE_ARCHIVE="rclone-v${RCLONE_VERSION}.tar.gz"' "$installer"
grep -Fq 'RCLONE_VENDOR_ARCHIVE="rclone-v${RCLONE_VERSION}-vendor.tar.gz"' "$installer"
grep -Fq 'RCLONE_SOURCE_SHA256="6ada25ecda8b3c8d971ed0aae1167e44d257dbcbf0be6598bf6c127daca2e13f"' "$installer"
grep -Fq 'RCLONE_VENDOR_SHA256="a43cd21f71344ff96b3b00217196c069f2f28fa29835cd61cfa94edb1aa5b93b"' "$installer"
grep -Fq 'max-page-size=${BKX_ANDROID_PAGE_SIZE}' "$installer"
grep -Fq 'common-page-size=${BKX_ANDROID_PAGE_SIZE}' "$installer"
grep -Fq 'CGO_ENABLED=1' "$installer"
grep -Fq 'env -u LD_PRELOAD' "$installer"
grep -Fq 'ndk-termux-bash-shim' "$installer"
grep -Fq 'rejected by Go/cgo + 16 KiB probe; trying next candidate' "$installer"
grep -Fq 'termux-native-clang-ndk-sysroot' "$installer"
grep -Fq 'make_termux_ndk_sysroot_shim' "$installer"
grep -Fq 'go build -mod=vendor' "$installer"
grep -Fq -- '-tags "android noselfupdate"' "$installer"
grep -Fq 'CC_FOR_TARGET="$cc"' "$installer"
grep -Fq -- '--target=%q --sysroot=%q' "$installer"
grep -Fq 'select_toolchain || fail 69 toolchain' "$installer"
grep -Fq 'verify-native-page-size.go' "$installer"
if grep -Fq 'beta.rclone.org' "$installer"; then
    echo 'installer regressed to the upstream 4 KiB Android testbuild' >&2
    exit 1
fi

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

# Reproduce the official release layout. The plain source archive carries the
# rclone-vX.Y.Z/ source tree; the vendor archive carries only vendor/. They must
# be merged before an offline -mod=vendor build. This guards the v1 regression
# where the dependency-only vendor archive was incorrectly treated as source.
layout_tmp=$(mktemp -d "${TMPDIR:-/tmp}/bkx-rclone-release-layout.XXXXXX")
mkdir -p "$layout_tmp/source/rclone-v1.75.1" "$layout_tmp/vendor/vendor" "$layout_tmp/out"
printf 'module github.com/rclone/rclone\n' > "$layout_tmp/source/rclone-v1.75.1/go.mod"
printf '# fixture\n' > "$layout_tmp/vendor/vendor/modules.txt"
tar -czf "$layout_tmp/source.tar.gz" -C "$layout_tmp/source" rclone-v1.75.1
tar -czf "$layout_tmp/vendor.tar.gz" -C "$layout_tmp/vendor" vendor
tar -xzf "$layout_tmp/source.tar.gz" -C "$layout_tmp/out"
tar -xzf "$layout_tmp/vendor.tar.gz" -C "$layout_tmp/out/rclone-v1.75.1"
[[ -f "$layout_tmp/out/rclone-v1.75.1/go.mod" ]]
[[ -f "$layout_tmp/out/rclone-v1.75.1/vendor/modules.txt" ]]
rm -rf "$layout_tmp"

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
printf 'rclone Android runtime identity + 16 KiB contract tests: PASS\n'
