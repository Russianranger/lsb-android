#!/usr/bin/env python3
"""Verify installation isolation and identical code/runtime payloads in both APKs."""
import argparse
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def verify(aapt2, standard, restore_test):
    root = Path(__file__).resolve().parents[1]
    source = ET.parse(root/'app/src/main/AndroidManifest.xml').getroot()
    android = '{http://schemas.android.com/apk/res/android}'
    version = (source.get(android+'versionCode'), source.get(android+'versionName'))
    base = 'io.github.russianranger.lsb'
    components = {base+'.'+name for name in ('MainActivity', 'RuntimeActivity', 'BackupBrowserActivity', 'RuntimeService', 'ServerService', 'WorkService')}
    for apk, package, label in ((standard, base, 'LSB Android'), (restore_test, base+'.restoretest', 'LSB Restore Test')):
        badging = subprocess.check_output([str(aapt2), 'dump', 'badging', str(apk)], text=True)
        identity = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
        require(identity and identity.groups() == (package, *version), 'Incorrect APK package/version: '+str(apk))
        require("application-label:'"+label+"'" in badging, 'Incorrect app label: '+str(apk))
        require("launchable-activity: name='"+base+".MainActivity'" in badging, 'Incorrect launcher component: '+str(apk))
        tree = subprocess.check_output([str(aapt2), 'dump', 'xmltree', '--file', 'AndroidManifest.xml', str(apk)], text=True)
        require('sharedUserId' not in tree, 'APK must not share a UID: '+str(apk))
        require(not re.search(r'E: (?:provider|receiver) ', tree), 'Review added cross-app entry points: '+str(apk))
        require(re.search(r':allowBackup\([^)]*\)=false', tree), 'Android automatic restore must remain disabled: '+str(apk))
        names = set(re.findall(r'E: (?:activity|service) [^\n]*\n\s*A: [^\n]*:name\([^)]*\)="([^"]+)"', tree))
        require(names == components, 'APK components must retain the Java/JNI namespace: '+str(apk))
        require(len(re.findall(r':exported\([^)]*\)=true', tree)) == 1, 'Only the launcher may be exported: '+str(apk))

    def payload(apk):
        with zipfile.ZipFile(apk) as archive:
            require(len(archive.namelist()) == len(set(archive.namelist())), 'Duplicate APK entries: '+str(apk))
            require(archive.testzip() is None, 'Corrupt APK entry: '+str(apk))
            return {name: archive.read(name) for name in archive.namelist()
                    if name not in ('AndroidManifest.xml', 'resources.arsc') and not name.startswith('META-INF/')}

    normal, test = payload(standard), payload(restore_test)
    require(normal == test, 'APK code, native libraries or runtime assets differ between variants')
    print('PASS: separate app IDs/labels, private non-launcher components, identical '+str(len(normal))+' code/resource/runtime entries')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--aapt2', type=Path, required=True)
    parser.add_argument('--standard', type=Path, required=True)
    parser.add_argument('--restore-test', type=Path, required=True)
    args = parser.parse_args()
    verify(args.aapt2, args.standard, args.restore_test)
