"""HasherPort - interface for computing checksums.

Contract rule:
- Services accept Specs, use ports for IO.
- Ports are pure interfaces with fallback implementations.
"""

from __future__ import annotations

from collections.abc import Callable, Iterable
from dataclasses import dataclass
from pathlib import Path
from typing import Any, BinaryIO, Literal, Protocol

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

    def hash_file(
        self,
        path: Path,
        algorithm: HashAlgorithm = "sha256",
        *,
        chunk_size: int = 8192,
    ) -> HashResult:
        """Compute hash of a single file.

        Args:
            path: Path to file.
            algorithm: Hash algorithm to use.
            chunk_size: Size of chunks to read.

        Returns:
            HashResult with checksum and metadata.
        """
        ...

    def hash_files(
        self,
        paths: tuple[Path, ...],
        algorithm: HashAlgorithm = "sha256",
        *,
        progress_callback: Callable[[Path, int, int], Any] | None = None,
    ) -> dict[Path, HashResult]:
        """Compute hashes of multiple files.

        Args:
            paths: Paths to files.
            algorithm: Hash algorithm to use.
            progress_callback: Optional callback (path, current, total).

        Returns:
            Dict mapping path to HashResult.
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
        stream: Iterable[bytes] | BinaryIO,
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
