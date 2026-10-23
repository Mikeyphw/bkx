#!/usr/bin/env python3
"""REL-06: local-only, immutable BKX RM16 consumer enrollment.

No signing passwords, keystore, GitHub token, or signing certificate is generated.
The existing LIFE identity is resolved via Devtool's canonical signing profile.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'config/bkx-android-unsigned-artifacts.json'
DEST = ROOT / 'config/android-consumers.json'
IDENT = re.compile(r'[A-Za-z0-9_.-]+\Z')
SHA256 = re.compile(r'[0-9a-f]{64}\Z')

class EnrollmentError(RuntimeError):
    pass


def producer_contract():
    data = json.loads(SOURCE.read_text(encoding='utf-8'))
    if (data.get('schema_version') != 1 or data.get('repository') != 'Mikeyphw/bkx'
            or data.get('workflow') != 'bkx-android-unsigned-dual-release.yml'
            or data.get('application_id') != 'com.mikeyphw.bkx.android'):
        raise EnrollmentError('Reviewed unsigned producer identity mismatch')
    entries = data.get('consumers')
    if (not isinstance(entries, list) or len(entries) != 2
            or {v.get('id') for v in entries} != {'bkx-embedded','bkx-lite'}
            or {v.get('variant') for v in entries} != {'embeddedRelease','liteRelease'}):
        raise EnrollmentError('Reviewed BKX Embedded/Lite producers required')
    for v in entries:
        if not isinstance(v.get('required_files'), list) or not isinstance(v.get('forbidden_files'),list):
            raise EnrollmentError('Invalid producer APK member contract')
    return data


def _life(identity: str):
    # Module imports are here so inspection without installed Devtool can
    # still show safe instructions rather than injecting a second signer.
    try:
        from devtool.actions_setup import _signing_profile, _keystore
    except ImportError as exc:
        raise EnrollmentError('Installed Devtool Python package is required for LIFE enrollment') from exc
    profiles = []
    for variant in ('embeddedRelease','liteRelease'):
        try:
            profile = _signing_profile(ROOT, identity_id=identity, target='bkx_android', variant=variant)
        except Exception as exc:
            raise EnrollmentError(f'Existing LIFE identity cannot authorize {variant}: {type(exc).__name__}') from None
        if (profile.mode != 'devtool' or not profile.identity_id or not IDENT.fullmatch(profile.identity_id)
                or not SHA256.fullmatch(profile.expected_cert_sha256 or '') or not profile.alias):
            raise EnrollmentError('LIFE signing profile lacks a durable authorized identity, alias or certificate')
        try:
            _keystore(profile.keystore, ROOT)
        except Exception:
            raise EnrollmentError('Existing LIFE signing keystore is not private, valid and outside BKX') from None
        profiles.append(profile)
    if len({(p.identity_id,p.expected_cert_sha256.lower(),p.alias,p.keystore) for p in profiles}) != 1:
        raise EnrollmentError('Embedded and Lite must reuse the exact same LIFE identity, key and certificate')
    return profiles[0]


def consumers(profile):
    producer = producer_contract()
    return {'schema_version': 1, 'consumers': [
        {'id': entry['id'], 'repository': producer['repository'],
         'workflow': producer['workflow'], 'artifact': entry['artifact'], 'apk': entry['apk'],
         'application_id': producer['application_id'], 'variant': entry['variant'],
         'build_type': 'release', 'target': 'bkx_android',
         'project_dir': 'android/BKX.Android', 'module': 'app',
         'signing_identity_id': profile.identity_id,
         'signer_sha256': profile.expected_cert_sha256.lower(),
         'required_files': entry['required_files'],
         'forbidden_files': entry['forbidden_files']}
        for entry in producer['consumers']]}


def _assert_ignored():
    tracked = subprocess.run(['git','-C',str(ROOT),'ls-files','--error-unmatch',
                              'config/android-consumers.json'], capture_output=True, check=False)
    if tracked.returncode == 0:
        raise EnrollmentError('Local LIFE consumer policy may not be Git-tracked')
    proc = subprocess.run(['git','-C',str(ROOT),'check-ignore','-q',
                           'config/android-consumers.json'], capture_output=True, check=False)
    if proc.returncode != 0:
        raise EnrollmentError('Local consumer policy must be gitignored before enrollment')


def _official_validate(document):
    from devtool.actions_android_contracts import select_consumer, contract_sha256
    with tempfile.TemporaryDirectory(prefix='bkx-consumer-review-') as d:
        root = Path(d)
        (root / 'config').mkdir()
        (root / 'config/android-consumers.json').write_text(json.dumps(document)+'\n')
        contracts = [select_consumer(root, consumer_id=id, repository='Mikeyphw/bkx')
                     for id in ('bkx-embedded','bkx-lite')]
        hashes = [contract_sha256(c) for c in contracts]
    if len(set(hashes)) != 2:
        raise EnrollmentError('Consumer contract hashes unexpectedly identical')
    return dict(zip(('bkx-embedded','bkx-lite'),hashes))


def inspect(identity=''):
    _assert_ignored()
    profile = _life(identity)
    doc = consumers(profile)
    hashes = _official_validate(doc)
    return doc,hashes


def enroll(identity: str, yes: bool):
    document, hashes = inspect(identity)
    if DEST.exists() or DEST.is_symlink():
        raise EnrollmentError('Local policy already exists; inspect it, never overwrite or silently re-key')
    if not yes:
        raise EnrollmentError('No change made; rerun enrollment with explicit --yes')
    payload = (json.dumps(document,sort_keys=True,indent=2)+'\n').encode()
    fd = os.open(DEST, os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,'O_NOFOLLOW',0),0o600)
    try:
        with os.fdopen(fd,'wb') as out:
            out.write(payload)
            out.flush()
            os.fsync(out.fileno())
        os.chmod(DEST,0o600)
        verify(identity)
    except BaseException:
        DEST.unlink(missing_ok=True)
        raise
    return {'operation':'enroll','configured':True,'signing_identity_id':document['consumers'][0]['signing_identity_id'],
            'signer_sha256':document['consumers'][0]['signer_sha256'],
            'contract_sha256':hashes,'qualified_release':False,'signing_performed':False}


def verify(identity=''):
    _assert_ignored()
    if DEST.is_symlink() or not DEST.is_file():
        raise EnrollmentError('Local reviewed consumer policy missing or symlinked; enroll first')
    if DEST.stat().st_mode & 0o077:
        raise EnrollmentError('Local reviewed policy must be private (0600)')
    expected, hashes = inspect(identity)
    actual = json.loads(DEST.read_text('utf-8'))
    if actual != expected:
        raise EnrollmentError('Local consumer policy differs from reviewed producer or existing LIFE identity')
    return {'operation':'verify','registered_consumers':['bkx-embedded','bkx-lite'],
            'signing_identity_id':expected['consumers'][0]['signing_identity_id'],
            'signer_sha256':expected['consumers'][0]['signer_sha256'],
            'contract_sha256':hashes,'qualified_release':False,'signing_performed':False}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('inspect','enroll','verify'))
    parser.add_argument('--identity',default='', help='Exact existing LIFE ID; never a keystore path')
    parser.add_argument('--yes',action='store_true',help='Allow local policy creation; never signing')
    args=parser.parse_args(argv)
    try:
        if args.identity and not IDENT.fullmatch(args.identity):
            raise EnrollmentError('Invalid LIFE identity ID')
        if args.operation=='inspect':
            document, hashes = inspect(args.identity)
            result={'operation':'inspect','would_register':True,
                    'signing_identity_id':document['consumers'][0]['signing_identity_id'],
                    'signer_sha256':document['consumers'][0]['signer_sha256'],
                    'contract_sha256':hashes,'qualified_release':False,'signing_performed':False}
        elif args.operation=='enroll':
            result=enroll(args.identity,args.yes)
        else:
            result=verify(args.identity)
        print(json.dumps(result,indent=2,sort_keys=True))
        return 0
    except (EnrollmentError, OSError, ValueError, KeyError, ImportError) as exc:
        print('REL06_BLOCKED: '+str(exc),file=sys.stderr)
        return 2

if __name__=='__main__':
    sys.exit(main())
