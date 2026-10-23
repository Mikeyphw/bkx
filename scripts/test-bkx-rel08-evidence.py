#!/usr/bin/env python3
"""REL08 promise index and adversarial receipt reconciliation. Offline only."""
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
sp=importlib.util.spec_from_file_location('rel08',ROOT/'scripts/bkx-rel08-evidence.py')
index=importlib.util.module_from_spec(sp);sp.loader.exec_module(index)


def options(**kw):
    fields=('github','import','sign','rel07','gradle','skybind_embedded','skybind_lite',
            'instrumentation_embedded','instrumentation_lite','runtime_embedded','runtime_lite')
    return SimpleNamespace(**{k+'_json':kw.get(k) for k in fields})


def receipts(source):
    ids=('bkx-embedded','bkx-lite');variants=('embeddedRelease','liteRelease')
    imported={'phase':'ACTIONS-ANDROID-BATCH','operation':'import','complete':True,
      'qualified_release':False,'repository':'Mikeyphw/bkx','consumers':list(ids),
      'source_sha':source,'run_id':10012,'run_attempt':2,'imports':[]}
    signed={'phase':'ACTIONS-ANDROID-BATCH','operation':'local-life-sign','complete':True,
      'qualified_release':False,'consumers':list(ids),'signed':[]}
    for i,(cid,variant) in enumerate(zip(ids,variants)):
        aid=100+i
        unsigned=str(i+1)*64;digest=str(i+3)*64
        imported['imports'].append({'consumer_id':cid,'source_sha':source,'run_id':10012,
          'run_attempt':2,'qualified_release':False,'registry_id':'unsigned-'+cid,
          'artifact_id':aid,'variant':variant,'archive_verified':True,
          'unsigned_verified':True,'package':{'sha256':unsigned}})
        signed['signed'].append({'qualified_release':False,'signing':{
          'consumer_id':cid,'variant':variant,'registry_id':'unsigned-'+cid,
          'remote_run_id':10012,'remote_run_attempt':2,'remote_head_sha':source,
          'remote_artifact_id':aid,'unsigned_sha256':unsigned,
          'locally_signed':True,'signature_verified':True,'apk_16k_zipalign_verified':True,
          'qualified_release':False,'github_provenance_verified':True,
          'expected_signer_sha256':'a'*64,'signed_sha256':digest,
          'signed_registry_id':'signed-'+cid,'remote_repository':'Mikeyphw/bkx'}})
    pair={'schema':'bkx-rel07-signed-pair-v1','qualified_release':False,'apk_preflight':'PASS',
          'source_sha':source,'github_run_id':10012,'github_run_attempt':2,
          'signer_sha256':'a'*64,'version_code':18,'variants':{}}
    for i,edition in enumerate(('embedded','lite')):
        pair['variants'][edition]={'variant':variants[i],
          'apk_sha256':signed['signed'][i]['signing']['signed_sha256'],
          'signer_sha256':'a'*64,'zip_16k_verified':True,'elf_16k':True}
    pair['qualified_release']=False
    return imported,signed,pair

