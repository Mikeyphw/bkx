"""CLI -> Spec builders.

Contract rule:
- Interfaces translate inputs -> Specs.
- Deterministic normalization lives in Specs.

These builders bridge the CLI layer to the core specs layer.
- Config values provide defaults (flags > env > config file > hardcoded defaults)
"""

from __future__ import annotations

from pathlib import Path

from bkx.core.config import get_config
from bkx.core.specs.archive import ArchiveBrowseSpec, ArchiveCreateSpec, ArchiveInfoSpec, ArchiveListSpec, ArchiveVerifySpec
from bkx.core.specs.backup import BackupSpec, CompressionProfile
from bkx.core.specs.restore import RestoreSpec
from bkx.core.specs.transfer import TransferSpec


def build_backup_spec(
    sources: list[Path],
    *,
    destination: Path | None = None,
    archive_name: str | None = None,
    excludes: list[str] | None = None,
    includes: list[str] | None = None,
    split_size: int | None = None,
    algorithm: str | None = None,
    level: int | None = None,
    verify: bool | None = None,
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
        algorithm: Compression algorithm (zstd, gzip, none), defaults to config
        level: Compression level, defaults to config
        verify: Whether to verify after creation, defaults to config
        dry_run: Whether this is a dry run

    Returns:
        Normalized BackupSpec ready for service
    """
    # Get defaults from config
    config = get_config()
    _algorithm = config.resolve("compression_algorithm", cli_value=algorithm).value if algorithm is not None else config.compression_algorithm
    _level = config.resolve("compression_level", cli_value=level).value if level is not None else config.compression_level
    _verify = config.resolve("verify_after_operation", cli_value=verify).value if verify is not None else config.verify_after_operation

    spec = BackupSpec(
        sources=tuple(sources),
        destination=destination,
        archive_name=archive_name,
        excludes=tuple(excludes) if excludes else tuple(),
        includes=tuple(includes) if includes else tuple(),
        split_size=split_size,
        compression=CompressionProfile(algorithm=_algorithm, level=_level),
        verify=_verify,
        dry_run=dry_run,
    )

    # Normalize with auto-naming
    return spec.normalize(default_destination=config.default_destination, auto_name=True)


def build_archive_spec(
    sources: list[Path],
    output: Path,
    *,
    excludes: list[str] | None = None,
    includes: list[str] | None = None,
    split_size: int | None = None,
    algorithm: str | None = None,
    level: int | None = None,
    verify: bool | None = None,
    dry_run: bool = False,
) -> ArchiveCreateSpec:
    """Build ArchiveCreateSpec from CLI inputs.

    Args:
        sources: Source paths to archive
        output: Output file path
        excludes: Glob patterns to exclude
        includes: Glob patterns to include
        split_size: Optional chunk size for split archives
        algorithm: Compression algorithm, defaults to config
        level: Compression level, defaults to config
        verify: Whether to verify after creation, defaults to config
        dry_run: Whether this is a dry run

    Returns:
        Normalized ArchiveCreateSpec ready for service
    """
    # Get defaults from config
    config = get_config()
    _algorithm = config.resolve("compression_algorithm", cli_value=algorithm).value if algorithm is not None else config.compression_algorithm
    _level = config.resolve("compression_level", cli_value=level).value if level is not None else config.compression_level
    _verify = config.resolve("verify_after_operation", cli_value=verify).value if verify is not None else config.verify_after_operation

    spec = ArchiveCreateSpec(
        sources=tuple(sources),
        output_path=output,
        excludes=tuple(excludes) if excludes else tuple(),
        includes=tuple(includes) if includes else tuple(),
        split_size=split_size,
        compression=CompressionProfile(algorithm=_algorithm, level=_level),
        verify=_verify,
        dry_run=dry_run,
    )

    return spec.normalize()


def build_transfer_spec(
    source: Path | str,
    destination: Path | str,
    *,
    direction: str = "push",
    verify: bool | None = None,
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
    config = get_config()
    _verify = verify if verify is not None else config.verify_after_operation
    spec = TransferSpec(
        sources=(source,),
        destination=destination,
        direction=direction,
        verify=_verify,
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
    overwrite: bool | None = None,
    verify: bool | None = None,
    dry_run: bool = False,
    fresh_dir: bool | None = None,
    confirm_overwrite: bool | None = None,
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
        fresh_dir: Whether to create fresh directory by default, defaults to config
        confirm_overwrite: Whether to require confirmation for overwrite. If omitted,
                        confirmation remains enabled unless overwrite=True was explicitly set
                        for backward compatibility.

    Returns:
        Normalized RestoreSpec ready for service
    """
    # Get defaults from config
    config = get_config()
    _fresh_dir = fresh_dir if fresh_dir is not None else config.fresh_restore_dir
    _verify = verify if verify is not None else config.verify_after_operation

    # Keep the safety default local to RestoreSpec. This is not a public config
    # knob because the current restore engine does not consume it independently.
    if confirm_overwrite is None:
        if overwrite is True:
            _confirm_overwrite = False
        else:
            _confirm_overwrite = True
    else:
        _confirm_overwrite = confirm_overwrite

    # Normalize overwrite: None -> False
    _overwrite = overwrite if overwrite is not None else False

    spec = RestoreSpec(
        archive=archive,
        destination=destination,
        paths=tuple(paths) if paths else tuple(),
        overwrite=_overwrite,
        verify=_verify,
        dry_run=dry_run,
        fresh_dir=_fresh_dir,
        confirm_overwrite=_confirm_overwrite,
    )

    return spec.normalize(default_destination=config.default_destination)


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
) -> ArchiveBrowseSpec:
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


def build_archive_list_spec(archive: Path, *, filter_pattern: str | None = None, page: int = 0, page_size: int = 100) -> ArchiveListSpec:
    return ArchiveListSpec(archive_path=archive, filter_pattern=filter_pattern, page=page, page_size=page_size).normalize()


def build_archive_info_spec(archive: Path) -> ArchiveInfoSpec:
    return ArchiveInfoSpec(archive_path=archive).normalize()


def build_archive_verify_spec(archive: Path) -> ArchiveVerifySpec:
    return ArchiveVerifySpec(archive_path=archive).normalize()
