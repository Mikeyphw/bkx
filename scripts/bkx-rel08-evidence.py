#!/usr/bin/env python3
"""REL-08: fail-closed BKX dual-release evidence index and promise reconciliation.

This is an evidence *index*, not a trust root. Remote/device JSON cannot
self-attest a release: independent authorities must qualify those lanes.
No network, device, signing, Gradle execution, or destructive operation occurs.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT=Path(__file__).resolve().parents[1]
SHA40=re.compile(r'[a-f0-9]{40}\Z')
SHA64=re.compile(r'[a-f0-9]{64}\Z')
GATES=('source_contract','gradle_real','hosted_dual_unsigned','life_dual_signed',
       'signed_pair_preflight','in_place_variant_switch','root_runtime',
       'instrumentation_embedded','instrumentation_lite','skybind_embedded','skybind_lite','negative_auth',
       'runtime_embedded','runtime_lite','data_and_tasker','release_authority')
FILES={
 'REL-00':['docs/release/DUAL_VARIANT_CONTRACT.md','config/bkx-android-unsigned-artifacts.json'],
 'REL-01':['android/BKX.Android/app/build.gradle.kts','scripts/verify-android-editions.py'],
 'REL-02':['internal/rcloneops/runtime.go','android/BKX.Android/app/src/main/java/com/mikeyphw/bkx/android/embedded/GoNativeGateway.kt'],
 'REL-03':['scripts/verify-bkx-rel03-gates.py','build-logic/android-verification.gradle.kts'],
 'REL-04':['config/bclone-android-source.lock','scripts/verify-bclone-source-lock.py'],
 'REL-05':['.github/workflows/bkx-android-unsigned-dual-release.yml','scripts/produce-bkx-unsigned-candidates.py'],
 'REL-06':['scripts/bkx-devtool-life.py','scripts/bkx-audit-rm16-receipts.py','scripts/bkx-devtool-release.py'],
 'REL-07':['scripts/bkx-rel07-qualify.py','android/BKX.Android/scripts/qualify-device.sh'],
 'REL-08':['scripts/bkx-rel08-evidence.py','docs/release/REL08_FINAL_EVIDENCE_CLOSURE.md'],
}

class Invalid(ValueError):pass

def require(ok,reason):
    if not ok:raise Invalid(reason)

def sha_file(path):
    h=hashlib.sha256()
    with open(path,'rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
    return h.hexdigest()

def read_authority(path,kind):
    path=Path(path)
    require(path.is_file() and not path.is_symlink() and path.stat().st_size<=8*1024*1024,
            'Missing, unsafe, or oversized '+kind+' receipt')
    data=json.loads(path.read_text('utf-8'))
    require(isinstance(data,dict),'Invalid '+kind+' receipt document')
    return data,{'sha256':sha_file(path),'kind':kind,'file':str(path.resolve())}

def module(rel,key):
    spec=importlib.util.spec_from_file_location(key,ROOT/rel)
    require(spec is not None and spec.loader is not None,'Required verifier not available')
    result=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result

def source_manifest():
    status=json.loads((ROOT/'docs/release/BKX_RELEASE_ROADMAP_STATUS.json').read_text('utf-8'))
    require(isinstance(status.get('milestones'),dict),'Roadmap ledger missing')
    promises=[]
    for milestone,files in FILES.items():
        missing=[name for name in files if not (ROOT/name).is_file()]
        require(not missing, milestone+' promised source missing: '+', '.join(missing))
        state=status['milestones'].get(milestone,{}).get('status','')
        require(bool(state),'Roadmap milestone untracked '+milestone)
        promises.append({'milestone':milestone,'source_paths':files,
                         'source_state':'SOURCE_PRESENT_NOT_RUNTIME_PROOF',
                         'roadmap_state':state,
                         'external_qualification':'PENDING'})
    result=subprocess.run(['git','rev-parse','HEAD'],cwd=ROOT,capture_output=True,text=True,check=True)
    source_sha=result.stdout.strip().lower()
    require(SHA40.fullmatch(source_sha),'BKX source SHA unavailable')
    dirty=subprocess.run(['git','status','--porcelain','--untracked-files=normal'],cwd=ROOT,
                         capture_output=True,text=True,check=True)
    # Running from a checkout with uncommitted changes is allowed for an
    # explicitly unqualified source index, not for a bound release receipt.
    clean=not bool(dirty.stdout.strip())
    return source_sha,clean,promises

def build(opts):
    source_sha,clean,promises=source_manifest()
    gates={name:{'status':'PENDING','authority':'NOT_SUPPLIED'} for name in GATES}
    gates['source_contract']={'status':'SOURCE_ONLY','authority':'BKX tracked source',
                              'source_sha':source_sha,'clean_checkout':clean}
    sources={}
    imported=signed=pair=None
    input_paths={'github':opts.github_json,'import':opts.import_json,'sign':opts.sign_json,
                 'rel07':opts.rel07_json,'skybind_embedded':opts.skybind_embedded_json,
                 'skybind_lite':opts.skybind_lite_json,
                 'instrumentation_embedded':opts.instrumentation_embedded_json,
                 'instrumentation_lite':opts.instrumentation_lite_json,
                 'runtime_embedded':opts.runtime_embedded_json,
                 'runtime_lite':opts.runtime_lite_json}
    docs={}
    for kind,value in input_paths.items():
        if value:
            docs[kind],sources[kind]=read_authority(value,kind)
    if bool(docs.get('import'))!=bool(docs.get('sign')):
        raise Invalid('REL-06 import and local LIFE signing receipts must be supplied together')
    if 'import' in docs:
        audit=module('scripts/bkx-audit-rm16-receipts.py','rel08_rm16_audit')
        imported,signed=docs['import'],docs['sign']
        valid=audit.audit(imported,signed)
        require(valid['source_sha']==source_sha,'REL-06 signing source differs from current BKX checkout')
        gates['hosted_dual_unsigned']={'status':'RECEIPT_VERIFIED','authority':'Devtool ACTIONS-10/RM-16',
           'source_sha':valid['source_sha'],'run_id':valid['run_id'],'run_attempt':valid['run_attempt']}
        gates['life_dual_signed']={'status':'RECEIPT_VERIFIED','authority':'Devtool native LIFE',
           'signer_sha256':valid['signer_sha256'],'signed_distinct':True}
    if 'github' in docs:
        gh=docs['github']
        require(imported is not None,'A GitHub receipt alone cannot qualify unsigned artifacts')
        require(gh.get('id')==imported['run_id'] and gh.get('run_attempt')==imported['run_attempt']
                and gh.get('head_sha')==source_sha and gh.get('conclusion')=='success'
                and gh.get('status')=='completed','GitHub run differs from imported verified source')
        gates['hosted_dual_unsigned']['github_run_receipt_crosschecked']=True
    if 'rel07' in docs:
        require(imported is not None,'REL-07 signed candidate evidence requires REL-06 receipts')
        pair=docs['rel07']
        require(pair.get('schema')=='bkx-rel07-signed-pair-v1'
                and pair.get('qualified_release') is False
                and pair.get('apk_preflight')=='PASS'
                and pair.get('source_sha')==source_sha,'REL-07 pair source/preflight is not qualified')
        require(pair.get('github_run_id')==imported['run_id']
                and pair.get('github_run_attempt')==imported['run_attempt'],'REL-07 run/attempt mismatch')
        require(pair.get('signer_sha256')==gates['life_dual_signed']['signer_sha256'],
                'REL-07 verified certificate differs from LIFE signer')
        require(isinstance(pair.get('variants'),dict) and set(pair['variants'])=={'embedded','lite'},
                'Both variants required in REL-07 evidence')
        fingerprints=[]
        for index,edition in enumerate(('embedded','lite')):
            apk=pair['variants'][edition]
            local=signed['signed'][index]['signing']
            require(apk.get('variant')==edition+'Release'
                    and apk.get('apk_sha256')==local.get('signed_sha256')
                    and apk.get('signer_sha256')==local.get('expected_signer_sha256')
                    and apk.get('zip_16k_verified') is True and apk.get('elf_16k') is True,
                    'REL-07 actual APK bytes, signer, variant, or 16KiB checks mismatch')
            fingerprints.append(apk['signer_sha256'])
        require(len(set(fingerprints))==1,'REL-07 signers diverged')
        gates['signed_pair_preflight']={'status':'RECEIPT_VERIFIED','authority':'REL-07 + Android SDK + Devtool',
             'embedded_sha256':pair['variants']['embedded']['apk_sha256'],
             'lite_sha256':pair['variants']['lite']['apk_sha256']}
        steps=pair.get('device_steps',[])
        if pair.get('device_install_swap')=='PASS':
            require(isinstance(steps,list) and len(steps)==3 and
                [x.get('edition') for x in steps]==['embedded','lite','embedded'] and
                all(x.get('installed_sha256')==pair['variants'][x['edition']]['apk_sha256']
                    for x in steps) and len({x.get('app_uid') for x in steps})==1 and
                all(x.get('in_place_install')=='PASS' for x in steps) and
                all(x.get('private_marker_retained') is True for x in steps[1:]) and
                pair.get('existing_install_authority',{}).get('in_place_upgrade_authorized') is True and
                pair['existing_install_authority'].get('preexisting_signer_sha256')==pair['signer_sha256'],
                'REL-07 in-place switch is incomplete or lacks original signer authority')
            gates['in_place_variant_switch']={'status':'PARTIAL_DEVICE_RECEIPT','authority':'REL-07 rooted APK swap',
                 'steps':3,'app_uid':steps[0]['app_uid'],'not_full_runtime_qualification':True}
    # External files are retained as hashed attachments and never promoted on
    # a caller's self-reported PASS; their gateway authority is independently reviewed.
    for edition in ('embedded','lite'):
        for kind in ('skybind','instrumentation','runtime'):
            key=kind+'_'+edition
            if key in docs:
                obj=docs[key]
                require(obj.get('qualified_release') is not True,
                        'External receipt must not self-promote complete release authority')
                if kind=='skybind':
                    require(obj.get('schema')=='skybind-r1-bkx-device-acceptance-v1',
                            'Unrecognized Skybind operator receipt schema')
                target=key
                gates[target]={'status':'SUBMITTED_NEEDS_INDEPENDENT_AUTHORITY',
                               'authority':key,'receipt_sha256':sources[key]['sha256']}
    if opts.gradle_json:
        document,sources['gradle']=read_authority(opts.gradle_json,'gradle')
        # A self-authored gate JSON is evidence submitted, not Gradle's test exit code.
        require(document.get('qualified_release') is not True,
                'Gradle receipt may not promote release')
        gates['gradle_real']={'status':'SUBMITTED_NEEDS_INDEPENDENT_AUTHORITY',
                              'authority':'hosted Gradle','receipt_sha256':sources['gradle']['sha256']}
    return {'schema':'bkx-rel08-evidence-index-v1',
            'captured_at_utc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),
            'source_sha':source_sha,'source_checkout_clean':clean,
            'roadmap_promises':promises,'gates':gates,'receipt_inputs':sources,
            'blockers':[k for k,v in gates.items() if v['status'] not in ('RECEIPT_VERIFIED','SOURCE_ONLY')],
            'overall':'BLOCKED_EXTERNAL_QUALIFICATION',
            'external_authority_review_required':True,'qualified_release':False}

def private_output(output,data):
    destination=Path(output)
    require(not destination.exists() and not destination.is_symlink(),'Do not overwrite immutable evidence index')
    require(destination.parent.is_dir(),'Evidence destination directory must already exist')
    fd=os.open(destination,os.O_CREAT|os.O_EXCL|os.O_WRONLY|getattr(os,'O_NOFOLLOW',0),0o600)
    try:
        with os.fdopen(fd,'w') as stream:
            json.dump(data,stream,indent=2,sort_keys=True);stream.write('\n')
            stream.flush();os.fsync(stream.fileno())
    except BaseException:
        destination.unlink(missing_ok=True)
        raise

def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    for field in ('github','import','sign','rel07','gradle','skybind-embedded','skybind-lite',
                  'instrumentation-embedded','instrumentation-lite','runtime-embedded','runtime-lite'):
        p.add_argument('--'+field+'-json',type=Path)
    p.add_argument('--output',type=Path)
    p.add_argument('--require-qualified',action='store_true',help='Fail while external gates are pending')
    opts=p.parse_args(argv)
    try:
        result=build(opts)
        if opts.output:private_output(opts.output,result)
        else:print(json.dumps(result,indent=2,sort_keys=True))
        return 3 if opts.require_qualified else 0
    except (ValueError,OSError,KeyError,TypeError,subprocess.SubprocessError,json.JSONDecodeError) as exc:
        print('REL08_BLOCKED: invalid or inconsistent release evidence: '+type(exc).__name__,file=sys.stderr)
        return 2

if __name__=='__main__':sys.exit(main())
