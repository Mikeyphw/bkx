#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

printf '==> OV4 structured remote transport replay\n'
scripts/verify-rclone-remote-transport.sh
printf '==> OV5 Google Drive product replay\n'
scripts/verify-google-drive-product.sh
printf '==> OV6 roadmap evidence\n'
for file in \
  docs/guide/05_RCLONE_REMOTE_TRANSPORT.md \
  docs/guide/06_GOOGLE_DRIVE_PRODUCT.md \
  docs/guide/07_ANDROID_PRODUCT_ROADMAP_SEAL.md \
  docs/checklists/CHECKLIST_ANDROID_REMOTE_TRANSPORT.md \
  docs/checklists/CHECKLIST_ANDROID_GOOGLE_DRIVE.md \
  android/BKX.Android/docs/OV56_GOOGLE_DRIVE_RELEASE_ACCEPTANCE.md; do
  test -f "$file" || { echo "missing roadmap evidence: $file" >&2; exit 1; }
done

grep -q 'OV1' docs/guide/07_ANDROID_PRODUCT_ROADMAP_SEAL.md
grep -q 'OV2' docs/guide/07_ANDROID_PRODUCT_ROADMAP_SEAL.md
grep -q 'OV3' docs/guide/07_ANDROID_PRODUCT_ROADMAP_SEAL.md
grep -q 'OV4' docs/guide/07_ANDROID_PRODUCT_ROADMAP_SEAL.md
grep -q 'OV5' docs/guide/07_ANDROID_PRODUCT_ROADMAP_SEAL.md
grep -q 'OV6' docs/guide/07_ANDROID_PRODUCT_ROADMAP_SEAL.md

printf '==> Go formatting/tests/vet\n'
test -z "$(gofmt -l internal/backupops internal/remotearchive internal/rcloneops internal/services internal/bridge)"
go test ./...
go vet ./...
printf 'BKX OV5+OV6 roadmap seal passed.\n'
