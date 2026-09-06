#!/usr/bin/env bash
set -euo pipefail
repo="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
cd "$repo"

# GO17 is intentionally retained as the pure-Go compatibility/release seal.
bash ./scripts/validate-go17.sh

echo '==> GO18 repository Python source/package purge seal'
go test ./internal/compatfixtures -run '^TestRepositoryPythonPurgeSeal$' -count=1

echo '==> GO18 active source inventory'
if find . \
    -path './migration/archive' -prune -o \
    -path './.git' -prune -o \
    -path './.devtool' -prune -o \
    -path './artifacts' -prune -o \
    -path './dist' -prune -o \
    -path './build' -prune -o \
    -path '*/__pycache__' -prune -o \
    -path '*/.pytest_cache' -prune -o \
    -path '*/.mypy_cache' -prune -o \
    -path '*/.ruff_cache' -prune -o \
    -path '*/.pyright' -prune -o \
    -path '*/.venv' -prune -o \
    -path '*/venv' -prune -o \
    -type f \( -name '*.py' -o -name '*.pyc' \) -print | grep -q .; then
  echo 'GO18 active repository still contains Python source/bytecode' >&2
  exit 1
fi

for path in pyproject.toml setup.py setup.cfg mypy.ini pyrightconfig.json; do
  if [ -e "$path" ]; then
    echo "GO18 forbidden Python-era project file remains: $path" >&2
    exit 1
  fi
done
for path in src/bkx tests tools/go_migration; do
  if [ -d "$path" ] && find "$path" \
      -type d \( -name '__pycache__' -o -name '.pytest_cache' -o -name '.mypy_cache' -o -name '.ruff_cache' -o -name '.pyright' -o -name '.venv' -o -name 'venv' \) -prune -o \
      -type f -print -quit | grep -q .; then
    echo "GO18 forbidden Python-era active files remain under: $path" >&2
    exit 1
  fi
done

if grep -Eq 'bkx_reference|\[python\]|\[python\.package\]|python[[:space:]]*=[[:space:]]*"python3"' .devtool.toml; then
  echo 'GO18 Devtool configuration still exposes Python ownership' >&2
  exit 1
fi

phase="$(sed -nE 's/^[[:space:]]*MigrationPhase[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' internal/version/version.go | head -n1)"
case "$phase" in GO18|GO19|GO2[0-9]|GO[3-9][0-9]) ;; *) echo "GO18+ phase identity required, got: $phase" >&2; exit 1 ;; esac

echo 'BKX GO18 Python source/package purge and Go-only repository purity gates passed.'
