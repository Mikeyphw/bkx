#!/usr/bin/env python3
"""Source contracts and optional exact unsigned APK pair check (no fake qualification)."""
import argparse
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
GO = "lib/arm64-v8a/libbkxgo.so"
RCLONE = {"lib/arm64-v8a/libbkxrclone_exec.so", "assets/bkx-rclone-runtime.properties"}


def source_check() -> None:
    app = (ROOT / "android/BKX.Android/app/build.gradle.kts").read_text()
    assert 'flavorDimensions += "edition"' in app
    assert 'onVariants(selector().withFlavor("edition" to "embedded"))' in app
    go_section = app.split('onVariants(selector().all())', 1)[1].split('onVariants(selector().withFlavor', 1)[0]
    assert "buildBkxGoAndroidRuntime" in go_section and "installBkxRcloneAndroidRuntime" not in go_section
    embedded_section = app.split('onVariants(selector().withFlavor("edition" to "embedded"))', 1)[1]
    assert 'installBkxRcloneAndroidRuntime' in embedded_section
    assert 'verifyBkxEditionApkContents' in app
    gateway = (ROOT / 'android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt').read_text()
    assert 'builder.environment()["BKX_RCLONE_PATH"] = rcloneRuntimeFile().absolutePath' in gateway
    assert (ROOT / "docs/release/DUAL_VARIANT_CONTRACT.md").is_file()
    devtool = (ROOT / '.devtool.toml').read_text()
    assert 'variant = "embeddedDebug"' in devtool
    assert ':app:bkxBr37StaticPreflightSeal' in devtool
    assert ':app:testEmbeddedDebugUnitTest' not in devtool.split('preflight = [',1)[1].split(']',1)[0]
    verification = (ROOT / 'build-logic/android-verification.gradle.kts').read_text()
    behavioral = verification.split('val bkxBr37BehavioralUnitTasks = listOf', 1)[1].split('val bkxBr37PackageAndReleaseLintTasks',1)[0]
    assert '"testEmbeddedDebugUnitTest"' in behavioral and '"testLiteDebugUnitTest"' in behavioral
    static = verification.split('val bkxBr37StaticPreflightTasks = listOf',1)[1].split('val bkxBr37BehavioralUnitTasks',1)[0]
    assert 'testEmbeddedDebugUnitTest' not in static and 'testLiteDebugUnitTest' not in static
    assert '"verifyBkxEditionApkContents"' in verification
    policy = (ROOT / 'android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/core/release/BkxReleaseArtifactPolicy.kt').read_text()
    assert 'forbiddenLiteMembers' in policy and 'editionMembersAreValid' in policy
    r1 = (ROOT / 'android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/r1/SkybindR1ReadClient.kt').read_text()
    assert 'Skybind' in r1 and 'read' in r1  # unchanged dedicated Skybind transport exists


def apk_check(edition: str, path: Path) -> None:
    with zipfile.ZipFile(path) as archive:
        names = set(archive.namelist())
    assert GO in names, f"{edition}: BKX Go ELF absent"
    if edition == 'embedded':
        assert RCLONE <= names, f"{edition}: missing {RCLONE - names}"
    else:
        assert not (RCLONE & names), f"{edition}: forbidden embedded assets {RCLONE & names}"
    assert not any(name.startswith('lib/') and not name.startswith('lib/arm64-v8a/') for name in names), f"{edition}: foreign ABI"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--embedded-apk', type=Path)
    parser.add_argument('--lite-apk', type=Path)
    args = parser.parse_args()
    source_check()
    if (args.embedded_apk is None) != (args.lite_apk is None):
        parser.error('provide both APKs or neither')
    if args.embedded_apk:
        apk_check('embedded', args.embedded_apk)
        apk_check('lite', args.lite_apk)
        print('PASS_BKX_REL_A_SOURCE_AND_EXACT_APK_PAIR')
    else:
        print('PASS_BKX_REL_A_SOURCE_ONLY; exact APKs NOT TESTED')


if __name__ == '__main__':
    main()
