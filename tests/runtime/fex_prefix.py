"""CI-only copy of an actual stopped Box64 prefix, or empty native probe prefix."""
import hashlib,json,shutil,sys
from pathlib import Path

def snapshot(root):
    out={}
    for p in root.rglob('*'):
        name=str(p.relative_to(root))
        if p.is_symlink():out[name]=('link',str(p.readlink()))
        elif p.is_file():
            with p.open('rb') as f:out[name]=('file',hashlib.file_digest(f,'sha256').hexdigest())
    return out

source=Path('/baseline-prefix');target=Path('/prefix');receipt=Path('/logs/baseline-prefix-snapshot.json')
if sys.argv[1]=='verify':
    assert json.loads(json.dumps(snapshot(source)))==json.loads(receipt.read_text()),'FEX changed Box64 prefix'
    print('PASS: every Box64 prefix file and link remains unchanged after FEX tests',flush=True)
else:
    if source.exists():
        receipt.write_text(json.dumps(snapshot(source)))
        shutil.copytree(source,target,symlinks=True,dirs_exist_ok=True)
    else:target.mkdir(exist_ok=True)
    m=json.loads(Path('/opt/lsb/fex-bundle.json').read_text())
    (target/'lsb-runtime-engine.json').write_text(json.dumps({'engine':'fex','runtime':m['sha256']}))
    (target/'lsb-prefix-ready.json').unlink(missing_ok=True)
    print('Prepared separate FEX prefix; baseline mounted read-only',flush=True)
