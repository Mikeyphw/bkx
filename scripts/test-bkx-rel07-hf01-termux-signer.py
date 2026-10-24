#!/usr/bin/env python3
"""Termux 0.9 and SDK apksigner compatibility, fail-closed mutation tests."""
from __future__ import annotations
import importlib.util
from pathlib import Path
import os
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import sys

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
import bkx_apksigner_certificate as parser

CERT='5601d49884487a5d8f378b7665e37fff8b6808e7a1a038a465de1d07c9a5ef8b'
TERMUX='''Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Number of signers: 1
V3.0 Signer: certificate DN: CN=Devtool Android Release
V3.0 Signer: certificate SHA-256 digest: '''+CERT+'''
V3.0 Signer: certificate SHA-1 digest: 1bee1cc1f1afa898c3bb9da32e9aa713cc8c4730
V3.0 Signer: public key SHA-256 digest: f645cf96dc48e64a4dc0e0c5a69c5114e62d6d8dcedf5e3f6116039caa584491
'''
LEGACY='Verifies\nSigner #1 certificate SHA-256 digest: '+CERT+'\n'


class TermuxSignerTest(unittest.TestCase):
    def test_actual_termux_09(self):
        self.assertEqual(parser.parse_certificate(TERMUX), CERT)

    def test_sdk_version(self):
        self.assertEqual(parser.parse_certificate(LEGACY), CERT)

    def test_termux_v2_and_v3_same_signer(self):
        s=TERMUX+'V2.0 Signer: certificate SHA-256 digest: '+CERT+'\n'
        self.assertEqual(parser.parse_certificate(s),CERT)

    def test_colon_separated_digest(self):
        digest=':'.join(CERT[i:i+2] for i in range(0,64,2))
        self.assertEqual(parser.parse_certificate('Signer #1 certificate SHA-256 digest: '+digest),CERT)

    def test_wrong_signer_count_rejected(self):
        for n in (0,2,3):
            with self.subTest(n=n),self.assertRaises(parser.CertificateRejected):
                parser.parse_certificate(TERMUX.replace('Number of signers: 1',f'Number of signers: {n}'))

    def test_indexed_second_signer_even_identical_cert_rejected(self):
        with self.assertRaises(parser.CertificateRejected):
            parser.parse_certificate(LEGACY+'Signer #2 certificate SHA-256 digest: '+CERT+'\n')

    def test_conflicting_schemes_rejected(self):
        with self.assertRaises(parser.CertificateRejected):
            parser.parse_certificate(TERMUX+'V2.0 Signer: certificate SHA-256 digest: '+'a'*64+'\n')

    def test_malformed_or_spoofed_lines_rejected(self):
        for line in ('Signer #1 certificate SHA-256 digest: nope',
                     'V3.0 Signer: certificate SHA-256 digest: not-a-certificate',
                     'Unknown signer certificate SHA-256 digest: '+CERT):
            with self.subTest(line=line),self.assertRaises(parser.CertificateRejected):
                parser.parse_certificate(LEGACY+line+'\n')

    def test_cannot_confuse_public_key_digest_for_cert(self):
        with self.assertRaises(parser.CertificateRejected):
            parser.parse_certificate('Verifies\nV3.0 Signer: public key SHA-256 digest: '+CERT)

    def test_no_cert_rejected(self):
        with self.assertRaises(parser.CertificateRejected):
            parser.parse_certificate('Verifies\nNumber of signers: 1')

    def test_duplicate_count_lines_rejected(self):
        with self.assertRaises(parser.CertificateRejected):
            parser.parse_certificate(TERMUX+'Number of signers: 1\n')

    def test_cli_success_and_nonzero_verify_failure(self):
        with tempfile.TemporaryDirectory() as td:
            t=Path(td);apk=t/'apk';apk.write_bytes(b'mock fixture only')
            signer=t/'mock-signer'
            signer.write_text('#!/bin/sh\nprintf "%s\\n" "'+TERMUX.replace('\n','" "')+'"\n')
            signer.chmod(0o755)
            with patch.object(parser.subprocess,'run',return_value=type('P',(),{'returncode':0,'stdout':TERMUX})()):
                with patch('builtins.print') as out:
                    self.assertEqual(parser.main(['--apksigner',str(signer),'--apk',str(apk)]),0)
                    out.assert_called_once_with(CERT)
            with patch.object(parser.subprocess,'run',return_value=type('P',(),{'returncode':1,'stdout':TERMUX})()):
                self.assertEqual(parser.main(['--apksigner',str(signer),'--apk',str(apk)]),2)
            with patch.object(parser.subprocess,'run',return_value=type('P',(),{'returncode':0,'stdout':LEGACY.replace('Verifies','Not verified')})()):
                self.assertEqual(parser.main(['--apksigner',str(signer),'--apk',str(apk)]),2)

    def test_rel07_preflight_parses_termux(self):
        spec=importlib.util.spec_from_file_location('bkx_rel07_hf01',ROOT/'scripts/bkx-rel07-qualify.py')
        mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)
        with tempfile.TemporaryDirectory() as td:
            root=Path(td);apk=root/'embedded.apk'
            # APK shape checked by existing REL07 source suite; here focus signer format.
            with patch.object(mod,'verify_archive',return_value={'native_libraries':2,'elf_16k':True}):
                def fake(args,**kwargs):
                    if args[0]=='apksigner': return TERMUX
                    if args[0]=='zipalign': return ''
                    if args[0]=='aapt': return "package: name='com.mikeyphw.bkx.android' versionCode='1000000' versionName='1.0.0'\nnative-code: 'arm64-v8a'\n"
                    raise AssertionError(args)
                apk.write_bytes(b'test bytes')
                with patch.object(mod,'command',side_effect=fake):
                    self.assertEqual(mod.inspect(apk,'embedded','apksigner','zipalign','aapt')['signer_sha256'],CERT)

    def test_shell_consumer_shared_helper_no_weak_fallback(self):
        script=(ROOT/'android/BKX.Android/scripts/qualify-device.sh').read_text()
        self.assertIn('bkx_apksigner_certificate.py',script)
        self.assertIn('if ! app_cert=',script)
        self.assertIn('if ! test_cert=',script)
        self.assertNotIn('sed -n \'s/^Signer #1',script)
        self.assertEqual(subprocess.run(['bash','-n',str(ROOT/'android/BKX.Android/scripts/qualify-device.sh')]).returncode,0)


if __name__=='__main__':unittest.main(verbosity=2)
