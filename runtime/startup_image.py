"""Build an opt-in, temporary PE32 diagnostic copy; never edit the import.

Only a new import section is added. Original code, RVAs, entry point and imports
stay in place. Unsupported layouts fail before launch; no heuristic patching.
"""
import hashlib
import os
from pathlib import Path
import struct
import tempfile


def instrument(raw):
    data = bytearray(raw)
    def need(ok):
        if not ok:
            raise ValueError('Unsupported loader layout for startup trace. Turn off Capture FFXI startup result to use the original loader.')
    def u16(p):
        need(0 <= p <= len(data)-2)
        return struct.unpack_from('<H', data, p)[0]
    def u32(p):
        need(0 <= p <= len(data)-4)
        return struct.unpack_from('<I', data, p)[0]
    def put(p, value):
        struct.pack_into('<I', data, p, value)
    def align(n, a):
        return (n+a-1)//a*a
    need(64 <= len(data) <= 64*1024*1024 and data[:2] == b'MZ')
    pe = u32(60)
    need(data[pe:pe+4] == b'PE\0\0' and u16(pe+4) == 0x14c)
    count, opt_size = u16(pe+6), u16(pe+20)
    opt = pe+24
    need(1 <= count < 96 and opt_size >= 224 and u16(opt) == 0x10b and not u16(pe+22)&0x2000)
    need(u32(opt+92) >= 16)
    section_align, file_align = u32(opt+32), u32(opt+36)
    need(512 <= file_align <= 65536 and file_align & (file_align-1) == 0)
    need(file_align <= section_align <= 65536 and section_align & (section_align-1) == 0)
    table = opt+opt_size
    sections = []
    for i in range(count):
        p = table+i*40
        need(p+40 <= len(data))
        size, rva, length, offset = (u32(p+k) for k in (8,12,16,20))
        need(rva % section_align == 0 and offset+length <= len(data))
        need(rva+max(size,length) < 0x40000000)
        sections.append((rva,size,length,offset))
    header = table+count*40
    need(header+40 <= u32(opt+60) <= len(data))
    need(all(header+40 <= off for _,_,length,off in sections if length))
    need(data[header:header+40] == bytes(40))
    def file_offset(rva, length):
        for start, _, raw_length, off in sections:
            if start <= rva and rva+length <= start+raw_length:
                return off+rva-start
        need(False)
    import_rva, import_size = u32(opt+104), u32(opt+108)
    need(import_rva != 0 and 20 <= import_size <= 20*257)
    descriptors = bytearray()
    found = False
    for n in range(import_size//20):
        p = file_offset(import_rva+n*20,20)
        row = bytearray(data[p:p+20])
        if row == bytes(20):
            break
        name = file_offset(u32(p+12),1)
        need(b'\0' in data[name:name+256])
        # Do not reuse stale bound addresses in the new import directory.
        struct.pack_into('<I',row,4,0)
        descriptors += row
        found = True
    else:
        need(False)
    need(found)
    rva = align(max(s+max(v,n) for s,v,n,_ in sections),section_align)
    offset = align(len(data),file_align)
    desc_end = len(descriptors)+40
    ilt, iat = desc_end, desc_end+8
    symbol = desc_end+16
    symbol_bytes = b'\0\0LsbStartupTrace\0'
    name = symbol+len(symbol_bytes)
    payload = descriptors+struct.pack('<IIIII',rva+ilt,0,0,rva+name,rva+iat)+bytes(20)
    payload += struct.pack('<IIII',rva+symbol,0,rva+symbol,0)+symbol_bytes+b'P:\\startup-trace.dll\0'
    raw_size = align(len(payload),file_align)
    need(rva+align(len(payload),section_align) < 0x40000000)
    data += bytes(offset-len(data))+payload+bytes(raw_size-len(payload))
    struct.pack_into('<8sIIIIIIHHI',data,header,b'.lsbimp\0',len(payload),rva,raw_size,offset,0,0,0,0,0xc0000040)
    struct.pack_into('<H',data,pe+6,count+1)
    put(opt+8,u32(opt+8)+raw_size)  # SizeOfInitializedData
    put(opt+56,rva+align(len(payload),section_align))
    put(opt+64,0)  # checksum; diagnostic copy is explicitly unsigned
    put(opt+104,rva);put(opt+108,desc_end)
    for directory in (4,11):  # security and bound imports cannot describe this copy
        put(opt+96+directory*8,0);put(opt+100+directory*8,0)
    return bytes(data)


def prepare(loader, expected_sha):
    loader = Path(loader)
    raw = loader.read_bytes()
    if hashlib.sha256(raw).hexdigest() != expected_sha:
        raise ValueError('Loader changed after preparation; startup trace was not created.')
    traced = instrument(raw)
    # Exclusive same-directory file: retain DLL lookup and working-directory
    # behavior, never overwrite an existing file, and keep the original intact.
    fd, name = tempfile.mkstemp(prefix='lsb-startup-',suffix='.exe',dir=loader.parent)
    target = Path(name)
    try:
        with os.fdopen(fd,'wb') as output:
            output.write(traced);output.flush();os.fsync(output.fileno())
        return target, {'policy':'temporary_import_copy','source_sha256':expected_sha,
                        'image_sha256':hashlib.sha256(traced).hexdigest()}
    except BaseException:
        target.unlink(missing_ok=True)
        raise
