#!/usr/bin/env python3
"""REL-04/05 source invariants for the reviewed unsigned GitHub producer."""
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
def read(rel):return (ROOT/rel).read_text('utf-8')

def require(value,message):
    if not value:raise AssertionError('REL0405_SOURCE_BLOCKED: '+message)

def check():
    lock=read('config/bclone-android-source.lock')
    flow=read('.github/workflows/bkx-android-unsigned-dual-release.yml')
    installer=read('scripts/install-rclone-android-runtime.sh')
    app=read('android/BKX.Android/app/build.gradle.kts')
    producer=read('scripts/produce-bkx-unsigned-candidates.py')
    contracts=json.loads(read('config/bkx-android-unsigned-artifacts.json'))
    require('BKX_RCLONE_REQUIRE_LOCK: \'1\'' in flow and 'BKX_RCLONE_SOURCE_LOCK:' in flow,
            'hosted rclone input not explicitly locked')
    require('BKX_UNSIGNED_CI_RELEASE: \'1\'' in flow and
            'BKX_ANDROID_SIGNING_PROPERTIES: ${{ github.workspace }}/.bkx-signing-disabled-do-not-create.properties' in flow,
            'hosted signing-disabled guard must use a valid early GitHub context')
    # GitHub rejects workflow_dispatch for invalid expression contexts before a job starts.
    # github context is allowed in job.env; runner context is allowed only in steps.env.
    job_prefix = flow.split('\n    steps:',1)[0]
    require('${{ runner.' not in job_prefix,
            'runner context is forbidden in job-level env, causing GitHub HTTP 422')

    require('check(!bkxHostedUnsignedRelease || !bkxPersonalSigningConfigured)' in app,
            'Gradle must reject private signing config in unsigned workflow')
    require('resolve_reviewed_bclone_release' in installer and
            'fail 65 lock \'upstream bclone tag changed after review\'' in installer,
            'source pin must be compared to immutable upstream tag target')
    require('go_toolchain=' in installer and 'ndk_revision=' in installer and 'resolve_latest_bclone_release' in installer,
            'reviewed toolchain provenance and development compatibility must coexist')
    require('config/bclone-android-source.lock' in app and 'scripts/verify-bclone-source-lock.py' in app,
            'Gradle must treat reviewed source lock and validator as task inputs')
    require(flow.count('gradle -p android/BKX.Android --no-daemon --stacktrace')==1 and
            ':app:assembleEmbeddedRelease :app:assembleLiteRelease' in flow,
            'single shared Gradle invocation for two editions required')
    # REL05-HF02: the real 40037123461 hosted run exhausted a 1152m Gradle
    # heap in Zipflinger while R8 for Lite was also active. A bounded CI-only
    # heap/worker override is required; do not change local Termux defaults.
    gradle_block=flow.split('gradle -p android/BKX.Android --no-daemon --stacktrace',1)[1].split('\n      - name:',1)[0]
    require('--no-parallel --max-workers=1' in gradle_block,
            'hosted APK packaging must serialize Gradle/R8 workers')
    require("'-Dorg.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8'" in gradle_block,
            'hosted APK packaging needs isolated 4 GiB Gradle daemon heap')
    require('useLegacyPackaging = true' in app,
            'Android native executable extraction policy must remain intact')
    require('python3 scripts/produce-bkx-unsigned-candidates.py' in flow and
            'verifyBkxEmbeddedRelease16Kb' in flow and 'verifyBkxLiteRelease16Kb' in flow,
            'both candidate and native gates required before upload')
    require('actions/upload-artifact@v4' in flow and flow.count('uses: actions/upload-artifact@v4')==2,
            'exactly two independently named upload artifacts required')
    require('signing_state\':\'unsigned\'' in producer and
            "'qualified_release':False" in producer and "'source_attested':False" in producer,
            'ACTIONS-10 must never accept GitHub as signing/qualification authority')
    require('apksigner' in producer and 'zipalign' in producer and
            'verify-native-page-size.go' in producer and 'output-metadata.json' in producer,
            'real APK inspection rather than source markers required')
    require(contracts['workflow']=='bkx-android-unsigned-dual-release.yml' and
            {v['variant'] for v in contracts['consumers']}=={'embeddedRelease','liteRelease'},
            'contract must cover exact two editions')
    for c in contracts['consumers']:
        require('name: '+c['artifact'] in flow,
                'workflow must upload registered artifact '+c['artifact'])
    for token in ('secrets.', 'signingConfig = signingConfigs.getByName("bkxPersonal")'):
        require(token not in flow,'GitHub workflow must not import private signer')
    print('PASS_REL0405_SOURCE_PRODUCER_REVIEWED_DUAL_UNSIGNED; HOSTED AND DEVICE UNQUALIFIED')
if __name__=='__main__':check()
