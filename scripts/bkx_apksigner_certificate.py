#!/usr/bin/env python3
"""Extract one verified Android APK signing certificate from supported apksigner formats.

Android SDK apksigner prints `Signer #1 certificate SHA-256 digest: ...`.
Termux apksigner 0.9 prints `V3.0 Signer: certificate SHA-256 digest: ...`.
This parser does not grant trust: callers MUST invoke `apksigner verify` and
reject nonzero exits. It refuses ambiguous/multi-signer output rather than
picking the first digest or accidentally matching a public-key digest.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys

_CERT = re.compile(
    r'^\s*(?:Signer #(?P<index>[1-9][0-9]*)\s+|V[1-4](?:\.[0-9]+)? Signer:\s*)'
    r'certificate SHA-256 digest:\s*(?P<digest>[0-9a-fA-F:]+)\s*$',
    re.MULTILINE,
)
_CERT_LABEL = re.compile(r'^.*\bcertificate SHA-256 digest:.*$', re.MULTILINE)
_COUNT = re.compile(r'^\s*Number of signers:\s*(\d+)\s*$', re.MULTILINE)
_SHA64 = re.compile(r'[0-9a-f]{64}\Z')


class CertificateRejected(ValueError):
    """apksigner certificate output is unsupported or ambiguous."""


def parse_certificate(output: str) -> str:
    if not isinstance(output, str):
        raise CertificateRejected('Non-text certificate output')
    records = list(_CERT.finditer(output))
    labels = list(_CERT_LABEL.finditer(output))
    if not records or len(records) != len(labels):
        raise CertificateRejected('Missing or unrecognized signer certificate lines')
    counts = _COUNT.findall(output)
    if len(counts) > 1 or (counts and int(counts[0]) != 1):
        raise CertificateRejected('Expected one signing certificate')
    # Even duplicate identical certificates from different numbered signers
    # are not a single signer. V2/V3 scheme repetitions are allowed only when
    # their certificate digests agree.
    if any(m.group('index') not in (None, '1') for m in records):
        raise CertificateRejected('More than one numbered signer')
    values = [m.group('digest').replace(':', '').lower() for m in records]
    if any(not _SHA64.fullmatch(v) for v in values) or len(set(values)) != 1:
        raise CertificateRejected('Malformed or conflicting signer certificate')
    return values[0]


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description='Verify APK and output its unique signer SHA-256')
    parser.add_argument('--apksigner', default='apksigner')
    parser.add_argument('--apk', required=True)
    args = parser.parse_args(argv)
    try:
        result = subprocess.run([args.apksigner, 'verify', '--verbose', '--print-certs', args.apk],
                                capture_output=True, text=True, timeout=120, check=False)
        if result.returncode != 0:
            raise CertificateRejected('apksigner verification failed')
        if not re.search(r'^Verifies\s*$', result.stdout, re.MULTILINE):
            raise CertificateRejected('Missing successful signature verification marker')
        cert = parse_certificate(result.stdout)
    except (OSError, ValueError, subprocess.TimeoutExpired):
        print('BKX_CERT_REJECTED: signature or certificate verification failed', file=sys.stderr)
        return 2
    print(cert)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
