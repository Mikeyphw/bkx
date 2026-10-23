#!/usr/bin/env python3
"""Validate the reviewed bclone source lock. Never contact mutable latest endpoints."""
import argparse
from pathlib import Path
import re
import sys

FIELDS = ('schema','repository','tag','commit','go_version','ndk_revision','android_api','android_arch','page_size')
EXPECTED = {'schema':'bkx-bclone-source-lock-v1',
    'repository':'https://github.com/BenjiThatFoxGuy/bclone.git',
    'ndk_revision':'29.0.14206865','android_api':'24','android_arch':'arm64','page_size':'16384'}

def load(path):
    path=Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 4096:
        raise ValueError('bclone lock must be a bounded ordinary file')
    result={}
    for raw in path.read_text('utf-8').splitlines():
        if not raw or raw.startswith('#'):continue
        if '=' not in raw:raise ValueError('invalid bclone lock line')
        key,value=raw.split('=',1)
        if key not in FIELDS or key in result or not value or value != value.strip():
            raise ValueError('duplicate, unknown or malformed bclone lock field')
        result[key]=value
    if set(result)!=set(FIELDS):raise ValueError('incomplete bclone source lock')
    for key,value in EXPECTED.items():
        if result[key]!=value:raise ValueError('bclone lock contract mismatch: '+key)
    if not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+',result['tag']):
        raise ValueError('bclone lock tag is not stable semver')
    if not re.fullmatch(r'[0-9a-f]{40}',result['commit']) or result['commit']=='0'*40:
        raise ValueError('bclone lock commit must be a full reviewed Git SHA')
    if not re.fullmatch(r'go1\.[0-9]+\.[0-9]+',result['go_version']):
        raise ValueError('bclone lock Go compiler must be exact')
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('file')
    parser.add_argument('--field',choices=FIELDS)
    args=parser.parse_args()
    try:
        data=load(args.file)
    except (OSError, UnicodeError, ValueError) as ex:
        print('BKX_SOURCE_LOCK_BLOCKED: '+str(ex),file=sys.stderr)
        raise SystemExit(2)
    if args.field:print(data[args.field])
    else:print('PASS_BKX_BCLONE_REVIEWED_LOCK')
