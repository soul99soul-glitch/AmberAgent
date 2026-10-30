#!/usr/bin/env python3
"""Primary device owner only: bounded Home instrumentation, never installs or changes settings.

The construction agent tests this with a local fake adb only. On outer timeout,
first persist stdout/checkpoints, then stop ONLY the isolated fixture package.
No whole-device log clear, no product/user content collection.
"""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import signal
import subprocess
import time
import uuid

PACKAGE = 'app.amber.agent.perffixture'
CLASS = PACKAGE + '.HomeFirstDeviceTest'
COMPONENT = PACKAGE + '.test/' + PACKAGE + '.PerfFixtureAndroidTestRunner'


def classify(output, returncode, timed_out):
    if timed_out:
        return 'timeout'
    if returncode != 0:
        return 'failed'
    # adb shell exit 0 is not a JUnit verdict. Require exactly one successful
    # test AND the test's terminal checkpoint (checked by run()).
    if re.search(r'(?m)^\s*OK \(1 test\)\s*$', output) and not re.search(
            r'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[12]', output):
        return 'ok'
    return 'failed'


def run(adb, serial, output_dir, timeout_seconds=130):
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=False)  # preserve every prior attempt
    token = 'home-' + uuid.uuid4().hex
    prefix = [*adb, '-s', serial]
    command = [*prefix, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class', CLASS,
               '-e', 'homeTestToken', token, COMPONENT]
    report = {'schema': 1, 'token': token, 'testClass': CLASS, 'command': command,
              'outerTimeoutSeconds': timeout_seconds, 'status': 'running', 'deviceTestPassed': False}
    result_path = output_dir / 'result.json'

    def save():
        result_path.write_text(json.dumps(report, indent=2) + '\n')

    def checkpoint_log():
        try:
            result = subprocess.run([*prefix, 'logcat', '-d', '-v', 'threadtime', '-s',
                                     'AmberHomeDeviceTest:I', '*:S'],
                                    text=True, capture_output=True, timeout=8)
            # Token filters out older attempts without clearing any system log.
            lines = [line for line in result.stdout.splitlines() if 'token=' + token + ' ' in line]
            (output_dir / 'checkpoints.log').write_text('\n'.join(lines) + '\n')
            report['checkpointReadExitCode'] = result.returncode
            if result.stderr:
                (output_dir / 'checkpoint-read.stderr').write_text(result.stderr)
            events = [line for line in lines if ' event=' in line]
            report['lastCheckpoint'] = events[-1] if events else None
            report['terminalCheckpoint'] = any('stage=test event=ok ' in line for line in events)
        except (OSError, subprocess.TimeoutExpired) as error:
            report['checkpointReadError'] = type(error).__name__
            report['terminalCheckpoint'] = False

    save()
    started = time.monotonic()
    timed_out = False
    try:
        with (output_dir / 'instrumentation.txt').open('w') as output:
            process = subprocess.Popen(command, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            try:
                process.wait(timeout=timeout_seconds)
            except subprocess.TimeoutExpired:
                timed_out = True
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)
        report['exitCode'] = process.returncode
    except OSError as error:
        report.update(status='failed', launchError=type(error).__name__)
        save()
        return report
    report['elapsedSeconds'] = time.monotonic() - started
    raw = (output_dir / 'instrumentation.txt').read_text()
    report['status'] = classify(raw, process.returncode, timed_out)
    report['outerTimedOut'] = timed_out
    checkpoint_log()
    if report['status'] == 'ok' and not report.get('terminalCheckpoint'):
        report.update(status='failed', reason='JUnit OK without matching test terminal checkpoint')
    report['deviceTestPassed'] = report['status'] == 'ok'
    # Persist the failure BEFORE any cleanup. Stopping this isolated test process
    # is operator cleanup, never evidence of a spontaneous product crash.
    save()
    if timed_out:
        stop = [*prefix, 'shell', 'am', 'force-stop', PACKAGE]
        report['timeoutCleanupCommand'] = stop
        try:
            cleanup = subprocess.run(stop, text=True, capture_output=True, timeout=8)
            (output_dir / 'timeout-cleanup.txt').write_text(cleanup.stdout + cleanup.stderr)
            report['timeoutCleanupExitCode'] = cleanup.returncode
        except (OSError, subprocess.TimeoutExpired) as error:
            report['timeoutCleanupError'] = type(error).__name__
        save()
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=os.environ.get('ADB', 'adb'))
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output-dir', required=True)
    # Fixed bounded contract: 90s test stages, 110s JUnit, 130s host. No infinite option.
    args = parser.parse_args()
    report = run(shlex.split(args.adb), args.serial, args.output_dir)
    print(json.dumps(report, indent=2))
    return 0 if report['deviceTestPassed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
