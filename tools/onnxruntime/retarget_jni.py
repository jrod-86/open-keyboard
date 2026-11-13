#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""
Makes ONNX Runtime's Java JNI library (libonnxruntime4j_jni.so, MIT, from
com.microsoft.onnxruntime:onnxruntime-android) ask for the ONNX Runtime version that
sherpa-onnx already ships, so the app carries one libonnxruntime.so instead of two.

The JNI library names the version of each ONNX Runtime symbol it needs (e.g. OrtGetApiBase
@VERS_1.28.0). Android's linker refuses a library whose version tag differs, even for a patch
release with the same C API. This rewrites the needed version tag (same length) and its ELF hash.

usage: retarget_jni.py in.so out.so VERS_1.28.0 VERS_1.28.2
"""
import struct, sys

def elf_hash(name: bytes) -> int:
    h = 0
    for c in name:
        h = ((h << 4) + c) & 0xffffffff
        g = h & 0xf0000000
        if g: h ^= g >> 24
        h &= ~g & 0xffffffff
    return h

src, dst, old, new = sys.argv[1], sys.argv[2], sys.argv[3].encode(), sys.argv[4].encode()
assert len(old) == len(new)
b = bytearray(open(src, 'rb').read())
is64 = b[4] == 2
end = '<'
if is64:
    shoff, = struct.unpack_from(end + 'Q', b, 0x28); shentsize, shnum, shstrndx = struct.unpack_from(end + 'HHH', b, 0x3A)
else:
    shoff, = struct.unpack_from(end + 'I', b, 0x20); shentsize, shnum, shstrndx = struct.unpack_from(end + 'HHH', b, 0x2E)

def sh(i):
    o = shoff + i * shentsize
    if is64:
        name, typ, flags, addr, off, size, link = struct.unpack_from(end + 'IIQQQQI', b, o)
    else:
        name, typ, flags, addr, off, size, link = struct.unpack_from(end + 'IIIIIII', b, o)
    return dict(name=name, type=typ, off=off, size=size, link=link)

SHT_GNU_verneed = 0x6ffffffe
changed = 0
for i in range(shnum):
    s = sh(i)
    if s['type'] != SHT_GNU_verneed: continue
    strtab = sh(s['link'])['off']
    o = s['off']
    while True:
        vn_version, vn_cnt, vn_file, vn_aux, vn_next = struct.unpack_from(end + 'HHIII', b, o)
        a = o + vn_aux
        for _ in range(vn_cnt):
            vna_hash, vna_flags, vna_other, vna_name, vna_next = struct.unpack_from(end + 'IHHII', b, a)
            p = strtab + vna_name
            if b[p:p + len(old) + 1] == old + b'\0':
                b[p:p + len(old)] = new
                struct.pack_into(end + 'I', b, a, elf_hash(new))
                changed += 1
            if not vna_next: break
            a += vna_next
        if not vn_next: break
        o += vn_next
if not changed: sys.exit('version tag not found')
open(dst, 'wb').write(b)
print(f'{dst}: {changed} version tag(s) now {new.decode()}')
