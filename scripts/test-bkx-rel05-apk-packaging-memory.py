#!/usr/bin/env python3
"""REL05-HF02: source-bound regression for the hosted APK Zipflinger OOM."""
from pathlib import Path
import importlib.util
from unittest.mock import patch
import unittest

ROOT=Path(__file__).resolve().parents[1]
WORKFLOW='.github/workflows/bkx-android-unsigned-dual-release.yml'
SPEC=importlib.util.spec_from_file_location('bkx_release_producer', ROOT/'scripts/verify-bkx-rel0405-producer.py')
V=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(V)
FLOW=(ROOT/WORKFLOW).read_text()

class HostedPackagingMemory(unittest.TestCase):
    def edited(self, flow):
        orig=V.read
        with patch.object(V,'read',side_effect=lambda path: flow if path==WORKFLOW else orig(path)):
            V.check()

    def test_real_source_contract(self):
        self.edited(FLOW)

    def test_missing_heap_override_rejected(self):
        bad=FLOW.replace('-Xmx4096m','-Xmx1152m')
        self.assertNotEqual(bad,FLOW)
        with self.assertRaisesRegex(AssertionError,'4 GiB'):
            self.edited(bad)

    def test_parallel_packaging_rejected(self):
        bad=FLOW.replace('--no-parallel --max-workers=1','--parallel --max-workers=2')
        self.assertNotEqual(bad,FLOW)
        with self.assertRaisesRegex(AssertionError,'serialize'):
            self.edited(bad)

    def test_only_one_gradle_invocation(self):
        self.assertEqual(FLOW.count('gradle -p android/BKX.Android --no-daemon --stacktrace'),1)
        self.assertIn(':app:assembleEmbeddedRelease :app:assembleLiteRelease',FLOW)
        self.assertEqual(FLOW.count('uses: actions/upload-artifact@v4'),2)

    def test_signing_guards_preserved(self):
        self.assertNotIn('secrets.',FLOW)
        self.assertIn('BKX_UNSIGNED_CI_RELEASE:',FLOW)
        self.assertIn('BKX_ANDROID_SIGNING_PROPERTIES:',FLOW)
        self.assertNotIn('${{ runner.temp }}',FLOW.split('\n    steps:',1)[0])

    def test_termux_gradle_properties_unchanged(self):
        props=(ROOT/'android/BKX.Android/gradle.properties').read_text()
        self.assertIn('org.gradle.jvmargs=-Xmx1152m',props)
        self.assertIn('org.gradle.workers.max=2',props)

if __name__=='__main__':
    unittest.main(verbosity=2)
