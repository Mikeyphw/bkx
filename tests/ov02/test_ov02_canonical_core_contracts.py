"""OV-02 contract reset regressions for RM-009 through RM-015."""

from __future__ import annotations

import inspect
import io
import json
import os
from dataclasses import dataclass, replace
from pathlib import Path, PurePosixPath

import pytest

from bkx.cli.render.renderer import JSONRenderer
from bkx.core.errors import ExternalToolError, ValidationError
from bkx.core.impl.archiver import FallbackArchiver
from bkx.core.impl.hasher import FallbackHasher
from bkx.core.parity import (
    compute_archive_spec_hash,
    compute_backup_spec_hash,
    compute_restore_spec_hash,
    compute_transfer_spec_hash,
    spec_to_dict,
    validate_spec_against_schema,
)
from bkx.core.ports.archiver import SUPPORTED_ARCHIVE_FORMATS
from bkx.core.results.archive import ArchiveCreateResult, ArchiveEntry, ArchiveInfoResult, ArchiveVerifyResult
from bkx.core.results.backup import BackupResult, Timing
from bkx.core.results.execution_plan import execution_plan_to_dict, plan_for_spec
from bkx.core.results.restore import RestoreResult
from bkx.core.results.transfer import TransferProgress, TransferResult
from bkx.core.schemas import get_schema_for_spec
from bkx.core.services.archive import ArchiveService
from bkx.core.services.transfer import TransferService
from bkx.core.specs.archive import ArchiveCreateSpec, ArchiveInfoSpec, ArchiveListSpec, ArchiveVerifySpec
from bkx.core.specs.backup import BackupSpec, CompressionProfile
from bkx.core.specs.common import contained_path
from bkx.core.specs.restore import RestoreSpec
from bkx.core.specs.transfer import TransferSpec, TransferSyncSpec


# ---------------------------------------------------------------------------
# RM-009: canonical Spec validation
# ---------------------------------------------------------------------------

@pytest.mark.parametrize(
    ("algorithm", "level"),
    [("wat", 3), ("gzip", 0), ("gzip", 10), ("zstd", 0), ("zstd", 23), ("bzip2", 0)],
)
def test_compression_profile_rejects_invalid_algorithm_or_level(algorithm: str, level: int) -> None:
    with pytest.raises(ValidationError):
        CompressionProfile(algorithm=algorithm, level=level)


