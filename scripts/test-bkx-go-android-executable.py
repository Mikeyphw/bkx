#!/usr/bin/env python3
"""REL09: real ELF64 header + interpreter and adversarial APK-runtime fixtures."""
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest

SOURCE = Path(__file__).with_name('verify-bkx-go-android-executable.py')
spec = importlib.util.spec_from_file_location('bkx_runtime_elf', SOURCE)
v = importlib.util.module_from_spec(spec); spec.loader.exec_module(v)

def elf(*, kind=3, machine=183, interp=b'/system/bin/linker64\0', align=65536):
    raw=bytearray(512)
    raw[:6]=b'\x7fELF\x02\x01'
    struct.pack_into('<HH',raw,16,kind,machine)
    struct.pack_into('<Q',raw,32,64)
    struct.pack_into('<H',raw,54,56)
    struct.pack_into('<H',raw,56,2)
    struct.pack_into('<IIQQQQQQ',raw,64,1,5,0,0,0,64,64,align)
    struct.pack_into('<IIQQQQQQ',raw,120,3,4,256,256,256,len(interp),len(interp),1)
    raw[256:256+len(interp)] = interp
    return bytes(raw)

class TestGoAndroidExecutable(unittest.TestCase):
    def check(self,blob):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'libbkxgo.so';path.write_bytes(blob)
            return v.inspect(path)
    def test_good_android_pie(self):
        self.assertEqual(self.check(elf())['interpreter'],'/system/bin/linker64')
    def test_linux_glibc_interpreter_rejected(self):
        with self.assertRaisesRegex(v.Rejected,'interpreter'):
            self.check(elf(interp=b'/lib/ld-linux-aarch64.so.1\0'))
    def test_missing_interpreter_rejected(self):
        with self.assertRaisesRegex(v.Rejected,'interpreter'):
            self.check(elf(interp=b''))
    def test_exec_type_rejected(self):
        with self.assertRaisesRegex(v.Rejected,'PIE'):
            self.check(elf(kind=2))
    def test_abi_rejected(self):
        with self.assertRaisesRegex(v.Rejected,'PIE'):
            self.check(elf(machine=62))
    def test_4k_alignment_rejected(self):
        with self.assertRaisesRegex(v.Rejected,'16 KiB'):
            self.check(elf(align=4096))
    def test_bounds_rejected(self):
        with self.assertRaisesRegex(v.Rejected,'bounds'):
            self.check(elf()[:100])
    def test_magic_rejected(self):
        with self.assertRaisesRegex(v.Rejected,'little-endian'):
            self.check(b'data')

if __name__=='__main__': unittest.main(verbosity=2)
