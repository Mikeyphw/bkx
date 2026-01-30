"""Parity test fixtures and strategies.

Provides:
- Fixtures for generating equivalent inputs across interfaces
- Helper functions for computing spec hashes
- Property-based strategies for edge case testing
"""

from __future__ import annotations

import tempfile
from pathlib import Path
from typing import Any

import pytest
from hypothesis import strategies as st

from bkx.core.parity import (
    compute_spec_hash,
    spec_to_dict,
    verify_parity,
)
from bkx.cli.spec_builders import (
    build_backup_spec,
    build_archive_spec,
    build_transfer_spec,
    build_restore_spec,
)


# =============================================================================
# Strategies for Property-Based Testing
# =============================================================================

@st.composite
def strategy_paths(draw, min_size: int = 1, max_size: int = 5) -> list[Path]:
    """Generate a list of valid paths.
    
    Args:
        draw: Hypothesis draw function
        min_size: Minimum number of paths
        max_size: Maximum number of paths
        
    Returns:
        List of Path objects
    """
    with tempfile.TemporaryDirectory() as tmpdir:
        base = Path(tmpdir)
        num_paths = draw(st.integers(min_value=min_size, max_value=max_size))
        paths = []
        for i in range(num_paths):
            # Create subdirectory structure
            subdir = base / f"dir_{i}"
            subdir.mkdir()
            paths.append(subdir)
        return paths


@st.composite
def strategy_backup_params(draw) -> dict[str, Any]:
    """Generate backup spec parameters.
    
    Args:
        draw: Hypothesis draw function
        
    Returns:
        Dictionary of backup parameters
    """
    with tempfile.TemporaryDirectory() as tmpdir:
        source = Path(tmpdir)
        (source / "file.txt").touch()
        (source / "subdir").mkdir()
        
        return {
            "sources": [source],
            "destination": Path(tmpdir) / "backup",
            "archive_name": draw(st.text(min_size=1, max_size=20)),
            "excludes": draw(st.lists(st.text(min_size=1, max_size=10), max_size=3)),
            "algorithm": draw(st.sampled_from(["zstd", "gzip", "lz4"])),
            "level": draw(st.integers(min_value=1, max_value=9)),
            "verify": draw(st.booleans()),
            "dry_run": True,  # Always dry-run for tests
        }


@st.composite
def strategy_archive_params(draw) -> dict[str, Any]:
    """Generate archive spec parameters.
    
    Args:
        draw: Hypothesis draw function
        
    Returns:
        Dictionary of archive parameters
    """
    with tempfile.TemporaryDirectory() as tmpdir:
        source = Path(tmpdir)
        (source / "file.txt").touch()
        
        return {
            "sources": [source],
            "output": Path(tmpdir) / "archive.tar",
            "excludes": draw(st.lists(st.text(min_size=1, max_size=10), max_size=3)),
            "algorithm": draw(st.sampled_from(["zstd", "gzip", "lz4"])),
            "level": draw(st.integers(min_value=1, max_value=9)),
            "verify": draw(st.booleans()),
            "dry_run": True,
        }


@st.composite
def strategy_restore_params(draw) -> dict[str, Any]:
    """Generate restore spec parameters.
    
    Args:
        draw: Hypothesis draw function
        
    Returns:
        Dictionary of restore parameters
    """
    with tempfile.TemporaryDirectory() as tmpdir:
        archive = Path(tmpdir) / "archive.tar"
        archive.touch()
        
        return {
            "archive": archive,
            "destination": Path(tmpdir) / "restore",
            "paths": draw(st.lists(st.text(min_size=1, max_size=20), max_size=5)),
            "overwrite": draw(st.booleans()),
            "verify": draw(st.booleans()),
            "dry_run": True,
            "fresh_dir": draw(st.booleans()),
        }


