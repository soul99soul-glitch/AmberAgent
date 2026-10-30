"""Host-only checks for explicit inputs, isolated install guards and argv quoting."""
import importlib.util
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('portable_perf', SCRIPTS / 'chat-history-entry-perf.py')
perf = importlib.util.module_from_spec(spec)
spec.loader.exec_module(perf)


class PortableHostTest(unittest.TestCase):
    def test_fallback_evidence_uses_configured_root_and_unique_directories(self):
        from argparse import Namespace
        with tempfile.TemporaryDirectory() as directory, patch.dict(os.environ, {'CHAT_PERF_EVIDENCE_ROOT': directory}):
            first = perf.evidence_target(Namespace(), 'start')
            second = perf.evidence_target(Namespace(), 'start')
            self.assertEqual(Path(directory) / 'runs', first.parent.parent)
            self.assertNotEqual(first.parent, second.parent)

    def test_install_uses_quoted_adb_argv_and_rejects_product_apk_before_adb(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log = root / 'calls.jsonl'
            adb = root / 'fake adb.py'
            adb.write_text('import json,os,sys\nwith open(os.environ["CALLS"], "a") as f: f.write(json.dumps(sys.argv[1:])+"\\n")\n')
            aapt = root / 'aapt2'
            aapt.write_text('#!/usr/bin/env python3\nimport pathlib,sys\nprint(pathlib.Path(sys.argv[-1]).read_text())\n')
            aapt.chmod(0o755)
            sleep = root / 'sleep'
            sleep.write_text('#!/bin/sh\nexit 0\n')
            sleep.chmod(0o755)
            on = root / 'current on.apk'
            off = root / 'current off.apk'
            test = root / 'current test.apk'
            for apk, package in [(on, perf.PACKAGE), (off, perf.PACKAGE), (test, perf.PACKAGE + '.test')]:
                apk.write_text(f"package: name='{package}'")
            env = {**os.environ, 'PATH': directory + os.pathsep + os.environ['PATH'],
                   'CALLS': str(log), 'ADB': shlex.join(['python3', str(adb)]),
                   'AAPT2': str(aapt), 'APKSIGNER': 'unused', 'SERIAL': 'fixture-test',
                   'EVIDENCE_ROOT': str(root / 'evidence'), 'ON_APK': str(on), 'OFF_APK': str(off),
                   'TEST_APK': str(test), 'SOURCE_MANIFEST': str(root / 'source.json')}
            command = ['/bin/bash', str(SCRIPTS / 'chat-history-entry-device.sh'), 'install', 'on']
            result = subprocess.run(command, env=env, text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            self.assertEqual([['-s', 'fixture-test', 'install', '-r', str(on)],
                              ['-s', 'fixture-test', 'install', '-r', str(test)]], calls)
            on.write_text("package: name='app.amber.agent.graphite'")
            before = log.read_text()
            result = subprocess.run(command, env=env, text=True, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('refusing to install', result.stderr)
            self.assertEqual(before, log.read_text())
