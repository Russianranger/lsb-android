"""Build and operate the optional handheld world inside the managed LSB generation."""
import shutil
import subprocess
import time
from pathlib import Path

from . import settings

FORMAT = 2
MODULES = ("handheld_solo", "handheld_squad")
ASSETS = Path(__file__).resolve().parent


def install(root):
    """Install into the disposable build workspace, before xi_map's CMake configure."""
    root = Path(root)
    modules = root / "modules"
    init = modules / "init.txt"
    if not init.is_file() or not (modules / "module_utils.lua").is_file():
        raise ValueError("This server source does not support the handheld world modules.")
    for filename in ("0001-personal-squad.patch", "0002-auction-demand.patch", "0003-personal-squad-release.patch"):
        patch = ASSETS / "patches" / filename
        args = ["git", "apply", "--whitespace=nowarn"]
        if subprocess.run(args + ["--check", str(patch)], cwd=root, capture_output=True).returncode == 0:
            subprocess.run(args + [str(patch)], cwd=root, check=True)
        elif subprocess.run(args + ["--reverse", "--check", str(patch)], cwd=root, capture_output=True).returncode:
            raise ValueError(f"{filename} does not match the selected source. Fetch the supported "
                             "LandSandBoat revision 6d5a5137024e21602e37032f6e55a682971329be and build again.")
    for name in MODULES:
        source = ASSETS / "modules" / name
        target = modules / name
        # A re-import may already contain these names. Preserve independent user edits.
        if target.exists():
            for item in target.rglob("*"):
                original = source / item.relative_to(target)
                if item.is_file() and (not original.is_file() or item.read_bytes() != original.read_bytes()):
                    raise ValueError(f"Conflicting files in modules/{name}. Choose a clean source snapshot.")
        shutil.copytree(source, target, dirs_exist_ok=True)
    content = init.read_text()
    registered = {line.strip().rstrip("/") for line in content.splitlines() if not line.startswith("#")}
    missing = [name for name in MODULES if name not in registered]
    if missing:
        with init.open("a") as output:
            if content and not content.endswith("\n"):
                output.write("\n")
            output.write("\n".join(missing) + "\n")
    settings.apply(root)


def prepare_schema(root, query):
    """Also works with older dumps restored onto an already-built solo generation."""
    from . import auction
    path = Path(root) / "modules/handheld_squad/sql/handheld_squad.sql"
    query(path.read_text())
    auction.prepare(query)
    # Detect incompatible player imports while still in the isolated staging generation.
    # These reads also cover restores from a backup created before the feature existed.
    tables = query("""SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()
          AND TABLE_NAME IN ('auction_house','auction_house_items','delivery_box','conquest_system')
          AND ENGINE='InnoDB';""").strip()
    triggers = query("""SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()
          AND EVENT_OBJECT_TABLE='auction_house'
          AND ((TRIGGER_NAME='auction_house_buy' AND EVENT_MANIPULATION='UPDATE')
          OR (TRIGGER_NAME='auction_house_list' AND EVENT_MANIPULATION='INSERT'));""").strip()
    regions = query("SELECT COUNT(*) FROM conquest_system WHERE region_id BETWEEN 0 AND 18;").strip()
    if (tables, triggers, regions) != ('4', '2', '19'):
        raise ValueError("Solo world needs the standard auction and delivery tables, payout triggers, "
                         "and all 19 conquest regions. Check the staged database import.")


class WorldSession:
    """Run the local market and the away clock on the existing server supervisor."""
    def __init__(self, query):
        from . import auction, conquest
        self.query = query
        self.conquest = conquest
        self.auction = auction
        self.session = None

    def start(self):
        self.session = self.conquest.start(self.query)
        self.next_heartbeat = time.monotonic() + self.conquest.HEARTBEAT_SECONDS
        self.next_market = 0
        self.service()

    def service(self):
        now = time.monotonic()
        if now >= self.next_heartbeat:
            self.next_heartbeat = now + self.conquest.HEARTBEAT_SECONDS
            try:
                self.conquest.heartbeat(self.query, self.session)
            except (RuntimeError, subprocess.TimeoutExpired) as error:
                print(f"Solo conquest heartbeat postponed: {error}", flush=True)
        if now >= self.next_market:
            self.next_market = now + self.auction.INTERVAL_SECONDS
            try:
                self.auction.tick(self.query)
            except (RuntimeError, ValueError, subprocess.TimeoutExpired) as error:
                # Existing stock and character data remain usable until a later retry.
                print(f"Solo auction refresh postponed: {error}", flush=True)

    def stop(self):
        if self.session is not None:
            self.conquest.stop(self.query, self.session)
