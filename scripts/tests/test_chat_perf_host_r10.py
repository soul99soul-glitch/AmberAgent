import contextlib
import io
import json
import re
import shlex
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from test_chat_perf_protocols import Clock, main
from test_chat_history_entry_perf import module, FakeAdb


class IndependentClocks(Clock):
    # Host wall, host monotonic and Android elapsedRealtime have unrelated epochs.
    def time(self): return 1_792_900_000.0 + (self.now - 1000) * 1.01
    def device_ns(self): return int((239_827 + self.now - 1000) * 1_000_000_000)


class PostAckWindowTest(unittest.TestCase):
    def test_real_main_never_sends_before_device_window_with_input_and_log_delays(self):
        for scenario, mode in [('S7', 'on'), ('S1', 'on'), ('S1', 'off'), ('S0', 'on')]:
            for input_delay, log_delay in [(0.004, 0.0), (0.3, 0.07), (1.1, 0.8)]:
                with self.subTest(scenario=scenario, mode=mode, input=input_delay, log=log_delay):
                    clock = IndependentClocks()
                    class Device(FakeAdb):
                        device_t0 = None
                        ack_received = None
                        send_age_ms = None
                        auxiliary_ages = []
                        def __call__(self, serial, *args, check=True):
                            is_entry = list(args[:5]) == ['shell', 'input', 'tap', '20', '20']
                            if is_entry:
                                clock.sleep(input_delay)
                                self.device_t0 = clock.device_ns()
                            if module.ACTION_SEND in args:
                                self.send_age_ms = (clock.device_ns() - self.device_t0) / 1e6
                                if self.send_age_ms < 1500:
                                    self.logs.append(f's7-send-phase-rejected runId={self.launcher_run}')
                                else:
                                    self.logs += [f'{event} runId={self.launcher_run}{suffix}' for event, suffix in
                                                  [('s7-send-accepted', ' accepted=true'), ('s7-first-chunk', ''), ('s7-settled', '')]]
                            if self.device_t0 is not None and args[:2] == ('shell', 'uiautomator'):
                                self.auxiliary_ages.append((clock.device_ns() - self.device_t0) / 1e6)
                            if args[:2] == ('logcat', '-d') and self.device_t0 is not None and self.ack_received is None:
                                clock.sleep(log_delay)
                                self.ack_received = clock.monotonic()
                            result = super().__call__(serial, *args, check=check)
                            if is_entry:
                                self.logs[-2 if self.trace_enabled else -1] = re.sub(
                                    r't0MonoNs=\d+', f't0MonoNs={self.device_t0}', self.logs[-2 if self.trace_enabled else -1])
                            if args[:2] == ('shell', 'cat'):
                                result.stdout = result.stdout.replace('</hierarchy>', '<node text="S7 scripted" /></hierarchy>')
                            return result
                    fake = Device(trace_enabled=mode == 'on')
                    with tempfile.TemporaryDirectory() as directory, patch.object(module, 'adb', fake), patch.object(module, 'time', clock):
                        capture = Path(directory) / 'capture.json'
                        self.assertEqual(0, main(['start', '--serial', 'mock', '--scenario', scenario, '--run-id', 'delayed-entry',
                                                 '--cold', '--trace-' + mode, '--trace-seconds', '0', '--capture-log', str(capture)]))
                        result = json.loads(capture.read_text())
                        self.assertEqual(fake.device_t0, result['entryMonoNs'])
                        self.assertGreaterEqual(result['windowGuard']['waitedMs'], 1500)
                        self.assertTrue(all(age >= 1500 for age in fake.auxiliary_ages))
                        if scenario == 'S7': self.assertGreaterEqual(fake.send_age_ms, 1500)
                        self.assertFalse(any('phase-rejected' in line for line in fake.logs))

    def test_phase_or_composer_rejection_fails_immediately_not_after_settled_timeout(self):
        for marker in ['s7-send-phase-rejected', 'send-rejected', 's7-send-accepted']:
            clock = Clock()
            class Rejected(FakeAdb):
                sent_at = None
                def __call__(self, serial, *args, check=True):
                    result = super().__call__(serial, *args, check=check)
                    if module.ACTION_SEND in args:
                        self.sent_at = clock.now
                        self.logs.append(f'{marker} runId={self.launcher_run}' + (' accepted=false' if marker == 's7-send-accepted' else ''))
                    return result
            fake = Rejected()
            with tempfile.TemporaryDirectory() as directory, patch.object(module, 'adb', fake), patch.object(module, 'time', clock):
                capture = Path(directory) / 'failure.json'
                with self.assertRaises(SystemExit) as error:
                    main(['start', '--serial', 'mock', '--scenario', 'S7', '--run-id', 'rejected-send-', '--cold',
                          '--trace-seconds', '0', '--s7-timeout', '20', '--capture-log', str(capture)])
                self.assertIn(marker, str(error.exception))
                self.assertLess(clock.now - fake.sent_at, 0.25)
                self.assertEqual('invalid', json.loads(capture.read_text())['status'])


