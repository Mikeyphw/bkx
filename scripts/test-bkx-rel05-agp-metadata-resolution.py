#!/usr/bin/env python3
"""REL05-HF03: guard unsigned AGP output resolution after GitHub run 40043082497.

Structural tests protect Kotlin Gradle task bindings; executable tests run the
same AGP-metadata contract through BKX's Python unsigned candidate producer.
These do NOT claim a real Gradle build or installed APK qualification.
"""
from __future__ import annotations
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import shutil
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

def load(path, name):
    spec = importlib.util.spec_from_file_location(name, ROOT/path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

GATES = load('scripts/verify-bkx-rel03-gates.py', 'hf03_rel03')
PRODUCER = load('scripts/produce-bkx-unsigned-candidates.py', 'hf03_producer')

class SourceContracts(unittest.TestCase):
    def test_baseline(self):
        with contextlib.redirect_stdout(io.StringIO()):
            GATES.check()

    def test_locator_fail_closed_mutations(self):
        original_root = GATES.ROOT
        with tempfile.TemporaryDirectory(prefix='bkx-rel05-hf03-') as work:
            GATES.ROOT = Path(work)
            resources = (
                'android/BKX.Android/app/build.gradle.kts',
                'build-logic/android-verification.gradle.kts',
                '.devtool.toml',
                '.github/workflows/ci.yml',
                '.github/workflows/bkx-r1-device-acceptance-apks.yml',
                'android/BKX.Android/docs/OV56_GOOGLE_DRIVE_RELEASE_ACCEPTANCE.md',
            )
            try:
                for item in resources:
                    target = GATES.ROOT/item
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(ROOT/item, target)
                source = GATES.ROOT/resources[0]
                original = source.read_text()
                mutations = (
                    ('"output-metadata.json"', '"guessed-output.apk"'),
                    ('doc["variantName"] == variant', 'true'),
                    ('doc["applicationId"] == "com.mikeyphw.bkx.android"', 'true'),
                    ('apks.size == 1', 'apks.isNotEmpty()'),
                    ('expectedVariant.set("liteRelease")', 'expectedVariant.set("embeddedRelease")'),
                    ('BkxReleaseApkOutputs.locate(liteOutput.get().asFile, "liteRelease")',
                     'BkxReleaseApkOutputs.locate(liteOutput.get().asFile, "embeddedRelease")'),
                )
                for before, after in mutations:
                    self.assertIn(before, original)
                    source.write_text(original.replace(before, after, 1))
                    with self.subTest(before=before):
                        with contextlib.redirect_stdout(io.StringIO()), self.assertRaises((AssertionError, IndexError)):
                            GATES.check()
                    source.write_text(original)
            finally:
                GATES.ROOT = original_root

class AGPMetadata(unittest.TestCase):
    def setUp(self):
        self.t = tempfile.TemporaryDirectory(prefix='bkx-agp-output-')
        self.addCleanup(self.t.cleanup)
        self.root = Path(self.t.name)
        self.consumer = {'variant': 'embeddedRelease'}
        self.directory = self.root/'embedded'/'release'
        self.directory.mkdir(parents=True)
        self.name = 'app-embedded-release-unsigned.apk'
        (self.directory/self.name).write_bytes(b'fixture-only; not real APK')
        self.doc = {
            'variantName':'embeddedRelease','applicationId':'com.mikeyphw.bkx.android',
            'elements':[{'outputFile':self.name,'versionCode':123, 'versionName':'0.0.123'}],
        }
        self.commit()
    def commit(self):
        (self.directory/'output-metadata.json').write_text(json.dumps(self.doc))
    def select(self):
        return PRODUCER.selected_apk(self.root, self.consumer)[0]
    def test_unsigned_filename_is_selected(self):
        self.assertEqual(self.select().name,self.name)
    def test_missing_declared_output_rejected(self):
        self.doc['elements'][0]['outputFile']='app-embedded-release.apk'
        self.commit()
        with self.assertRaises(PRODUCER.ContractError):self.select()
    def test_variant_mismatch_rejected(self):
        self.doc['variantName']='liteRelease'
        self.commit()
        with self.assertRaises(PRODUCER.ContractError):self.select()
    def test_multiple_outputs_rejected(self):
        (self.directory/'app-embedded-release.apk').write_bytes(b'fake')
        with self.assertRaises(PRODUCER.ContractError):self.select()
    def test_traversal_rejected(self):
        self.doc['elements'][0]['outputFile']='../app-embedded-release.apk'
        self.commit()
        with self.assertRaises(PRODUCER.ContractError):self.select()
    def test_symlink_rejected(self):
        path = self.directory/self.name
        path.unlink()
        path.symlink_to(self.directory/'output-metadata.json')
        with self.assertRaises(PRODUCER.ContractError):self.select()

if __name__ == '__main__': unittest.main(verbosity=2)
