#!/usr/bin/env bash
set -euo pipefail
repo="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
out_root="${1:?usage: build-go-android-runtime.sh OUTPUT_JNILIBS_DIR}"
out="$out_root/arm64-v8a/libbkxgo.so"
commit="${BKX_BUILD_COMMIT:-dev}"
mkdir -p "$(dirname -- "$out")"
tmp="$(mktemp "${TMPDIR:-/tmp}/bkx-go-android-runtime.XXXXXX")"
trap 'rm -f "$tmp"' EXIT HUP INT TERM
(
  cd "$repo"
  CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build \
    -trimpath \
    -buildvcs=false \
    -ldflags="-buildid= -X bkx/internal/version.Commit=$commit" \
    -o "$tmp" ./cmd/bkx
)
meta="$(go version -m "$tmp")"
printf '%s\n' "$meta" | grep -Eq '^[[:space:]]*build[[:space:]]+GOOS=android$'
printf '%s\n' "$meta" | grep -Eq '^[[:space:]]*build[[:space:]]+GOARCH=arm64$'
python3 - "$tmp" <<'PYELF'
from pathlib import Path
import sys
p = Path(sys.argv[1]).read_bytes()[:20]
if len(p) < 20 or p[:4] != b'\x7fELF':
    raise SystemExit('BKX Go Android runtime is not ELF')
bo = 'little' if p[5] == 1 else 'big' if p[5] == 2 else None
if bo is None:
    raise SystemExit('BKX Go Android runtime has invalid ELF byte order')
machine = int.from_bytes(p[18:20], bo)
if machine != 183:
    raise SystemExit(f'BKX Go Android runtime e_machine={machine}, expected AArch64 (183)')
PYELF
chmod 0755 "$tmp"
mv -f "$tmp" "$out"
trap - EXIT HUP INT TERM
printf '%s\n' "$out"
