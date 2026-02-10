"""ExecutionPlan - structured plan for dry-run/--plan output.

Returns structured data about what would happen without executing.
"""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path

from .backup import Timing


@dataclass(frozen=True, kw_only=True)
class ExecutionStep:
    """Single step in an execution plan."""

    step_type: str  # "archive", "transfer", "restore", "verify"
    description: str
    source: Path | None = None
    destination: Path | None = None
    files_affected: int = 0
    bytes_affected: int = 0
    warnings: tuple[str, ...] = field(default_factory=tuple)


@dataclass(frozen=True, kw_only=True)
class ExecutionPlan:
    """Structured plan showing what would happen.

    Generated from a Spec without executing the actual operation.
    Used for --plan / --dry-run output.
    """

    # Plan metadata
    command: str  # e.g., "backup create", "archive create"
    dry_run: bool = True

    # Timing estimate
    timing: Timing | None = None

    # Steps in the plan
    steps: tuple[ExecutionStep, ...] = field(default_factory=tuple)

    # Summary
    total_files: int = 0
    total_bytes: int = 0

    # Warnings
    warnings: tuple[str, ...] = field(default_factory=tuple)

    # Computed properties
    @property
    def human_timing_estimate(self) -> str:
        """Get human-readable timing estimate."""
        if self.timing:
            duration_ms = self.timing.duration_ms
            if duration_ms < 1000:
                return f"{duration_ms}ms"
            elif duration_ms < 60000:
                return f"{duration_ms / 1000:.1f}s"
            else:
                return f"{duration_ms / 60000:.1f}min"
        return "unknown"

    @property
    def human_bytes_estimate(self) -> str:
        """Get human-readable byte estimate."""
        return _human_bytes(self.total_bytes)

    @property
    def step_count(self) -> int:
        """Get number of steps."""
        return len(self.steps)

    @property
    def is_empty(self) -> bool:
        """Check if plan has no steps."""
        return len(self.steps) == 0


@dataclass(frozen=True, kw_only=True)
class PlanFromSpecResult:
    """Result of generating a plan from a spec."""

    success: bool
    plan: ExecutionPlan | None = None
    error: str | None = None


def generate_backup_plan(
    command: str,
    sources: tuple[Path, ...],
    destination: Path | None,
    archive_name: str | None,
    verify: bool,
) -> ExecutionPlan:
    """Generate execution plan for backup operation.

    Args:
        command: CLI command string
        sources: Source paths
        destination: Destination path
        archive_name: Archive name
        verify: Whether verification will run

    Returns:
        ExecutionPlan showing what would happen
    """
    import time

    start_ms = int(time.time() * 1000)

    # Count files and bytes in sources
    total_files = 0
    total_bytes = 0
    for source in sources:
        if source.is_file():
            total_files += 1
            total_bytes += source.stat().st_size
        elif source.is_dir():
            for root, _, fnames in os.walk(source):
                for fname in fnames:
                    fpath = Path(root) / fname
                    total_files += 1
                    total_bytes += fpath.stat().st_size

    # Generate archive name if not provided
    if not archive_name:
        archive_name = f"{sources[0].name}_{int(time.time())}" if sources else "backup"

    archive_path = destination / f"{archive_name}.archive" if destination else None

    steps = []

    # Step 1: Archive
    steps.append(
        ExecutionStep(
            step_type="archive",
            description=f"Create archive '{archive_name}.archive'",
            source=sources[0] if len(sources) == 1 else None,
            destination=archive_path,
            files_affected=total_files,
            bytes_affected=total_bytes,
        )
    )

    # Step 2: Transfer (if destination is remote/cloud)
    if destination:
        steps.append(
            ExecutionStep(
                step_type="transfer",
                description=f"Transfer archive to {destination}",
                source=archive_path,
                destination=destination,
                files_affected=1,
                bytes_affected=0,  # Will be calculated after archive
            )
        )

    # Step 3: Verify (if requested)
    if verify:
        steps.append(
            ExecutionStep(
                step_type="verify",
                description="Verify archive integrity",
                source=archive_path,
            )
        )

    # Recalculate total bytes for transfer step
    if steps:
        steps = list(steps)
        if len(steps) > 1:
            steps[1] = ExecutionStep(
                step_type="transfer",
                description=f"Transfer archive to {destination}",
                source=archive_path,
                destination=destination,
                files_affected=1,
                bytes_affected=total_bytes,
            )

    return ExecutionPlan(
        command=command,
        dry_run=True,
        timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
        steps=tuple(steps),
        total_files=total_files,
        total_bytes=total_bytes,
    )


