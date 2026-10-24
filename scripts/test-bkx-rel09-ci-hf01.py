#!/usr/bin/env python3
"""REL09-CI-HF01: source-locked bclone + Go formatting maintenance regression."""
from __future__ import annotations
import re
import subprocess
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
KOTLIN = 'android/BKX.Android/app/src/test/java/com/mikeyphw/bkx/android/contracts/RcloneRemoteTransportContractTest.kt'
BUILD = 'android/BKX.Android/app/build.gradle.kts'
INSTALL = 'scripts/install-rclone-android-runtime.sh'
LOCK = 'config/bclone-android-source.lock'
GO_FILES = ('internal/bridge/edition_readiness_test.go', 'internal/rcloneops/edition_test.go')


def validate_contract(kotlin: str, build: str, installer: str, lock: str) -> None:
    """Reject both obsolete test assertions and weakening of reviewed-source rules."""
    must_kotlin = (
        'bclone/source-reviewed-or-development-latest/android-24-arm64/16k',
        'config/bclone-android-source.lock',
        'scripts/verify-bclone-source-lock.py',
        'BCLONE_RESOLUTION=\\"reviewed-source-lock\\"',
        'BKX_RCLONE_REQUIRE_LOCK',
        'resolve_reviewed_bclone_release',
        'python3 \\"${\'$\'}validator\\" \\"${\'$\'}lock\\"',
    )
    # Check the Kotlin quoted string itself, so an obsolete assertion cannot silently return.
    assert all(token in kotlin for token in must_kotlin), 'Kotlin test lost reviewed-source policy coverage'
    assert 'assertTrue(build.contains("bclone/github-latest-stable/' not in kotlin, 'Stale mutable source assertion'
    assert 'assertFalse(installer.contains("python3"))' not in kotlin, 'False Python-free validator claim'
    assert 'assertTrue(installer.contains("resolution=github-latest-stable-release"))' not in kotlin, 'Stale provenance assertion'
    assert 'bclone/source-reviewed-or-development-latest/android-24-arm64/16k' in build, 'Runtime input not labeled reviewed-source'
    assert 'config/bclone-android-source.lock' in build and 'scripts/verify-bclone-source-lock.py' in build, 'Gradle lacks reviewed-lock inputs'
    assert 'BCLONE_RESOLUTION="reviewed-source-lock"' in installer, 'Installer lacks pinned-source resolution'
    assert 'BKX_RCLONE_REQUIRE_LOCK' in installer and 'resolve_reviewed_bclone_release' in installer, 'Installer loses fail-closed release lock selection'
    assert 'python3 "$validator" "$lock"' in installer, 'Installer lacks actual lock validation'
    assert 'schema=bkx-bclone-source-lock-v1' in lock, 'Missing lock schema'
    assert 'repository=https://github.com/BenjiThatFoxGuy/bclone.git' in lock, 'Wrong bclone repository'
    assert re.search(r'^tag=v\d+\.\d+\.\d+$',lock,re.M), 'Lock tag not stable semver'
    assert re.search(r'^commit=[0-9a-f]{40}$',lock,re.M), 'Lock commit not pinned'


class ContractChecks(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.k = (ROOT / KOTLIN).read_text()
        cls.b = (ROOT / BUILD).read_text()
        cls.i = (ROOT / INSTALL).read_text()
        cls.l = (ROOT / LOCK).read_text()

    def test_positive_reviewed_source_policy(self):
        validate_contract(self.k,self.b,self.i,self.l)

    def test_reject_obsolete_latest_assertion(self):
        with self.assertRaises(AssertionError):
            validate_contract(self.k + '\nassertTrue(build.contains("bclone/github-latest-stable/foo"))',self.b,self.i,self.l)

    def test_reject_obsolete_no_python_assertion(self):
        with self.assertRaises(AssertionError):
            validate_contract(self.k + '\nassertFalse(installer.contains("python3"))',self.b,self.i,self.l)

    def test_reject_missing_release_lock(self):
        with self.assertRaises(AssertionError):
            validate_contract(self.k,self.b,self.i.replace('BCLONE_RESOLUTION="reviewed-source-lock"','BCLONE_RESOLUTION="unreviewed"'),self.l)

    def test_reject_missing_lock_validation(self):
        with self.assertRaises(AssertionError):
            validate_contract(self.k,self.b,self.i.replace('python3 "$validator" "$lock"','true'),self.l)

    def test_reject_unpinned_commit(self):
        with self.assertRaises(AssertionError):
            validate_contract(self.k,self.b,self.i,re.sub(r'(?m)^commit=.*$', 'commit=latest', self.l))

    def test_go_edition_test_files_are_gofmt_clean(self):
        p=subprocess.run(['gofmt','-l',*(str(ROOT/x) for x in GO_FILES)],capture_output=True,text=True,check=True)
        self.assertEqual(p.stdout.strip(),'','Go edition tests require gofmt')


if __name__ == '__main__':
    unittest.main(verbosity=2)
