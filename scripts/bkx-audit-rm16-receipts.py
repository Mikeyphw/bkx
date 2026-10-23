#!/usr/bin/env python3
"""REL-06: cross-check Devtool RM-16 import/sign JSON receipts without promoting release."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import re
import sys

IDS={'bkx-embedded','bkx-lite'}
SHA40=re.compile(r'[a-f0-9]{40}\Z')
SHA64=re.compile(r'[a-f0-9]{64}\Z')

class ReceiptError(ValueError):pass

def _read(path):
    document=json.loads(Path(path).read_text('utf-8'))
    # Devtool's --json presentation may carry a top-level result wrapper.
    if isinstance(document,dict) and isinstance(document.get('result'),dict):
        document=document['result']
    if not isinstance(document,dict):raise ReceiptError('Receipt must be a JSON object')
    return document

def _require(ok,why):
    if not ok:raise ReceiptError(why)

def audit(imported,signed):
    _require(imported.get('phase')=='ACTIONS-ANDROID-BATCH' and imported.get('operation')=='import' and imported.get('complete') is True,'Verified complete batch import required')
    _require(signed.get('phase')=='ACTIONS-ANDROID-BATCH' and signed.get('operation')=='local-life-sign' and signed.get('complete') is True,'Verified complete local LIFE batch signing required')
    _require(imported.get('qualified_release') is False and signed.get('qualified_release') is False,'Batch receipts must not claim qualified release')
    _require(imported.get('repository')=='Mikeyphw/bkx' and imported.get('consumers')==['bkx-embedded','bkx-lite'] and signed.get('consumers')==['bkx-embedded','bkx-lite'], 'Exact BKX consumer order and source required')
    sha=imported.get('source_sha','')
    _require(isinstance(sha,str) and SHA40.fullmatch(sha), 'Exact producer SHA required')
    _require(type(imported.get('run_id')) is int and imported['run_id']>0 and type(imported.get('run_attempt')) is int and imported['run_attempt']>0, 'Source run/attempt missing')
    imports=imported.get('imports')
    signatures=signed.get('signed')
    _require(isinstance(imports,list) and len(imports)==2 and isinstance(signatures,list) and len(signatures)==2,'Two immutable imports and two local sign results required')
    meta={}
    for i in imports:
        _require(isinstance(i,dict) and i.get('consumer_id') in IDS and i.get('consumer_id') not in meta,'Duplicate or missing import identity')
        _require(i.get('source_sha')==sha and i.get('run_id')==imported['run_id'] and i.get('run_attempt')==imported['run_attempt'],'Imported artifact source/run differs')
        _require(i.get('qualified_release') is False,'Imported candidate incorrectly qualified')
        _require(isinstance(i.get('registry_id'),str) and i['registry_id'],'Missing immutable unsigned registry ID')
        meta[i['consumer_id']]=i
    fingerprints=set()
    digest=set()
    for cid,result in zip(['bkx-embedded','bkx-lite'],signatures):
        _require(isinstance(result,dict) and result.get('qualified_release') is False,'Signed receipt must not qualify release')
        s=result.get('signing')
        _require(isinstance(s,dict) and s.get('consumer_id')==cid and s.get('variant')==('embeddedRelease' if cid=='bkx-embedded' else 'liteRelease'),'Signed variant/consumer mismatch')
        _require(s.get('registry_id')==meta[cid]['registry_id'] and s.get('remote_run_id')==imported['run_id'] and s.get('remote_run_attempt')==imported['run_attempt'] and s.get('remote_head_sha')==sha,'Signed candidate differs from imported source')
        _require(s.get('locally_signed') is True and s.get('signature_verified') is True and s.get('apk_16k_zipalign_verified') is True,'Signed APK lacks verified LIFE signature or 16KiB ZIP alignment')
        _require(s.get('qualified_release') is False and s.get('github_provenance_verified') is True,'Signed receipt overstates qualification or lacks provenance')
        cert=s.get('expected_signer_sha256','')
        signed_sha=s.get('signed_sha256','')
        _require(isinstance(cert,str) and SHA64.fullmatch(cert) and isinstance(signed_sha,str) and SHA64.fullmatch(signed_sha),'Signing fingerprint and APK SHA-256 required')
        fingerprints.add(cert)
        digest.add(signed_sha)
        _require(isinstance(s.get('signed_registry_id'),str) and s['signed_registry_id'],'Missing immutable signed registry ID')
        _require(s.get('remote_repository')=='Mikeyphw/bkx','Unexpected signing repository')
    _require(len(fingerprints)==1 and len(digest)==2,'APK signatures must share one signer and have distinct bytes')
    return {'schema_version':1,'operation':'REL06_SOURCE_BOUND_SIGNED_PAIR_RECEIPT_AUDIT',
            'source_sha':sha,'run_id':imported['run_id'],'run_attempt':imported['run_attempt'],
            'consumers':sorted(IDS),'signer_sha256':next(iter(fingerprints)),
            'signed_distinct':True,'receipts_pass':True,'qualified_release':False,
            'device_qualification':'PENDING','skybind_qualification':'PENDING'}

def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--import-json',required=True)
    p.add_argument('--sign-json',required=True)
    args=p.parse_args(argv)
    try:
        print(json.dumps(audit(_read(args.import_json),_read(args.sign_json)),indent=2,sort_keys=True))
        return 0
    except (OSError,ValueError,KeyError,TypeError):
        print('REL06_RECEIPT_REJECTED: incomplete or inconsistent Devtool authority',file=sys.stderr)
        return 2
if __name__=='__main__':sys.exit(main())
