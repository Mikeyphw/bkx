#!/usr/bin/env python3
"""REL05-HF01: host-side GitHub expression-context regression tests, no network."""
import importlib.util
from pathlib import Path
from unittest.mock import patch
import unittest

ROOT=Path(__file__).resolve().parents[1]
SPEC=importlib.util.spec_from_file_location('bkx_rel05_verify', ROOT/'scripts/verify-bkx-rel0405-producer.py')
MOD=importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MOD)
PATH='.github/workflows/bkx-android-unsigned-dual-release.yml'
ORIG=(ROOT/PATH).read_text(encoding='utf-8')

class DispatchWorkflowContext(unittest.TestCase):
    def check_edited(self, edited):
        normal_read=MOD.read
        with patch.object(MOD, 'read', side_effect=lambda p: edited if p==PATH else normal_read(p)):
            MOD.check()

    def test_valid_job_environment(self):
        self.assertIn('BKX_ANDROID_SIGNING_PROPERTIES: ${{ github.workspace }}/', ORIG)
        self.check_edited(ORIG)

    def test_runner_context_in_job_env_is_rejected(self):
        bad=ORIG.replace('${{ github.workspace }}/.bkx-signing-disabled',
                         '${{ runner.temp }}/bkx-signing-disabled')
        self.assertNotEqual(ORIG,bad)
        with self.assertRaisesRegex(AssertionError, 'signing-disabled guard'):
            self.check_edited(bad)

    def test_extra_unsupported_runner_context_in_job_env_rejected(self):
        bad=ORIG.replace('BKX_RCLONE_BUILD_JOBS: \'2\'',
                         'BKX_RCLONE_BUILD_JOBS: ${{ runner.temp }}')
        self.assertNotEqual(ORIG,bad)
        with self.assertRaisesRegex(AssertionError, 'runner context is forbidden'):
            self.check_edited(bad)

    def test_missing_signing_guard_rejected(self):
        bad=ORIG.replace('BKX_ANDROID_SIGNING_PROPERTIES:', 'BKX_SIGNING_PROPERTIES:')
        with self.assertRaisesRegex(AssertionError, 'signing-disabled guard'):
            self.check_edited(bad)

    def test_both_artifacts_and_workflow_trigger_preserved(self):
        self.assertIn('\non:\n  workflow_dispatch:',ORIG)
        self.assertEqual(ORIG.count('uses: actions/upload-artifact@v4'),2)
        self.assertIn(':app:assembleEmbeddedRelease :app:assembleLiteRelease',ORIG)
        self.assertNotIn('secrets.',ORIG)

if __name__=='__main__':
    unittest.main(verbosity=2)
