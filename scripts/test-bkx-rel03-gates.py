#!/usr/bin/env python3
"""Run the actual REL-03 checker and mutation-based negative checks."""
from pathlib import Path
import importlib.util
import tempfile
import shutil
import contextlib
import io

MOD = Path(__file__).with_name("verify-bkx-rel03-gates.py")
spec = importlib.util.spec_from_file_location("rel03", MOD)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
module.check()
root = Path(__file__).resolve().parents[1]
paths = ["android/BKX.Android/app/build.gradle.kts", "build-logic/android-verification.gradle.kts", ".devtool.toml", ".github/workflows/ci.yml", ".github/workflows/bkx-r1-device-acceptance-apks.yml", "android/BKX.Android/docs/OV56_GOOGLE_DRIVE_RELEASE_ACCEPTANCE.md"]
with tempfile.TemporaryDirectory(prefix="bkx-rel03-") as d:
    tmp = Path(d)
    for path in paths:
        dest = tmp / path
        dest.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root/path, dest)
    module.ROOT = tmp
    def must_reject(path, before, after):
        f=tmp/path
        text=f.read_text()
        assert before in text, (path,before)
        f.write_text(text.replace(before,after))
        with contextlib.redirect_stdout(io.StringIO()):
            try:
                module.check()
            except (AssertionError,IndexError):
                pass
            else:
                raise AssertionError('failed to reject mutation '+before)
        f.write_text(text)
    must_reject("android/BKX.Android/app/build.gradle.kts", 'dependsOn(verifyBkxEmbeddedRelease16Kb, verifyBkxLiteRelease16Kb)', 'dependsOn(verifyBkxEmbeddedRelease16Kb)')
    must_reject("build-logic/android-verification.gradle.kts", '"testLiteDebugUnitTest",\n    "verifyBkxStartupAppGraphReadinessSeal"', '"testDebugUnitTest",\n    "verifyBkxStartupAppGraphReadinessSeal"')
    must_reject(".devtool.toml", ':app:bkxBr37StaticPreflightSeal', ':app:lintEmbeddedRelease')
    must_reject(".github/workflows/bkx-r1-device-acceptance-apks.yml", 'app-embedded-debug-androidTest.apk', 'app-debug-androidTest.apk')
    must_reject(".github/workflows/ci.yml", 'lint-results-liteDebug', 'lint-results-ignored')
print('PASS_BKX_REL03_MUTATION_NEGATIVE_TESTS=5')
