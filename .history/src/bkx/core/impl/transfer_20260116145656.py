"""FallbackTransfer - pure Python file transfer operations.

Fallback implementation using shutil for file operations.
Supports local copy operations.
"""

from __future__ import annotations

import os
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from ..ports.transfer import TransferProgress
from ..specs.transfer import TransferSpec
from ..results.transfer import TransferResult, Timing


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
        progress_callback: callable[[TransferProgress], None] | None = None,
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
                warnings=("Dry run - files not transferred",),
            )

        files_transferred = 0
        bytes_transferred = 0
        warnings = []

        destination = Path(spec.destination)
        destination.mkdir(parents=True, exist_ok=True)

        for source in spec.sources:
            if source.is_file():
                dest_path = destination / source.name
                bytes = self._copy_with_progress(
                    source, dest_path, progress_callback
                )
                files_transferred += 1
                bytes_transferred += bytes
            elif source.is_dir():
                files, bytes = self._copy_dir_with_progress(
                    source, destination, progress_callback
                )
                files_transferred += files
                bytes_transferred += bytes

        return TransferResult(
            success=True,
            direction="push",
            timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
            files_transferred=files_transferred,
            bytes_transferred=bytes_transferred,
            warnings=tuple(warnings),
        )

    def pull(
        self,
        spec: TransferSpec,
        *,
        progress_callback: callable[[TransferProgress], None] | None = None,
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

        # For fallback, pull is the same as push but from remote to local
        # In a real implementation, this would handle remote sources
        return self.push(spec, progress_callback=progress_callback)

    def sync(
        self,
        source: Path,
        destination: Path,
        *,
        delete: bool = False,
        exclude: tuple[str, ...] = (),
        progress_callback: callable[[TransferProgress], None] | None = None,
    ) -> TransferResult:
        """Sync source to destination (mirror semantics).

        Args:
            source: Source path.
            destination: Destination path.
            delete: Remove files not in source.
            exclude: Patterns to exclude.
            progress_callback: Optional callback for progress updates.

        Returns:
            Result with metrics.
        """
        import time

        start_ms = int(time.time() * 1000)

        files_transferred = 0
        bytes_transferred = 0
        warnings = []

        if not source.exists():
            raise FileNotFoundError(f"Source not found: {source}")

        destination.mkdir(parents=True, exist_ok=True)

        if source.is_file():
            bytes = self._copy_with_progress(
                source, destination / source.name, progress_callback
            )
            files_transferred = 1
            bytes_transferred = bytes
        elif source.is_dir():
            files, bytes = self._sync_dir(
                source,
                destination,
                delete=delete,
                exclude=exclude,
                progress_callback=progress_callback,
            )
            files_transferred = files
            bytes_transferred = bytes

        return TransferResult(
            success=True,
            direction="sync",
            timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
            source=str(source),
            destination=str(destination),
            files_transferred=files_transferred,
            bytes_transferred=bytes_transferred,
            warnings=tuple(warnings),
        )

    def _copy_with_progress(
        self,
        source: Path,
        destination: Path,
        progress_callback: callable[[TransferProgress], None] | None = None,
    ) -> int:
        """Copy a single file with optional progress reporting."""
        file_size = source.stat().st_size

        def update_progress(bytes_copied: int) -> None:
            if progress_callback:
                progress_callback(
                    TransferProgress(
                        current_file=source.name,
                        bytes_current=bytes_copied,
                        total_bytes=file_size,
                        percent=bytes_copied / file_size if file_size > 0 else 1.0,
                    )
                )

        # Use shutil.copy2 to preserve metadata
        shutil.copy2(source, destination)
        return file_size

    def _copy_dir_with_progress(
        self,
        source: Path,
        destination: Path,
        progress_callback: callable[[TransferProgress], None] | None = None,
    ) -> tuple[int, int]:
        """Copy a directory with optional progress reporting."""
        files = 0
        bytes_total = 0

        for root, dirs, fnames in os.walk(source):
            rel_path = Path(root).relative_to(source)
            dest_dir = destination / rel_path
            dest_dir.mkdir(parents=True, exist_ok=True)

            for fname in fnames:
                fpath = Path(root) / fname
                dest_path = dest_dir / fname
                bytes_copied = self._copy_with_progress(
                    fpath, dest_path, progress_callback
                )
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
        progress_callback: callable[[TransferProgress], None] | None = None,
    ) -> tuple[int, int]:
        """Sync directory with mirror semantics."""
        import fnmatch

        files = 0
        bytes_total = 0

        # First, copy/update files
        for root, dirs, fnames in os.walk(source):
            rel_path = Path(root).relative_to(source)
            dest_dir = destination / rel_path
            dest_dir.mkdir(parents=True, exist_ok=True)

            # Get existing files in destination
            existing_files = set(f.name for f in dest_dir.iterdir() if f.is_file())

            for fname in fnames:
                # Check exclude patterns
                if exclude and any(
                    fnmatch.fnmatch(fname, pattern) for pattern in exclude
                ):
                    continue

                fpath = Path(root) / fname
                dest_path = dest_dir / fname

                # Remove from existing set if copied
                existing_files.discard(fname)

                # Copy if newer or doesn't exist
                if not dest_path.exists() or dest_path.stat().st_mtime < fpath.stat().st_mtime:
                    bytes_copied = self._copy_with_progress(
                        fpath, dest_path, progress_callback
                    )
                    files += 1
                    bytes_total += bytes_copied

        # Remove files not in source (if delete=True)
        if delete:
            for fpath in existing_files:
                (destination / fpath).unlink()

        return files, bytes_total

