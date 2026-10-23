#!/usr/bin/env python3
"""Cheap source integrity gate for REL-08, no GitHub, signer or device access."""
import json
from pathlib import Path
import subprocess
import sys
ROOT=Path(__file__).resolve().parents[1]
ledger=json.loads((ROOT/'docs/release/BKX_RELEASE_ROADMAP_STATUS.json').read_text())
assert ledger['milestones']['REL-08']['status']=='OPEN_EXTERNAL_GATES_SOURCE_IMPLEMENTED'
assert set(ledger['milestones'])=={f'REL-{i:02d}' for i in range(9)}
assert all(value=='PENDING' for value in ledger['external'].values())
for relative in ('scripts/bkx-rel08-evidence.py','scripts/test-bkx-rel08-evidence.py',
                 'docs/release/REL08_FINAL_EVIDENCE_CLOSURE.md',
                 'scripts/bkx-rel07-qualify.py','scripts/bkx-audit-rm16-receipts.py'):
    assert (ROOT/relative).is_file(), relative
code=(ROOT/'scripts/bkx-rel08-evidence.py').read_text()
for exact in ("'qualified_release':False",'SUBMITTED_NEEDS_INDEPENDENT_AUTHORITY',
              'BLOCKED_EXTERNAL_QUALIFICATION','source_sha','in_place_variant_switch'):
    assert exact in code,exact
assert 'prior_install_authority' in (ROOT/'scripts/bkx-rel07-qualify.py').read_text()
for rel in ('android/BKX.Android/docs/RELEASE_VALIDATION.md',
            'android/BKX.Android/docs/RELEASE_CONTRACT.md'):
    assert 'REL-08' in (ROOT/rel).read_text()
p=subprocess.run([sys.executable,str(ROOT/'scripts/bkx-rel08-evidence.py'),'--require-qualified'],
                 stdout=subprocess.PIPE,stderr=subprocess.PIPE,check=False)
assert p.returncode==3, 'REL08 source-only gate must not qualify release'
print('PASS_REL08_PROMISE_INDEX_SOURCE_ONLY; EXTERNAL RELEASE GATES NOT CLOSED')
