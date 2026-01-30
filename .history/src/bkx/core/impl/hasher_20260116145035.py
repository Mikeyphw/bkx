"""FallbackHasher - pure Python hash computation.

Fallback implementation using zlib for hash operations.
"""

from __future__ import annotations

import hashlib
import time
from pathlib import Path
from typing import Literal

from ..ports.hasher import HashAlgorithm, HashResult
from ..results.hashing import HashingResult


class FallbackHasher:
    """Pure Python hasher using zlib.

    This is the fallback when external tools like xxh128 are unavailable.
    Supports MD5, SHA256, and SHA512.
    """

    def __init__(self) -> None:
        """Initialize the hasher."""
        self._name = "python-zlib"

    @property
    def name(self) -> str:
        """Human-readable name of this hasher."""
        return self._name

    @property
    def supported_algorithms(self) -> tuple[str, ...]:
        """List of supported hash algorithms."""
        return ("md5", "sha256", "sha512")

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
            Hash result with checksum.
        """
        algo = self._get_algo(algorithm)
        hasher = algo()

        bytes_hashed = 0

        with open(path, "rb") as f:
            while chunk := f.read(chunk_size):
                hasher.update(chunk)
                bytes_hashed += len(chunk)

        return HashResult(
            algorithm=algorithm,
            checksum=hasher.hexdigest(),
            bytes_hashed=bytes_hashed,
        )

    def hash_files(
        self,
        paths: tuple[Path, ...],
        algorithm: HashAlgorithm = "sha256",
        *,
        progress_callback: callable[[Path, int, int], None] | None = None,
    ) -> dict[Path, HashResult]:
        """Compute hashes for multiple files.

        Args:
            paths: Paths to files to hash.
            algorithm: Hash algorithm to use.
            progress_callback: Optional callback (path, current, total).

        Returns:
            Dict mapping path to hash result.
        """
        results = {}
        total = len(paths)

        for i, path in enumerate(paths, 1):
            if progress_callback:
                progress_callback(path, i, total)

            if path.is_file():
                results[path] = self.hash_file(path, algorithm)
            else:
                # Create a result for non-existent files
                results[path] = HashResult(
                    algorithm=algorithm,
                    checksum="",
                    bytes_hashed=0,
                    error="file not found",
                )

        return results

    def hash_directory(
        self,
        path: Path,
        algorithm: HashAlgorithm = "sha256",
        *,
        recursive: bool = True,
    ) -> HashingResult:
        """Compute hash of directory contents.

        Args:
            path: Path to directory.
            algorithm: Hash algorithm to use.
            recursive: Whether to recurse into subdirectories.

        Returns:
            Hashing result with all file hashes.
        """
        start_ms = int(time.time() * 1000)

        algo = self._get_algo(algorithm)
        dir_hasher = algo()

        file_hashes: dict[Path, HashResult] = {}
        total_bytes = 0

        paths_to_process = [path] if path.is_file() else []

        if path.is_dir():
            if recursive:
                for root, _, fnames in path.walk():
                    for fname in fnames:
                        fpath = Path(root) / fname
                        paths_to_process.append(fpath)
            else:
                for fname in path.iterdir():
                    if fname.is_file():
                        paths_to_process.append(fname)

        for fpath in paths_to_process:
            result = self.hash_file(fpath, algorithm)
            file_hashes[fpath] = result
            total_bytes += result.bytes_hashed

        # Compute directory hash by combining file hashes
        for fpath in sorted(file_hashes.keys()):
            dir_hasher.update(fpath.as_posix().encode())
            dir_hasher.update(file_hashes[fpath].checksum.encode())

        return HashingResult(
            success=True,
            algorithm=algorithm,
            root_path=path,
            file_count=len(file_hashes),
            total_bytes=total_bytes,
            file_hashes=file_hashes,
            directory_checksum=dir_hasher.hexdigest(),
            timing=total_bytes,  # Placeholder
        )

    def _get_algo(self, algorithm: HashAlgorithm):
        """Get hashlib algorithm by name."""
        algos = {
            "md5": hashlib.md5,
            "sha256": hashlib.sha256,
            "sha512": hashlib.sha512,
        }

        if algorithm not in algos:
            raise ValueError(f"Unsupported algorithm: {algorithm}")

        return algos[algorithm]

