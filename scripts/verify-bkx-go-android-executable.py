#!/usr/bin/env python3
"""REL09: inspect the packaged BKX Android Go executable, not just an ELF magic byte.

The runtime is a PIE launched from extracted nativeLibraryDir. A binary may
be present and executable yet fail execve(ENOENT) if its PT_INTERP is wrong.
No subprocess, signing secret, network or device access is needed.
"""
from pathlib import Path
import argparse
import os
import struct

EXPECTED_LINKER = b'/system/bin/linker64'
PT_LOAD = 1
PT_INTERP = 3
ET_DYN = 3
EM_AARCH64 = 183

class Rejected(ValueError):
    pass

def require(ok, reason):
    if not ok:
        raise Rejected(reason)

def inspect(path: Path):
    require(path.is_file() and not path.is_symlink(), 'Go runtime is not a regular file')
    require(0 < path.stat().st_size <= 250 * 1024 * 1024, 'Go ELF size is invalid')
    with path.open('rb') as f:
        hdr = f.read(64)
        require(len(hdr) == 64 and hdr[:6] == b'\x7fELF\x02\x01', 'Go ELF must be little-endian ELF64')
        e_type, machine = struct.unpack_from('<HH', hdr, 16)
        require(e_type == ET_DYN and machine == EM_AARCH64, 'Go ELF must be Android ARM64 PIE (ET_DYN)')
        phoff = struct.unpack_from('<Q', hdr, 32)[0]
        entsize = struct.unpack_from('<H', hdr, 54)[0]
        count = struct.unpack_from('<H', hdr, 56)[0]
        require(entsize >= 56 and 0 < count <= 256 and phoff <= path.stat().st_size and
                count * entsize <= path.stat().st_size - phoff, 'Unsafe ELF program-header bounds')
        f.seek(phoff)
        phdrs = f.read(count * entsize)
        interpreter = []
        loads = 0
        for index in range(count):
            at = index * entsize
            kind, flags, offset, _, _, size, _, alignment = struct.unpack_from('<IIQQQQQQ', phdrs, at)
            if kind == PT_LOAD:
                require(alignment >= 16384 and (alignment & (alignment - 1)) == 0,
                        'Go executable has a PT_LOAD below 16 KiB alignment')
                loads += 1
            if kind == PT_INTERP:
                require(size in range(2, 129) and offset <= path.stat().st_size and
                        size <= path.stat().st_size - offset, 'Unsafe ELF interpreter offset')
                f.seek(offset)
                interpreter.append(f.read(size))
        require(loads > 0, 'Go runtime has no loadable ELF segments')
        require(interpreter == [EXPECTED_LINKER + b'\0'],
                'Go Android runtime has an unexpected or missing ELF interpreter')
    return {'android_pie': True, 'abi': 'arm64-v8a', 'pt_load_16k': True,
            'interpreter': EXPECTED_LINKER.decode(), 'native_library_name': 'libbkxgo.so'}

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('elf', type=Path)
    args = p.parse_args()
    try:
        output = inspect(args.elf)
    except (OSError, Rejected) as exc:
        p.exit(2, 'BKX_GO_ANDROID_ELF_REJECTED: ' + str(exc) + '\n')
    print('PASS_BKX_GO_ANDROID_PIE_LINKER_16K ' + output['interpreter'])

if __name__ == '__main__':
    main()
