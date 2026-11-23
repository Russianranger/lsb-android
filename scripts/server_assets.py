"""Stage the managed server tree for both APK build routes."""
from pathlib import Path
import shutil


def stage_server_assets(root: Path) -> Path:
    destination = root / 'out/server-packaged-assets/server'
    if destination.exists():
        shutil.rmtree(destination)
    shutil.copytree(root / 'server', destination,
                    ignore=shutil.ignore_patterns('__pycache__', '*.py[co]', '.*', '*~'))
    shutil.copyfile(root / 'runtime/proot_preflight.py', destination / 'proot_preflight.py')
    return destination
