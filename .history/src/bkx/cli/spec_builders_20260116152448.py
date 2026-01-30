"""CLI -> Spec builders.

Contract rule:
- Interfaces translate inputs -> Specs.
- Deterministic normalization lives in Specs.

These builders bridge the CLI layer to the core specs layer.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from bkx.core.specs.backup import BackupSpec, CompressionProfile
from bkx.core.specs.archive import ArchiveCreateSpec, ArchiveType
from bkx.core.specs.transfer import TransferSpec, TransferDirection
from bkx.core.specs.restore import RestoreSpec


def build_backup_spec(
    sources: list[Path],
    *,
    destination: Path | None = None,
    archive_name: str | None = None,
    excludes: list[str] | None = None,
    includes: list[str] | None = None,
    split_size: int | None = None,
    algorithm: str = "zstd",
    level: int = 3,
    verify: bool = True,
    dry_run: bool = False,
) -> BackupSpec:
    """Build BackupSpec from CLI inputs.
    
    Args:
        sources: Source paths to back up
        destination: Optional destination path
        archive_name: Optional explicit archive name
        excludes: Glob patterns to exclude
        includes: Glob patterns to include
        split_size: Optional chunk size for split archives
        algorithm: Compression algorithm (zstd, gzip, none)
        level: Compression level
        verify: Whether to verify after creation
        dry_run: Whether this is a dry run
        
    Returns:
        Normalized BackupSpec ready for service
    """
    spec = BackupSpec(
        sources=tuple(sources),
        destination=destination,
        archive_name=archive_name,
        excludes=tuple(excludes) if excludes else tuple(),
        includes=tuple(includes) if includes else tuple(),
        split_size=split_size,
        compression=CompressionProfile(algorithm=algorithm, level=level),
        verify=verify,
        dry_run=dry_run,
    )
    
    # Normalize with auto-naming
    return spec.normalize(default_destination=destination, auto_name=True)


def build_archive_spec(
    sources: list[Path],
    output: Path,
    *,
    archive_type: str = " bakery",
    excludes: list[str] | None = None,
    includes: list[str] | None = None,
    split_size: int | None = None,
    algorithm: str = "zstd",
    level: int = 3,
    dry_run: bool = False,
) -> ArchiveCreateSpec:
    """Build ArchiveCreateSpec from CLI inputs.
    
    Args:
        sources: Source paths to archive
        output: Output file path
        archive_type: Type of archive (bakery, tar, zip)
        excludes: Glob patterns to exclude
        includes: Glob patterns to include
        split_size: Optional chunk size for split archives
        algorithm: Compression algorithm
        level: Compression level
        dry_run: Whether this is a dry run
        
    Returns:
        Normalized ArchiveCreateSpec ready for service
    """
    # Map string to ArchiveType enum
    archive_type_map = {
        "bakery": ArchiveType.BAKERY,
        "tar": ArchiveType.TAR,
        "zip": ArchiveType.ZIP,
    }
    atype = archive_type_map.get(archive_type, ArchiveType.BAKERY)
    
    spec = ArchiveCreateSpec(
        sources=tuple(sources),
        output=output,
        archive_type=atype,
        excludes=tuple(excludes) if excludes else tuple(),
        includes=tuple(includes) if includes else tuple(),
        split_size=split_size,
        compression=CompressionProfile(algorithm=algorithm, level=level),
        dry_run=dry_run,
    )
    
    return spec.normalize()


def build_transfer_spec(
    source: Path,
    destination: str,
    *,
    direction: str = "push",
    algorithm: str = "zstd",
    level: int = 3,
    verify: bool = True,
    dry_run: bool = False,
    **extra: Any,
) -> TransferSpec:
    """Build TransferSpec from CLI inputs.
    
    Args:
        source: Source path (local or remote URL)
        destination: Destination path (local or remote URL)
        direction: Transfer direction (push or pull)
        algorithm: Compression algorithm
        level: Compression level
        verify: Whether to verify after transfer
        dry_run: Whether this is a dry run
        **extra: Additional transport options
        
    Returns:
        Normalized TransferSpec ready for service
    """
    # Map string to TransferDirection enum
    direction_map = {
        "push": TransferDirection.PUSH,
        "pull": TransferDirection.PULL,
    }
    dir_enum = direction_map.get(direction, TransferDirection.PUSH)
    
    spec = TransferSpec(
        source=source,
        destination=destination,
        direction=dir_enum,
        compression=CompressionProfile(algorithm=algorithm, level=level),
        verify=verify,
        dry_run=dry_run,
        options=extra,
    )
    
    return spec.normalize()


def build_restore_spec(
    archive: Path,
    destination: Path,
    *,
    includes: list[str] | None = None,
    excludes: list[str] | None = None,
    strip_components: int = 0,
    dry_run: bool = False,
) -> RestoreSpec:
    """Build RestoreSpec from CLI inputs.
    
    Args:
        archive: Path to archive to restore
        destination: Destination path
        includes: Optional glob patterns to include (for pick mode)
        excludes: Optional glob patterns to exclude
        strip_components: Number of path components to strip
        dry_run: Whether this is a dry run
        
    Returns:
        Normalized RestoreSpec ready for service
    """
    spec = RestoreSpec(
        archive=archive,
        destination=destination,
        includes=tuple(includes) if includes else tuple(),
        excludes=tuple(excludes) if excludes else tuple(),
        strip_components=strip_components,
        dry_run=dry_run,
    )
    
    return spec.normalize()

