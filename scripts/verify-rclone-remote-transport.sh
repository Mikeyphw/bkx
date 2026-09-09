#!/usr/bin/env bash
set -euo pipefail

repo=${1:-.}
cd "$repo"

fail() { printf 'OV4 remote transport verification failed: %s\n' "$*" >&2; exit 1; }
require_file() { [[ -f "$1" ]] || fail "missing $1"; }
require_text() { grep -Fq -- "$2" "$1" || fail "$1 is missing contract token: $2"; }
forbid_text() { ! grep -Fq -- "$2" "$1" || fail "$1 contains forbidden token: $2"; }

installer=scripts/install-rclone-android-runtime.sh
runtime_contract=scripts/rclone-runtime-contract.sh
runtime_contract_test=scripts/test-rclone-runtime-contract.sh
native_page_verifier=scripts/verify-native-page-size.go
app_build=android/BKX.Android/app/build.gradle.kts
gateway=android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt
manager=android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/remotes/RcloneConfigManager.kt
parser=android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/remotes/RcloneConfigParser.kt
models=android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/remotes/RcloneModels.kt
remote_screen=android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/feature/more/RcloneRemotesScreen.kt
transfer=android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/feature/transfer/TransferScreen.kt
browser=android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/feature/home/FilesystemBrowserDialog.kt
browser_models=android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/files/FilesystemBrowserModels.kt
more=android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/feature/more/MoreScreen.kt
backup=android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/feature/backup/BackupScreen.kt
bridge=internal/bridge/bridge.go
rclone_runtime=internal/rcloneops/runtime.go
rclone_transfer=internal/rcloneops/transfer.go
transfer_validation=internal/validation/validation.go
transfer_models=internal/models/models.go

for file in "$installer" "$runtime_contract" "$runtime_contract_test" "$native_page_verifier" "$app_build" "$gateway" "$manager" "$parser" "$models" "$remote_screen" "$transfer" "$browser" "$browser_models" "$more" "$backup" "$bridge" "$rclone_runtime" "$rclone_transfer" "$transfer_validation" "$transfer_models"; do
    require_file "$file"
done

require_text "$installer" 'RCLONE_VERSION="1.75.1"'
require_text "$installer" 'RCLONE_ANDROID_API="24"'
require_text "$installer" 'RCLONE_SOURCE_ARCHIVE="rclone-v${RCLONE_VERSION}.tar.gz"'
require_text "$installer" 'RCLONE_VENDOR_ARCHIVE="rclone-v${RCLONE_VERSION}-vendor.tar.gz"'
require_text "$installer" 'RCLONE_SOURCE_SHA256="6ada25ecda8b3c8d971ed0aae1167e44d257dbcbf0be6598bf6c127daca2e13f"'
require_text "$installer" 'RCLONE_VENDOR_SHA256="a43cd21f71344ff96b3b00217196c069f2f28fa29835cd61cfa94edb1aa5b93b"'
require_text "$installer" 'RCLONE_RELEASE_BASE_URL="https://downloads.rclone.org/v${RCLONE_VERSION}"'
require_text "$installer" 'CGO_ENABLED=1'
require_text "$installer" 'env -u LD_PRELOAD'
require_text "$installer" 'ndk-termux-bash-shim'
require_text "$installer" 'rejected by Go/cgo + 16 KiB probe; trying next candidate'
require_text "$installer" 'termux-native-clang-ndk-sysroot'
require_text "$installer" 'make_termux_ndk_sysroot_shim'
require_text "$installer" 'go build -mod=vendor'
require_text "$installer" 'max-page-size=${BKX_ANDROID_PAGE_SIZE}'
require_text "$installer" 'common-page-size=${BKX_ANDROID_PAGE_SIZE}'
require_text "$installer" 'verify-native-page-size.go'
require_text "$native_page_verifier" 'rclone runtime is missing embedded version marker'
require_text "$installer" 'bkx_rclone_validate_build_info'
require_text "$installer" 'bkx_rclone_validate_version_output'
require_text "$runtime_contract" 'bkx_rclone_validate_build_info'
require_text "$runtime_contract" 'bkx_rclone_validate_version_output'
require_text "$runtime_contract" 'CGO_ENABLED=1'
require_text "$app_build" 'scripts/rclone-runtime-contract.sh'
require_text "$app_build" 'androidComponents.sdkComponents.ndkDirectory'
require_text "$app_build" 'verifyBkx16KbNativePageSizeContract'
require_text "$app_build" 'verify-native-page-size.go'
require_text "$native_page_verifier" 'archive-safe'
require_text "$native_page_verifier" 'APK native-page-size contract'
forbid_text "$installer" 'beta.rclone.org'
forbid_text "$installer" 'linux-arm64'
forbid_text "$installer" 'python3'
forbid_text "$app_build" 'python3'
require_text "$app_build" 'installBkxRcloneAndroidRuntime'
require_text "$app_build" 'generated/bkx-rclone-runtime/jniLibs'
require_text "$gateway" 'BKX_RCLONE_PATH'
require_text "$gateway" 'BKX_RCLONE_CONFIG'
require_text "$gateway" 'libbkxrclone_exec.so'

require_text "$manager" 'filesDir.resolve("bkx/rclone")'
require_text "$manager" 'MAX_RCLONE_CONFIG_BYTES'
require_text "$manager" 'RCLONE_ENCRYPT_V0:'
require_text "$manager" 'setReadable(true, true)'
require_text "$parser" 'SAFE_RCLONE_REMOTE_NAME'
require_text "$models" 'val rootUri: String get() = "rclone://$name/"'
require_text "$remote_screen" 'Import rclone.conf'
require_text "$remote_screen" 'remote.rclone.configured'
forbid_text "$models" 'token'
forbid_text "$models" 'password'

require_text "$bridge" 'remote.rclone'
require_text "$bridge" 'transfer.remote'
require_text "$bridge" 'transfer.sync'
require_text "$rclone_runtime" 'sanitizeDiagnostic'
require_text "$rclone_transfer" 'copyto'
require_text "$rclone_transfer" '"check"'
require_text "$transfer_validation" 'DeleteExtraneousOnSync'
require_text "$transfer_models" 'delete_extraneous_on_sync'
require_text "$browser_models" 'allowRemote'
require_text "$browser" 'rclone://'
require_text "$transfer" 'Browse remote'
require_text "$transfer" 'Manual path · Advanced'

require_text "$more" 'Build.VERSION.SDK_INT >= Build.VERSION_CODES.O'
require_text "$more" 'ACTION_APPLICATION_DETAILS_SETTINGS'
require_text "$backup" 'getAllocatableBytes'
require_text "$backup" 'getUuidForPath'
require_text "$backup" 'StatFs'
forbid_text "$backup" '.usableSpace'

bash "$runtime_contract_test"
printf 'OV4 static remote transport contract: PASS\n'

if [[ "${BKX_VERIFY_STATIC_ONLY:-0}" == "1" ]]; then
    printf 'OV4 focused Go validation: skipped by BKX_VERIFY_STATIC_ONLY=1\n'
    exit 0
fi

go test ./internal/rcloneops ./internal/transferops ./internal/services ./internal/bridge ./internal/validation
go vet ./internal/rcloneops ./internal/transferops ./internal/services ./internal/bridge ./internal/validation
printf 'OV4 focused Go validation: PASS\n'
