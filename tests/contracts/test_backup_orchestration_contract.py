from __future__ import annotations

from pathlib import Path

import pytest

from bkx.core.ports.hasher import HashAlgorithm
from bkx.core.ports.tool_detector import ToolInfo
from bkx.core.results.archive import ArchiveCreateResult, ArchiveVerifyResult
from bkx.core.results.backup import Timing
from bkx.core.results.hashing import HashResult
from bkx.core.results.transfer import TransferResult
from bkx.core.services.backup import BackupService
from bkx.core.specs.archive import ArchiveCreateSpec, ArchiveVerifySpec
from bkx.core.specs.backup import BackupSpec
from bkx.core.specs.transfer import TransferSpec


class Hashing:
    name = "hashing"
    algorithm: HashAlgorithm = "sha256"
    digest_length = 64
    supported_algorithms = ("sha256",)

    def hash_file(self, path: Path, algorithm: HashAlgorithm = "sha256", *, chunk_size: int = 8192) -> HashResult:
        return HashResult(algorithm=algorithm, checksum=f"sha:{path.name}", bytes_hashed=path.stat().st_size if path.exists() else 0)


class Detector:
    def detect(self, tool_name: str) -> ToolInfo:
        return ToolInfo(name=tool_name, path=None, version=None, available=False, capabilities=())

    def detect_all(self, tool_names: tuple[str, ...]) -> dict[str, ToolInfo]:
        return {name: self.detect(name) for name in tool_names}

    def list_available(self) -> tuple[str, ...]:
        return ()

    def get_preferred(self, tool_category: str) -> str | None:
        return None


