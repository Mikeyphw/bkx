#!/usr/bin/env bash
set -euo pipefail

: "${MIKOMN_ROOT:?Set MIKOMN_ROOT to a local mikomn checkout}"
[[ -d "$MIKOMN_ROOT" ]] || { echo "MIKOMN_ROOT is not a directory: $MIKOMN_ROOT" >&2; exit 2; }

python -m pip install -r scripts/validator-local-deps.txt
python -m pip install -e '.[interactive,tui]'
pytest -q tests/interactive tests/tui tests/contracts/test_optional_interface_parity_contract.py