@pytest.mark.parametrize("split_size", [0, -1, True, 1.5])
def test_split_size_must_be_positive_integer(split_size: object, tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    with pytest.raises(ValidationError):
        BackupSpec(sources=(source,), split_size=split_size)  # type: ignore[arg-type]


def test_include_exclude_conflict_rejected(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    with pytest.raises(ValidationError):
        ArchiveCreateSpec(sources=(source,), includes=("*.txt",), excludes=("*.txt",))


@pytest.mark.skipif(not hasattr(os, "mkfifo"), reason="FIFO probe requires os.mkfifo")
def test_archive_special_file_root_rejected(tmp_path: Path) -> None:
    fifo = tmp_path / "pipe"
    os.mkfifo(fifo)
    with pytest.raises(ValidationError):
        ArchiveCreateSpec(sources=(fifo,), output_path=tmp_path / "out.tar").normalize()




def test_directional_transfer_and_restore_policy_impossibilities_fail_at_construction(tmp_path: Path) -> None:
    with pytest.raises(ValidationError):
        TransferSpec(sources=("s3://bucket/key",), destination=tmp_path / "local", direction="push")
    with pytest.raises(ValidationError):
        TransferSpec(sources=(tmp_path / "local",), destination="s3://bucket/key", direction="pull")
    archive = tmp_path / "archive.tar"
    archive.write_bytes(b"x")
    with pytest.raises(ValidationError):
        RestoreSpec(archive=archive, overwrite=True, confirm_overwrite=True)

def test_transfer_source_elements_are_validated_and_all_sources_preserved(tmp_path: Path) -> None:
    with pytest.raises(ValidationError):
        TransferSpec(sources=(object(),), destination=tmp_path / "dest")  # type: ignore[arg-type]

    first = tmp_path / "first"
    second = tmp_path / "second"
    destination = tmp_path / "dest"
    first.write_text("a")
    second.write_text("b")
    destination.mkdir()
    spec = TransferSpec(sources=(first, second), destination=destination).normalize()
    assert spec.effective_sources == (first.resolve(), second.resolve())
    assert spec.effective_source == first.resolve()


# ---------------------------------------------------------------------------
# RM-010: canonical path/name normalization
# ---------------------------------------------------------------------------

@pytest.mark.parametrize("name", ["../escape", "a/b", r"a\\b", "/absolute", "C:drive"])
def test_backup_archive_name_cannot_encode_a_path(name: str, tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    with pytest.raises(ValidationError):
        BackupSpec(sources=(source,), archive_name=name)


@pytest.mark.parametrize("selector", ["", "../x", "a/../../x", "/abs", r"C:\\abs"])
def test_restore_member_selectors_reject_empty_absolute_or_traversal(selector: str, tmp_path: Path) -> None:
    archive = tmp_path / "archive.tar"
    archive.write_bytes(b"")
    with pytest.raises(ValidationError):
        RestoreSpec(archive=archive, paths=(selector,))


def test_contained_path_stays_beneath_destination(tmp_path: Path) -> None:
    root = tmp_path / "restore"
    result = contained_path(root, "docs/readme.txt")
    assert result == (root / "docs" / "readme.txt").resolve()
    with pytest.raises(ValidationError):
        contained_path(root, "../escape")


def test_archive_output_inside_source_tree_is_rejected(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    with pytest.raises(ValidationError):
        ArchiveCreateSpec(sources=(source,), output_path=source / "archive.tar").normalize()


# ---------------------------------------------------------------------------
# RM-011: deterministic semantic identity
# ---------------------------------------------------------------------------


def test_auto_generated_backup_identity_is_deterministic(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    destination = tmp_path / "backup"
    first = BackupSpec(sources=(source,), destination=destination).normalize()
    second = BackupSpec(sources=(source,), destination=destination).normalize()
    assert first == second
    assert first.archive_name == second.archive_name
    assert compute_backup_spec_hash(first) == compute_backup_spec_hash(second)


def test_private_derived_fields_are_excluded_from_serialization_and_hash(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    destination = tmp_path / "backup"
    spec = BackupSpec(sources=(source,), destination=destination, archive_name="stable").normalize()
    changed_derived = replace(spec, _archive_path=tmp_path / "other.bakery")
    assert spec == changed_derived
    assert "_archive_path" not in spec_to_dict(spec)
    assert "_resolved_destination" not in spec_to_dict(spec)
    assert compute_backup_spec_hash(spec) == compute_backup_spec_hash(changed_derived)


@dataclass(frozen=True)
class _PurePathSpec:
    path: PurePosixPath


def test_spec_serialization_supports_purepath() -> None:
    assert spec_to_dict(_PurePathSpec(PurePosixPath("a/b"))) == {"path": "a/b"}


def test_specialized_hash_helpers_are_stable(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    archive_path = tmp_path / "archive.tar"
    archive_path.write_bytes(b"x")
    backup = BackupSpec(sources=(source,)).normalize()
    archive = ArchiveCreateSpec(sources=(source,), output_path=tmp_path / "output.tar").normalize()
    transfer = TransferSpec(sources=(source,), destination="s3://bucket/key").normalize()
    restore = RestoreSpec(archive=archive_path, destination=tmp_path / "restore", fresh_dir=False).normalize()
    for helper, spec in (
        (compute_backup_spec_hash, backup),
        (compute_archive_spec_hash, archive),
        (compute_transfer_spec_hash, transfer),
        (compute_restore_spec_hash, restore),
    ):
        assert helper(spec) == helper(spec)
        assert len(helper(spec)) == 64


# ---------------------------------------------------------------------------
# RM-012: standards-compliant JSON Schema parity
# ---------------------------------------------------------------------------


def test_normalized_specs_validate_against_checked_in_draft_2020_12_schemas(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    archive_path = tmp_path / "archive.tar"
    archive_path.write_bytes(b"x")
    specs = {
        "backup": BackupSpec(sources=(source,), destination=tmp_path / "backup").normalize(),
        "archive": ArchiveCreateSpec(sources=(source,), output_path=tmp_path / "output.tar").normalize(),
        "transfer": TransferSpec(sources=(source,), destination="s3://bucket/key").normalize(),
        "restore": RestoreSpec(archive=archive_path, destination=tmp_path / "restore", fresh_dir=False).normalize(),
    }
    for name, spec in specs.items():
        schema = get_schema_for_spec(name)
        assert schema["$schema"].endswith("draft/2020-12/schema")
        valid, errors = validate_spec_against_schema(spec_to_dict(spec), schema)
        assert valid, errors


def test_schema_rejects_invalid_nullable_enum_items_and_unknown_fields(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    payload = spec_to_dict(BackupSpec(sources=(source,)).normalize())
    schema = get_schema_for_spec("backup")

    bad_algorithm = json.loads(json.dumps(payload))
    bad_algorithm["compression"]["algorithm"] = "wat"
    assert not validate_spec_against_schema(bad_algorithm, schema)[0]

    bad_items = json.loads(json.dumps(payload))
    bad_items["sources"] = [1]
    assert not validate_spec_against_schema(bad_items, schema)[0]

    bad_unknown = json.loads(json.dumps(payload))
    bad_unknown["surprise"] = True
    assert not validate_spec_against_schema(bad_unknown, schema)[0]

    nullable = json.loads(json.dumps(payload))
    nullable["destination"] = None
    assert validate_spec_against_schema(nullable, schema)[0]

    for invalid_archive_name in ("C:escape", ".", "..", " ", "bad\x00name"):
        bad_name = json.loads(json.dumps(payload))
        bad_name["archive_name"] = invalid_archive_name
        assert not validate_spec_against_schema(bad_name, schema)[0], invalid_archive_name

    bad_pattern = json.loads(json.dumps(payload))
    bad_pattern["includes"] = [" "]
    assert not validate_spec_against_schema(bad_pattern, schema)[0]

    restore_schema = get_schema_for_spec("restore")
    restore_payload = {
        "archive": "/tmp/archive.tar",
        "destination": None,
        "paths": [r"C:\\escape"],
        "overwrite": False,
        "verify": True,
        "dry_run": False,
        "fresh_dir": True,
        "confirm_overwrite": True,
    }
    assert not validate_spec_against_schema(restore_payload, restore_schema)[0]

    for invalid_selector in (".", "..", "./x", " ", "bad\x00name"):
        selector_payload = dict(restore_payload)
        selector_payload["paths"] = [invalid_selector]
        assert not validate_spec_against_schema(selector_payload, restore_schema)[0], invalid_selector

    transfer_schema = get_schema_for_spec("transfer")
    invalid_transfer = {
        "sources": [" "],
        "destination": "s3://bucket/key",
        "direction": "push",
        "verify": True,
        "checksum": None,
        "dry_run": False,
        "overwrite": False,
    }
    assert not validate_spec_against_schema(invalid_transfer, transfer_schema)[0]
    invalid_checksum = dict(invalid_transfer, sources=["/tmp/file"], checksum=" ")
    assert not validate_spec_against_schema(invalid_checksum, transfer_schema)[0]

    with pytest.raises(ValidationError):
        get_schema_for_spec("unknown")


# ---------------------------------------------------------------------------
# RM-013: truthful immutable Results
# ---------------------------------------------------------------------------


def test_result_equality_ignores_volatile_timing_and_nested_state_is_immutable() -> None:
    first = BackupResult(
        success=False,
        dry_run=False,
        timing=Timing(1, 2),
        warnings=("warning",),
        errors=("error",),
        step_results={"archive": False},
        checksums={"archive": "abc"},
    )
    second = BackupResult(
        success=False,
        dry_run=False,
        timing=Timing(100, 900),
        warnings=("warning",),
        errors=("error",),
        step_results={"archive": False},
        checksums={"archive": "abc"},
    )
    assert first == second
    assert first.errors != first.warnings
    with pytest.raises(TypeError):
        first.step_results["archive"] = True
    with pytest.raises(TypeError):
        first.checksums["archive"] = "changed"
    with pytest.raises(ValueError):
        BackupResult(success=True, dry_run=False, timing=Timing(0, 0), step_results={"archive": "not-bool"})


def test_restore_result_destination_is_root_not_first_member(tmp_path: Path) -> None:
    destination = tmp_path / "restore"
    result = RestoreResult(
        success=True,
        dry_run=False,
        timing=Timing(0, 1),
        destination_path=destination,
        restored_paths=(destination / "file.txt",),
        files_restored=1,
    )
    assert result.destination == destination


def test_archive_verify_failure_cannot_claim_all_checks_passed(tmp_path: Path) -> None:
    with pytest.raises(ValueError):
        ArchiveVerifyResult(success=False, archive_path=tmp_path / "a.tar", checksums_valid=True, structure_valid=True)


def test_transfer_progress_never_exceeds_100_percent() -> None:
    assert TransferProgress(bytes_transferred=200, total_bytes=100).percent_complete == 100.0
    assert TransferProgress(files_transferred=10, total_files=2).percent_complete == 100.0


def test_json_renderer_reads_checksum_from_checksum_contract(capsys: pytest.CaptureFixture[str]) -> None:
    result = BackupResult(
        success=True,
        dry_run=False,
        timing=Timing(0, 1),
        step_results={"archive": True},
        checksums={"archive": "deadbeef"},
    )
    JSONRenderer().render_backup_result(result)
    assert json.loads(capsys.readouterr().out)["checksum"] == "deadbeef"


# ---------------------------------------------------------------------------
# RM-014: one authoritative ExecutionPlan
# ---------------------------------------------------------------------------


def test_backup_plan_matches_bakery_resolution_when_destination_is_configured(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    (source / "a.txt").write_text("a")
    destination = tmp_path / "backup"
    plan = plan_for_spec(BackupSpec(sources=(source,), destination=destination, verify=True, dry_run=True))
    assert [step.step_type for step in plan.steps] == ["archive", "transfer", "verify"]
    assert str(plan.steps[0].destination).endswith(".bakery")
    assert plan.human_timing_estimate == "unknown"


def test_backup_plan_without_destination_keeps_verify_and_has_no_bogus_transfer(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    (source / "a.txt").write_text("a")
    plan = plan_for_spec(BackupSpec(sources=(source,), verify=True, dry_run=True))
    assert [step.step_type for step in plan.steps] == ["archive", "verify"]
    assert all(step.step_type != "transfer" for step in plan.steps)


def test_execution_plan_recursively_counts_directory_files(tmp_path: Path) -> None:
    source = tmp_path / "source"
    (source / "nested").mkdir(parents=True)
    (source / "one.txt").write_text("1")
    (source / "nested" / "two.txt").write_text("22")
    destination = tmp_path / "destination"
    plan = plan_for_spec(TransferSpec(sources=(source,), destination=destination, direction="push", verify=False))
    assert plan.total_files == 2
    assert plan.total_bytes == 3


def test_full_restore_plan_keeps_unknown_count_and_safety_verification_policy(tmp_path: Path) -> None:
    archive = tmp_path / "archive.tar"
    archive.write_bytes(b"archive")
    destination = tmp_path / "restore"
    spec = RestoreSpec(
        archive=archive,
        destination=destination,
        fresh_dir=False,
        verify=True,
        confirm_overwrite=True,
    ).normalize()
    plan = plan_for_spec(spec)
    assert plan.total_files is None
    assert [step.step_type for step in plan.steps] == ["verify", "restore"]
    details = plan.steps[-1].details
    assert details["full_restore"] is True
    assert details["verify"] if "verify" in details else True  # verify is represented by its own step
    assert details["confirm_overwrite"] is True
    assert details["fresh_dir"] is False
    assert execution_plan_to_dict(plan)["timing_estimate"] == "unknown"


def test_cli_plan_paths_import_the_authoritative_planner() -> None:
    import bkx.cli.app as cli_app

    source = inspect.getsource(cli_app)
    assert "plan_for_spec(" in source
    assert "execution_plan_to_dict(" in source
    assert "generate_backup_plan(" not in source


# ---------------------------------------------------------------------------
# RM-015: Spec-first ports/services and framework-free boundaries
# ---------------------------------------------------------------------------


class _RecordingArchiver:
    name = "recording"
    supported_formats = ("tar",)

    def __init__(self) -> None:
        self.seen: list[object] = []

    def create(self, spec: ArchiveCreateSpec, *, progress_callback=None) -> ArchiveCreateResult:
        self.seen.append(spec)
        return ArchiveCreateResult(success=True, dry_run=spec.dry_run, timing=Timing(0, 1), archive_path=spec.output_path)

    def list(self, spec: ArchiveListSpec) -> tuple[ArchiveEntry, ...]:
        self.seen.append(spec)
        return ()

    def info(self, spec: ArchiveInfoSpec) -> ArchiveInfoResult:
        self.seen.append(spec)
        return ArchiveInfoResult(success=True, archive_path=spec.archive_path, format="tar", created_at=0, total_entries=0, total_size=0, uncompressed_size=0, compression_ratio=0.0)

    def verify(self, spec: ArchiveVerifySpec) -> ArchiveVerifyResult:
        self.seen.append(spec)
        return ArchiveVerifyResult(success=True, archive_path=spec.archive_path, checksums_valid=True, structure_valid=True)

    def extract(self, spec: RestoreSpec) -> tuple[Path, ...]:
        self.seen.append(spec)
        return ()


class _RecordingTransfer:
    name = "recording"
    supports_push = True
    supports_pull = True

    def __init__(self) -> None:
        self.seen: list[object] = []

    def push(self, spec: TransferSpec, *, progress_callback=None) -> TransferResult:
        self.seen.append(spec)
        return TransferResult(success=True, direction="push", timing=Timing(0, 1), source=spec.effective_source, destination=spec.destination)

    def pull(self, spec: TransferSpec, *, progress_callback=None) -> TransferResult:
        self.seen.append(spec)
        return TransferResult(success=True, direction="pull", timing=Timing(0, 1), source=spec.effective_source, destination=spec.destination)

    def sync(self, spec: TransferSyncSpec, *, progress_callback=None) -> TransferResult:
        self.seen.append(spec)
        return TransferResult(success=True, direction="sync", timing=Timing(0, 1), source=spec.source, destination=spec.destination)


def test_archive_service_list_info_verify_are_spec_first(tmp_path: Path) -> None:
    archive = tmp_path / "archive.tar"
    archive.write_bytes(b"x")
    port = _RecordingArchiver()
    service = ArchiveService(archiver=port)
    service.list(ArchiveListSpec(archive_path=archive))
    service.info(ArchiveInfoSpec(archive_path=archive))
    service.verify(ArchiveVerifySpec(archive_path=archive))
    assert [type(item) for item in port.seen] == [ArchiveListSpec, ArchiveInfoSpec, ArchiveVerifySpec]


def test_transfer_sync_is_spec_first_and_preserves_policy(tmp_path: Path) -> None:
    source = tmp_path / "source"
    source.mkdir()
    destination = tmp_path / "destination"
    port = _RecordingTransfer()
    service = TransferService(transfer=port)
    spec = TransferSyncSpec(source=source, destination=destination, delete=True, excludes=("*.tmp",), verify=False, overwrite=True)
    result = service.sync(spec)
    seen = port.seen[-1]
    assert isinstance(seen, TransferSyncSpec)
    assert seen.delete is True
    assert seen.excludes == ("*.tmp",)
    assert seen.verify is False
    assert seen.overwrite is True
    assert result.direction == "sync"
    assert result.source == source.resolve()
    assert result.destination == destination.resolve()


def test_remote_uri_semantics_survive_transfer_normalization() -> None:
    uri = "s3://bucket/key"
    spec = TransferSpec(sources=(uri,), destination=Path("local"), direction="pull").normalize()
    assert spec.sources == (uri,)
    assert spec.effective_source == uri
    assert "://" in str(spec.effective_source)


def test_archive_capability_registry_matches_fallback_and_does_not_claim_split() -> None:
    fallback = FallbackArchiver()
    assert tuple(item.identifier for item in SUPPORTED_ARCHIVE_FORMATS) == fallback.supported_formats
    assert all(item.supports_splitting is False for item in SUPPORTED_ARCHIVE_FORMATS)


def test_archiver_progress_callback_and_hasher_stream_contract(tmp_path: Path) -> None:
    source = tmp_path / "source.txt"
    source.write_text("hello")
    output = tmp_path / "archive.tar"
    progress: list[tuple[int, int]] = []
    FallbackArchiver().create(
        ArchiveCreateSpec(sources=(source,), output_path=output).normalize(),
        progress_callback=lambda files, size: progress.append((files, size)),
    )
    assert progress and progress[-1][0] == 1 and progress[-1][1] > 0
    assert FallbackHasher().hash_stream(io.BytesIO(b"abc")) == FallbackHasher().hash_bytes(b"abc")


def test_interface_layers_do_not_import_core_implementations() -> None:
    import bkx.cli.app as cli_app

    assert "bkx.core.impl" not in inspect.getsource(cli_app)
    root = Path(__file__).parents[2]
    interactive_source = root / "src" / "bkx" / "interactive" / "main.py"
    tui_source = root / "src" / "bkx" / "tui" / "app.py"
    assert "bkx.core.impl" not in interactive_source.read_text()
    assert "bkx.core.impl" not in tui_source.read_text()


def test_ov01_error_metadata_lock_does_not_break_exception_runtime_state() -> None:
    error = ExternalToolError(msg="boom", tool_name="tool", details={"nested": {"x": [1]}})
    with pytest.raises(AttributeError):
        error.tool_name = "other"
    with pytest.raises(TypeError):
        error.details["nested"]["x"].append(2)
    # Python must still be allowed to attach traceback/cause/context state.
    error.__traceback__ = None
    with pytest.raises(ExternalToolError):
        raise error
