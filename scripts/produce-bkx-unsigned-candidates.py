#!/usr/bin/env python3
"""GitHub-only unsigned APK producer for Devtool ACTIONS-10; no signing authority.

Requires real Android SDK aapt/apksigner/zipalign and the Go native ELF checker.
Never accepts a caller-supplied package identity, manifest claim or APK path.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / 'config/bkx-android-unsigned-artifacts.json'
MAX_APK = 512 * 1024 * 1024

class ContractError(Exception): pass

def contracts():
    doc = json.loads(CONFIG.read_text('utf-8'))
    if (doc.get('schema_version') != 1 or doc.get('repository') != 'Mikeyphw/bkx' or
        doc.get('workflow') != 'bkx-android-unsigned-dual-release.yml' or
        doc.get('application_id') != 'com.mikeyphw.bkx.android' or
        not isinstance(doc.get('consumers'),list) or len(doc['consumers']) != 2):
        raise ContractError('invalid BKX unsigned producer contract')
    expected = {'embeddedRelease','liteRelease'}
    if {v['variant'] for v in doc['consumers']} != expected:
        raise ContractError('producer must describe both release editions')
    for v in doc['consumers']:
        for k in ('id','artifact','apk','variant'):
            if not isinstance(v.get(k),str) or not re.fullmatch(r'[A-Za-z0-9_.-]+',v[k]):
                raise ContractError('invalid producer '+k)
        if not v['apk'].endswith('.apk'):
            raise ContractError('invalid candidate APK extension')
        if not isinstance(v.get('required_files'),list) or not isinstance(v.get('forbidden_files'),list):
            raise ContractError('invalid APK member rules')
        if set(v['required_files']) & set(v['forbidden_files']):
            raise ContractError('contradictory APK member contract')
    return doc

def provenance(env, doc):
    for k in ('GITHUB_REPOSITORY','GITHUB_SHA','GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT','GITHUB_REF','GITHUB_WORKFLOW_REF'):
        if not env.get(k):raise ContractError('missing GitHub provenance: '+k)
    if env['GITHUB_REPOSITORY'].lower() != doc['repository'].lower():
        raise ContractError('unexpected GitHub repository')
    if not re.fullmatch(r'[0-9a-f]{40}',env['GITHUB_SHA']):
        raise ContractError('invalid dispatched SHA')
    if not env['GITHUB_REF'].startswith(('refs/heads/','refs/tags/')):
        raise ContractError('disallowed Github ref')
    if '.github/workflows/'+doc['workflow']+'@' not in env['GITHUB_WORKFLOW_REF']:
        raise ContractError('workflow is not the registered producer')
    for key in ('GITHUB_RUN_ID','GITHUB_RUN_ATTEMPT'):
        if not env[key].isdigit() or int(env[key]) < 1:raise ContractError('invalid GitHub run identity')
    result = subprocess.run(['git','rev-parse','HEAD'],cwd=ROOT,capture_output=True,text=True,check=True)
    if result.stdout.strip().lower()!=env['GITHUB_SHA']:
        raise ContractError('checkout and GitHub workflow source SHA differ')
    result=subprocess.run(['git','status','--porcelain','--untracked-files=no'],cwd=ROOT,capture_output=True,text=True,check=True)
    if result.stdout.strip():raise ContractError('GitHub source checkout changed during build')

def android_tools(env):
    sdk = Path(env.get('ANDROID_HOME',''))
    if not sdk.is_absolute() or not sdk.is_dir():
        raise ContractError('ANDROID_HOME SDK unavailable')
    path = sdk/'build-tools/36.0.0'
    tools={n:path/n for n in ('aapt','apksigner','zipalign')}
    for name,p in tools.items():
        if not p.is_file() or not os.access(p,os.X_OK):
            raise ContractError('missing Android 36.0.0 '+name)
    return tools

def checked(command):
    result=subprocess.run([str(v) for v in command],capture_output=True,text=True)
    if result.returncode:
        raise ContractError('verification command failed: '+str(command[0])+': '+result.stderr[-350:])
    return result.stdout

def unsigned_apk(path, consumer, tools, app):
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= MAX_APK:
        raise ContractError('APK is missing, a symlink or exceeds size bound')
    with zipfile.ZipFile(path) as z:
        infos=z.infolist()
        names=[i.filename for i in infos if not i.is_dir()]
        if len(names)!=len(set(names)):
            raise ContractError('APK has duplicate ZIP entries')
        if any(i.filename.startswith('/') or '..' in i.filename.split('/') for i in infos):
            raise ContractError('APK has unsafe ZIP members')
        present=set(names)
        if not set(consumer['required_files']) <= present or set(consumer['forbidden_files']) & present:
            raise ContractError('APK violates Embedded/Lite native packaging contract')
        if any(re.fullmatch(r'META-INF/[^/]+\.(RSA|DSA|EC|SF)',n,re.I) for n in names):
            raise ContractError('APK contains JAR-signature material')
        # v2/v3 signing block magic must never appear in an unsigned release candidate.
        with path.open('rb') as f:
            f.seek(max(0, path.stat().st_size - 4 * 1024 * 1024))
            if b'APK Sig Block 42' in f.read():
                raise ContractError('APK contains Android signing block')
    signature=subprocess.run([str(tools['apksigner']),'verify','--verbose',str(path)],capture_output=True,text=True)
    if signature.returncode == 0:
        raise ContractError('GitHub candidate unexpectedly signed')
    checked([tools['zipalign'],'-c','-P','16','4',path])
    info=checked([tools['aapt'],'dump','badging',path])
    m=re.search(r"^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'",info,re.M)
    if not m or m.group(1)!=app or not m.group(3) or int(m.group(2))<1:
        raise ContractError('APK package/version identity mismatch')
    if re.search(r'^application-debuggable\b',info,re.M):
        raise ContractError('release candidate is debuggable')
    edition=consumer['variant'].removesuffix('Release').lower()
    if edition not in ('embedded','lite'):
        raise ContractError('unknown edition for native APK verification')
    checked(['go','run',ROOT/'scripts/verify-native-page-size.go','apk','--edition',edition,'--min-page-size','16384',path])
    return {'package':m.group(1),'code':int(m.group(2)),'name':m.group(3)}

def selected_apk(out_root, consumer):
    edition=consumer['variant'].removesuffix('Release').lower()
    directory=out_root/edition/'release'
    metadata=directory/'output-metadata.json'
    if metadata.is_symlink() or not metadata.is_file() or metadata.stat().st_size>1024*1024:
        raise ContractError('missing regular AGP output-metadata.json for '+edition)
    data=json.loads(metadata.read_text('utf-8'))
    elems=data.get('elements')
    if data.get('variantName') != consumer['variant'] or not isinstance(elems,list) or len(elems)!=1:
        raise ContractError('AGP output variant/element identity mismatch')
    name=elems[0].get('outputFile')
    if not isinstance(name,str) or not re.fullmatch(r'[A-Za-z0-9_.-]+\.apk',name):
        raise ContractError('AGP output filename is unsafe')
    apk=directory/name
    if len(list(directory.glob('*.apk'))) != 1:
        raise ContractError('unexpected extra APK outputs for '+edition)
    if apk.is_symlink() or not apk.is_file():raise ContractError('missing regular AGP APK output')
    return apk, data, elems[0]

def sha256_file(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for block in iter(lambda:f.read(1024 * 1024),b''):
            h.update(block)
    return h.hexdigest()

def stage(out_root, destination, env):
    doc=contracts()
    provenance(env,doc)
    tools=android_tools(env)
    # Validate BOTH editions before publishing either candidate artifact.
    verified=[]
    for consumer in doc['consumers']:
        apk,metadata,element=selected_apk(out_root,consumer)
        identity=unsigned_apk(apk,consumer,tools,doc['application_id'])
        if metadata.get('applicationId')!=doc['application_id'] or element.get('versionCode')!=identity['code'] or element.get('versionName')!=identity['name']:
            raise ContractError('APK disagrees with Gradle metadata')
        verified.append((consumer,apk,identity))
    if verified[0][2] != verified[1][2]:
        raise ContractError('Embedded and Lite have divergent package/version identities')
    if destination.exists():raise ContractError('candidate staging target already exists')
    destination.parent.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.bkx-dual-candidates-',dir=destination.parent) as temp:
        scratch=Path(temp)
        for consumer,apk,identity in verified:
            target=scratch/consumer['id']
            target.mkdir()
            copied=target/consumer['apk']
            shutil.copyfile(apk,copied)
            if sha256_file(copied) != sha256_file(apk):
                raise ContractError('candidate copy digest mismatch')
            manifest={
                'schema_version':1,'kind':'devtool.unsigned-android-apk',
                'repository':doc['repository'],'head_sha':env['GITHUB_SHA'],
                'run_id':int(env['GITHUB_RUN_ID']),'run_attempt':int(env['GITHUB_RUN_ATTEMPT']),
                'ref':env['GITHUB_REF'],'workflow_path':'.github/workflows/'+doc['workflow'],
                'artifact_name':consumer['artifact'],'apk_file':consumer['apk'],
                'apk_sha256':sha256_file(copied),
                'apk_size_bytes':copied.stat().st_size,
                'application_id':identity['package'],'version_code':identity['code'],
                'version_name':identity['name'],'variant':consumer['variant'],
                'format':'apk','debuggable':False,'signing_state':'unsigned',
                'qualified_release':False,'source_attested':False,
            }
            (target/'manifest.json').write_text(json.dumps(manifest,sort_keys=True,indent=2)+'\n')
            if {p.name for p in target.iterdir()} != {'manifest.json',consumer['apk']}:
                raise ContractError('candidate root has extra members')
        scratch.rename(destination)
    print('PASS_BKX_DUAL_UNSIGNED_CANDIDATES source='+env['GITHUB_SHA']+' run='+env['GITHUB_RUN_ID'])

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('--apk-root',required=True,type=Path)
    parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args()
    try:stage(args.apk_root,args.output,os.environ)
    except (ContractError, OSError, ValueError, subprocess.CalledProcessError, zipfile.BadZipFile) as ex:
        print('BKX_UNSIGNED_PRODUCER_BLOCKED: '+str(ex),file=sys.stderr)
        raise SystemExit(2)
