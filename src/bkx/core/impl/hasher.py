"""Pure Python and command-backed hash computation."""

from __future__ import annotations

import hashlib
import os
import subprocess
import tempfile
import time
from collections.abc import Callable, Iterable
from pathlib import Path
from typing import Any, BinaryIO

from bkx.core.errors import ExternalToolError, NotFoundError, ValidationError, expected_error_boundary

from ..ports.hasher import HashAlgorithm, HashResult
from ..results.hashing import HashingResult
from .tool_detector import get_tool_detector
from .traversal import build_source_manifest

_HASHLIB_ALGOS: dict[str, Any] = {
    "md5": hashlib.md5,
    "sha256": hashlib.sha256,
    "sha512": hashlib.sha512,
}
_COMMAND_ALGORITHMS: dict[str, str] = {
    "md5sum": "md5",
    "sha256sum": "sha256",
    "sha512sum": "sha512",
    "xxh64": "xxh64",
    "xxh128": "xxh128",
}


class FallbackHasher:
    """Pure Python hasher with deterministic source-tree semantics."""

    def __init__(self, *, name: str = "python-zlib") -> None:
        self._name = name

    @property
    def name(self) -> str:
        return self._name

    @property
    def algorithm(self) -> str:
        return "sha256"

    @property
    def digest_length(self) -> int:
        return 32

    @property
    def supported_algorithms(self) -> tuple[str, ...]:
        return tuple(_HASHLIB_ALGOS)

    def hash_file(
        self,
        path: Path,
        algorithm: HashAlgorithm = "sha256",
        *,
        chunk_size: int = 8192,
    ) -> HashResult:
        chunk_size = _validate_chunk_size(chunk_size)
        algo = self._get_algo(algorithm)
        raw_path = path.expanduser()
        if raw_path.is_symlink():
            raise ValidationError(msg="Hash source must be a regular non-symlink file", details={"path": str(raw_path)})
        target = raw_path.resolve(strict=False)
        if not target.exists():
            raise NotFoundError(msg=f"File not found for hashing: {target}", details={"path": str(target)})
        if not target.is_file():
            raise ValidationError(msg="Hash source must be a regular non-symlink file", details={"path": str(target)})
        hasher = algo()
        bytes_hashed = 0
        with expected_error_boundary("hashing file", resource=str(target), backend=self.name):
            with open(target, "rb") as f:
                while chunk := f.read(chunk_size):
                    hasher.update(chunk)
                    bytes_hashed += len(chunk)
        return HashResult(algorithm=algorithm, checksum=hasher.hexdigest(), bytes_hashed=bytes_hashed)

    def hash_files(
        self,
        paths: tuple[Path, ...],
        algorithm: HashAlgorithm = "sha256",
        *,
        progress_callback: Callable[[Path, int, int], Any] | None = None,
    ) -> dict[Path, HashResult]:
        if not isinstance(paths, tuple):
            raise ValidationError(msg="paths must be a tuple")
        results: dict[Path, HashResult] = {}
        total = len(paths)
        for i, path in enumerate(paths, 1):
            if progress_callback:
                progress_callback(path, i, total)
            results[path] = self.hash_file(path, algorithm)
        return results

    def hash_directory(
        self,
        path: Path,
        algorithm: HashAlgorithm = "sha256",
        *,
        recursive: bool = True,
    ) -> HashingResult:
        if not recursive and path.is_dir():
            files = tuple(child for child in sorted(path.iterdir()) if child.is_file())
            manifest = build_source_manifest(files or (path,), symlink_policy="reject") if files else build_source_manifest((path,), symlink_policy="reject")
            entries = tuple(e for e in manifest.files if e.path.parent == path.resolve(strict=False))
        else:
            manifest = build_source_manifest((path,), symlink_policy="reject")
            entries = manifest.files

        start = time.perf_counter_ns()
        algo = self._get_algo(algorithm)
        dir_hasher = algo()
        file_hashes: dict[Path, HashResult] = {}
        total_bytes = 0
        for entry in sorted(entries, key=lambda e: e.archive_name):
            result = self.hash_file(entry.path, algorithm)
            file_hashes[entry.path] = result
            total_bytes += result.bytes_hashed
            dir_hasher.update(entry.archive_name.encode("utf-8"))
            dir_hasher.update(b"\0")
            dir_hasher.update(result.checksum.encode("ascii"))
            dir_hasher.update(b"\0")
        elapsed_ms = max(0, int((time.perf_counter_ns() - start) / 1_000_000))
        return HashingResult(
            success=True,
            algorithm=algorithm,
            root_path=path.expanduser().resolve(strict=False),
            file_count=len(file_hashes),
            total_bytes=total_bytes,
            file_hashes=file_hashes,
            directory_checksum=dir_hasher.hexdigest(),
            timing_ms=elapsed_ms,
        )

    def _get_algo(self, algorithm: HashAlgorithm):
        if algorithm not in _HASHLIB_ALGOS:
            raise ValidationError(
                msg="Unsupported hash algorithm for pure Python hasher",
                details={"algorithm": algorithm, "supported": sorted(_HASHLIB_ALGOS)},
            )
        return _HASHLIB_ALGOS[algorithm]

    def hash_bytes(self, data: bytes) -> str:
        if not isinstance(data, (bytes, bytearray, memoryview)):
            raise TypeError("data must be bytes-like")
        hasher = hashlib.sha256()
        hasher.update(bytes(data))
        return hasher.hexdigest()

    def verify(self, path: Path, expected: str, *, chunk_size: int = 64 * 1024) -> bool:
        return self.hash_file(path, chunk_size=chunk_size).checksum.lower() == expected.lower()

    def hash_stream(self, stream: Iterable[bytes] | BinaryIO, *, chunk_size: int = 64 * 1024) -> str:
        chunk_size = _validate_chunk_size(chunk_size)
        hasher = hashlib.sha256()
        read = getattr(stream, "read", None)
        if callable(read):
            while True:
                chunk = read(chunk_size)
                if not chunk:
                    break
                _update_bytes(hasher, chunk, "binary stream read() must return bytes-like chunks")
        else:
            for chunk in stream:
                _update_bytes(hasher, chunk, "hash stream iterable must yield bytes-like chunks")
        return hasher.hexdigest()


