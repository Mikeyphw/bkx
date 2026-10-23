#!/usr/bin/env python3
"""REL-06: explicitly consented BKX RM-16 dual release operations.

Delegates all network verification, artifact import and signing to installed Devtool.
Requires a reviewed local enrollment before every operation. Emits Devtool JSON
receipts as-is; never calls Gradle, handles a keystore, or promotes qualification.
"""
from __future__ import annotations

import argparse
import importlib.util
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
REPO = 'Mikeyphw/bkx'
IDS = 'bkx-embedded,bkx-lite'
SHA40 = re.compile('[0-9a-fA-F]{40}\\Z')
POSITIVE = re.compile('[1-9][0-9]*\\Z')
REGISTRY = re.compile('[A-Za-z0-9_.-]{1,150}\\Z')
BRANCH = re.compile('[A-Za-z0-9][A-Za-z0-9_./-]{0,180}\\Z')


def _check_value(ok, reason):
    if not ok:
        raise ValueError(reason)


def command(opts):
    spec = importlib.util.spec_from_file_location('bkx_life_enrollment', ROOT/'scripts/bkx-devtool-life.py')
    _check_value(spec is not None and spec.loader is not None,'LIFE enrollment script unavailable')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.verify(opts.identity)
    executable = shutil.which('devtool')
    _check_value(executable is not None,'Devtool executable is not installed')
    args = [executable,'-r',str(ROOT),'actions','android','release-batch-'+opts.operation,
            '--github-repo',REPO,'--consumers',IDS,'--json']
    if opts.operation in ('plan','run'):
        _check_value(bool(opts.ref) and BRANCH.fullmatch(opts.ref) and '..' not in opts.ref,
                     'Explicit safe --ref is required')
        args += ['--ref', opts.ref,'--source-root',str(ROOT)]
    if opts.operation in ('check','import'):
        _check_value(POSITIVE.fullmatch(opts.run_id or ''), 'Exact GitHub --run-id required')
        _check_value(SHA40.fullmatch(opts.source_sha or ''),'Exact --source-sha required')
        _check_value(POSITIVE.fullmatch(opts.embedded_artifact_id or '') and
                     POSITIVE.fullmatch(opts.lite_artifact_id or '') and
                     opts.embedded_artifact_id != opts.lite_artifact_id,
                     'Two distinct positive GitHub artifact IDs required')
        args += ['--run-id',opts.run_id,'--expected-sha',opts.source_sha,
                 '--artifact','bkx-embedded='+opts.embedded_artifact_id,
                 '--artifact','bkx-lite='+opts.lite_artifact_id]
        if opts.download:args.append('--download')
    if opts.operation == 'sign':
        _check_value(REGISTRY.fullmatch(opts.embedded_registry_id or '') and
                     REGISTRY.fullmatch(opts.lite_registry_id or '') and
                     opts.embedded_registry_id != opts.lite_registry_id,
                     'Two distinct immutable unsigned Devtool registry IDs required')
        args += ['--registry','bkx-embedded='+opts.embedded_registry_id,
                 '--registry','bkx-lite='+opts.lite_registry_id]
    if opts.operation in ('run','import','sign'):
        _check_value(opts.yes,'Mutating operation requires explicit --yes')
        args.append('--yes')
    else:
        _check_value(not opts.yes,'--yes only applies to explicitly mutating operations')
    return args


def main(argv=None):
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('operation',choices=('plan','run','check','import','sign'))
    p.add_argument('--identity',default='',help='Exact installed LIFE signing identity (optional if unique)')
    p.add_argument('--ref',default='',help='Exact reviewed remote ref, e.g. main')
    p.add_argument('--run-id',default='')
    p.add_argument('--source-sha',default='')
    p.add_argument('--embedded-artifact-id',default='')
    p.add_argument('--lite-artifact-id',default='')
    p.add_argument('--embedded-registry-id',default='')
    p.add_argument('--lite-registry-id',default='')
    p.add_argument('--download',action='store_true')
    p.add_argument('--yes',action='store_true')
    opts=p.parse_args(argv)
    try:
        args=command(opts)
        result=subprocess.run(args,cwd=ROOT,check=False)
        return result.returncode
    except (OSError,ValueError,ImportError,Exception) as error:
        # Fixed structured guard message; never print exceptions from a signer
        # because provider errors could inadvertently contain sensitive data.
        print('REL06_BLOCKED: reviewed LIFE registration or exact operation identity rejected', file=sys.stderr)
        return 2

if __name__=='__main__':
    sys.exit(main())
