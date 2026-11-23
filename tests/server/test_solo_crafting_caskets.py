import ctypes
import ctypes.util
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'server'))
from solo_world import settings


class HandheldCraftingCasketTests(unittest.TestCase):
    def test_installer_preserves_settings(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            directory = root / 'settings'
            directory.mkdir()
            user = directory / 'map.lua'
            user.write_text('user overrides')
            settings.apply(root)
            settings.apply(root)
            self.assertEqual((directory / settings.NAME).read_text(), settings.PAYLOAD)
            settings.apply(root, False)
            self.assertEqual(user.read_text(), 'user overrides')
            self.assertFalse((directory / settings.NAME).exists())
            (directory / settings.NAME).write_text('custom override')
            with self.assertRaises(ValueError):
                settings.apply(root, False)
            self.assertEqual((directory / settings.NAME).read_text(), 'custom override')

    def test_gameplay(self):
        name = ctypes.util.find_library('lua5.4') or ctypes.util.find_library('lua5.3')
        if not name:
            self.skipTest('Lua runtime not installed')
        lua = ctypes.CDLL(name)
        lua.luaL_newstate.restype = ctypes.c_void_p
        lua.luaL_openlibs.argtypes = [ctypes.c_void_p]
        lua.luaL_loadstring.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
        lua.lua_pcallk.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int,
                                   ctypes.c_ssize_t, ctypes.c_void_p]
        lua.lua_tolstring.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p]
        lua.lua_tolstring.restype = ctypes.c_char_p
        lua.lua_close.argtypes = [ctypes.c_void_p]
        module_directory = ROOT / 'server/solo_world/modules/handheld_solo'
        fixture = Path(__file__).with_name('solo_crafting_caskets.lua').read_text()
        script = 'local moduleDir = [=[' + str(module_directory) + ']=]\n'
        script += 'local function applySettings()\n' + settings.PAYLOAD + '\nend\n' + fixture
        state = lua.luaL_newstate()
        lua.luaL_openlibs(state)
        try:
            status = lua.luaL_loadstring(state, script.encode())
            if status == 0:
                status = lua.lua_pcallk(state, 0, 0, 0, 0, None)
            self.assertEqual(status, 0, lua.lua_tolstring(state, -1, None))
        finally:
            lua.lua_close(state)


if __name__ == '__main__':
    unittest.main()
