#!/usr/bin/env python3
"""REL07 source behavior and adversarial tests. No Android/device required."""
import importlib.util
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('bkx_rel07',ROOT/'scripts/bkx-rel07-qualify.py')
mod=importlib.util.module_from_spec(spec);spec.loader.exec_module(mod)


def elf(align=16384):
    data=bytearray(512)
    data[:6]=b'\x7fELF\x02\x01'
    struct.pack_into('<Q',data,32,64)
    struct.pack_into('<H',data,54,56)
    struct.pack_into('<H',data,56,1)
    struct.pack_into('<I',data,64,1)
    struct.pack_into('<Q',data,64+48,align)
    return bytes(data)

class TestREL07(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(prefix='bkx-rel07-test-')
        self.addCleanup(self.tmp.cleanup)
        self.base=Path(self.tmp.name)
        self.embedded=self.base/'embedded.apk'
        self.lite=self.base/'lite.apk'
        for edition,apk in (('embedded',self.embedded),('lite',self.lite)):
            with zipfile.ZipFile(apk,'w') as z:
                z.writestr('lib/arm64-v8a/libbkxgo.so',elf())
                if edition=='embedded':
                    z.writestr('lib/arm64-v8a/libbkxrclone_exec.so',elf())
                    z.writestr('assets/bkx-rclone-runtime.properties','source=test')

    def fake_command(self,args,**kwargs):
        if args[0]=='apksigner':return 'Verifies\nSigner #1 certificate SHA-256 digest: '+'a'*64+'\n'
        if args[0]=='zipalign':return ''
        if args[0]=='aapt':return "package: name='com.mikeyphw.bkx.android' versionCode='18' versionName='1'\nnative-code: 'arm64-v8a'\n"
        raise AssertionError(args)

    def test_elf_16k_rejects_4k_and_truncation(self):
        mod.elf_16k(elf())
        with self.assertRaisesRegex(mod.Reject,'below 16KiB'):mod.elf_16k(elf(4096))
        with self.assertRaisesRegex(mod.Reject,'bounds'):mod.elf_16k(elf()[:70])
        with self.assertRaisesRegex(mod.Reject,'unsupported'):mod.elf_16k(b'abc')

    def test_exact_variant_contents(self):
        e=mod.verify_archive(self.embedded,'embedded')
        l=mod.verify_archive(self.lite,'lite')
        self.assertEqual(e['native_libraries'],2)
        self.assertEqual(l['native_libraries'],1)
        with self.assertRaisesRegex(mod.Reject,'Required variant'):
            mod.verify_archive(self.lite,'embedded')
        with self.assertRaisesRegex(mod.Reject,'Forbidden variant'):
            mod.verify_archive(self.embedded,'lite')

    def test_stray_abi_rejected(self):
        with zipfile.ZipFile(self.lite,'a') as z:
            z.writestr('lib/armeabi-v7a/not-allowed.so',elf())
        with self.assertRaisesRegex(mod.Reject,'Unexpected native ABI'):
            mod.verify_archive(self.lite,'lite')

    def test_duplicate_member_rejected(self):
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter('ignore',UserWarning)
            with zipfile.ZipFile(self.lite,'a') as z:z.writestr('lib/arm64-v8a/libbkxgo.so',elf())
        with self.assertRaisesRegex(mod.Reject,'Duplicate APK'):
            mod.verify_archive(self.lite,'lite')

    def test_signature_16k_package_validation(self):
        with patch.object(mod,'command',side_effect=self.fake_command):
            e=mod.inspect(self.embedded,'embedded','apksigner','zipalign','aapt')
        self.assertEqual(e['version_code'],18)
        self.assertEqual(e['signer_sha256'],'a'*64)
        self.assertTrue(e['zip_16k_verified'])

    def test_wrong_package_denied(self):
        def wrong(args,**kwargs):
            result=self.fake_command(args,**kwargs)
            return result.replace('com.mikeyphw.bkx.android', 'com.other.app') if args[0]=='aapt' else result
        with patch.object(mod,'command',side_effect=wrong):
            with self.assertRaisesRegex(mod.Reject,'Wrong APK package'):
                mod.inspect(self.embedded,'embedded','apksigner','zipalign','aapt')

    def test_source_and_life_receipts_required(self):
        with patch.object(mod,'command',side_effect=self.fake_command):
            with patch.object(mod,'release_receipts',return_value={'source_sha':'a'*40,'run_id':31,'run_attempt':1}):
                pair=mod.preflight(self.embedded,self.lite,'apksigner','zipalign','aapt',None,None)
        self.assertEqual(pair['apk_preflight'],'PASS')
        self.assertEqual(pair['skybind_qualification'],'NOT_RUN')
        self.assertFalse(pair['qualified_release'])
        self.assertNotEqual(pair['variants']['lite']['apk_sha256'],pair['variants']['embedded']['apk_sha256'])

    def test_real_signed_sha_mismatch_denied(self):
        sha='b'*40
        imported={'phase':'ACTIONS-ANDROID-BATCH','operation':'import','complete':True,
                  'qualified_release':False,'repository':'Mikeyphw/bkx',
                  'consumers':['bkx-embedded','bkx-lite'],'source_sha':sha,
                  'run_id':51,'run_attempt':1,'imports':[
                     {'consumer_id':cid,'source_sha':sha,'run_id':51,'run_attempt':1,
                      'qualified_release':False,'registry_id':'unsigned-'+cid,
                      'artifact_id':101 if cid=='bkx-embedded' else 102,
                      'variant':'embeddedRelease' if cid=='bkx-embedded' else 'liteRelease',
                      'archive_verified':True,'unsigned_verified':True,
                      'package':{'sha256':'1'*64 if cid=='bkx-embedded' else '2'*64}}
                     for cid in ('bkx-embedded','bkx-lite')]}
        results=[]
        for cid,variant,digest in [('bkx-embedded','embeddedRelease','c'*64),('bkx-lite','liteRelease','d'*64)]:
            results.append({'qualified_release':False,'signing':{
                'consumer_id':cid,'variant':variant,'registry_id':'unsigned-'+cid,
                'remote_run_id':51,'remote_run_attempt':1,'remote_head_sha':sha,
                'locally_signed':True,'signature_verified':True,'apk_16k_zipalign_verified':True,
                'qualified_release':False,'github_provenance_verified':True,
                'expected_signer_sha256':'a'*64,'signed_sha256':digest,
                'signed_registry_id':'signed-'+cid,'remote_repository':'Mikeyphw/bkx',
                'remote_artifact_id':101 if cid=='bkx-embedded' else 102,
                'unsigned_sha256':'1'*64 if cid=='bkx-embedded' else '2'*64}})
        signed={'phase':'ACTIONS-ANDROID-BATCH','operation':'local-life-sign','complete':True,
                'qualified_release':False,'consumers':['bkx-embedded','bkx-lite'],'signed':results}
        im=self.base/'import.json';si=self.base/'sign.json'
        im.write_text(json.dumps(imported));si.write_text(json.dumps(signed))
        with self.assertRaisesRegex(mod.Reject,'bytes or signer'):
            mod.release_receipts(im,si,{'embedded':{'apk_sha256':'2'*64,'signer_sha256':'a'*64},
                                        'lite':{'apk_sha256':'3'*64,'signer_sha256':'a'*64}})

    def test_device_updates_stay_unqualified(self):
        report={'variants':{'embedded':{'apk_sha256':'a'*64},'lite':{'apk_sha256':'b'*64}},'qualified_release':False,'signer_sha256':'a'*64,'version_code':18}
        marker_events=[]
        def fake_adb(serial,*args,**kw):
            if args==('get-state',):return 'device'
            if args[:3]==('shell','getprop','ro.product.cpu.abilist'):return 'arm64-v8a,armeabi-v7a'
            if args==('shell','su','-c','id'):return 'uid=0(root)'
            if args[0]=='install':return 'Success'
            raise AssertionError(args)
        def probe(serial,sha,uid):return {'installed_sha256':sha,'app_uid':10333,'package':mod.PACKAGE}
        with patch.object(mod,'adb',side_effect=fake_adb),patch.object(mod,'device_sample',side_effect=probe),patch.object(mod,'marker',side_effect=lambda *x:marker_events.append(x)),patch.object(mod,'retention_marker_parent_uid',return_value=10333),patch.object(mod,'prior_install_authority',return_value={'in_place_upgrade_authorized':True}):
            output=mod.device_run(self.embedded,self.lite,'serial123',report,lambda x:None)
        self.assertEqual([x['edition'] for x in output['device_steps']],['embedded','lite','embedded'])
        self.assertEqual([x[2] for x in marker_events],['create','read','read','remove'])
        self.assertEqual(output['device_qualification'],'PARTIAL_INSTALL_SWAP_ONLY')
        self.assertEqual(output['retention_marker_parent'],'VERIFIED_APP_OWNED_PREINSTALL')
        self.assertEqual(output['root_runtime_instrumentation'],'NOT_RUN')
        self.assertFalse(output['qualified_release'])

    def test_failed_marker_stops_swap_and_still_cleans(self):
        report={'variants':{'embedded':{'apk_sha256':'a'*64},'lite':{'apk_sha256':'b'*64}},'signer_sha256':'a'*64,'version_code':18}
        events=[]
        def fake_adb(serial,*args,**kw):
            if args==('get-state',):return 'device'
            if args[:3]==('shell','getprop','ro.product.cpu.abilist'):return 'arm64-v8a'
            if args==('shell','su','-c','id'):return 'uid=0(root)'
            if args[0]=='install':return 'Success'
            raise AssertionError(args)
        def marker(serial,token,action):
            events.append(action)
            if action=='read':raise mod.Reject('sentinel mismatch')
        with patch.object(mod,'adb',side_effect=fake_adb),patch.object(mod,'device_sample',side_effect=lambda *a: {'installed_sha256':a[1],'app_uid':10333}),patch.object(mod,'marker',side_effect=marker),patch.object(mod,'retention_marker_parent_uid',return_value=10333),patch.object(mod,'prior_install_authority',return_value={'in_place_upgrade_authorized':True}):
            with self.assertRaisesRegex(mod.Reject,'sentinel mismatch'):
                mod.device_run(self.embedded,self.lite,'serial123',report,lambda x:None)
        self.assertEqual(events,['create','read','remove'])

    def test_marker_parent_requires_existing_app_owned_directory(self):
        command_output=[]
        def checked_adb(serial,*args,**kwargs):
            command_output.append(args)
            if args==('shell','cmd','package','list','packages','-U',mod.PACKAGE):
                return 'package:'+mod.PACKAGE+' uid:10341\n'
            if args[:3]==('shell','su','-c'):
                self.assertIn('stat -c %u:%a',args[3])
                self.assertNotIn('mkdir',args[3])
                return checked_adb.probe
            raise AssertionError(args)
        with patch.object(mod,'adb',side_effect=checked_adb):
            for observed in ('MISSING\n','0:700\n','10342:700\n','10341:500\n','unexpected'):
                checked_adb.probe=observed
                with self.assertRaises(mod.Reject):
                    mod.retention_marker_parent_uid('serial123')
            checked_adb.probe='10341:700\n'
            self.assertEqual(mod.retention_marker_parent_uid('serial123'),10341)
            checked_adb.probe='10341:771\n'
            self.assertEqual(mod.retention_marker_parent_uid('serial123'),10341)
        self.assertFalse(any('mkdir' in ' '.join(x) for x in command_output))

    def test_marker_create_uses_exclusive_app_owned_file_without_root_mkdir(self):
        seen=[]
        def checked_adb(serial,*args,**kwargs):
            seen.append(args)
            return ''
        with patch.object(mod,'retention_marker_parent_uid',return_value=10341),patch.object(mod,'adb',side_effect=checked_adb):
            token='a'*32
            mod.marker('serial123',token,'create')
            mod.marker('serial123',token,'read') if False else None
            mod.marker('serial123',token,'remove')
        self.assertEqual(len(seen),2)
        command=seen[0][3]
        self.assertIn('set -C;',command)
        self.assertIn("printf '%s' '"+'a'*32+"'",command)
        self.assertIn('chown 10341:10341',command)
        self.assertIn('chmod 600',command)
        self.assertIn('rm -f ',command)  # partial marker creation must clean up
        self.assertIn('exit 1',command)
        self.assertNotIn('mkdir',command)
        self.assertNotIn('chmod 777',command)
        with self.assertRaisesRegex(mod.Reject,'Unsafe'):
            mod.marker('serial123','bad;token','create')
        with self.assertRaisesRegex(mod.Reject,'Unknown'):
            mod.marker('serial123','a'*32,'wrong')

    def test_missing_marker_parent_aborts_before_any_install(self):
        report={'variants':{'embedded':{'apk_sha256':'a'*64},'lite':{'apk_sha256':'b'*64}},
                'signer_sha256':'a'*64,'version_code':18}
        seen=[]
        def checked_adb(serial,*args,**kwargs):
            seen.append(args)
            if args==('get-state',):return 'device'
            if args[:3]==('shell','getprop','ro.product.cpu.abilist'):return 'arm64-v8a'
            if args==('shell','su','-c','id'):return 'uid=0(root)'
            if args[0]=='install':raise AssertionError('No installation before marker-parent preflight')
            raise AssertionError(args)
        with patch.object(mod,'adb',side_effect=checked_adb),patch.object(mod,'prior_install_authority',return_value={'in_place_upgrade_authorized':True}),patch.object(mod,'retention_marker_parent_uid',side_effect=mod.Reject('filesDir missing')):
            with self.assertRaisesRegex(mod.Reject,'filesDir missing'):
                mod.device_run(self.embedded,self.lite,'serial123',report,lambda x:None)
        self.assertFalse(any(x[0]=='install' for x in seen))
        self.assertNotIn('device_steps',report)
        self.assertNotIn('device_install_swap',report)

    def test_root_refusal_has_no_pass(self):
        report={'variants':{'embedded':{'apk_sha256':'a'*64},'lite':{'apk_sha256':'b'*64}},'signer_sha256':'a'*64,'version_code':18}
        def fake_adb(serial,*args,**kw):
            return {'get-state':'device','shell':'denied'}.get(args[0],'')
        with patch.object(mod,'adb',side_effect=fake_adb):
            with self.assertRaisesRegex(mod.Reject,'arm64'):
                mod.device_run(self.embedded,self.lite,'serial123',report,lambda x:None)
        self.assertNotIn('device_install_swap',report)

    def test_preexisting_signer_refusal_before_any_install(self):
        report={'variants':{'embedded':{'apk_sha256':'a'*64},'lite':{'apk_sha256':'b'*64}},
                'signer_sha256':'a'*64,'version_code':18}
        def mock_adb(serial,*args,**kw):
            if args==('get-state',):return 'device'
            if args[:3]==('shell','getprop','ro.product.cpu.abilist'):return 'arm64-v8a'
            if args==('shell','su','-c','id'):return 'uid=0(root)'
            if args[0]=='install':raise AssertionError('Must not install before existing signature check')
            raise AssertionError(args)
        with patch.object(mod,'adb',side_effect=mock_adb),patch.object(mod,'prior_install_authority',side_effect=mod.Reject('Existing BKX signer differs')):
            with self.assertRaisesRegex(mod.Reject,'signer differs'):
                mod.device_run(self.embedded,self.lite,'serial123',report,lambda _:None)
        self.assertNotIn('device_steps',report)

    def test_preexisting_apk_denies_wrong_signer_and_downgrade(self):
        original_command=mod.command
        def fake_check(args,**kwargs):
            if args[0]=='apksigner':return 'Signer #1 certificate SHA-256 digest: '+'b'*64+'\n'
            if args[0]=='aapt':return "package: name='com.mikeyphw.bkx.android' versionCode='19' versionName='1'\n"
            raise AssertionError(args)
        def fake_adb(serial,*args,**kwargs):
            if args==('shell','pm','path',mod.PACKAGE):return 'package:/data/app/example/base.apk\n'
            raise AssertionError(args)
        original_run=subprocess.run
        def fake_run(cmd,**kwargs):
            if cmd[0]=='adb':
                kwargs['stdout'].write(b'installed-apk')
                return type('Completed',(),{'returncode':0})()
            return original_run(cmd,**kwargs)
        with patch.object(mod,'adb',side_effect=fake_adb),patch.object(mod,'command',side_effect=fake_check),patch.object(mod.subprocess,'run',side_effect=fake_run):
            with self.assertRaisesRegex(mod.Reject,'signer differs'):
                mod.prior_install_authority('serial','a'*64,20,'apksigner','aapt')
        def correct_check(args,**kwargs):
            if args[0]=='apksigner':return 'Signer #1 certificate SHA-256 digest: '+'a'*64+'\n'
            if args[0]=='aapt':return "package: name='com.mikeyphw.bkx.android' versionCode='19' versionName='1'\n"
            raise AssertionError(args)
        with patch.object(mod,'adb',side_effect=fake_adb),patch.object(mod,'command',side_effect=correct_check),patch.object(mod.subprocess,'run',side_effect=fake_run):
            with self.assertRaisesRegex(mod.Reject,'downgrade'):
                mod.prior_install_authority('serial','a'*64,18,'apksigner','aapt')

    def test_atomic_file_safety(self):
        out=self.base/'sub/evidence.json'
        mod.atomic_json(out,{'qualified_release':False})
        self.assertEqual(out.stat().st_mode & 0o777,0o600)
        self.assertFalse(json.loads(out.read_text())['qualified_release'])
        out.unlink();out.symlink_to(self.lite)
        with self.assertRaisesRegex(mod.Reject,'symlink'):
            mod.atomic_json(out,{})

    def test_no_mutating_work_without_explicit_consent(self):
        output=self.base/'refusal.json'
        args=['--embedded-apk',str(self.embedded),'--lite-apk',str(self.lite),
              '--import-json',str(self.base/'missing-import.json'),
              '--sign-json',str(self.base/'missing-sign.json'),
              '--evidence-json',str(output),'--execute']
        self.assertEqual(mod.main(args),2)
        self.assertFalse(output.exists())

if __name__=='__main__':unittest.main(verbosity=2)
