#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
fail() { printf 'BKX Google Drive verification failed: %s\n' "$*" >&2; exit 1; }

MANIFEST=android/BKX.Android/app/src/main/AndroidManifest.xml
AUTH=android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/drive/GoogleDriveAuthorizationManager.kt
CONFIG=android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/drive/GoogleDriveRcloneConfig.kt
MANAGER=android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/remotes/RcloneConfigManager.kt
SCREEN=android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/feature/more/GoogleDriveScreen.kt
VIEWMODEL=android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/app/BkxAppViewModel.kt
BACKUP=internal/backupops/backup.go
DISPATCH=internal/services/dispatcher.go
BRIDGE=internal/bridge/bridge.go
CATALOG=android/BKX.Android/gradle/libs.versions.toml

DRIVE_TEST=android/BKX.Android/app/src/test/java/com/mikeyphw/bkx/android/contracts/GoogleDriveProductContractTest.kt
CONFIG_TEST=android/BKX.Android/app/src/test/java/com/mikeyphw/bkx/android/contracts/GoogleDriveRcloneConfigTest.kt
ROADMAP_TEST=android/BKX.Android/app/src/test/java/com/mikeyphw/bkx/android/contracts/Ov56RoadmapSealContractTest.kt
REMOTE_BACKUP_TEST=internal/backupops/backup_remote_test.go
REMOTE_ARCHIVE_TEST=internal/remotearchive/materialize_test.go

for file in "$MANIFEST" "$AUTH" "$CONFIG" "$MANAGER" "$SCREEN" "$VIEWMODEL" "$BACKUP" "$DISPATCH" "$BRIDGE" "$CATALOG" "$DRIVE_TEST" "$CONFIG_TEST" "$ROADMAP_TEST" "$REMOTE_BACKUP_TEST" "$REMOTE_ARCHIVE_TEST"; do
  [[ -f "$file" ]] || fail "missing $file"
done

grep -q 'android.permission.INTERNET' "$MANIFEST" || fail 'Android app lacks INTERNET permission for packaged rclone networking'
grep -q 'Identity.getAuthorizationClient' "$AUTH" || fail 'AuthorizationClient is not wired'
grep -q 'Scope(Scopes.DRIVE_FILE)' "$AUTH" || fail 'drive.file scope is not wired'
grep -q 'RevokeAccessRequest.builder()' "$AUTH" || fail 'Drive disconnect does not revoke access'
! grep -q 'GoogleSignInOptions\|GoogleSignInClient' "$AUTH" || fail 'legacy Google Sign-In API returned'
grep -q 'playServicesAuth = "21.6.0"' "$CATALOG" || fail 'Google Play services auth lane is not pinned to the verified stable version'
grep -q 'rootUri: String = "rclone://\$BKX_GOOGLE_DRIVE_REMOTE_NAME/BKX"' "$CONFIG" || fail 'managed Drive root is missing'
grep -q 'BKX_GOOGLE_DRIVE_REMOTE_NAME = "bkx-google-drive"' android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/remotes/RcloneModels.kt || fail 'managed Drive remote name is not reserved'
grep -q 'scope = drive.file' "$CONFIG" || fail 'rclone Drive scope is not drive.file'
grep -q 'preserveManagedSection' "$MANAGER" || fail 'imported remotes are not preserved around managed Drive config'
grep -q 'Back up to Google Drive' "$SCREEN" || fail 'first-class Drive backup action is missing'
grep -q 'Open Drive archives' "$SCREEN" || fail 'Drive archive/restore entry point is missing'
grep -q 'authorizeGoogleDriveForOperation' "$VIEWMODEL" || fail 'managed Drive operations do not refresh authorization'
grep -q 'Remote archive verification failed after upload' "$BACKUP" || fail 'remote backup lacks final logical verification'
grep -q 'materializeArchive' "$DISPATCH" || fail 'remote archive/restore materialization is missing'
for cap in backup.remote archive.remote restore.remote cloud.google_drive; do grep -q "\"$cap\"" "$BRIDGE" || fail "missing capability $cap"; done
! grep -qiE 'accessToken|refreshToken|password' android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/drive/GoogleDriveModels.kt || fail 'Drive UI model exposes secret-bearing state'
grep -q 'TestCreateRemoteRefusesExistingLogicalArchiveWithoutChangingIt' "$REMOTE_BACKUP_TEST" || fail 'remote collision preservation regression is missing'
grep -q 'TestCreateRemoteSplitPublishesSidecarsAndFailureRollsBackEarlierParts' "$REMOTE_BACKUP_TEST" || fail 'partial split upload rollback regression is missing'
grep -q 'TestCreateRemotePostUploadVerificationFailureRollsBackPublishedObjects' "$REMOTE_BACKUP_TEST" || fail 'post-upload verification rollback regression is missing'
grep -q 'TestAcquireRejectsMissingMiddleSplitPart' "$REMOTE_ARCHIVE_TEST" || fail 'missing split-part materialization regression is missing'
grep -q 'toUri()' "$VIEWMODEL" || fail 'OV4 UseKtx warning cleanup is missing'

GOWORK="${GOWORK:-off}" go test ./internal/backupops ./internal/remotearchive ./internal/rcloneops ./internal/services ./internal/bridge
printf 'BKX Google Drive product verification passed.\n'
