#!/usr/bin/env python3
"""REL-07: exact signed BKX pair and conservative rooted Android swap qualification.

Runs only with --execute --yes and explicit Android serial. The operator's
Skybind R1 root-owned campaign is separate, never simulated by this tool.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import struct
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile

from bkx_apksigner_certificate import parse_certificate

ROOT=Path(__file__).resolve().parents[1]
PACKAGE='com.mikeyphw.bkx.android'
CONSUMERS={'embedded':('bkx-embedded','embeddedRelease'),'lite':('bkx-lite','liteRelease')}
SHA64=re.compile(r'^[0-9a-f]{64}$')
SHA40=re.compile(r'^[0-9a-f]{40}$')
HEX=re.compile(r'^[a-f0-9]{32}$')

class Reject(ValueError): pass

def require(value,message):
    if not value: raise Reject(message)

def digest(path):
    h=hashlib.sha256()
    with open(path,'rb') as handle:
        for block in iter(lambda:handle.read(1024*1024),b''):h.update(block)
    return h.hexdigest()

def command(args,*,binary=False):
    p=subprocess.run([str(s) for s in args],check=False,stdout=subprocess.PIPE,
                     stderr=subprocess.PIPE,timeout=120)
    if p.returncode: raise Reject('External verification command failed: '+str(args[0]))
    return p.stdout if binary else p.stdout.decode('utf-8','replace')

def elf_16k(raw):
    """Inspect every ELF64 PT_LOAD p_align, without shelling out or executing bytes."""
    require(len(raw)>=64 and raw[:4]==b'\x7fELF' and raw[4]==2 and raw[5]==1,
            'APK contains an unsupported native ELF header')
    phoff=struct.unpack_from('<Q',raw,32)[0]
    entsize=struct.unpack_from('<H',raw,54)[0]
    count=struct.unpack_from('<H',raw,56)[0]
    require(entsize>=56 and 0<count<=512 and phoff+entsize*count<=len(raw),
            'Malformed ELF program header bounds')
    found=False
    for i in range(count):
        off=phoff+i*entsize
        if struct.unpack_from('<I',raw,off)[0]!=1:continue
        found=True
        align=struct.unpack_from('<Q',raw,off+48)[0]
        require(align>=16384 and align&(align-1)==0,'Native ELF PT_LOAD below 16KiB alignment')
    require(found,'Native ELF has no loadable segments')

def verify_archive(apk,edition):
    require(apk.is_file() and not apk.is_symlink() and apk.stat().st_size>0,'Missing regular signed APK')
    contract=json.loads((ROOT/'config/bkx-android-unsigned-artifacts.json').read_text('utf-8'))
    consumer=next(c for c in contract['consumers'] if c['id']==CONSUMERS[edition][0])
    with zipfile.ZipFile(apk) as z:
        names=z.namelist()
        require(len(names)==len(set(names)),'Duplicate APK members')
        require(all(not name.startswith('/') and '..' not in name.split('/') for name in names),'Unsafe APK member')
        files=set(names)
        require(all(name in files for name in consumer['required_files']),'Required variant member missing')
        require(all(name not in files for name in consumer['forbidden_files']),'Forbidden variant member present')
        libs=[name for name in names if name.startswith('lib/') and name.endswith('.so')]
        require(bool(libs) and len(libs)<=64,'Invalid native runtime library inventory')
        require(all(name.startswith('lib/arm64-v8a/') for name in libs),'Unexpected native ABI')
        require(sum(z.getinfo(name).file_size for name in libs)<=400*1024*1024,'APK native members exceed safe inspection bound')
        for name in libs:
            info=z.getinfo(name)
            require(info.file_size <= 250*1024*1024,'Native member too large')
            elf_16k(z.read(name))
    return {'native_libraries':len(libs),'required_files':consumer['required_files'],
            'forbidden_files':consumer['forbidden_files'],'elf_16k':True}

def inspect(apk,edition,apksigner,zipalign,aapt):
    packaging=verify_archive(apk,edition)
    signing=command([apksigner,'verify','--verbose','--print-certs',apk])
    require('Verifies' in signing,'APK signature verification incomplete')
    cert=parse_certificate(signing)
    command([zipalign,'-c','-P','16','4',apk])
    badge=command([aapt,'dump','badging',apk])
    match=re.search(r"^package: name='([^']+)' versionCode='([0-9]+)'",badge,re.M)
    require(match is not None and match[1]==PACKAGE and int(match[2])>0,'Wrong APK package/version')
    require("native-code: 'arm64-v8a'" in badge,'APK does not advertise the arm64 native ABI')
    return {'edition':edition,'variant':CONSUMERS[edition][1],'apk_sha256':digest(apk),
            'signer_sha256':cert,'package':match[1],'version_code':int(match[2]),
            'zip_16k_verified':True,**packaging}

def release_receipts(import_path,sign_path,preflight):
    spec=importlib.util.spec_from_file_location('bkx_rel06_audit',ROOT/'scripts/bkx-audit-rm16-receipts.py')
    audit_module=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit_module)
    imports=audit_module._read(import_path)
    signatures=audit_module._read(sign_path)
    audited=audit_module.audit(imports,signatures)
    require(SHA40.fullmatch(audited['source_sha']),'Missing exact source commit')
    for index,edition in enumerate(('embedded','lite')):
        receipt=signatures['signed'][index]['signing']
        require(receipt['signed_sha256']==preflight[edition]['apk_sha256'] and
                receipt['expected_signer_sha256']==preflight[edition]['signer_sha256'],
                'Signed APK bytes or signer differ from immutable LIFE receipt')
    return audited

def preflight(embedded,lite,apksigner,zipalign,aapt,import_path,sign_path):
    e=inspect(embedded,'embedded',apksigner,zipalign,aapt)
    l=inspect(lite,'lite',apksigner,zipalign,aapt)
    require(e['signer_sha256']==l['signer_sha256'],'Two variants have different signers')
    require(e['version_code']==l['version_code'],'Incompatible variant version codes')
    require(e['apk_sha256']!=l['apk_sha256'],'Two variants share identical bytes')
    pair={'embedded':e,'lite':l}
    receipt=release_receipts(import_path,sign_path,pair)
    return {'schema':'bkx-rel07-signed-pair-v1','source_sha':receipt['source_sha'],
            'github_run_id':receipt['run_id'],'github_run_attempt':receipt['run_attempt'],
            'signer_sha256':e['signer_sha256'],'version_code':e['version_code'],
            'variants':pair,'apk_preflight':'PASS','device_qualification':'NOT_RUN',
            'skybind_qualification':'NOT_RUN','qualified_release':False}

def adb(serial,*args,binary=False):
    return command(['adb','-s',serial,*args],binary=binary)

def trusted_installed_apk_path(path):
    """Accept Android's legacy and modern base.apk paths, never shell syntax.

    Android 10+ package paths often include an obfuscated `~~` component.
    The returned pm-path remains untrusted until the original APK signature,
    package ID, version, and (after updates) exact bytes have been checked.
    """
    if not isinstance(path, str) or len(path) > 512:
        return False
    if re.fullmatch(r'/data/app/(?:[A-Za-z0-9_+=@.~-]+/){1,4}base\.apk', path) is None:
        return False
    return all(part not in ('.', '..') for part in path.split('/'))


def device_sample(serial,expected_sha,expected_uid=None):
    packages=adb(serial,'shell','cmd','package','list','packages','-U',PACKAGE)
    uids=re.findall(r'^package:'+re.escape(PACKAGE)+r'\s+uid:(\d+)\s*$',packages,re.M)
    require(len(uids)==1,'Installed BKX UID not verified')
    uid=int(uids[0]);require(uid>10000,'Unexpected application UID')
    if expected_uid is not None:require(uid==expected_uid,'BKX UID changed across update')
    paths=adb(serial,'shell','pm','path',PACKAGE)
    entries=re.findall(r'^package:(/\S+\.apk)\s*$',paths,re.M)
    require(len(entries)==1,'Exactly one installed base APK required (no splits)')
    path=entries[0]
    require(trusted_installed_apk_path(path),
            'Unexpected installed APK location')
    # Operator-approved root is required; verify actual installed APK bytes.
    actual=adb(serial,'shell','su','-c','sha256sum '+path)
    sha=actual.strip().split(' ',1)[0]
    require(sha==expected_sha,'Installed APK SHA differs from signed artifact')
    return {'installed_sha256':sha,'app_uid':uid,'package':PACKAGE}

def prior_install_authority(serial, expected_signer, expected_version, apksigner, aapt):
    """Verify the ORIGINAL installed APK before mutating the selected device.

    Do not trust a remote pm/dumpsys signature string: stream exact root-read
    installed base.apk into a private bounded local file and inspect it using
    the same Android SDK authorities as the release candidates.
    """
    require(SHA64.fullmatch(expected_signer), 'Expected LIFE certificate unavailable')
    require(type(expected_version) is int and expected_version > 0, 'Expected version unavailable')
    paths=adb(serial,'shell','pm','path',PACKAGE)
    matches=re.findall(r'^package:(/\S+\.apk)\s*$',paths,re.M)
    require(len(matches)==1, 'In-place qualification requires one already-installed BKX base APK')
    existing=matches[0]
    require(trusted_installed_apk_path(existing),
            'Installed APK path does not meet trusted package policy')
    with tempfile.TemporaryDirectory(prefix='bkx-rel07-existing-') as tmp:
        destination=Path(tmp)/'installed-base.apk'
        # No shell interpolation beyond the deliberately restricted package path.
        with destination.open('xb') as out:
            proc=subprocess.run(['adb','-s',serial,'exec-out','su','-c','cat '+existing],
                                stdout=out,stderr=subprocess.PIPE,timeout=120,check=False)
        require(proc.returncode==0 and 0<destination.stat().st_size<=512*1024*1024,
                'Cannot privately read currently installed BKX package')
        signed=command([apksigner,'verify','--print-certs',destination])
        cert=parse_certificate(signed)
        require(cert==expected_signer,'Existing BKX signer differs from authorized local LIFE signer')
        details=command([aapt,'dump','badging',destination])
        m=re.search(r"^package: name='([^']+)' versionCode='([0-9]+)'",details,re.M)
        require(m is not None and m[1]==PACKAGE,'Installed package ID differs from BKX')
        old_version=int(m[2])
        require(old_version<=expected_version,'Refusing an in-place downgrade of existing BKX')
        return {'preexisting_package':PACKAGE,'preexisting_apk_sha256':digest(destination),
                'preexisting_signer_sha256':cert,'preexisting_version_code':old_version,
                'in_place_upgrade_authorized':True}

def marker(serial,token,action):
    require(bool(HEX.fullmatch(token)),'Unsafe private retention marker')
    path='/data/user/0/'+PACKAGE+'/files/.bkx-rel07-retention-marker'
    if action=='create':
        # Marker contains random, non-secret fixture bytes; never touches settings.
        adb(serial,'shell','su','-c',"mkdir -p /data/user/0/"+PACKAGE+"/files && printf '"+token+"' > "+path+" && chmod 600 "+path)
    elif action=='read':
        require(adb(serial,'shell','su','-c','cat '+path).strip()==token,
                'App-private retention marker disappeared after update')
    elif action=='remove':
        adb(serial,'shell','su','-c','rm -f '+path)

def device_run(embedded,lite,serial,report,save,apksigner="apksigner",aapt="aapt"):
    require(re.fullmatch('[A-Za-z0-9_.:-]{1,100}',serial),'Invalid explicit Android serial')
    require(adb(serial,'get-state').strip()=='device','Android device not online')
    require(adb(serial,'shell','getprop','ro.product.cpu.abilist').strip().startswith('arm64-v8a'),
            'Qualification requires arm64 device')
    require('uid=0' in adb(serial,'shell','su','-c','id'),'Authorized root unavailable')
    report['existing_install_authority']=prior_install_authority(
        serial,report['signer_sha256'],report['version_code'],apksigner,aapt)
    save(report)
    token=secrets.token_hex(16)
    sequence=[('embedded',embedded),('lite',lite),('embedded',embedded)]
    captured=[];uid=None;created=False
    try:
        for i,(edition,apk) in enumerate(sequence):
            # -r preserves app-private data; never uninstall, clear data, or -d downgrade.
            result=adb(serial,'install','-r',str(apk))
            require('Success' in result,'In-place APK installation not confirmed')
            sample=device_sample(serial,report['variants'][edition]['apk_sha256'],uid)
            uid=sample['app_uid']
            if i==0:
                marker(serial,token,'create');created=True
            else:marker(serial,token,'read')
            captured.append({'step':i+1,'edition':edition,'in_place_install':'PASS',
                             'private_marker_retained':i>0,**sample})
            report['device_steps']=captured
            save(report)
    finally:
        if created:
            try:marker(serial,token,'remove')
            except (OSError,Reject,subprocess.TimeoutExpired):pass
    report['device_install_swap']='PASS'
    report['device_qualification']='PARTIAL_INSTALL_SWAP_ONLY'
    report['device_serial_recorded_locally']=True
    report['root_runtime_instrumentation']='NOT_RUN'
    report['skybind_qualification']='NOT_RUN'
    report['qualified_release']=False
    return report

def atomic_json(path,object):
    path.parent.mkdir(parents=True,exist_ok=True)
    require(not path.is_symlink(),'Evidence file must not be a symlink')
    tmp=path.with_name(path.name+'.tmp-'+str(os.getpid()))
    try:
        fd=os.open(tmp,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
        with os.fdopen(fd,'w') as handle:
            json.dump(object,handle,indent=2,sort_keys=True)
            handle.write('\n');handle.flush();os.fsync(handle.fileno())
        os.replace(tmp,path)
    finally:
        if tmp.exists():tmp.unlink()

def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--embedded-apk',type=Path,required=True)
    p.add_argument('--lite-apk',type=Path,required=True)
    p.add_argument('--import-json',type=Path,required=True)
    p.add_argument('--sign-json',type=Path,required=True)
    p.add_argument('--apksigner',default='apksigner')
    p.add_argument('--zipalign',default='zipalign')
    p.add_argument('--aapt',default='aapt')
    p.add_argument('--evidence-json',type=Path,required=True)
    p.add_argument('--serial',default='')
    p.add_argument('--execute',action='store_true')
    p.add_argument('--yes',action='store_true')
    opts=p.parse_args(argv)
    try:
        require(opts.embedded_apk.resolve()!=opts.lite_apk.resolve(),'Same APK provided twice')
        require(not opts.evidence_json.exists(),'Refuse to replace immutable evidence output')
        require(not opts.execute or (opts.yes and bool(opts.serial)),
                'Device installation requires --execute --yes --serial')
        require(not opts.yes or opts.execute,'--yes is only valid for device execution')
        report=preflight(opts.embedded_apk,opts.lite_apk,opts.apksigner,opts.zipalign,
                         opts.aapt,opts.import_json,opts.sign_json)
        report['captured_at_utc']=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())
        if opts.execute:
            # Initial evidence is saved before device changes; a crash cannot claim PASS.
            report['device_qualification']='IN_PROGRESS'
            atomic_json(opts.evidence_json,report)
            try:
                report=device_run(opts.embedded_apk,opts.lite_apk,opts.serial,report,
                                  lambda data:atomic_json(opts.evidence_json,data),opts.apksigner,opts.aapt)
            except (OSError,Reject,subprocess.TimeoutExpired) as error:
                report['device_qualification']='FAILED'
                report['device_error_type']=type(error).__name__
                atomic_json(opts.evidence_json,report)
                raise
        atomic_json(opts.evidence_json,report)
        print(json.dumps({'schema':report['schema'],'apk_preflight':report['apk_preflight'],
             'device_qualification':report['device_qualification'],
             'skybind_qualification':report['skybind_qualification'],'qualified_release':False}))
        return 0
    except (OSError,ValueError,KeyError,TypeError,subprocess.TimeoutExpired,zipfile.BadZipFile) as error:
        print('REL07_REJECTED: '+type(error).__name__+': '+str(error)[:140],file=sys.stderr)
        return 2
if __name__=='__main__':sys.exit(main())