class CommandHasher(FallbackHasher):
    """Hash files with a detected external hash command when selected."""

    def __init__(self, tool_path: Path, algorithm: HashAlgorithm) -> None:
        super().__init__(name=tool_path.name)
        self._tool_path = tool_path
        self._algorithm = algorithm

    @property
    def algorithm(self) -> str:
        return self._algorithm

    @property
    def supported_algorithms(self) -> tuple[str, ...]:
        return (self._algorithm,)

    def hash_file(self, path: Path, algorithm: HashAlgorithm = "sha256", *, chunk_size: int = 8192) -> HashResult:
        del chunk_size
        if algorithm != self._algorithm:
            raise ValidationError(msg="Selected external hasher cannot provide requested algorithm", details={"requested": algorithm, "available": self._algorithm})
        raw_path = path.expanduser()
        if raw_path.is_symlink():
            raise ValidationError(msg="Hash source must be a regular non-symlink file", details={"path": str(raw_path)})
        target = raw_path.resolve(strict=False)
        if not target.exists():
            raise NotFoundError(msg=f"File not found for hashing: {target}", details={"path": str(target)})
        if not target.is_file():
            raise ValidationError(msg="Hash source must be a regular non-symlink file", details={"path": str(target)})
        try:
            result = subprocess.run([str(self._tool_path), str(target)], capture_output=True, text=True, timeout=10, check=False)
        except (subprocess.TimeoutExpired, OSError) as exc:
            raise ExternalToolError(msg=f"Hash command failed: {exc}", tool_name=self.name, details={"path": str(target), "reason": str(exc)}) from exc
        if result.returncode != 0:
            raise ExternalToolError(msg="Hash command returned non-zero", tool_name=self.name, exit_code=result.returncode, stderr=result.stderr, details={"path": str(target)})
        checksum = result.stdout.strip().split()[0] if result.stdout.strip() else ""
        if not checksum:
            raise ExternalToolError(msg="Hash command produced no checksum", tool_name=self.name, details={"path": str(target)})
        return HashResult(algorithm=algorithm, checksum=checksum, bytes_hashed=target.stat().st_size)

    def hash_stream(self, stream: Iterable[bytes] | BinaryIO, *, chunk_size: int = 64 * 1024) -> str:
        # Most checksum commands are file oriented. Preserve the public stream
        # contract by writing chunks to a bounded temporary file and hashing it.
        chunk_size = _validate_chunk_size(chunk_size)
        with tempfile.NamedTemporaryFile(delete=True) as tmp:
            read = getattr(stream, "read", None)
            if callable(read):
                while True:
                    chunk = read(chunk_size)
                    if not chunk:
                        break
                    if not isinstance(chunk, (bytes, bytearray, memoryview)):
                        raise TypeError("binary stream read() must return bytes-like chunks")
                    tmp.write(bytes(chunk))
            else:
                for chunk in stream:
                    if not isinstance(chunk, (bytes, bytearray, memoryview)):
                        raise TypeError("hash stream iterable must yield bytes-like chunks")
                    tmp.write(bytes(chunk))
            tmp.flush()
            return self.hash_file(Path(tmp.name), self._algorithm).checksum


def _validate_chunk_size(chunk_size: int) -> int:
    if isinstance(chunk_size, bool) or not isinstance(chunk_size, int) or chunk_size <= 0:
        raise ValueError("chunk_size must be a positive integer")
    return chunk_size


def _update_bytes(hasher: Any, chunk: object, message: str) -> None:
    if not isinstance(chunk, (bytes, bytearray, memoryview)):
        raise TypeError(message)
    hasher.update(bytes(chunk))


def get_hasher() -> FallbackHasher:
    """Get the configured hasher, falling back to pure Python."""
    detector = get_tool_detector()
    preferred = detector.get_preferred("hashing")
    if preferred:
        info = detector.detect(preferred)
        algorithm = _COMMAND_ALGORITHMS.get(preferred)
        if info.available and info.path is not None and algorithm is not None:
            return CommandHasher(info.path, algorithm)  # type: ignore[arg-type]
    return FallbackHasher()
