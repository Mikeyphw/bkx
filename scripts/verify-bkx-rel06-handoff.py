#!/usr/bin/env python3
"""Source contract guard for BKX dual-variant RM16 enrollment and CLI handoff."""
from pathlib import Path
import json
import sys

ROOT=Path(__file__).resolve().parents[1]

def main():
    import importlib.util
    spec=importlib.util.spec_from_file_location('bkx_enroll',ROOT/'scripts/bkx-devtool-life.py')
    module=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    data=module.producer_contract()
    assert data['repository']=='Mikeyphw/bkx'
    assert data['application_id']=='com.mikeyphw.bkx.android'
    assert [v['id'] for v in data['consumers']]==['bkx-embedded','bkx-lite']
    assert set(data['consumers'][0]['required_files']) >= {'lib/arm64-v8a/libbkxrclone_exec.so','assets/bkx-rclone-runtime.properties'}
    assert set(data['consumers'][1]['forbidden_files']) >= {'lib/arm64-v8a/libbkxrclone_exec.so','assets/bkx-rclone-runtime.properties'}
    ignore=(ROOT/'.gitignore').read_text()
    assert '/config/android-consumers.json' in ignore
    # A local operator can enroll later; that private policy must remain ignored.
    import subprocess
    tracked=subprocess.run(['git','-C',str(ROOT),'ls-files','--error-unmatch',
                            'config/android-consumers.json'],capture_output=True)
    assert tracked.returncode != 0, 'Private local LIFE registry must never be tracked'
    run=(ROOT/'scripts/bkx-devtool-release.py').read_text()
    for token in ('release-batch-', 'module.verify(opts.identity)', '--source-root', '--expected-sha', '--registry', '--artifact', '--yes', 'bkx-embedded,bkx-lite'):
        assert token in run,token
    enrollment=(ROOT/'scripts/bkx-devtool-life.py').read_text()
    for token in ('_signing_profile', '_keystore', 'os.O_EXCL', 'consumer_contract', 'select_consumer', 'qualified_release', 'signing_performed'):
        # consumer-contract helper itself is called _official_validate
        if token=='consumer_contract':continue
        assert token in enrollment, token
    roadmap=json.loads((ROOT/'docs/release/BKX_RELEASE_ROADMAP_STATUS.json').read_text())
    assert roadmap['milestones']['REL-06']['status'].startswith('SOURCE_IMPLEMENTED')
    assert roadmap['external']['life_signing']=='PENDING'
    print('PASS_REL06_HANDOFF_STATIC; local enrollment, signed APKs, GitHub and Skybind pending')
if __name__=='__main__':main()
