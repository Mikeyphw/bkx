#!/usr/bin/env bash
set -euo pipefail
repo="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$repo"
export PYTHONPATH="$repo/src${PYTHONPATH:+:$PYTHONPATH}"
export TZ=UTC

probe_root="${TMPDIR:-/tmp}/bkx-go16-$$"
mkdir -p "$probe_root"
trap 'rm -rf "$probe_root"' EXIT HUP INT TERM

echo '==> GO00-GO15 regression chain (reuse GO16 release build for Android seal)'
BKX_GO15_SKIP_ANDROID_RUNTIME=1 bash ./scripts/validate-go15.sh

echo '==> GO16 production identity / Python reference retirement contract'
python3 - <<'PYIDENTITY'
import tomllib
from pathlib import Path

devtool = tomllib.loads(Path('.devtool.toml').read_text(encoding='utf-8'))
assert devtool['project']['kind'] == 'go'
assert devtool['targets']['bkx']['type'] == 'go'
assert devtool['targets']['bkx_reference']['type'] == 'python'
assert 'bkx_go' not in devtool['targets']
assert devtool['python']['package']['console_scripts'] == []
assert devtool['python']['package']['verify_cli'] is False
project = tomllib.loads(Path('pyproject.toml').read_text(encoding='utf-8'))['project']
assert project['dependencies'] == []
assert 'scripts' not in project or 'bkx' not in project.get('scripts', {})
reference = '\n'.join(project['optional-dependencies']['reference']).lower()
for dep in ('typer', 'rich', 'pyyaml', 'jsonschema'):
    assert dep in reference
print('GO16 identity: Go production; Python reference-only; no Python bkx console script')
PYIDENTITY

echo '==> GO16 production binary + external UI plugin fail-closed behavior'
go build -trimpath -buildvcs=false -ldflags='-buildid= -X bkx/internal/version.Commit=go16-validation' -o "$probe_root/bkx" ./cmd/bkx
"$probe_root/bkx" --version
set +e
HOME="$probe_root/home" BKX_TUI_COMMAND=bkx-go16-definitely-missing "$probe_root/bkx" --json ui start --mode tui >"$probe_root/ui.json" 2>"$probe_root/ui.stderr"
ui_rc=$?
set -e
test "$ui_rc" -eq 5
python3 - "$probe_root/ui.json" <<'PYUI'
import json, sys
p = json.load(open(sys.argv[1], encoding='utf-8'))
assert p['type'] == 'error' and p['success'] is False
assert p['details']['feature'] == 'tui'
assert p['details']['plugin'] == 'bkx-tui'
assert p['details']['dependency'] == 'bkx-tui'
assert p['details']['env_override'] == 'BKX_TUI_COMMAND'
print('GO16 UI plugin absence: typed optional-feature exit 5')
PYUI

echo '==> GO16 deterministic host + Android release artifacts'
BKX_BUILD_COMMIT=go16-validation bash ./scripts/build-go-release.sh "$probe_root/release-a" >/dev/null
BKX_BUILD_COMMIT=go16-validation bash ./scripts/build-go-release.sh "$probe_root/release-b" >/dev/null
diff -u "$probe_root/release-a/SHA256SUMS" "$probe_root/release-b/SHA256SUMS"
(
  cd "$probe_root/release-a"
  sha256sum -c SHA256SUMS
  test "$(wc -l < SHA256SUMS)" -eq 2
  grep -Eq '  bkx-1\.0\.0-[^/]+-[^/]+$' SHA256SUMS
  grep -Fq '  android/arm64-v8a/libbkxgo.so' SHA256SUMS
)
while read -r _ rel; do
  cmp "$probe_root/release-a/$rel" "$probe_root/release-b/$rel"
done < "$probe_root/release-a/SHA256SUMS"
meta="$(go version -m "$probe_root/release-a/android/arm64-v8a/libbkxgo.so")"
printf '%s\n' "$meta" | grep -Eq '^[[:space:]]*build[[:space:]]+GOOS=android$'
printf '%s\n' "$meta" | grep -Eq '^[[:space:]]*build[[:space:]]+GOARCH=arm64$'
python3 - "$probe_root/release-a/android/arm64-v8a/libbkxgo.so" <<'PYELF'
from pathlib import Path
import sys
p = Path(sys.argv[1]).read_bytes()[:20]
assert p[:4] == b'\x7fELF'
bo = 'little' if p[5] == 1 else 'big' if p[5] == 2 else None
assert bo is not None
assert int.from_bytes(p[18:20], bo) == 183
print('GO16 Android release runtime: AArch64 ELF')
PYELF

echo 'BKX GO16 production Go identity / deterministic release gates passed.'
