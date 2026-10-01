"""CI consumes one immutable build artifact; APK embeds its exact manifest."""
import hashlib,json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
expected=(root/'runtime/fex-bundle.json').read_bytes()
folder=root/'out/fex-runtime'
assert (folder/'fex-bundle.json').read_bytes()==expected,'FEX build artifact differs from pinned APK manifest'
m=json.loads(expected);archive=folder/'runtime-fex-arm64.tar.gz'
assert archive.stat().st_size==m['bytes'],'FEX archive size mismatch'
with archive.open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()==m['sha256'],'FEX archive checksum mismatch'
print('PASS: pinned FEX archive and APK manifest agree',m['sha256'])
