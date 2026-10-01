"""Require every FEX PE import to exist in the exact packaged native Wine DLLs."""
import re,struct,sys
from pathlib import Path
root=Path(sys.argv[1] if len(sys.argv)>1 else '/stage/opt/wine')
def exports(path):
    b=path.read_bytes();nt=struct.unpack_from('<I',b,60)[0]
    assert b[nt:nt+6]==b'PE\0\0\x64\xaa',str(path)+' is not ARM64 PE'
    sections=struct.unpack_from('<H',b,nt+6)[0];optsize=struct.unpack_from('<H',b,nt+20)[0];opt=nt+24
    rva=struct.unpack_from('<I',b,opt+112)[0];ranges=[]
    for i in range(sections):
        at=opt+optsize+i*40;vsize,va,size,raw=struct.unpack_from('<4I',b,at+8);ranges.append((va,max(vsize,size),raw))
    def offset(rva):
        for va,size,raw in ranges:
            if va<=rva<va+size:return raw+rva-va
        raise ValueError('PE RVA outside sections')
    at=offset(rva);count=struct.unpack_from('<I',b,at+24)[0];names=offset(struct.unpack_from('<I',b,at+32)[0]);out=set()
    for i in range(count):
        p=offset(struct.unpack_from('<I',b,names+i*4)[0]);out.add(b[p:b.index(0,p)].decode())
    return out
text=(root/'share/lsb-sources/fex-imports.txt').read_text();total=0
for body in re.findall(r'Import \{(.*?)\}',text,re.S):
    dll=re.search(r'Name: (\S+)',body).group(1)
    assert dll in ('ntdll.dll','wow64.dll'),'Unexpected FEX dependency: '+dll
    required=re.findall(r'Symbol: (\S+)',body)
    missing=set(required)-exports(root/'lib/wine/aarch64-windows'/dll)
    assert not missing,(dll,sorted(missing))
    print(dll,len(required),'imports resolved');total+=len(required)
assert total>0,'No FEX import audit'
print('PASS:',total,'FEX imports exist in the packaged ARM64 Wine DLLs')
