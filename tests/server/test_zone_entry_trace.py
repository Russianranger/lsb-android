import ctypes
import ctypes.util
import importlib.util
from pathlib import Path
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[2] / 'server/zone_entry_trace.py'
spec = importlib.util.spec_from_file_location('zone_entry_trace', SOURCE)
trace = importlib.util.module_from_spec(spec)
spec.loader.exec_module(trace)

class InstallTraceTest(unittest.TestCase):
    def source(self, root):
        for name, text in {
            'modules/module_utils.lua': 'function Module:addOverride(',
            'scripts/globals/player.lua': 'xi.player.charCreate = function(\nxi.player.onGameIn = function(',
            'scripts/globals/interaction/interaction_global.lua': '\n'.join('function InteractionGlobal.' + f + '(' for f in ('onZoneIn','afterZoneIn','onEventFinish')),
            'modules/init.txt': '# custom modules\r\ncustom/lua/gameplay.lua',
        }.items():
            p = root / name; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(text)

    def test_idempotent_install_preserves_existing_modules_and_source(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); self.source(root)
            before = {p: p.read_bytes() for p in root.rglob('*') if p.is_file()}
            self.assertIn('installed', trace.install(root))
            after = {p: p.read_bytes() for p in root.rglob('*') if p.is_file()}
            trace.install(root)
            self.assertEqual(after, {p:p.read_bytes() for p in root.rglob('*') if p.is_file()})
            for p, content in before.items():
                if p.name != 'init.txt': self.assertEqual(content, p.read_bytes())
            self.assertEqual((root/'modules/init.txt').read_text(), '# custom modules\ncustom/lua/gameplay.lua\n'+trace.ENTRY+'\n')

    def test_existing_directory_registration_does_not_duplicate_module(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); self.source(root); (root/'modules/init.txt').write_text('lsb_android/ # enabled\n')
            trace.install(root); self.assertEqual((root/'modules/init.txt').read_text(), 'lsb_android/ # enabled\n')

    def test_custom_files_links_and_unsupported_source_are_left_alone(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); self.assertIn('unavailable', trace.install(root)); self.assertEqual(list(root.iterdir()), [])
            self.source(root); target=root/'modules'/trace.ENTRY; target.parent.mkdir(); target.write_text('-- custom')
            self.assertIn('preserving', trace.install(root)); self.assertEqual(target.read_text(), '-- custom')
            target.unlink(); outside=root/'outside'; outside.write_text('unchanged'); target.symlink_to(outside)
            self.assertIn('symlink', trace.install(root)); self.assertEqual(outside.read_text(), 'unchanged')

    def test_lua_hooks_forward_arguments_results_and_errors_without_mutating_player(self):
        library=ctypes.util.find_library('lua5.4')
        if not library: self.skipTest('Lua shared library unavailable')
        lua=ctypes.CDLL(library)
        lua.luaL_newstate.restype=ctypes.c_void_p
        lua.luaL_openlibs.argtypes=[ctypes.c_void_p]
        lua.luaL_loadstring.argtypes=[ctypes.c_void_p,ctypes.c_char_p]
        lua.lua_pcallk.argtypes=[ctypes.c_void_p,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_longlong,ctypes.c_void_p]
        lua.lua_tolstring.argtypes=[ctypes.c_void_p,ctypes.c_int,ctypes.c_void_p];lua.lua_tolstring.restype=ctypes.c_char_p
        lua.lua_close.argtypes=[ctypes.c_void_p]
        fixture=r'''
            package.preload['modules/module_utils'] = function() end
            local hooks, lines = {}, {}
            Module = { new = function() return { addOverride = function(_, name, fn) hooks[name] = fn end } end }
            printf = function(fmt, ...) table.insert(lines, string.format(fmt, ...)) end
            io.open = function() error('disk full') end
            local player = { getName=function() return 'Fixture' end, getID=function() return 4 end, getZoneID=function() return 234 end }
            local module = (function()
        ''' + SOURCE.with_suffix('.lua').read_text() + r'''
            end)()
            local event, fallback = {1, -1, 48}, function() end
            super = function(p, previous, f) assert(p==player and previous==0 and f==fallback); return event end
            assert(hooks['InteractionGlobal.onZoneIn'](player, 0, fallback) == event)
            assert(lines[#lines]:find('event=1', 1, true))
            super = function(p, first, zoning) assert(p==player and first==true and zoning==false); return 19 end
            assert(hooks['xi.player.onGameIn'](player, true, false) == 19)
            super = function(p) assert(p==player); return 7 end
            assert(hooks['xi.player.charCreate'](player) == 7)
            super = function(p, f) assert(p==player and f==fallback); return false end
            assert(hooks['InteractionGlobal.afterZoneIn'](player, fallback) == false)
            super = function(p, id, option, npc, f) assert(p==player and id==1 and option==3 and npc==42 and f==fallback) end
            assert(hooks['InteractionGlobal.onEventFinish'](player, 1, 3, 42, fallback) == nil)
            super = function() error('original game failure') end
            local ok, message = pcall(hooks['xi.player.charCreate'], player)
            assert(not ok and message:find('original game failure', 1, true))
            assert(lines[#lines]:find('charCreate.begin', 1, true))
        '''
        state=lua.luaL_newstate();lua.luaL_openlibs(state)
        try:
            code=lua.luaL_loadstring(state,fixture.encode())
            if code==0: code=lua.lua_pcallk(state,0,0,0,0,None)
            self.assertEqual(code,0,lua.lua_tolstring(state,-1,None))
        finally: lua.lua_close(state)

if __name__ == '__main__': unittest.main()