def generate_archive_plan(
    command: str,
    sources: tuple[Path, ...],
    output_path: Path,
    verify: bool,
) -> ExecutionPlan:
    """Generate execution plan for archive creation.

    Args:
        command: CLI command string
        sources: Source paths
        output_path: Output archive path
        verify: Whether verification will run

    Returns:
        ExecutionPlan showing what would happen
    """
    import time

    start_ms = int(time.time() * 1000)

    # Count files and bytes in sources
    total_files = 0
    total_bytes = 0
    for source in sources:
        if source.is_file():
            total_files += 1
            total_bytes += source.stat().st_size
        elif source.is_dir():
            for root, _, fnames in os.walk(source):
                for fname in fnames:
                    fpath = Path(root) / fname
                    total_files += 1
                    total_bytes += fpath.stat().st_size

    steps = [
        ExecutionStep(
            step_type="archive",
            description=f"Create archive {output_path.name}",
            source=sources[0] if len(sources) == 1 else None,
            destination=output_path,
            files_affected=total_files,
            bytes_affected=total_bytes,
        ),
    ]

    if verify:
        steps.append(
            ExecutionStep(
                step_type="verify",
                description="Verify archive integrity",
                source=output_path,
            )
        )

    return ExecutionPlan(
        command=command,
        dry_run=True,
        timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
        steps=tuple(steps),
        total_files=total_files,
        total_bytes=total_bytes,
    )


def generate_transfer_plan(
    command: str,
    sources: tuple[Path, ...],
    destination: Path,
    direction: str,
    verify: bool,
) -> ExecutionPlan:
    """Generate execution plan for transfer operation.

    Args:
        command: CLI command string
        sources: Source paths
        destination: Destination path
        direction: "push" or "pull"
        verify: Whether verification will run

    Returns:
        ExecutionPlan showing what would happen
    """
    import time

    start_ms = int(time.time() * 1000)

    # Count files and bytes
    total_files = len(sources)
    total_bytes = 0
    for source in sources:
        if source.is_file():
            total_bytes += source.stat().st_size
        elif source.is_dir():
            for root, _, fnames in os.walk(source):
                for fname in fnames:
                    fpath = Path(root) / fname
                    total_bytes += fpath.stat().st_size

    direction_desc = "push" if direction == "push" else "pull"

    steps = [
        ExecutionStep(
            step_type="transfer",
            description=f"{direction_desc} {total_files} file(s) to {destination}",
            source=sources[0] if len(sources) == 1 else None,
            destination=destination,
            files_affected=total_files,
            bytes_affected=total_bytes,
        ),
    ]

    if verify:
        steps.append(
            ExecutionStep(
                step_type="verify",
                description="Verify transfer integrity",
                source=destination,
            )
        )

    return ExecutionPlan(
        command=command,
        dry_run=True,
        timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
        steps=tuple(steps),
        total_files=total_files,
        total_bytes=total_bytes,
    )


def generate_restore_plan(
    command: str,
    archive: Path,
    destination: Path | None,
    paths: tuple[str, ...],
) -> ExecutionPlan:
    """Generate execution plan for restore operation.

    Args:
        command: CLI command string
        archive: Archive path
        destination: Destination path
        paths: Specific paths to restore (empty = full restore)

    Returns:
        ExecutionPlan showing what would happen
    """
    import time

    start_ms = int(time.time() * 1000)

    steps = [
        ExecutionStep(
            step_type="restore",
            description=f"Extract from {archive.name}",
            source=archive,
            destination=destination or Path.cwd(),
            files_affected=len(paths) if paths else 0,
        )
    ]

    return ExecutionPlan(
        command=command,
        dry_run=True,
        timing=Timing(start_ms=start_ms, end_ms=int(time.time() * 1000)),
        steps=tuple(steps),
    )


def _human_bytes(n: float) -> str:
    """Format bytes as human-readable string."""
    if n == 0:
        return "0 B"
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024:
            return f"{n:.2f}{unit}"
        n /= 1024
    return f"{n:.2f}PB"
