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

for file in "$installer" "$runtime_contract" "$runtime_contract_test" "$app_build" "$gateway" "$manager" "$parser" "$models" "$remote_screen" "$transfer" "$browser" "$browser_models" "$more" "$backup" "$bridge" "$rclone_runtime" "$rclone_transfer" "$transfer_validation" "$transfer_models"; do
    require_file "$file"
done

require_text "$installer" 'RCLONE_VERSION="1.75.1"'
require_text "$installer" 'rclone-android-${RCLONE_ANDROID_API}-${RCLONE_ANDROID_ARCH}.gz'
require_text "$runtime_contract" 'GOOS=android'
require_text "$runtime_contract" 'GOARCH=arm64'
require_text "$installer" 'https://beta.rclone.org/v${RCLONE_VERSION}/testbuilds/${RCLONE_ARCHIVE}'
require_text "$installer" 'BKX_RCLONE_ANDROID_SHA256'
require_text "$installer" 'embedded version marker'
require_text "$installer" 'bkx_rclone_validate_build_info'
require_text "$installer" 'bkx_rclone_validate_version_output'
require_text "$runtime_contract" 'bkx_rclone_validate_build_info'
require_text "$runtime_contract" 'bkx_rclone_validate_version_output'
require_text "$app_build" 'scripts/rclone-runtime-contract.sh'
forbid_text "$installer" 'linux-arm64'
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

go test ./internal/rcloneops ./internal/transferops ./internal/services ./internal/bridge ./internal/validation
go vet ./internal/rcloneops ./internal/transferops ./internal/services ./internal/bridge ./internal/validation
printf 'OV4 focused Go validation: PASS\n'
