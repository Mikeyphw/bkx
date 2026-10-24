#!/usr/bin/env python3
"""REL07 HF02 path policy tests including real Android `~~` install locations."""
from __future__ import annotations
import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
spec=importlib.util.spec_from_file_location('bkx_rel07_hf02',ROOT/'scripts/bkx-rel07-qualify.py')
mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
HASH='2230a3cf45f02e41a5678a6d752291b9d5d055b188317f1b855db1489cb70eca'
CERT='5601d49884487a5d8f378b7665e37fff8b6808e7a1a038a465de1d07c9a5ef8b'
PKG='com.mikeyphw.bkx.android'
VALID=(
 '/data/app/~~wERTy_+-abc==/com.mikeyphw.bkx.android-abcdef==/base.apk',
 '/data/app/com.mikeyphw.bkx.android-1/base.apk',
 '/data/app/~~abc/com.mikeyphw.bkx.android-test@123/base.apk',
)
INVALID=(
 '/data/app/../base.apk','/data/app/./base.apk','/data/app/~~abc/../base.apk',
 '/data/app/~~abc/com.pkg/base.apk;id', '/data/app/~~abc/com.pkg/base.apk$(id)',
 '/data/app/~~abc/com.pkg/base.apk\n', '/data/local/tmp/base.apk',
 '/system/priv-app/BKX/base.apk','/data/app/com.pkg/split_config.arm64_v8a.apk',
 '/data/app/~~abc/com.pkg/other.apk', '/data/app/com.pkg\x00/base.apk',
 '/data/app/~/../com.pkg/base.apk', '/data/app/base.apk',
 '/data/app//com.pkg/base.apk', '/data/app/a/b/c/d/e/base.apk',
)

class ModernPackagePathTest(unittest.TestCase):
    def test_valid_modern_paths(self):
        for v in VALID:
            with self.subTest(path=v):self.assertTrue(mod.trusted_installed_apk_path(v))

    def test_bad_paths_fail(self):
        for v in INVALID:
            with self.subTest(path=v):self.assertFalse(mod.trusted_installed_apk_path(v))
        for v in (None, b'/data/app/com.pkg/base.apk','/data/app/'+'a'*513+'/base.apk'):
            with self.subTest(path=str(v)[:60]):self.assertFalse(mod.trusted_installed_apk_path(v))

    def test_post_install_hash_check_accepts_modern(self):
        path=VALID[0]
        def fake(serial,*args,**kw):
            if args[:5]==('shell','cmd','package','list','packages'):return 'package:'+PKG+' uid:10242\n'
            if args[:3]==('shell','pm','path'):return 'package:'+path+'\n'
            if args[:3]==('shell','su','-c'):return HASH+'  '+path+'\n'
            raise AssertionError(args)
        with patch.object(mod,'adb',side_effect=fake):
            r=mod.device_sample('emulator-5554',HASH)
            self.assertEqual(r['app_uid'],10242)
            self.assertEqual(r['installed_sha256'],HASH)

    def test_post_install_hash_check_rejects_dangerous(self):
        for path in (x for x in INVALID if not x.endswith('\n')):
            def fake(serial,*args,**kw):
                if args[:5]==('shell','cmd','package','list','packages'):return 'package:'+PKG+' uid:10242\n'
                if args[:3]==('shell','pm','path'):return 'package:'+path+'\n'
                raise AssertionError('should not access root for unsafe path')
            with self.subTest(path=path),patch.object(mod,'adb',side_effect=fake),self.assertRaises(mod.Reject):
                mod.device_sample('emulator-5554',HASH)

    def test_preexisting_authority_rejects_unsafe_before_root(self):
        for path in (x for x in INVALID if not x.endswith('\n')):
            def fake(serial,*args,**kw):
                if args[:3]==('shell','pm','path'):return 'package:'+path+'\n'
                raise AssertionError('should not access root')
            with self.subTest(path=path),patch.object(mod,'adb',side_effect=fake),self.assertRaises(mod.Reject):
                mod.prior_install_authority('emulator-5554',CERT,1000000,'apksigner','aapt')

    def test_preexisting_authority_accepts_modern_signer_and_path(self):
        from types import SimpleNamespace
        path=VALID[0]
        def fake_adb(serial,*args,**kw):
            self.assertEqual(args[:3],('shell','pm','path'))
            return 'package:'+path+'\n'
        def fake_run(args,**kw):
            self.assertEqual(args[:5],['adb','-s','emulator-5554','exec-out','su'])
            self.assertEqual(args[-1],'cat '+path)
            kw['stdout'].write(b'bounded signed fixture bytes')
            return SimpleNamespace(returncode=0)
        def fake_command(args,**kw):
            if args[0]=='apksigner':return 'Verifies\nNumber of signers: 1\nV3.0 Signer: certificate SHA-256 digest: '+CERT+'\n'
            if args[0]=='aapt':return "package: name='"+PKG+"' versionCode='1000000' versionName='1.0.0'\n"
            raise AssertionError(args)
        with patch.object(mod,'adb',side_effect=fake_adb),patch.object(mod.subprocess,'run',side_effect=fake_run),patch.object(mod,'command',side_effect=fake_command):
            result=mod.prior_install_authority('emulator-5554',CERT,1000000,'apksigner','aapt')
            self.assertTrue(result['in_place_upgrade_authorized'])
            self.assertEqual(result['preexisting_signer_sha256'],CERT)

    def test_source_path_checks_share_helper(self):
        src=(ROOT/'scripts/bkx-rel07-qualify.py').read_text()
        self.assertIn('trusted_installed_apk_path(path)',src)
        self.assertIn('trusted_installed_apk_path(existing)',src)
        self.assertNotIn("re.fullmatch(r'/[A-Za-z0-9_.=/+@-]+'",src)

if __name__=='__main__':unittest.main(verbosity=2)
