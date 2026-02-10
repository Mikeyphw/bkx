"""Concrete implementations of ports (fallback implementations).

Contract rule:
- Must have a fallback implementation in core/impl/.

These implementations use only Python standard library features
and can be used when external tools are unavailable.
"""

from .archiver import FallbackArchiver
from .hasher import FallbackHasher
from .tool_detector import FallbackToolDetector
from .transfer import FallbackTransfer

__all__ = [
    "FallbackArchiver",
    "FallbackTransfer",
    "FallbackHasher",
    "FallbackToolDetector",
]
