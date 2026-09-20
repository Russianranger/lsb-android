#!/usr/bin/env python3
"""Dependency-free JVM tests of actual archive, migration, and repair behavior."""
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[1]
classes = root / 'out' / 'tests'
classes.mkdir(parents=True, exist_ok=True)
sources = sorted((root / 'app/src/main/java/io/github/russianranger/lsb/core').glob('*.java'))
subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '--release', '8', '-d', str(classes), *map(str, sources), str(root/'tests/CoreTest.java'), str(root/'tests/PreparedClientTest.java')], check=True)
subprocess.run(['java', '-ea', '-cp', str(classes), 'CoreTest'], check=True)

subprocess.run(['java', '-ea', '-cp', str(classes), 'PreparedClientTest'], check=True)
