from __future__ import annotations

import json
from pathlib import Path

from bkx.android_api import execute_json

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android" / "BKX.Android"
SRC = ANDROID / "app" / "src" / "main" / "java" / "com" / "mikeyphw" / "bkx" / "android"


def source(path: str) -> str:
    return (SRC / path).read_text(encoding="utf-8")


def execute(operation: str, request_id: str, payload: dict) -> dict:
    return json.loads(execute_json(json.dumps({
        "protocol_version": 1,
        "request_id": request_id,
        "operation": operation,
        "payload": payload,
    })))


def test_archive_index_scans_direct_roots_and_returns_real_metadata(tmp_path: Path) -> None:
    root = tmp_path / "BKX"
    nested = root / "nested"
    nested.mkdir(parents=True)
    archive = nested / "manual.tar"
    archive.write_bytes(b"not a real tar but indexable by extension")
    ignored = nested / "notes.txt"
    ignored.write_text("ignore", encoding="utf-8")

    result = execute("archive.list", "archive-direct-index", {"roots": [str(root)], "max_depth": 4, "limit": 50})
    assert result["status"] == "success", result
    item = next(a for a in result["artifacts"] if a["path"] == str(archive))
    assert item["label"] == "archive"
    assert item["size_bytes"] == archive.stat().st_size
    assert item["modified_at_epoch_millis"] > 0
    assert all(a["path"] != str(ignored) for a in result["artifacts"])


def test_direct_storage_browser_removable_discovery_and_saf_fallback_are_product_wired() -> None:
    vm = source("app/BkxAppViewModel.kt")
    storage = source("storage/AndroidStorageAccessManager.kt")
    provider = source("files/AndroidProviderStagingManager.kt")
    backup = source("feature/backup/BackupScreen.kt")
    archives = source("feature/archives/ArchivesScreen.kt")
    more = source("feature/more/MoreScreen.kt")

    assert 'operation = "filesystem.list"' in vm
    assert "OpenFilesystemBrowser" in vm and "SelectFilesystemPath" in vm
    assert "archiveRootsProvider(_state.value.rootAvailable)" in vm
    assert "sizeBytes = artifact.sizeBytes" in vm
    assert "storageVolumes" in storage and "volume.directory" in storage
    assert "archiveSearchRoots" in storage
    assert "copyTree" in provider and "copyDocument" in provider
    assert "COPY_BUFFER_BYTES = 1024 * 1024" in provider
    assert "MAX_TREE_ENTRIES = 100_000L" in provider
    assert "totals.entries < MAX_TREE_ENTRIES" in provider and "totals.entries += 1" in provider
    assert "ImportProviderBackupTree" in backup
    assert "ImportProviderArchive" in archives
    assert "ImportProviderTransferTree" in more


def test_live_progress_cancel_foreground_and_settings_are_not_dead_controls() -> None:
    vm = source("app/BkxAppViewModel.kt")
    coordinator = source("app/runtime/BkxOperationCoordinator.kt")
    activity = source("feature/activity/ActivityScreen.kt")
    service = source("app/service/BkxOperationForegroundService.kt")
    more = source("feature/more/MoreScreen.kt")

    assert "operationCoordinator.runtimeState.collect" in vm
    assert "MutableStateFlow(OperationRuntimeState())" in coordinator
    assert "_runtimeState.value = next" in coordinator
    assert "progressPreferenceProvider" in coordinator
    assert "ProgressPreference.EventDriven -> 250L" in coordinator
    assert "ProgressPreference.Polling -> 1_000L" in coordinator
    assert "ProgressPreference.Compact -> 2_000L" in coordinator
    assert "Cancel operation" in activity and "SubmitOperationCancellation" in activity
    assert "updateForeground" in coordinator and "BkxOperationForegroundService.intentFor(context, operation)" in coordinator
    assert "BkxNotificationPermissionStatus.NotRequired" in service
    assert "SharedPreferencesSettingsStore(this).readNow().logPrivacyMode" in service
    assert "override fun onTimeout" in service and "operationCoordinator(this@BkxOperationForegroundService).cancel" in service
    assert "LogPrivacyMode.Diagnostic" in activity
    assert "Root policy is evaluated for every operation" in more


def test_accessibility_visual_and_large_tree_qualification_are_release_gates() -> None:
    build = (ANDROID / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    visual = (ANDROID / "app" / "src" / "androidTest" / "java" / "com" / "mikeyphw" / "bkx" / "android" / "release" / "ProductAccessibilityVisualQualificationTest.kt").read_text(encoding="utf-8")
    qualifier = (ANDROID / "scripts" / "qualify-device.sh").read_text(encoding="utf-8")
    profiler = (ANDROID / "scripts" / "verify-large-tree-profile.py").read_text(encoding="utf-8")

    assert "verifyBkxLargeTreePerformanceSeal" in build
    assert "verifyBkxStorageCompletenessContract" in build
    assert "verifyBkxAccessibilityVisualContract" in build
    assert "createAndroidComposeRule<MainActivity>" in visual
    assert "assertHasClickAction" in visual and "captureToImage" in visual
    assert "bkxVisualQualification" in visual and "bkxVisualQualification" in qualifier
    assert "qualification-screenshots" in qualifier
    assert "tracemalloc" in profiler and "PEAK_MEMORY_LIMIT" in profiler and "FILE_COUNT = 768" in profiler


def test_archive_browse_classifies_links_and_special_entries(tmp_path: Path) -> None:
    import io
    import tarfile

    archive = tmp_path / "typed.tar"
    with tarfile.open(archive, "w") as tf:
        regular = tarfile.TarInfo("regular.txt")
        regular.size = 1
        tf.addfile(regular, io.BytesIO(b"x"))
        link = tarfile.TarInfo("link")
        link.type = tarfile.SYMTYPE
        link.linkname = "regular.txt"
        tf.addfile(link)
        special = tarfile.TarInfo("fifo")
        special.type = tarfile.FIFOTYPE
        tf.addfile(special)

    result = execute("archive.browse", "typed-archive", {
        "archive": {"staged_path": str(archive)},
        "page": {"page_size": 50},
    })
    assert result["status"] == "success", result
    labels = {item["path"]: item["label"] for item in result["artifacts"]}
    assert labels["regular.txt"] == "archive_entry"
    assert labels["link"] == "archive_entry_symlink"
    assert labels["fifo"] == "archive_entry_special"
