#!/usr/bin/env bash
set -euo pipefail
python -m pip install -U pip
pip install -e .
pip install ruff pyright pytest
ruff check .
ruff format .
pyright
pytest
