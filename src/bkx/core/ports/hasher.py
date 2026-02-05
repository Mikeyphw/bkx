"""HasherPort - interface for computing checksums.

Contract rule:
- Services accept Specs, use ports for IO.
- Ports are pure interfaces with fallback implementations.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass
from pathlib import Path
from typing import Literal, Protocol


# Type alias for hash algorithm identifiers
HashAlgorithm = Literal["md5", "sha256", "sha512", "xxh128", "xxh64"]


@dataclass(frozen=True)
class HashResult:
    """Result of a hash computation."""

    algorithm: HashAlgorithm
    checksum: str
    bytes_hashed: int
    error: str | None = None


class HasherPort(Protocol):
    """Protocol for checksum/hashing operations.

    Implement this to support different hash algorithms.
    Each implementation must have a fallback in core/impl/.
    """

    @property
    def name(self) -> str:
        """Human-readable name of this hasher."""
        ...

    @property
    def algorithm(self) -> str:
        """Hash algorithm identifier (e.g., 'sha256', 'xxh3')."""
        ...

    @property
    def digest_length(self) -> int:
        """Length of digest in bytes."""
        ...

    def hash_file(self, path: Path) -> str:
        """Compute hash of a single file.

        Args:
            path: Path to file.

        Returns:
            Hex digest string.
        """
        ...

    def hash_files(self, paths: tuple[Path, ...]) -> dict[Path, str]:
        """Compute hashes of multiple files.

        Args:
            paths: Paths to files.

        Returns:
            Dict mapping path to hex digest.
        """
        ...

    def hash_bytes(self, data: bytes) -> str:
        """Compute hash of bytes.

        Args:
            data: Bytes to hash.

        Returns:
            Hex digest string.
        """
        ...

    def verify(
        self,
        path: Path,
        expected: str,
        *,
        chunk_size: int = 64 * 1024,
    ) -> bool:
        """Verify file matches expected hash.

        Args:
            path: Path to file.
            expected: Expected hex digest.
            chunk_size: Bytes per read chunk.

        Returns:
            True if matches, False otherwise.
        """
        ...

    def hash_stream(
        self,
        stream: bytes,
        *,
        chunk_size: int = 64 * 1024,
    ) -> str:
        """Compute hash from a stream.

        Args:
            stream: Bytes iterator/stream.
            chunk_size: Bytes per chunk.

        Returns:
            Hex digest string.
        """
        ...


# Predefined hashers
SHA256 = "sha256"
SHA512 = "sha512"
XXH3 = "xxh3"
MD5 = "md5"
