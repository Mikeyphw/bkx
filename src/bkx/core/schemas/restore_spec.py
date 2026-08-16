"""Runtime restore Spec schema loaded from the packaged JSON snapshot."""

from __future__ import annotations

import json
from importlib.resources import files

_RESTORE_RESOURCE = files(__package__).joinpath("restore_spec.json")
RESTORE_SPEC_SCHEMA = json.loads(_RESTORE_RESOURCE.read_text(encoding="utf-8"))
