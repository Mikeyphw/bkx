#!/usr/bin/env python3
"""REL-06 behavioral + adversarial tests, no network, private keys or mutation of live Devtool."""
from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

ORIGIN=Path(__file__).resolve().parents[1]

def load(path):
    spec=importlib.util.spec_from_file_location('bkx_test_'+path.stem.replace('-','_'),path)
    module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

class TestRM16(unittest.TestCase):
    def setUp(self):
        self.dir=tempfile.TemporaryDirectory(prefix='bkx-rel06-fixture-')
        self.addCleanup(self.dir.cleanup)
        self.root=Path(self.dir.name)/'bkx'
        (self.root/'scripts').mkdir(parents=True)
        (self.root/'config').mkdir()
        for name in ('bkx-devtool-life.py','bkx-devtool-release.py'):
            shutil.copyfile(ORIGIN/'scripts'/name,self.root/'scripts'/name)
        shutil.copyfile(ORIGIN/'config/bkx-android-unsigned-artifacts.json', self.root/'config/bkx-android-unsigned-artifacts.json')
        (self.root/'.gitignore').write_text('/config/android-consumers.json\n')
        subprocess.run(['git','init','-q',str(self.root)],check=True)
        self.life=load(self.root/'scripts/bkx-devtool-life.py')
        self.release=load(self.root/'scripts/bkx-devtool-release.py')
        self.profile=SimpleNamespace(mode='devtool',identity_id='already-installed-life',
          expected_cert_sha256='a'*64,alias='configured-alias',keystore='/outside-private.jks')
        self.life._life=lambda identity='': self.profile
        self.path=self.root/'config/android-consumers.json'

    def test_enroll_uses_real_devtool_registry_parser_and_pair(self):
        record=self.life.enroll('',True)
        self.assertTrue(record['configured'])
        self.assertEqual(self.path.stat().st_mode & 0o777,0o600)
        data=json.loads(self.path.read_text())
        self.assertEqual({c['id'] for c in data['consumers']},{'bkx-embedded','bkx-lite'})
        self.assertEqual({c['signer_sha256'] for c in data['consumers']},{'a'*64})
        self.assertEqual({c['signing_identity_id'] for c in data['consumers']},{'already-installed-life'})
        self.assertEqual([c['target'] for c in data['consumers']],['bkx_android']*2)
        self.assertIn('lib/arm64-v8a/libbkxrclone_exec.so',data['consumers'][1]['forbidden_files'])
        self.assertFalse(record['qualified_release'])
        with self.assertRaisesRegex(self.life.EnrollmentError,'already exists'):
            self.life.enroll('',True)

    def test_no_mutation_without_explicit_consent(self):
        with self.assertRaisesRegex(self.life.EnrollmentError,'--yes'):
            self.life.enroll('',False)
        self.assertFalse(self.path.exists())

    def test_rejects_changed_signed_identity_or_producer_contract(self):
        self.life.enroll('',True)
        self.profile=SimpleNamespace(**{**vars(self.profile),'expected_cert_sha256':'b'*64})
        with self.assertRaisesRegex(self.life.EnrollmentError,'differs'):
            self.life.verify('')
        self.profile=SimpleNamespace(**{**vars(self.profile),'expected_cert_sha256':'a'*64})
        modified=json.loads(self.path.read_text())
        modified['consumers'][1]['forbidden_files']=[]
        self.path.write_text(json.dumps(modified))
        with self.assertRaisesRegex(self.life.EnrollmentError,'differs'):
            self.life.verify('')

    def test_forbids_tracked_policy_and_symlink(self):
        self.life.enroll('',True)
        self.path.unlink()
        self.path.symlink_to(self.root/'config/bkx-android-unsigned-artifacts.json')
        with self.assertRaisesRegex(self.life.EnrollmentError,'symlinked'):
            self.life.verify('')
        self.path.unlink()
        self.path.write_text('{}')
        subprocess.run(['git','-C',str(self.root),'add','-f','config/android-consumers.json'],check=True)
        with self.assertRaisesRegex(self.life.EnrollmentError,'Git-tracked'):
            self.life.verify('')

    def test_inconsistent_signers_refused(self):
        doc=self.life.consumers(self.profile)
        doc['consumers'][1]['signer_sha256']='b'*64
        self.life._official_validate(doc) # structurally valid but not same LIFE identity
        self.assertEqual(len({x['signer_sha256'] for x in doc['consumers']}),2)
        self._assert_pair_gate(doc)

    def _assert_pair_gate(self, doc):
        from devtool.actions_android_release import _batch_contracts
        from devtool.github_actions import GitHubActionsError
        root=self.root
        self.path.write_text(json.dumps(doc))
        with self.assertRaises(GitHubActionsError):
            _batch_contracts(root,'Mikeyphw/bkx',['bkx-embedded','bkx-lite'])

    def test_receipt_audit_fails_closed_on_cross_variant_false_claim(self):
        audit=load(ORIGIN/'scripts/bkx-audit-rm16-receipts.py')
        source='b'*40
        imported={'phase':'ACTIONS-ANDROID-BATCH','operation':'import','complete':True,'qualified_release':False,
                  'repository':'Mikeyphw/bkx','consumers':['bkx-embedded','bkx-lite'],
                  'source_sha':source,'run_id':51,'run_attempt':1,'imports':[
                      {'consumer_id':id,'source_sha':source,'run_id':51,'run_attempt':1,
                       'qualified_release':False,'registry_id':'unsigned-'+id,
                       'artifact_id': 101 if id=='bkx-embedded' else 102,
                       'variant':'embeddedRelease' if id=='bkx-embedded' else 'liteRelease',
                       'archive_verified':True,'unsigned_verified':True,
                       'package':{'sha256':'1'*64 if id=='bkx-embedded' else '2'*64}}
                      for id in ('bkx-embedded','bkx-lite')]}
        signatures=[]
        for cid,variant,digest in [('bkx-embedded','embeddedRelease','c'*64),('bkx-lite','liteRelease','d'*64)]:
            signatures.append({'qualified_release':False,'signing':{
                'consumer_id':cid,'variant':variant,'registry_id':'unsigned-'+cid,
                'remote_run_id':51,'remote_run_attempt':1,'remote_head_sha':source,
                'locally_signed':True,'signature_verified':True,'apk_16k_zipalign_verified':True,
                'qualified_release':False,'github_provenance_verified':True,
                'expected_signer_sha256':'a'*64,'signed_sha256':digest,
                'signed_registry_id':'signed-'+cid,'remote_repository':'Mikeyphw/bkx',
                'remote_artifact_id':101 if cid=='bkx-embedded' else 102,
                'unsigned_sha256':'1'*64 if cid=='bkx-embedded' else '2'*64}})
        signed={'phase':'ACTIONS-ANDROID-BATCH','operation':'local-life-sign','complete':True,
                'qualified_release':False,'consumers':['bkx-embedded','bkx-lite'],'signed':signatures}
        report=audit.audit(imported,signed)
        self.assertFalse(report['qualified_release'])
        self.assertEqual(report['skybind_qualification'],'PENDING')
        signed['signed'][1]['signing']['expected_signer_sha256']='e'*64
        with self.assertRaisesRegex(audit.ReceiptError,'one signer'):
            audit.audit(imported,signed)
        signed['signed'][1]['signing']['expected_signer_sha256']='a'*64
        signed['signed'][1]['signing']['remote_head_sha']='f'*40
        with self.assertRaisesRegex(audit.ReceiptError,'source'):
            audit.audit(imported,signed)
        signed['signed'][1]['signing']['remote_head_sha']=source
        imported['qualified_release']=True
        with self.assertRaisesRegex(audit.ReceiptError,'must not claim'):
            audit.audit(imported,signed)

    def test_delegated_devtool_arguments_and_refusal(self):
        # This uses real executable command assembly; Devtool is never executed.
        (self.root/'scripts/bkx-devtool-life.py').write_text(
             (self.root/'scripts/bkx-devtool-life.py').read_text()+'\ndef verify(identity=""): return {"ok": True}\n')
        with patch.object(self.release.shutil,'which',return_value='/usr/bin/devtool'):
            def args(op,**kw):
                return SimpleNamespace(operation=op,identity='',ref=kw.get('ref',''),
                  run_id=kw.get('run_id',''),source_sha=kw.get('source_sha',''),
                  embedded_artifact_id=kw.get('embedded_artifact_id',''),
                  lite_artifact_id=kw.get('lite_artifact_id',''),
                  embedded_registry_id=kw.get('embedded_registry_id',''),
                  lite_registry_id=kw.get('lite_registry_id',''),
                  download=kw.get('download',False),yes=kw.get('yes',False))
            cmd=self.release.command(args('plan',ref='main'))
            self.assertIn('--source-root',cmd)
            self.assertIn('release-batch-plan',cmd)
            self.assertIn('bkx-embedded,bkx-lite',cmd)
            with self.assertRaisesRegex(ValueError,'--yes'):
                self.release.command(args('run',ref='main'))
            self.assertIn('--yes',self.release.command(args('run',ref='main',yes=True)))
            sha='a'*40
            with self.assertRaisesRegex(ValueError,'distinct'):
                self.release.command(args('check',run_id='2',source_sha=sha,
                  embedded_artifact_id='11',lite_artifact_id='11'))
            cmd=self.release.command(args('check',run_id='2',source_sha=sha,
                  embedded_artifact_id='11',lite_artifact_id='12',download=True))
            self.assertEqual(cmd.count('--artifact'),2)
            self.assertIn('--download',cmd)
            with self.assertRaisesRegex(ValueError,'--yes'):
                self.release.command(args('import',run_id='2',source_sha=sha,
                  embedded_artifact_id='11',lite_artifact_id='12'))
            cmd=self.release.command(args('sign',embedded_registry_id='abc',lite_registry_id='xyz',yes=True))
            self.assertEqual(cmd.count('--registry'),2)
            self.assertIn('--yes',cmd)
            self.assertNotIn('--source-root',cmd)

if __name__=='__main__': unittest.main(verbosity=2)
