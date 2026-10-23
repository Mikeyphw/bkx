#!/usr/bin/env python3
"""REL07 static promise gate, paired with executable adversarial tests."""
from pathlib import Path
import json
import subprocess
ROOT=Path(__file__).resolve().parents[1]
source=(ROOT/'scripts/bkx-rel07-qualify.py').read_text()
old=(ROOT/'android/BKX.Android/scripts/qualify-device.sh').read_text()
probe=(ROOT/'android/BKX.Android/app/src/androidTest/java/com/mikeyphw/bkx/android/r1/SkybindR1LiveBrokerAcceptanceTest.kt').read_text()
roadmap=json.loads((ROOT/'docs/release/BKX_RELEASE_ROADMAP_STATUS.json').read_text())
assert roadmap['milestones']['REL-07']['status']=='SOURCE_IMPLEMENTED_EXTERNAL_QUALIFICATION_PENDING'
assert roadmap['milestones']['REL-08']['status'].startswith('OPEN')
assert all(state=='PENDING' for state in roadmap['external'].values())
for fragment in ('zipalign','apksigner','elf_16k','release_receipts','device_sample',
                 'install\',\'-r','qualified_release\':False', 'private_marker_retained',
                 "'NOT_RUN'", "'--execute'", "'--yes'", "'--serial'"):
    assert fragment in source,fragment
assert 'adb uninstall' not in source and "'pm','clear'" not in source
for fragment in ('same-signer instrumentation','INSTRUMENTATION_CODE: -1',
                 'RELEASE_CLASSES','exec-out su -c','-e bkxDeviceQualification true'):
    assert fragment in old,fragment
assert 'SkybindR1ReplayProbeService::class.java' not in probe
assert 'ComponentName(ctx.packageName,' in probe
assert 'Skybind R1' in (ROOT/'docs/release/REL07_ANDROID_SKYBIND_QUALIFICATION.md').read_text()
assert subprocess.run(['bash','-n',str(ROOT/'android/BKX.Android/scripts/qualify-device.sh')]).returncode==0
assert subprocess.run(['python3',str(ROOT/'scripts/bkx-rel07-qualify.py'),'--help'],stdout=subprocess.DEVNULL).returncode==0
print('PASS_BKX_REL07_SOURCE_CONTRACT; signed device and Skybind evidence NOT RUN')
