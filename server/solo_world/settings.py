"""Install the settings LandSandBoat caches before it loads any map modules."""
from pathlib import Path
from tempfile import NamedTemporaryFile


NAME = "zz_handheld_solo.lua"
PAYLOAD = """-- Managed by LSB Android: handheld solo world
-- settings/map.lua remains the user's file. LSB reads settings in filename order.
local map = xi.settings.map
local solo = xi.settings.handheld_solo or {}
xi.settings.handheld_solo = solo

if solo.ALL_CRAFTS ~= false then
    -- Skills are stored in tenths. Leave guild tests and each craft's rank cap intact.
    local points = 8 * math.max(0, 1100 - (map.CRAFT_COMMON_CAP or 700))
    map.CRAFT_SPECIALIZATION_POINTS = math.max(map.CRAFT_SPECIALIZATION_POINTS or 0, points)
end
if solo.ENABLE_FISHING ~= false then
    map.FISHING_ENABLE = true
end
"""


def apply(root, enabled=True):
    """Maintain our additional settings file without editing existing settings."""
    directory = Path(root) / "settings"
    path = directory / NAME
    if directory.is_symlink() or path.is_symlink():
        raise ValueError("Solo settings path is a symlink")
    if path.exists():
        if path.read_text() != PAYLOAD:
            raise ValueError("Preserving custom settings at " + str(path))
        if not enabled:
            path.unlink()
        return
    if enabled:
        with NamedTemporaryFile("w", dir=directory, delete=False) as temp:
            pending = Path(temp.name)
            try:
                temp.write(PAYLOAD)
                temp.flush()
                pending.replace(path)
            finally:
                pending.unlink(missing_ok=True)
