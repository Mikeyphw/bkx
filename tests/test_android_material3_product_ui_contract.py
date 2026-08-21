from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android" / "BKX.Android"
SRC = ANDROID / "app" / "src" / "main" / "java" / "com" / "mikeyphw" / "bkx" / "android"


def read(relative: str) -> str:
    return (SRC / relative).read_text(encoding="utf-8")


def test_primary_shell_is_product_navigation_not_developer_tabs() -> None:
    navigation = read("app/navigation/BkxNavigation.kt")
    shell = read("feature/home/BkxProductApp.kt")
    assert all(f"{name}(" in navigation for name in ("Home", "Backup", "Archives", "Activity", "More"))
    assert "NavigationBar" in shell
    assert "NavigationRail" in shell
    for legacy in ('Restore("Restore")', 'Transfers("Transfers")', 'Tools("Tools")', 'Settings("Settings")'):
        assert legacy not in navigation


def test_backup_restore_and_transfer_screens_submit_real_embedded_actions() -> None:
    backup = read("feature/backup/BackupScreen.kt")
    archives = read("feature/archives/ArchivesScreen.kt")
    more = read("feature/more/MoreScreen.kt")
    view_model = read("app/BkxAppViewModel.kt")
    assert "BkxHomeEvent.RequestBackupPlan" in backup
    assert "BkxHomeEvent.CreateBackup" in backup
    assert "BkxHomeEvent.RequestRestorePreview" in archives
    assert "BkxHomeEvent.ExecuteRestore" in archives
    assert "Search archives" in archives
    assert "ArchiveSort" in archives
    assert "ArchiveEntryFilter" in archives
    assert "BkxHomeEvent.RequestTransferPreview" in more
    assert "BkxHomeEvent.RunTransfer" in more
    assert "operationCoordinator.execute" in view_model
    assert "operationCoordinator.runtimeState.collect" in view_model


def test_product_ui_hides_bridge_and_termux_backend_vocabulary() -> None:
    product = "\n".join(
        read(path)
        for path in (
            "feature/home/HomeScreen.kt",
            "feature/backup/BackupScreen.kt",
            "feature/archives/ArchivesScreen.kt",
            "feature/activity/ActivityScreen.kt",
            "feature/more/MoreScreen.kt",
        )
    )
    forbidden = ("RUN_COMMAND", "Termux backend", "bridge request", "request JSON", "response JSON", "Preview token:")
    for term in forbidden:
        assert term not in product
    assert "Termux home" in product  # storage target is intentionally first-class


def test_theme_has_dark_light_dynamic_and_persisted_appearance() -> None:
    theme = read("app/ui/theme/BkxTheme.kt")
    settings = read("core/settings/BkxSettings.kt")
    assert "lightColorScheme" in theme
    assert "darkColorScheme" in theme
    assert "dynamicLightColorScheme" in theme
    assert "dynamicDarkColorScheme" in theme
    assert "AppearancePreference" in settings
    assert "dynamicColor" in settings


def test_root_storage_presets_survive_product_ui_replacement() -> None:
    storage = read("storage/StorageAccessModels.kt")
    more = read("feature/more/MoreScreen.kt")
    for path in (
        "/data/data/com.termux/files",
        "/data/data/com.termux/files/home",
        "/data/data/com.termux/files/usr",
    ):
        assert path in storage
    assert "storageAccess.discoverLocations(state.rootAvailable)" in more
