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
from bkx.core.specs.archive import ArchiveCreateSpec
from bkx.core.specs.transfer import TransferSpec
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
    excludes: list[str] | None = None,
    includes: list[str] | None = None,
    split_size: int | None = None,
    algorithm: str = "zstd",
    level: int = 3,
    verify: bool = True,
    dry_run: bool = False,
) -> ArchiveCreateSpec:
    """Build ArchiveCreateSpec from CLI inputs.
    
    Args:
        sources: Source paths to archive
        output: Output file path
        excludes: Glob patterns to exclude
        includes: Glob patterns to include
        split_size: Optional chunk size for split archives
        algorithm: Compression algorithm
        level: Compression level
        verify: Whether to verify after creation
        dry_run: Whether this is a dry run
        
    Returns:
        Normalized ArchiveCreateSpec ready for service
    """
    spec = ArchiveCreateSpec(
        sources=tuple(sources),
        output_path=output,
        excludes=tuple(excludes) if excludes else tuple(),
        includes=tuple(includes) if includes else tuple(),
        split_size=split_size,
        compression=CompressionProfile(algorithm=algorithm, level=level),
        verify=verify,
        dry_run=dry_run,
    )
    
    return spec.normalize()


def build_transfer_spec(
    source: Path,
    destination: Path,
    *,
    direction: str = "push",
    verify: bool = True,
    checksum: str | None = None,
    dry_run: bool = False,
    overwrite: bool = False,
) -> TransferSpec:
    """Build TransferSpec from CLI inputs.
    
    Args:
        source: Source path
        destination: Destination path
        direction: Transfer direction (push or pull)
        verify: Whether to verify after transfer
        checksum: Expected checksum for verification
        dry_run: Whether this is a dry run
        overwrite: Whether to overwrite existing files
        
    Returns:
        Normalized TransferSpec ready for service
    """
    spec = TransferSpec(
        sources=(source,),
        destination=destination,
        direction=direction,
        verify=verify,
        checksum=checksum,
        dry_run=dry_run,
        overwrite=overwrite,
    )
    
    return spec.normalize()


def build_restore_spec(
    archive: Path,
    destination: Path | None = None,
    *,
    paths: list[str] | None = None,
    excludes: list[str] | None = None,
    overwrite: bool = False,
    verify: bool = True,
    dry_run: bool = False,
    fresh_dir: bool = True,
    confirm_overwrite: bool = False,  # Default to False to allow overwrite without error
) -> RestoreSpec:
    """Build RestoreSpec from CLI inputs.
    
    Args:
        archive: Path to archive to restore
        destination: Destination path
        paths: Specific paths to restore (for partial restore)
        excludes: Glob patterns to exclude
        overwrite: Whether to overwrite existing files
        verify: Whether to verify archive integrity before restore
        dry_run: Whether this is a dry run
        fresh_dir: Whether to create fresh directory by default
        confirm_overwrite: Whether to require confirmation for overwrite (CLI default False)
        
    Returns:
        Normalized RestoreSpec ready for service
    """
    spec = RestoreSpec(
        archive=archive,
        destination=destination,
        paths=tuple(paths) if paths else tuple(),
        overwrite=overwrite,
        verify=verify,
        dry_run=dry_run,
        fresh_dir=fresh_dir,
        confirm_overwrite=confirm_overwrite,
    )
    
    return spec.normalize(default_destination=destination)


def build_archive_browse_spec(
    archive: Path,
    *,
    query: str | None = None,
    file_type: str | None = None,
    min_size: int | None = None,
    max_size: int | None = None,
    limit: int = 50,
    offset: int = 0,
    sort_by: str = "path",
    sort_order: str = "asc",
) -> "ArchiveBrowseSpec":
    """Build ArchiveBrowseSpec from CLI inputs.
    
    Args:
        archive: Path to archive to browse
        query: Search query (matches path)
        file_type: Filter by type: "file", "dir"
        min_size: Minimum file size (bytes)
        max_size: Maximum file size (bytes)
        limit: Maximum results to return
        offset: Offset for pagination
        sort_by: Sort field: "path", "size", "mtime"
        sort_order: Sort order: "asc", "desc"
        
    Returns:
        Normalized ArchiveBrowseSpec ready for service
    """
    from bkx.core.specs.archive import ArchiveBrowseSpec
    
    spec = ArchiveBrowseSpec(
        archive_path=archive,
        query=query,
        file_type=file_type,
        min_size=min_size,
        max_size=max_size,
        limit=limit,
        offset=offset,
        sort_by=sort_by,
        sort_order=sort_order,
    )
    
    return spec.normalize()