class TestREL08(unittest.TestCase):
    def setUp(self):
        t=tempfile.TemporaryDirectory(prefix='bkx-rel08-audit-');self.addCleanup(t.cleanup)
        self.work=Path(t.name)
        self.sha,_,self.promise=index.source_manifest()
        self.imported,self.signed,self.pair=receipts(self.sha)

    def save(self,name,obj):
        target=self.work/(name+'.json');target.write_text(json.dumps(obj))
        return target

    def sources(self):
        return options(import_=None,**{'import':self.save('import',self.imported),
               'sign':self.save('sign',self.signed),'rel07':self.save('rel07',self.pair)})

    def test_all_milestones_recorded_and_no_unearned_pass(self):
        report=index.build(options())
        self.assertEqual(len(report['roadmap_promises']),9)
        self.assertEqual([x['milestone'] for x in report['roadmap_promises']],
                         [f'REL-{i:02d}' for i in range(9)])
        self.assertEqual(report['overall'],'BLOCKED_EXTERNAL_QUALIFICATION')
        self.assertFalse(report['qualified_release'])
        self.assertEqual(report['gates']['skybind_embedded']['status'],'PENDING')
        self.assertGreater(len(report['blockers']),10)

    def test_exact_rm16_and_signed_pair_but_not_device_pass(self):
        report=index.build(self.sources())
        self.assertEqual(report['gates']['life_dual_signed']['status'],'RECEIPT_VERIFIED')
        self.assertEqual(report['gates']['signed_pair_preflight']['status'],'RECEIPT_VERIFIED')
        self.assertEqual(report['gates']['in_place_variant_switch']['status'],'PENDING')
        self.assertFalse(report['qualified_release'])

    def test_cross_variant_artifact_swapped_rejected(self):
        self.signed['signed'][1]['signing']['remote_artifact_id']=100
        with self.assertRaisesRegex(ValueError,'GitHub APK identity'):
            index.build(self.sources())

    def test_unsigned_candidate_byte_swap_rejected(self):
        self.signed['signed'][0]['signing']['unsigned_sha256']='f'*64
        with self.assertRaisesRegex(ValueError,'GitHub APK identity'):
            index.build(self.sources())

    def test_duplicate_artifact_id_rejected(self):
        self.imported['imports'][1]['artifact_id']=100
        with self.assertRaisesRegex(ValueError,'Distinct positive GitHub'):
            index.build(self.sources())

    def test_wrong_source_or_attempt_rejected(self):
        self.imported['source_sha']='f'*40
        with self.assertRaises(ValueError):index.build(self.sources())
        self.imported['source_sha']=self.sha
        self.pair['github_run_attempt']=3
        with self.assertRaisesRegex(ValueError,'run/attempt'):
            index.build(self.sources())

    def test_missing_pair_receipt_rejected(self):
        with self.assertRaisesRegex(ValueError,'supplied together'):
            index.build(options(**{'import':self.save('import',self.imported)}))

    def test_forged_external_pass_cannot_promote(self):
        forged={'schema':'skybind-r1-bkx-device-acceptance-v1',
                'qualified_release':False,'overall':'PASS',
                'source_sha':self.sha,'cases':'PASS'}
        report=index.build(options(skybind_embedded=self.save('skybind',forged)))
        self.assertEqual(report['gates']['skybind_embedded']['status'],
                         'SUBMITTED_NEEDS_INDEPENDENT_AUTHORITY')
        self.assertEqual(report['gates']['skybind_lite']['status'],'PENDING')
        self.assertFalse(report['qualified_release'])
        forged['qualified_release']=True
        with self.assertRaisesRegex(ValueError,'self-promote'):
            index.build(options(skybind_embedded=self.save('skybind',forged)))

    def test_partial_root_swap_is_not_full_release(self):
        self.pair['device_install_swap']='PASS'
        self.pair['existing_install_authority']={
          'in_place_upgrade_authorized':True,'preexisting_signer_sha256':'a'*64}
        self.pair['device_steps']=[{'edition':ed,'installed_sha256':self.pair['variants'][ed]['apk_sha256'],
          'app_uid':10333,'in_place_install':'PASS','private_marker_retained':i>0}
          for i,ed in enumerate(('embedded','lite','embedded'))]
        report=index.build(self.sources())
        self.assertEqual(report['gates']['in_place_variant_switch']['status'],
                         'PARTIAL_DEVICE_RECEIPT')
        self.assertEqual(report['gates']['skybind_lite']['status'],'PENDING')
        self.assertFalse(report['qualified_release'])
        self.pair['existing_install_authority']['preexisting_signer_sha256']='b'*64
        with self.assertRaisesRegex(ValueError,'original signer authority'):
            index.build(self.sources())

    def test_private_immutable_output(self):
        path=self.work/'evidence.json';obj=index.build(options())
        index.private_output(path,obj)
        self.assertEqual(path.stat().st_mode&0o777,0o600)
        with self.assertRaisesRegex(ValueError,'overwrite'):index.private_output(path,obj)
        self.assertEqual(json.loads(path.read_text())['qualified_release'],False)

if __name__=='__main__':unittest.main(verbosity=2)
