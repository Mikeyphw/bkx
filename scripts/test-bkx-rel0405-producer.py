#!/usr/bin/env python3
"""Offline producer behavior and mutation tests (not Android SDK or GitHub qualification)."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT=Path(__file__).resolve().parents[1]

def module(name,path):
    spec=importlib.util.spec_from_file_location(name,str(path));mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod);return mod
LOCK=module('bkx_source_lock',ROOT/'scripts/verify-bclone-source-lock.py')
PRODUCER=module('bkx_unsigned',ROOT/'scripts/produce-bkx-unsigned-candidates.py')

class Producer(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='bkx-rel05-test-')
        self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        self.sdk=self.root/'sdk'
        bin=self.sdk/'build-tools/36.0.0'
        bin.mkdir(parents=True)
        for name,body in {
            'aapt': "#!/bin/sh\necho \"package: name='com.mikeyphw.bkx.android' versionCode='1002003' versionName='1.2.3'\"\n",
            'apksigner':'#!/bin/sh\nexit 1\n',
            'zipalign':'#!/bin/sh\nexit 0\n',
        }.items():
            path=bin/name;path.write_text(body);path.chmod(0o755)
        fake=self.root/'fakebin';fake.mkdir()
        prog=fake/'go';prog.write_text('#!/bin/sh\nexit 0\n');prog.chmod(0o755)
        self.fakebin=fake
        self.apkroot=self.root/'apks'
        self.doc=PRODUCER.contracts()
        self.env=dict(os.environ,
            ANDROID_HOME=str(self.sdk),GITHUB_REPOSITORY='Mikeyphw/bkx',
            GITHUB_SHA='a'*40,GITHUB_RUN_ID='12345',GITHUB_RUN_ATTEMPT='2',
            GITHUB_REF='refs/heads/main',
            GITHUB_WORKFLOW_REF='Mikeyphw/bkx/.github/workflows/bkx-android-unsigned-dual-release.yml@refs/heads/main')
        self.oldpath=os.environ['PATH']
        os.environ['PATH']=str(fake)+os.pathsep+self.oldpath
        self.addCleanup(lambda:os.environ.__setitem__('PATH',self.oldpath))
        for consumer in self.doc['consumers']:
            edition=consumer['variant'].removesuffix('Release').lower()
            directory=self.apkroot/edition/'release'; directory.mkdir(parents=True)
            apk=directory/('app-'+edition+'-release-unsigned.apk')
            self.create(apk,consumer)
            meta={'applicationId':'com.mikeyphw.bkx.android','variantName':consumer['variant'],
                  'elements':[{'outputFile':apk.name,'versionCode':1002003,'versionName':'1.2.3'}]}
            (directory/'output-metadata.json').write_text(json.dumps(meta))
    def create(self,apk,consumer,extras=(),omissions=()):
        with zipfile.ZipFile(apk,'w') as z:
            for name in set(consumer['required_files']+list(extras))-set(omissions):
                z.writestr(name,b'ELF fixture' if name.endswith('.so') else b'provenance')
    def stage(self, patch_provenance=True):
        with patch.object(PRODUCER,'provenance') if patch_provenance else __import__('contextlib').nullcontext():
            PRODUCER.stage(self.apkroot,self.root/'candidates',self.env)
    def test_success_pair_and_exact_actions10_fields(self):
        self.stage()
        assert len(list((self.root/'candidates').iterdir()))==2
        for consumer in self.doc['consumers']:
            folder=self.root/'candidates'/consumer['id']
            self.assertEqual({p.name for p in folder.iterdir()},{'manifest.json',consumer['apk']})
            obj=json.loads((folder/'manifest.json').read_text())
            self.assertEqual(obj['artifact_name'],consumer['artifact'])
            self.assertEqual(obj['variant'],consumer['variant'])
            self.assertEqual(obj['run_attempt'],2)
            self.assertEqual(obj['workflow_path'],'.github/workflows/'+self.doc['workflow'])
            self.assertFalse(obj['qualified_release'])
            self.assertFalse(obj['source_attested'])
            self.assertEqual(obj['signing_state'],'unsigned')
            self.assertEqual(obj['apk_sha256'],PRODUCER.sha256_file(folder/consumer['apk']))
    def test_mixed_lite_rejected_and_neither_published(self):
        consumer=self.doc['consumers'][1];apk=PRODUCER.selected_apk(self.apkroot,consumer)[0]
        self.create(apk,consumer,extras=['lib/arm64-v8a/libbkxrclone_exec.so'])
        with self.assertRaisesRegex(PRODUCER.ContractError,'packaging'): self.stage()
        self.assertFalse((self.root/'candidates').exists())
    def test_embedded_missing_runtime_rejected(self):
        consumer=self.doc['consumers'][0];apk=PRODUCER.selected_apk(self.apkroot,consumer)[0]
        self.create(apk,consumer,omissions=['lib/arm64-v8a/libbkxrclone_exec.so'])
        with self.assertRaisesRegex(PRODUCER.ContractError,'packaging'):self.stage()
    def test_signed_candidate_rejected(self):
        consumer=self.doc['consumers'][0];apk=PRODUCER.selected_apk(self.apkroot,consumer)[0]
        with zipfile.ZipFile(apk,'a') as z:z.writestr('META-INF/CERT.RSA',b'forbidden signer')
        with self.assertRaisesRegex(PRODUCER.ContractError,'signature'):self.stage()
    def test_gradle_output_identity_mismatch_rejected(self):
        metadata=self.apkroot/'lite/release/output-metadata.json';info=json.loads(metadata.read_text())
        info['variantName']='embeddedRelease';metadata.write_text(json.dumps(info))
        with self.assertRaisesRegex(PRODUCER.ContractError,'variant'):self.stage()
    def test_version_mismatch_rejected(self):
        metadata=self.apkroot/'lite/release/output-metadata.json';info=json.loads(metadata.read_text())
        info['elements'][0]['versionCode']=999;metadata.write_text(json.dumps(info))
        with self.assertRaisesRegex(PRODUCER.ContractError,'metadata'):self.stage()
    def test_signature_verifier_success_rejected(self):
        verifier=self.sdk/'build-tools/36.0.0/apksigner';verifier.write_text('#!/bin/sh\nexit 0\n')
        with self.assertRaisesRegex(PRODUCER.ContractError,'signed'):self.stage()
    def test_debuggable_candidate_rejected(self):
        aapt=self.sdk/'build-tools/36.0.0/aapt';aapt.write_text(aapt.read_text()+'echo application-debuggable\n')
        with self.assertRaisesRegex(PRODUCER.ContractError,'debuggable'):self.stage()
    def test_altered_source_sha_rejected(self):
        with patch.object(PRODUCER,'provenance', wraps=PRODUCER.provenance):
            with patch.object(PRODUCER.subprocess,'run') as proc:
                proc.return_value.stdout='b'*40
                self.assertRaisesRegex(PRODUCER.ContractError,'source SHA',PRODUCER.provenance,self.env,self.doc)
    def test_workflow_ref_mismatch_rejected(self):
        self.env['GITHUB_WORKFLOW_REF']='Mikeyphw/bkx/.github/workflows/other.yml@refs/heads/main'
        with self.assertRaisesRegex(PRODUCER.ContractError,'workflow'):
            PRODUCER.provenance(self.env,self.doc)

class Locks(unittest.TestCase):
    def test_pinned_full_commit(self):
        d=LOCK.load(ROOT/'config/bclone-android-source.lock')
        self.assertEqual(d['tag'],'v1.75.3')
        self.assertEqual(d['commit'],'7b63b1a4cc9d13465721ebbfc96f2f9bd810f600')
    def test_mutated_and_duplicate_lock_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            source=(ROOT/'config/bclone-android-source.lock').read_text()
            f=Path(tmp)/'lock'
            for bad in [source.replace('v1.75.3','v1.75'),source.replace('7b63b1a4cc9d13465721ebbfc96f2f9bd810f600','deadbeef'),source+'tag=v1.75.3\n',source+'extra=123\n',source.replace('go1.27.2','latest')]:
                f.write_text(bad)
                with self.assertRaises(ValueError):LOCK.load(f)
    def test_lock_symlink_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            f=Path(tmp)/'lock';f.symlink_to(ROOT/'config/bclone-android-source.lock')
            with self.assertRaises(ValueError):LOCK.load(f)

if __name__=='__main__':unittest.main(verbosity=2)
