"""FallbackTransfer - pure Python file transfer operations.

Fallback implementation using shutil for file operations.
Supports local copy operations.
"""

from __future__ import annotations

import os
import shutil
from collections.abc import Callable
from pathlib import Path
from typing import Any

from ..ports.transfer import TransferProgress
from ..results.transfer import Timing, TransferResult
from ..specs.transfer import TransferSpec, TransferSyncSpec


class FallbackTransfer:
    """Pure Python transfer using shutil.

    This is the fallback when external tools like rsync are unavailable.
    Supports local file and directory operations.
    """

    def __init__(self) -> None:
        """Initialize the transfer."""
        self._name = "python-shutil"
        self._supports_push = True
        self._supports_pull = True

    @property
    def name(self) -> str:
        """Human-readable name of this transfer method."""
        return self._name

    @property
    def supports_push(self) -> bool:
        """Whether this transfer method supports push operations."""
        return self._supports_push

    @property
    def supports_pull(self) -> bool:
        """Whether this transfer method supports pull operations."""
        return self._supports_pull

    def push(
        self,
        spec: TransferSpec,
        *,
        progress_callback: Callable[[TransferProgress], Any] | None = None,
    ) -> TransferResult:
        """Push files from local to destination.

        Args:
            spec: Normalized transfer specification.
            progress_callback: Optional callback for progress updates.

        Returns:
            Result with metrics.
        """
        import time

        start_ms = int(time.time() * 1000)

        if spec.dry_run:
            return TransferResult(
                success=True,
                direction="push",
                timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
                source=spec.sources[0] if spec.sources else None,
                destination=spec.destination,
                warnings=("Dry run - files not transferred",),
            )

        files_transferred = 0
        bytes_transferred = 0
        warnings: list[str] = []

        if not isinstance(spec.destination, Path):
            raise NotImplementedError("python-shutil transfer does not support URI destinations")
        destination = spec.destination

        for source in spec.sources:
            if not isinstance(source, Path):
                raise NotImplementedError("python-shutil transfer does not support URI sources")
            if source.is_file():
                # Determine the destination path
                if destination.is_dir():
                    # When destination is a directory, use source filename
                    dest_path = destination / source.name
                else:
                    # destination is a file path or doesn't exist yet
                    dest_path = destination
                # Ensure parent directory exists
                dest_path.parent.mkdir(parents=True, exist_ok=True)
                bytes = self._copy_with_progress(source, dest_path, progress_callback)
                files_transferred += 1
                bytes_transferred += bytes
            elif source.is_dir():
                if destination.is_dir():
                    dest_subdir = destination / source.name
                else:
                    dest_subdir = destination
                dest_subdir.mkdir(parents=True, exist_ok=True)
                files, bytes = self._copy_dir_with_progress(source, dest_subdir, progress_callback)
                files_transferred += files
                bytes_transferred += bytes

        return TransferResult(
            success=True,
            direction="push",
            timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
            source=spec.sources[0] if spec.sources else None,
            destination=destination,
            files_transferred=files_transferred,
            bytes_transferred=bytes_transferred,
            warnings=tuple(warnings),
        )

    def pull(
        self,
        spec: TransferSpec,
        *,
        progress_callback: Callable[[TransferProgress], Any] | None = None,
    ) -> TransferResult:
        """Pull files from destination to local.

        Args:
            spec: Normalized transfer specification.
            progress_callback: Optional callback for progress updates.

        Returns:
            Result with metrics.
        """
        import time

        start_ms = int(time.time() * 1000)

        if spec.dry_run:
            return TransferResult(
                success=True,
                direction="pull",
                timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
                warnings=("Dry run - files not transferred",),
            )

        if not isinstance(spec.destination, Path) or any(not isinstance(source, Path) for source in spec.sources):
            raise NotImplementedError("python-shutil transfer supports only local pull sources/destinations")
        local_spec = TransferSpec(
            sources=spec.sources,
            destination=spec.destination,
            direction="push",
            verify=spec.verify,
            checksum=spec.checksum,
            dry_run=False,
            overwrite=spec.overwrite,
        )
        copied = self.push(local_spec, progress_callback=progress_callback)
        return TransferResult(
            success=copied.success,
            direction="pull",
            timing=copied.timing,
            source=spec.effective_source,
            destination=spec.destination,
            files_transferred=copied.files_transferred,
            bytes_transferred=copied.bytes_transferred,
            verification_passed=copied.verification_passed,
            checksum_verified=copied.checksum_verified,
            warnings=copied.warnings,
            errors=copied.errors,
        )

    def sync(
        self,
        spec: TransferSyncSpec,
        *,
        progress_callback: Callable[[TransferProgress], Any] | None = None,
    ) -> TransferResult:
        """Sync a normalized local mirror Spec."""
        import time

        start_ms = int(time.time() * 1000)
        source = spec.source
        destination = spec.destination
        files_transferred = 0
        bytes_transferred = 0
        warnings: list[str] = []

        destination.mkdir(parents=True, exist_ok=True)
        if source.is_file():
            copied = self._copy_with_progress(source, destination / source.name, progress_callback)
            files_transferred = 1
            bytes_transferred = copied
        elif source.is_dir():
            files_transferred, bytes_transferred = self._sync_dir(
                source, destination, delete=spec.delete, exclude=spec.excludes, progress_callback=progress_callback
            )

        return TransferResult(
            success=True,
            direction="sync",
            timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
            source=source,
            destination=destination,
            files_transferred=files_transferred,
            bytes_transferred=bytes_transferred,
            warnings=tuple(warnings),
        )

    def _copy_with_progress(
        self,
        source: Path,
        destination: Path,
        progress_callback: Callable[[TransferProgress], Any] | None = None,
    ) -> int:
        """Copy a single file with optional progress reporting."""
        file_size = source.stat().st_size

        def update_progress(bytes_copied: int) -> None:
            if progress_callback:
                progress_callback(
                    TransferProgress(
                        current_file=source,
                        bytes_transferred=bytes_copied,
                        total_bytes=file_size,
                    )
                )

        # Use shutil.copy2 to preserve metadata
        shutil.copy2(source, destination)
        return file_size

    def _copy_dir_with_progress(
        self,
        source: Path,
        destination: Path,
        progress_callback: Callable[[TransferProgress], Any] | None = None,
    ) -> tuple[int, int]:
        """Copy a directory with optional progress reporting."""
        files = 0
        bytes_total = 0

        for root, _dirs, fnames in os.walk(source):
            rel_path = Path(root).relative_to(source)
            dest_dir = destination / rel_path
            dest_dir.mkdir(parents=True, exist_ok=True)

            for fname in fnames:
                fpath = Path(root) / fname
                dest_path = dest_dir / fname
                bytes_copied = self._copy_with_progress(fpath, dest_path, progress_callback)
                files += 1
                bytes_total += bytes_copied

        return files, bytes_total

    def _sync_dir(
        self,
        source: Path,
        destination: Path,
        *,
        delete: bool = False,
        exclude: tuple[str, ...] = (),
        progress_callback: Callable[[TransferProgress], Any] | None = None,
    ) -> tuple[int, int]:
        """Sync directory with mirror semantics."""
        import fnmatch

        files = 0
        bytes_total = 0

        # Track existing files in destination for potential deletion
        existing_files: set[Path] = set()

        # First, copy/update files
        for root, _dirs, fnames in os.walk(source):
            rel_path = Path(root).relative_to(source)
            dest_dir = destination / rel_path
            dest_dir.mkdir(parents=True, exist_ok=True)

            # Get existing files in destination
            existing_files.update(f for f in dest_dir.iterdir() if f.is_file())

            for fname in fnames:
                # Check exclude patterns
                if exclude and any(fnmatch.fnmatch(fname, pattern) for pattern in exclude):
                    continue

                fpath = Path(root) / fname
                dest_path = dest_dir / fname

                # Remove from existing set if copied
                existing_files.discard(dest_path)

                # Copy if newer or doesn't exist
                if not dest_path.exists() or dest_path.stat().st_mtime < fpath.stat().st_mtime:
                    bytes_copied = self._copy_with_progress(fpath, dest_path, progress_callback)
                    files += 1
                    bytes_total += bytes_copied

        # Remove files not in source (if delete=True)
        if delete:
            for fpath in existing_files:
                fpath.unlink()

        return files, bytes_total


def get_transfer() -> FallbackTransfer:
    """Get the default transfer implementation.

    Returns:
        FallbackTransfer instance (pure Python implementation)
    """
    return FallbackTransfer()
