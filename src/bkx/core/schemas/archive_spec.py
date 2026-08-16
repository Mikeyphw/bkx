"""Runtime archive Spec schema loaded from the packaged JSON snapshot."""

from __future__ import annotations

import json
from importlib.resources import files

_ARCHIVE_RESOURCE = files(__package__).joinpath("archive_spec.json")
ARCHIVE_SPEC_SCHEMA = json.loads(_ARCHIVE_RESOURCE.read_text(encoding="utf-8"))