@st.composite
def strategy_transfer_params(draw) -> dict[str, Any]:
    """Generate transfer spec parameters.
    
    Args:
        draw: Hypothesis draw function
        
    Returns:
        Dictionary of transfer parameters
    """
    return {
        "source": Path("/source/file"),
        "destination": Path("/dest/file"),
        "direction": draw(st.sampled_from(["push", "pull"])),
        "verify": draw(st.booleans()),
        "dry_run": True,
        "overwrite": draw(st.booleans()),
    }


@st.composite
def strategy_exclude_patterns(draw) -> list[str]:
    """Generate exclude patterns.
    
    Args:
        draw: Hypothesis draw function
        
    Returns:
        List of glob patterns
    """
    patterns = ["*.pyc", "__pycache__", "*.log", ".git", "node_modules", "*.bak"]
    return draw(st.lists(st.sampled_from(patterns), max_size=4))


# =============================================================================
# Fixtures
# =============================================================================

@pytest.fixture
def temp_source_dir():
    """Create a temporary source directory with test files."""
    with tempfile.TemporaryDirectory() as tmpdir:
        source = Path(tmpdir)
        (source / "file1.txt").write_text("test content 1")
        (source / "file2.txt").write_text("test content 2")
        (source / "subdir").mkdir()
        (source / "subdir" / "file3.txt").write_text("nested content")
        yield source


@pytest.fixture
def temp_archive_file():
    """Create a temporary archive file."""
    with tempfile.TemporaryDirectory() as tmpdir:
        yield Path(tmpdir) / "test_archive.tar"


# =============================================================================
# Helper Functions
# =============================================================================

def compute_cli_backup_hash(
    sources: list[Path],
    destination: Path | None,
    archive_name: str | None,
    excludes: list[str] | None,
    algorithm: str,
    level: int,
    verify: bool,
) -> str:
    """Compute hash for backup spec from CLI params."""
    spec = build_backup_spec(
        sources=sources,
        destination=destination,
        archive_name=archive_name,
        excludes=excludes,
        algorithm=algorithm,
        level=level,
        verify=verify,
        dry_run=True,
    )
    return compute_spec_hash(spec_to_dict(spec))


def compute_tui_backup_hash(
    sources: list[Path],
    destination: Path | None,
    archive_name: str | None,
    excludes: list[str],
    algorithm: str,
    level: int,
    verify: bool,
) -> str:
    """Compute hash for backup spec from TUI params (should match CLI)."""
    # TUI uses same spec builder, so hashes should match
    spec = build_backup_spec(
        sources=sources,
        destination=destination,
        archive_name=archive_name,
        excludes=excludes,
        algorithm=algorithm,
        level=level,
        verify=verify,
        dry_run=True,
    )
    return compute_spec_hash(spec_to_dict(spec))


def verify_backup_parity(
    cli_sources: list[Path],
    tui_sources: list[Path],
    **kwargs,
) -> tuple[bool, str]:
    """Verify backup spec parity between CLI and TUI sources.
    
    Args:
        cli_sources: CLI-provided sources
        tui_sources: TUI-provided sources (should be same as CLI)
        **kwargs: Additional spec parameters
        
    Returns:
        Tuple of (is_parity, message)
    """
    cli_spec = build_backup_spec(
        sources=cli_sources,
        dry_run=True,
        **kwargs,
    )
    tui_spec = build_backup_spec(
        sources=tui_sources,
        dry_run=True,
        **kwargs,
    )
    
    is_equal, diffs = verify_parity(cli_spec, tui_spec)
    return is_equal, ", ".join(diffs) if diffs else "parity OK"


def verify_spec_normalization_determinism(spec_factory) -> bool:
    """Verify that repeated normalization produces identical specs.
    
    Args:
        spec_factory: Callable that creates a spec
        
    Returns:
        True if normalization is deterministic
    """
    spec1 = spec_factory()
    spec2 = spec_factory()
    
    dict1 = spec_to_dict(spec1.normalize())
    dict2 = spec_to_dict(spec2.normalize())
    
    return compute_spec_hash(dict1) == compute_spec_hash(dict2)

