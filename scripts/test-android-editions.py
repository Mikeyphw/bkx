#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

FILE = Path(__file__).with_name('verify-android-editions.py')
spec = importlib.util.spec_from_file_location('editions', FILE)
editions = importlib.util.module_from_spec(spec)
spec.loader.exec_module(editions)

class EditionContracts(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
    def apk(self, members):
        path = Path(self.tmp.name) / 'test.apk'
        with zipfile.ZipFile(path, 'w') as zipfile_out:
            for member in members: zipfile_out.writestr(member, b'fixture-only')
        return path
    def test_source(self):
        editions.source_check()
    def test_valid_embedded(self):
        editions.apk_check('embedded', self.apk([editions.GO, *editions.RCLONE]))
    def test_valid_lite(self):
        editions.apk_check('lite', self.apk([editions.GO]))
    def test_reject_mixed_lite(self):
        with self.assertRaises(AssertionError): editions.apk_check('lite', self.apk([editions.GO, *editions.RCLONE]))
    def test_reject_incomplete_embedded(self):
        with self.assertRaises(AssertionError): editions.apk_check('embedded', self.apk([editions.GO]))
    def test_reject_foreign_abi(self):
        with self.assertRaises(AssertionError): editions.apk_check('lite', self.apk([editions.GO,'lib/x86/libbkxgo.so']))
    def test_reject_missing_go(self):
        with self.assertRaises(AssertionError): editions.apk_check('lite', self.apk([]))

if __name__ == '__main__': unittest.main()
