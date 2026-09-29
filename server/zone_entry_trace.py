"""Install diagnostic Lua hooks into a deployed generation without rebuilding it."""
from pathlib import Path

ENTRY = 'lsb_android/zone_entry_trace.lua'
MARKER = '-- LSB Android managed zone-entry trace '

def install(root):
    root = Path(root)
    modules = root / 'modules'
    init = modules / 'init.txt'
    target = modules / ENTRY
    # Imported older/custom sources may not expose this module API. Do not make
    # them unbootable or replace user-owned scripts in order to collect a trace.
    required = {
        modules / 'module_utils.lua': ('function Module:addOverride(',),
        root / 'scripts/globals/interaction/interaction_global.lua':
            ('function InteractionGlobal.onZoneIn(', 'function InteractionGlobal.afterZoneIn(', 'function InteractionGlobal.onEventFinish('),
        root / 'scripts/globals/player.lua': ('xi.player.charCreate = function(', 'xi.player.onGameIn = function('),
    }
    if any(not p.is_file() or any(token not in p.read_text() for token in tokens) for p, tokens in required.items()):
        return 'Zone-entry trace unavailable: this source does not expose the required Lua hooks.'
    if any(p.is_symlink() for p in (modules, init, target.parent, target)):
        return 'Zone-entry trace skipped: module path is a symlink.'
    if target.exists() and not target.read_text().startswith(MARKER):
        return 'Zone-entry trace skipped: preserving a custom module at the managed path.'
    content = init.read_text() if init.exists() else ''
    # Match LSB's loader: only full-line comments are comments; an inline '#'
    # belongs to the path and must not suppress our valid module entry.
    active = [line.strip().rstrip('/') for line in content.splitlines() if not line.startswith('#')]
    included = any(entry and (entry == ENTRY or ENTRY.startswith(entry + '/')) for entry in active)
    payload = Path(__file__).with_suffix('.lua').read_text()
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists() or target.read_text() != payload:
        pending = target.with_suffix('.lua.new')
        with pending.open('x') as out: out.write(payload)
        pending.replace(target)
    if not included:
        pending = init.with_suffix('.txt.new')
        with pending.open('x') as out: out.write(content + ('\n' if content and not content.endswith('\n') else '') + ENTRY + '\n')
        pending.replace(init)
    return 'Zone-entry trace v1 installed; character setup and cutscene callbacks will be included in support logs.'
