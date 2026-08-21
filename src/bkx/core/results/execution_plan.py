"""Authoritative executable plan projection from normalized Specs."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .common import FrozenMapping, frozen_mapping, require_nonnegative


@dataclass(frozen=True, kw_only=True)
class ExecutionStep:
    step_type: str
    description: str
    source: Path | str | None = None
    destination: Path | str | None = None
    files_affected: int | None = None
    bytes_affected: int | None = None
    details: FrozenMapping = field(default_factory=FrozenMapping)
    warnings: tuple[str, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        if self.files_affected is not None:
            require_nonnegative(files_affected=self.files_affected)
        if self.bytes_affected is not None:
            require_nonnegative(bytes_affected=self.bytes_affected)
        object.__setattr__(self, "details", frozen_mapping(dict(self.details)))
        object.__setattr__(self, "warnings", tuple(self.warnings))


@dataclass(frozen=True, kw_only=True)
class ExecutionPlan:
    command: str
    dry_run: bool = True
    steps: tuple[ExecutionStep, ...] = field(default_factory=tuple)
    total_files: int | None = 0
    total_bytes: int | None = 0
    warnings: tuple[str, ...] = field(default_factory=tuple)

    def __post_init__(self) -> None:
        if self.total_files is not None:
            require_nonnegative(total_files=self.total_files)
        if self.total_bytes is not None:
            require_nonnegative(total_bytes=self.total_bytes)
        object.__setattr__(self, "steps", tuple(self.steps))
        object.__setattr__(self, "warnings", tuple(self.warnings))

    @property
    def human_timing_estimate(self) -> str:
        # Planning time is not an estimate of operation duration.
        return "unknown"

    @property
    def human_bytes_estimate(self) -> str:
        return "unknown" if self.total_bytes is None else _human_bytes(self.total_bytes)

    @property
    def step_count(self) -> int:
        return len(self.steps)

    @property
    def is_empty(self) -> bool:
        return not self.steps


@dataclass(frozen=True, kw_only=True)
class PlanFromSpecResult:
    success: bool
    plan: ExecutionPlan | None = None
    error: str | None = None

    def __post_init__(self) -> None:
        if self.success and (self.plan is None or self.error is not None):
            raise ValueError("successful plan result requires a plan and no error")
        if not self.success and not self.error:
            raise ValueError("failed plan result requires an error")


def plan_for_spec(spec: Any, *, command: str | None = None) -> ExecutionPlan:
    """Project a normalized Spec into the same concrete paths/policies execution uses."""
    from ..specs.archive import ArchiveCreateSpec
    from ..specs.backup import BackupSpec
    from ..specs.restore import RestoreSpec
    from ..specs.transfer import TransferSpec, TransferSyncSpec

    if isinstance(spec, BackupSpec):
        normalized = spec.normalize(auto_name=True)
        archive_spec = ArchiveCreateSpec(
            sources=normalized.sources,
            output_path=normalized.archive_path,
            excludes=normalized.excludes,
            includes=normalized.includes,
            split_size=normalized.split_size,
            compression=normalized.compression,
            verify=normalized.verify,
            dry_run=True,
            skip_symlinks=normalized.skip_symlinks,
        ).normalize()
        files, size = _measure_local_sources(normalized.sources)
        output = archive_spec.effective_output_path
        steps: list[ExecutionStep] = [
            ExecutionStep(
                step_type="archive",
                description=f"Create archive {output.name if output else '(resolved by backend)'}",
                source=normalized.sources[0] if len(normalized.sources) == 1 else None,
                destination=output,
                files_affected=files,
                bytes_affected=size,
                details={"compression": normalized.compression.algorithm, "split_size": normalized.split_size},
            )
        ]
        if normalized.destination is not None:
            steps.append(
                ExecutionStep(
                    step_type="transfer",
                    description=f"Transfer archive to {normalized.destination}",
                    source=output,
                    destination=normalized.destination,
                    files_affected=1,
                    bytes_affected=None,
                )
            )
        if normalized.verify:
            steps.append(
                ExecutionStep(
                    step_type="verify",
                    description="Verify backup archive integrity",
                    source=output,
                    files_affected=1,
                    details={"requested": True},
                )
            )
        return ExecutionPlan(command=command or "backup create", steps=tuple(steps), total_files=files, total_bytes=size)

    if isinstance(spec, ArchiveCreateSpec):
        normalized = spec.normalize()
        files, size = _measure_local_sources(normalized.sources)
        output = normalized.effective_output_path
        steps = [
            ExecutionStep(
                step_type="archive",
                description=f"Create archive {output.name if output else '(resolved by backend)'}",
                source=normalized.sources[0] if len(normalized.sources) == 1 else None,
                destination=output,
                files_affected=files,
                bytes_affected=size,
                details={"compression": normalized.compression.algorithm, "split_size": normalized.split_size},
            )
        ]
        if normalized.verify:
            steps.append(ExecutionStep(step_type="verify", description="Verify archive integrity", source=output, files_affected=1))
        return ExecutionPlan(command=command or "archive create", steps=tuple(steps), total_files=files, total_bytes=size)

    if isinstance(spec, TransferSpec):
        normalized = spec.normalize()
        local_sources = tuple(source for source in normalized.sources if isinstance(source, Path))
        if len(local_sources) == len(normalized.sources):
            files, size = _measure_local_sources(local_sources)
        else:
            files, size = None, None
        steps = [
            ExecutionStep(
                step_type="transfer",
                description=f"{normalized.direction} to {normalized.destination}",
                source=normalized.effective_source if len(normalized.sources) == 1 else None,
                destination=normalized.destination,
                files_affected=files,
                bytes_affected=size,
                details={"direction": normalized.direction, "source_count": len(normalized.sources)},
            )
        ]
        if normalized.verify:
            steps.append(ExecutionStep(step_type="verify", description="Verify transfer integrity", destination=normalized.destination, files_affected=files, bytes_affected=size))
        return ExecutionPlan(command=command or f"transfer {normalized.direction}", steps=tuple(steps), total_files=files, total_bytes=size)

    if isinstance(spec, TransferSyncSpec):
        normalized = spec.normalize()
        files, size = _measure_local_sources((normalized.source,))
        steps = [ExecutionStep(
            step_type="transfer",
            description=f"sync {normalized.source} to {normalized.destination}",
            source=normalized.source,
            destination=normalized.destination,
            files_affected=files,
            bytes_affected=size,
            details={"direction": "sync", "delete": normalized.delete, "excludes": normalized.excludes},
        )]
        if normalized.verify:
            steps.append(ExecutionStep(step_type="verify", description="Verify sync integrity", destination=normalized.destination, files_affected=files, bytes_affected=size))
        return ExecutionPlan(command=command or "transfer sync", steps=tuple(steps), total_files=files, total_bytes=size)

    if isinstance(spec, RestoreSpec):
        normalized = spec.normalize()
        selected = len(normalized.paths) if normalized.paths else None
        destination = normalized.effective_destination
        steps = []
        if normalized.verify:
            steps.append(ExecutionStep(step_type="verify", description="Verify archive before restore", source=normalized.archive, files_affected=1))
        steps.append(
            ExecutionStep(
                step_type="restore",
                description=f"Extract from {normalized.archive.name}",
                source=normalized.archive,
                destination=destination,
                files_affected=selected,
                bytes_affected=None,
                details={
                    "full_restore": not normalized.paths,
                    "selected_paths": normalized.paths,
                    "overwrite": normalized.overwrite,
                    "fresh_dir": normalized.fresh_dir,
                    "confirm_overwrite": normalized.confirm_overwrite,
                    "requires_confirmation": normalized.requires_confirmation,
                },
            )
        )
        return ExecutionPlan(command=command or "restore extract", steps=tuple(steps), total_files=selected, total_bytes=None)

    raise TypeError(f"No ExecutionPlan projection for {type(spec).__name__}")


def execution_plan_to_dict(plan: ExecutionPlan) -> dict[str, Any]:
    """Stable renderer projection; interfaces do not invent plan semantics."""
    return {
        "command": plan.command,
        "dry_run": plan.dry_run,
        "steps": [
            {
                "step_type": step.step_type,
                "description": step.description,
                "source": str(step.source) if step.source is not None else None,
                "destination": str(step.destination) if step.destination is not None else None,
                "files_affected": step.files_affected,
                "bytes_affected": step.bytes_affected,
                "details": _json_safe(dict(step.details)),
                "warnings": list(step.warnings),
            }
            for step in plan.steps
        ],
        "total_files": plan.total_files,
        "total_bytes": plan.total_bytes,
        "timing_estimate": plan.human_timing_estimate,
        "warnings": list(plan.warnings),
    }


def generate_backup_plan(command: str, sources: tuple[Path, ...], destination: Path | None, archive_name: str | None, verify: bool) -> ExecutionPlan:
    from ..specs.backup import BackupSpec
    return plan_for_spec(BackupSpec(sources=sources, destination=destination, archive_name=archive_name, verify=verify, dry_run=True), command=command)


def generate_archive_plan(command: str, sources: tuple[Path, ...], output_path: Path, verify: bool) -> ExecutionPlan:
    from ..specs.archive import ArchiveCreateSpec
    return plan_for_spec(ArchiveCreateSpec(sources=sources, output_path=output_path, verify=verify, dry_run=True), command=command)


def generate_transfer_plan(command: str, sources: tuple[Path, ...], destination: Path, direction: str, verify: bool) -> ExecutionPlan:
    from ..specs.transfer import TransferSpec
    return plan_for_spec(TransferSpec(sources=sources, destination=destination, direction=direction, verify=verify, dry_run=True), command=command)


def generate_restore_plan(command: str, archive: Path, destination: Path | None, paths: tuple[str, ...]) -> ExecutionPlan:
    from ..specs.restore import RestoreSpec
    return plan_for_spec(RestoreSpec(archive=archive, destination=destination, paths=paths, dry_run=True), command=command)


def _measure_local_sources(sources: tuple[Path, ...]) -> tuple[int, int]:
    files = 0
    total = 0
    for source in sources:
        if source.is_file():
            files += 1
            total += source.stat().st_size
        elif source.is_dir():
            for root, _dirs, names in os.walk(source):
                for name in names:
                    path = Path(root) / name
                    if path.is_file():
                        files += 1
                        total += path.stat().st_size
    return files, total


def _json_safe(value: Any) -> Any:
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, tuple):
        return [_json_safe(item) for item in value]
    if isinstance(value, dict):
        return {str(key): _json_safe(item) for key, item in value.items()}
    return value


def _human_bytes(n: float) -> str:
    if n == 0:
        return "0 B"
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024:
            return f"{n:.2f}{unit}"
        n /= 1024
    return f"{n:.2f}PB"
