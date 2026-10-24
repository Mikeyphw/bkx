#!/usr/bin/env python3
"""REL-03: source + task-graph contracts; no Gradle build or device qualification."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def source(path):
    return (ROOT / path).read_text(encoding="utf-8")

def check():
    gradle = source("android/BKX.Android/app/build.gradle.kts")
    gates = source("build-logic/android-verification.gradle.kts")
    devtool = source(".devtool.toml")
    ci = source(".github/workflows/ci.yml")
    r1 = source(".github/workflows/bkx-r1-device-acceptance-apks.yml")
    guide = source("android/BKX.Android/docs/OV56_GOOGLE_DRIVE_RELEASE_ACCEPTANCE.md")
    required = ["testEmbeddedDebugUnitTest", "testLiteDebugUnitTest", "lintEmbeddedDebug", "lintLiteDebug", "lintEmbeddedRelease", "lintLiteRelease", "assembleEmbeddedRelease", "assembleLiteRelease"]
    for name in required:
        assert name in gates or name in ci, f"not wired: {name}"
    for old in (":app:testDebugUnitTest", ":app:lintDebug", ":app:lintRelease", ":app:assembleRelease"):
        assert old not in gates and old not in ci and old not in guide, f"active legacy task: {old}"
    assert 'verifyBkxEmbeddedRelease16Kb' in gradle and 'verifyBkxLiteRelease16Kb' in gradle
    # Distinct verification nodes must load each variant's real AGP output,
    # not assume signed-style filenames in the unsigned GitHub release lane.
    sections = [
        gradle.split('tasks.register<VerifyBkxApkNativePageSizeTask>("verifyBkxEmbeddedRelease16Kb")',1)[1].split('val verifyBkxLiteRelease16Kb',1)[0],
        gradle.split('tasks.register<VerifyBkxApkNativePageSizeTask>("verifyBkxLiteRelease16Kb")',1)[1].split('tasks.register("verifyBkx16KbNativePageSizeContract")',1)[0],
    ]
    for section, edition in zip(sections, ("embedded", "lite")):
        assert f'outputs/apk/{edition}/release' in section
        assert f'expectedVariant.set("{edition}Release")' in section
        assert 'releaseOutputDirectory.set(' in section
        assert 'releaseApk.set(' not in section
    assert 'object BkxReleaseApkOutputs' in gradle
    locator = gradle.split('object BkxReleaseApkOutputs', 1)[1].split('abstract class VerifyBkxApkNativePageSizeTask', 1)[0]
    for token in ('output-metadata.json', 'JsonSlurper', 'variantName', 'applicationId', 'elements', 'outputFile', 'apks.size == 1', 'Files.isSymbolicLink'):
        assert token in locator, f'AGP APK output locator missing {token}'
    assert 'app-embedded-release.apk' not in gradle and 'app-lite-release.apk' not in gradle
    contents = gradle.split('tasks.register("verifyBkxEditionApkContents")', 1)[1].split('// Verification tasks live outside', 1)[0]
    assert 'BkxReleaseApkOutputs.locate(embeddedOutput.get().asFile, "embeddedRelease")' in contents
    assert 'BkxReleaseApkOutputs.locate(liteOutput.get().asFile, "liteRelease")' in contents
    aggregate = gradle.split('tasks.register("verifyBkx16KbNativePageSizeContract")',1)[1].split('\nandroidComponents',1)[0]
    assert 'dependsOn(verifyBkxEmbeddedRelease16Kb, verifyBkxLiteRelease16Kb)' in aggregate
    assert '"verifyBkxEditionApkContents"' in gates
    behavioral = gates.split('val bkxBr37BehavioralUnitTasks = listOf',1)[1].split('val bkxBr37PackageAndReleaseLintTasks',1)[0]
    static = gates.split('val bkxBr37StaticPreflightTasks = listOf',1)[1].split('val bkxBr37BehavioralUnitTasks',1)[0]
    for name in ("testEmbeddedDebugUnitTest","testLiteDebugUnitTest"):
        assert name in behavioral and name not in static
    assert "bkxBr37PackageAndReleaseLintSeal" in devtool
    preflight = devtool.split('preflight = [',1)[1].split(']',1)[0]
    for disallowed in ("assemble", "lint", "test", "16Kb", "Package"):
        assert disallowed not in preflight, f"expensive preflight {disallowed}"
    assert 'lintEmbeddedRelease' in gates and 'lintLiteRelease' in gates
    assert gates.count('"lintEmbeddedDebug",\n        "lintEmbeddedDebug"') == 0
    for name in ("testEmbeddedDebugUnitTest", "testLiteDebugUnitTest", "lintEmbeddedDebug", "lintLiteDebug", "lintEmbeddedRelease", "lintLiteRelease"):
        assert name in guide, f"OV5/6 guide missing {name}"
    assert ":app:assembleEmbeddedDebug :app:assembleEmbeddedDebugAndroidTest" in r1
    assert 'app-embedded-debug-androidTest.apk' in r1
    assert 'BKX_BUILD_COMMIT' in r1 and 'apksigner' in r1
    assert 'lint-results-liteDebug' in ci
    assert 'bkxBr37StaticPreflightSeal' in gates and 'bkxBr37BehavioralUnitSeal' in gates
    print('PASS_BKX_REL03_TASK_GRAPH_SOURCE_CONTRACT; GRADLE AND DEVICE UNQUALIFIED')

if __name__ == "__main__":
    check()
