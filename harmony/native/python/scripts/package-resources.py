#!/usr/bin/env python3
"""Deterministic uncompressed stdlib archive, readable without zlib extension."""
from pathlib import Path
import zipfile
root = Path(__file__).resolve().parents[1]
source = root / 'build/sources/Python-3.14.7/Lib'
target = root.parents[1] / 'entry/src/main/resources/rawfile/python/python314.zip'
excluded = {'test', 'tests', '__pycache__', 'idlelib', 'turtledemo', 'ensurepip', 'tkinter'}
with zipfile.ZipFile(target, 'w', compression=zipfile.ZIP_STORED) as archive:
    notice = zipfile.ZipInfo('LICENSE.txt', (1980, 1, 1, 0, 0, 0))
    notice.external_attr = 0o644 << 16
    archive.writestr(notice, (source.parent / 'LICENSE').read_bytes())
    for path in sorted(source.rglob('*.py')):
        relative = path.relative_to(source)
        if any(part in excluded for part in relative.parts):
            continue
        item = zipfile.ZipInfo(relative.as_posix(), (1980, 1, 1, 0, 0, 0))
        item.external_attr = 0o644 << 16
        archive.writestr(item, path.read_bytes())
