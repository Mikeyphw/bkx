"""Runtime transfer Spec schema loaded from the packaged JSON snapshot."""

from __future__ import annotations

import json
from importlib.resources import files

_TRANSFER_RESOURCE = files(__package__).joinpath("transfer_spec.json")
TRANSFER_SPEC_SCHEMA = json.loads(_TRANSFER_RESOURCE.read_text(encoding="utf-8"))