class RestoreProbeTest(unittest.TestCase):
    def test_real_golden_requires_correct_new_pid_and_current_step_boundary(self):
        golden = (Path(__file__).parent / 'fixtures/s8-route-created-restored-r9.txt').read_text()
        self.assertEqual(golden.strip(), module.restored_route_line(golden, '29702', ''))
        self.assertIsNone(module.restored_route_line(golden, '29501', ''))
        self.assertIsNone(module.restored_route_line(golden, '29702', golden))
        self.assertIsNone(module.restored_route_line(golden.replace('true', 'false'), '29702', ''))
        with self.assertRaises(SystemExit): module.restored_route_line(golden, '29702', 'lost prefix')

    def test_complete_command_flow_rejects_historical_probe_old_pid_and_missing_current_probe(self):
        golden = (Path(__file__).parent / 'fixtures/s8-route-created-restored-r9.txt').read_text().strip()
        for mode in ['history-only', 'old-pid', 'false-state']:
            clock = Clock()
            logs = [golden]
            state = {'pid': '29501', 'owner': '10000000-0000-4000-8000-000000000008'}
            def process(command, **kwargs):
                device = shlex.split(command[-1]) if 'shell' in command else []
                out = ''
                if device[:2] == ['am', 'start'] and '--activity-reorder-to-front' in device:
                    state['pid'] = '29501' if mode == 'old-pid' else '29702'
                    if mode == 'old-pid': logs.append(golden.replace('29702', '29501'))
                    if mode == 'false-state': logs.append(golden.replace('true', 'false'))
                elif device[:2] == ['am', 'kill']: state['pid'] = ''
                elif device[:1] == ['pidof']: out = state['pid']
                elif device[:2] == ['am', 'broadcast']:
                    nonce = device[device.index('nonce') + 1]
                    logs.append(f"snapshot nonce={nonce} runId=none conversation={state['owner']} nodes=80 loading=false")
                elif device[:1] == ['cat']: out = '<hierarchy><node text="S8 tail" /></hierarchy>'
                elif 'logcat' in command: out = '\n'.join(logs) + '\n'
                return subprocess.CompletedProcess(command, 0, stdout=out, stderr='')
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as directory, patch.object(module.subprocess, 'run', process), patch.object(module, 'time', clock):
                output = Path(directory) / 's8.json'
                with self.assertRaises(SystemExit): main(['s8-sequence', '--serial', 'mock', '--output', str(output)])
                result = json.loads(output.read_text())
                self.assertEqual('invalid', result['status'])
                restore = next(step for step in result['steps'] if step['step'] == 'process-death-task-restore')
                self.assertFalse(restore['restored'])
                self.assertFalse(any(step['step'] == 'restored-owner' for step in result['steps']))


class Bash32Test(unittest.TestCase):
    def test_actual_system_bash_runs_delivery_sample_with_empty_and_populated_optional_arrays(self):
        script = (Path(__file__).resolve().parents[1] / 'chat-history-entry-device.sh').read_text()
        # Execute the exact production sample() body, replacing external commands
        # with local recording stubs. This invokes neither adb nor the device.
        body = script[script.index('sample() {'):script.index('\nprepare() {')]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stub = root / 'python3'
            stub.write_text('''#!/usr/bin/python3
import json,sys,os
with open(os.environ['ARGV_LOG'],'a') as f: f.write(json.dumps(sys.argv[1:])+"\\n")
if sys.argv[1]=='-':
    code=sys.stdin.read()
    print('123 239827149008057' if "pidMeasured" in code else '2500.0')
''')
            stub.chmod(0o755)
            log = root / 'argv.jsonl'
            shell = 'set -euo pipefail\n' + f'export PATH={shlex.quote(directory)}:"$PATH"\n' + f'export ARGV_LOG={shlex.quote(str(log))}\n'
            shell += f'PREFIX=test\nOUT={shlex.quote(directory)}\nROOT={shlex.quote(directory)}\nSOURCE_MANIFEST=unused\nFIXTURE_VERSION=test-fixture\nSERIAL=mock\nADB_STR=unused\nAAPT2=unused\nAPKSIGNER=unused\n'
            shell += body + '\nsample S7 cold on on.apk\nsample S1 cold off off.apk\n'
            result = subprocess.run(['/bin/bash', '-c', shell], text=True, capture_output=True)
            self.assertEqual(0, result.returncode, result.stderr)
            calls = [json.loads(line) for line in log.read_text().splitlines()]
            start = [call for call in calls if 'start' in call]
            collect = [call for call in calls if 'collect' in call]
            self.assertIn('--external-readable-text', start[0])
            self.assertIn('--external-readable-text', start[1])
            self.assertIn('S1', start[1])
            self.assertNotIn('--external-readable-ms', collect[0])
            self.assertIn('--external-readable-ms', collect[1])
