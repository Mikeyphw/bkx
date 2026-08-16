from __future__ import annotations

import hashlib
import os
import sys
from pathlib import Path

import pytest

from bkx.core.errors import NotFoundError, ValidationError
from bkx.core.impl.hasher import FallbackHasher
from bkx.core.impl.tool_detector import FallbackToolDetector


def _write_executable(path: Path, text: str) -> None:
    path.write_text(text)
    path.chmod(path.stat().st_mode | 0o111)


def test_tool_detector_parses_real_version_and_invalidates_path_cache(tmp_path: Path) -> None:
    first = tmp_path / "first"
    second = tmp_path / "second"
    first.mkdir()
    second.mkdir()
    _write_executable(first / "sha256sum", "#!/bin/sh\necho 'sha256sum (fake) 1.2.3'\n")
    _write_executable(second / "sha256sum", "#!/bin/sh\necho 'sha256sum (fake) 9.8.7'\n")

    environ = {"PATH": str(first)}
    detector = FallbackToolDetector(environ=environ, timeout_seconds=1)
    assert detector.detect("sha256sum").version == "1.2.3"

    environ["PATH"] = str(second)
    assert detector.detect("sha256sum").version == "9.8.7"


def test_tool_detector_uses_only_executable_tools_and_configured_preference(tmp_path: Path) -> None:
    tools = tmp_path / "tools"
    tools.mkdir()
    non_exec = tools / "rsync"
    non_exec.write_text("#!/bin/sh\necho nope\n")
    detector = FallbackToolDetector(preferences={"transfer": "rsync"}, environ={"PATH": str(tools)})
    assert detector.detect("rsync").available is False
    assert detector.get_preferred("transfer") is None

    non_exec.chmod(non_exec.stat().st_mode | 0o111)
    detector.clear_cache()
    assert detector.detect("rsync").available is True
    assert detector.get_preferred("transfer") == "rsync"


def test_hash_stream_validates_binary_chunks_and_positive_chunk_size() -> None:
    hasher = FallbackHasher()
    assert hasher.hash_stream([b"a", bytearray(b"b"), memoryview(b"c")]) == hashlib.sha256(b"abc").hexdigest()
    with pytest.raises(ValueError):
        hasher.hash_stream([b"x"], chunk_size=0)
    with pytest.raises(TypeError):
        hasher.hash_stream(["not bytes"])  # type: ignore[list-item]


def test_hash_file_missing_and_symlink_sources_fail_deterministically(tmp_path: Path) -> None:
    hasher = FallbackHasher()
    with pytest.raises(NotFoundError):
        hasher.hash_file(tmp_path / "missing.txt")

    target = tmp_path / "target.txt"
    target.write_text("secret")
    link = tmp_path / "link.txt"
    try:
        link.symlink_to(target)
    except (OSError, NotImplementedError):
        pytest.skip("symlinks unavailable")
    with pytest.raises(ValidationError):
        hasher.hash_file(link)


def test_directory_hash_is_deterministic_and_rejects_source_symlink(tmp_path: Path) -> None:
    root = tmp_path / "root"
    root.mkdir()
    (root / "b.txt").write_text("b")
    (root / "a.txt").write_text("a")
    hasher = FallbackHasher()
    first = hasher.hash_directory(root)
    second = hasher.hash_directory(root)
    assert first.directory_checksum == second.directory_checksum
    assert first.file_count == 2
    assert first.timing_ms >= 0

    outside = tmp_path / "outside.txt"
    outside.write_text("outside")
    try:
        (root / "escape").symlink_to(outside)
    except (OSError, NotImplementedError):
        pytest.skip("symlinks unavailable")
    with pytest.raises(ValidationError):
        hasher.hash_directory(root)