class RecordingArchiver:
    name = "recording-archiver"
    supported_formats = ("tar",)

    def __init__(self, *, split: bool = False, verify_success: bool = True) -> None:
        self.outputs: list[Path | None] = []
        self.split = split
        self.verify_success = verify_success

    def create(self, spec: ArchiveCreateSpec, *, progress_callback=None) -> ArchiveCreateResult:
        output = spec.effective_output_path or spec.output_path or Path("fallback-name.bakery")
        self.outputs.append(output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(b"primary")
        parts = ()
        if self.split:
            p1 = output.with_suffix(output.suffix + ".part1")
            p2 = output.with_suffix(output.suffix + ".part2")
            p1.write_bytes(b"part1")
            p2.write_bytes(b"part2")
            parts = (p1, p2)
        return ArchiveCreateResult(success=True, dry_run=False, timing=Timing(0, 0), files_archived=2, bytes_written=7, archive_path=output, split_parts=parts, verification_passed=True if spec.verify and self.verify_success else False if spec.verify else None, warnings=("archive-warning",))

    def verify(self, spec: ArchiveVerifySpec) -> ArchiveVerifyResult:
        return ArchiveVerifyResult(success=self.verify_success, archive_path=spec.archive_path, structure_valid=self.verify_success, checksums_valid=self.verify_success, entries_checked=1, entries_failed=0 if self.verify_success else 1, failed_entries=() if self.verify_success else ("broken",), errors=() if self.verify_success else ("broken",))


class RecordingTransfer:
    name = "recording-transfer"
    supports_push = True
    supports_pull = True

    def __init__(self, *, verify_success: bool = True) -> None:
        self.specs: list[TransferSpec] = []
        self.verify_success = verify_success

    def push(self, spec: TransferSpec, *, progress_callback=None) -> TransferResult:
        self.specs.append(spec)
        return TransferResult(success=True, direction="push", timing=Timing(0, 0), source=spec.effective_source, destination=spec.effective_destination, files_transferred=len(spec.sources), bytes_transferred=10, verification_passed=True if self.verify_success else False, checksum_verified=True if self.verify_success else False, warnings=("transfer-warning",))


def _source(tmp_path: Path) -> Path:
    source = tmp_path / "src"
    source.mkdir()
    (source / "file.txt").write_text("content", encoding="utf-8")
    return source


def test_backup_stages_locally_then_transfers_to_final_name(tmp_path: Path) -> None:
    archiver = RecordingArchiver()
    transfer = RecordingTransfer()
    service = BackupService(archiver=archiver, transfer=transfer, hasher=Hashing(), tool_detector=Detector())
    destination = tmp_path / "final"
    spec = BackupSpec(sources=(_source(tmp_path),), destination=destination, archive_name="explicit").normalize()

    result = service.create(spec)

    assert result.success is True
    assert result.archive_paths == (destination / "explicit.bakery",)
    assert archiver.outputs[0] != result.archive_paths[0]
    assert archiver.outputs[0].name == "explicit.bakery"
    assert transfer.specs[0].sources == (archiver.outputs[0],)
    assert transfer.specs[0].destination == destination / "explicit.bakery"
    assert "archive-warning" in result.warnings and "transfer-warning" in result.warnings


def test_backup_honors_explicit_name_without_destination(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.chdir(tmp_path)
    archiver = RecordingArchiver()
    service = BackupService(archiver=archiver, transfer=RecordingTransfer(), hasher=Hashing(), tool_detector=Detector())
    spec = BackupSpec(sources=(_source(tmp_path),), archive_name="named-local").normalize()

    result = service.create(spec)

    assert result.success is True
    assert result.archive_paths[0].name == "named-local.bakery"
    assert archiver.outputs[0].name == "named-local.bakery"


def test_backup_carries_split_artifacts_through_transfer_and_result(tmp_path: Path) -> None:
    destination = tmp_path / "final"
    destination.mkdir()
    archiver = RecordingArchiver(split=True)
    transfer = RecordingTransfer()
    service = BackupService(archiver=archiver, transfer=transfer, hasher=Hashing(), tool_detector=Detector())
    spec = BackupSpec(sources=(_source(tmp_path),), destination=destination, archive_name="split").normalize()

    result = service.create(spec)

    assert result.success is True
    assert len(transfer.specs[0].sources) == 2
    assert result.archive_paths == tuple(destination / part.name for part in transfer.specs[0].sources)
    assert result.archives_created == 2


def test_backup_fails_when_transfer_verification_fails(tmp_path: Path) -> None:
    service = BackupService(archiver=RecordingArchiver(), transfer=RecordingTransfer(verify_success=False), hasher=Hashing(), tool_detector=Detector())
    spec = BackupSpec(sources=(_source(tmp_path),), destination=tmp_path / "final", archive_name="x", verify=True).normalize()

    result = service.create(spec)

    assert result.success is False
    assert result.verification_passed is False
    assert result.step_results["verify_transfer"] is False
    assert "Transfer verification did not pass" in result.errors


def test_backup_fails_when_archive_verification_fails(tmp_path: Path) -> None:
    service = BackupService(archiver=RecordingArchiver(verify_success=False), transfer=RecordingTransfer(), hasher=Hashing(), tool_detector=Detector())
    spec = BackupSpec(sources=(_source(tmp_path),), destination=tmp_path / "final", archive_name="x", verify=True).normalize()

    result = service.create(spec)

    assert result.success is False
    assert result.step_results["archive"] is True
    assert result.step_results["verify_archive"] is False


class ArchiveFalseThenVerifyTrue(RecordingArchiver):
    def create(self, spec: ArchiveCreateSpec, *, progress_callback=None) -> ArchiveCreateResult:
        result = super().create(spec, progress_callback=progress_callback)
        return ArchiveCreateResult(
            success=True,
            dry_run=False,
            timing=result.timing,
            files_archived=result.files_archived,
            bytes_written=result.bytes_written,
            archive_path=result.archive_path,
            split_parts=result.split_parts,
            verification_passed=False,
        )

    def verify(self, spec: ArchiveVerifySpec) -> ArchiveVerifyResult:
        return ArchiveVerifyResult(
            success=True,
            archive_path=spec.archive_path,
            structure_valid=True,
            checksums_valid=True,
            entries_checked=1,
        )


class TransferMixedVerification(RecordingTransfer):
    def push(self, spec: TransferSpec, *, progress_callback=None) -> TransferResult:
        self.specs.append(spec)
        return TransferResult(
            success=True,
            direction="push",
            timing=Timing(0, 0),
            source=spec.effective_source,
            destination=spec.effective_destination,
            files_transferred=len(spec.sources),
            bytes_transferred=10,
            verification_passed=False,
            checksum_verified=True,
        )


def test_backup_does_not_overwrite_explicit_archive_verification_failure(tmp_path: Path) -> None:
    service = BackupService(archiver=ArchiveFalseThenVerifyTrue(), transfer=RecordingTransfer(), hasher=Hashing(), tool_detector=Detector())
    spec = BackupSpec(sources=(_source(tmp_path),), destination=tmp_path / "final", archive_name="x", verify=True).normalize()

    result = service.create(spec)

    assert result.success is False
    assert result.verification_passed is False
    assert result.step_results["verify_archive"] is False
    assert "Archive verification did not pass" in result.errors


def test_backup_does_not_overwrite_explicit_transfer_verification_failure(tmp_path: Path) -> None:
    service = BackupService(archiver=RecordingArchiver(), transfer=TransferMixedVerification(), hasher=Hashing(), tool_detector=Detector())
    spec = BackupSpec(sources=(_source(tmp_path),), destination=tmp_path / "final", archive_name="x", verify=True).normalize()

    result = service.create(spec)

    assert result.success is False
    assert result.verification_passed is False
    assert result.step_results["verify_transfer"] is False
    assert "Transfer verification did not pass" in result.errors


class TransferUnknownVerification(RecordingTransfer):
    def push(self, spec: TransferSpec, *, progress_callback=None) -> TransferResult:
        self.specs.append(spec)
        return TransferResult(
            success=True,
            direction="push",
            timing=Timing(0, 0),
            source=spec.effective_source,
            destination=spec.effective_destination,
            files_transferred=len(spec.sources),
            bytes_transferred=10,
            verification_passed=None,
            checksum_verified=None,
        )


def test_backup_fails_when_transfer_verification_is_unknown(tmp_path: Path) -> None:
    service = BackupService(archiver=RecordingArchiver(), transfer=TransferUnknownVerification(), hasher=Hashing(), tool_detector=Detector())
    spec = BackupSpec(sources=(_source(tmp_path),), destination=tmp_path / "final", archive_name="x", verify=True).normalize()

    result = service.create(spec)

    assert result.success is False
    assert result.verification_passed is False
    assert result.step_results["verify_transfer"] is False
    assert "Transfer verification did not pass" in result.errors
