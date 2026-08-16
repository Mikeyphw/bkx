"""Runtime backup Spec schema loaded from the packaged JSON snapshot."""

from __future__ import annotations

import json
from importlib.resources import files

_BACKUP_RESOURCE = files(__package__).joinpath("backup_spec.json")
BACKUP_SPEC_SCHEMA = json.loads(_BACKUP_RESOURCE.read_text(encoding="utf-8"))
