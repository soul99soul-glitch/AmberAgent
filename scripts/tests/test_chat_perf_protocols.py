import argparse
import contextlib
import importlib.util
import io
import json
import shlex
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from test_chat_history_entry_perf import module, FakeAdb
from test_summarize_chat_history_entry_perf import module as summary, manifest, metrics, write_run
from test_parse_atrace_frames import module as frames, BASE


class Clock:
    def __init__(self): self.now = 1000.0
    def time(self): return self.now
    def monotonic(self): return self.now
    def sleep(self, seconds): self.now += seconds


def main(argv):
    with patch.object(sys, 'argv', ['chat-history-entry-perf.py', argv[0], *(['--path-id', 'native-launcher-stress'] if argv[0] in ('start', 'collect') and '--path-id' not in argv else []), *argv[1:]]), contextlib.redirect_stdout(io.StringIO()):
        return module.main()


class WarmStateTest(unittest.TestCase):
    def test_real_main_handles_ime_chat_and_home_without_pid_change(self):
        class Surface(FakeAdb):
            state = 'ime'
            def __call__(self, serial, *args, check=True):
                result = super().__call__(serial, *args, check=check)
                cmd = list(args)
                if cmd[:3] == ['shell', 'dumpsys', 'input_method']:
                    result.stdout = 'mInputShown=' + ('true' if self.state == 'ime' else 'false')
                elif cmd[:3] == ['shell', 'input', 'keyevent']:
                    self.state = 'home'
                elif cmd[:3] == ['shell', 'am', 'start']:
                    self.state = 'launcher'
                elif cmd[:2] == ['shell', 'cat'] and self.state != 'launcher':
                    result.stdout = '<hierarchy><node text="production Home" /></hierarchy>'
                return result
        fake = Surface(trace_enabled=False)
        with tempfile.TemporaryDirectory() as directory, patch.object(module, 'adb', fake), patch.object(module, 'time', Clock()):
            capture = Path(directory) / 'capture.json'
            self.assertEqual(0, main(['start', '--serial', 'mock', '--scenario', 'S0', '--run-id', 'warm-state',
                                     '--warm', '--trace-off', '--trace-seconds', '0', '--capture-log', str(capture)]))
            result = json.loads(capture.read_text())
            self.assertEqual(['dismiss-ime', 'reorder-launcher', 'launcher-visible'], result['warmTransitions'])
            self.assertEqual(result['pidBefore'], result['pidMeasured'])
            self.assertEqual(1, sum('KEYCODE_BACK' in call for call in fake.calls))
            self.assertFalse(any('force-stop' in call or 'clear' in call for call in fake.calls))

    def test_home_never_turns_into_launcher_is_bounded_failure_with_evidence(self):
        class Home(FakeAdb):
            def __call__(self, serial, *args, check=True):
                result = super().__call__(serial, *args, check=check)
                if list(args)[:2] == ['shell', 'cat']:
                    result.stdout = '<hierarchy><node text="Home" /></hierarchy>'
                return result
        with tempfile.TemporaryDirectory() as directory, patch.object(module, 'adb', Home()), patch.object(module, 'time', Clock()):
            capture = Path(directory) / 'failed.json'
            with self.assertRaises(SystemExit):
                main(['start', '--serial', 'mock', '--scenario', 'S0', '--run-id', 'warm-fail', '--warm',
                      '--trace-off', '--trace-seconds', '0', '--launcher-timeout', '1', '--capture-log', str(capture)])
            result = json.loads(capture.read_text())
            self.assertEqual('invalid', result['status'])
            self.assertIn('visibly enabled', result['reason'])
            self.assertTrue(capture.with_suffix('.logcat.txt').is_file())


class SerializationTest(unittest.TestCase):
    def test_structured_adb_shell_preserves_empty_spaces_quotes_and_metacharacters(self):
        observed = []
        def run(command, **kwargs):
            # Model the actual adb remote shell boundary: adb joins arguments,
            # then the device shell parses them once before am receives argv.
            shell = command[command.index('shell') + 1:]
            observed.append(shlex.split(' '.join(shell)))
            return subprocess.CompletedProcess(command, 0, stdout='', stderr='')
        with patch.object(module.subprocess, 'run', run):
            module.adb('mock', 'shell', 'am', 'broadcast', '--es', 'runId', '', '--es', 'nonce', 'n 1',
                       '--es', 'text', "quotes' $(do_not_run) `literal`")
        self.assertEqual(['am', 'broadcast', '--es', 'runId', '', '--es', 'nonce', 'n 1',
                          '--es', 'text', "quotes' $(do_not_run) `literal`"], observed[0])

    def test_device_receiver_golden_is_exact_structured_serialization(self):
        observed = []
        def process(command, **kwargs):
            observed.append(command[-1])
            return subprocess.CompletedProcess(command, 0, stdout='', stderr='')
        with patch.object(module.subprocess, 'run', process):
            module.broadcast('mock', module.ACTION_SNAPSHOT, '--es', 'nonce', '0123456789abcdef')
        golden = Path(__file__).resolve().parents[2] / 'app/src/androidTestPerffixture/assets/s8-snapshot-command.txt'
        self.assertEqual(golden.read_text().strip(), observed[0])

    def test_s8_main_serializes_nonce_to_receiver_and_retains_failure_steps(self):
        commands = []
        logs = []
        original = module.adb
        def process(command, **kwargs):
            args = command[command.index('shell') + 1:] if 'shell' in command else []
            device_argv = shlex.split(' '.join(args)) if args else []
            out = ''
            if device_argv[:2] == ['am', 'broadcast']:
                commands.append(device_argv)
                extras = device_argv[device_argv.index('-n') + 2:]
                received = {extras[i + 1]: extras[i + 2] for i in range(0, len(extras), 3)}
                # Same nonce/owner fields as PerfFixtureInteractionReceiver;
                # actual receiver shell integration is in the test APK.
                logs.append('snapshot nonce=' + received.get('nonce', '') +
                            ' runId=none conversation=WRONG nodes=1 loading=false')
            elif command[-2:] == ['AmberPerfFixture:I', 'AmberPerf:I']:
                out = '\n'.join(logs)
            elif device_argv[:1] == ['cat']:
                out = '<hierarchy><node text="S8 tail" /></hierarchy>'
            return subprocess.CompletedProcess(command, 0, stdout=out, stderr='')
        with tempfile.TemporaryDirectory() as directory, patch.object(module.subprocess, 'run', process), patch.object(module, 'time', Clock()):
            output = Path(directory) / 's8.json'
            with self.assertRaises(SystemExit):
                main(['s8-sequence', '--serial', 'mock', '--output', str(output)])
            result = json.loads(output.read_text())
            self.assertEqual('invalid', result['status'])
            request = next(s for s in result['steps'] if s['step'] == 'initial-owner-request')
            nonce = request['nonce']
            self.assertEqual(nonce, commands[0][-1])
            self.assertNotIn('runId', commands[0])
            self.assertIn('nonce=' + nonce, result['reason'])
            self.assertFalse(any(s['step'] == 'process-death-task-restore' for s in result['steps']))
            self.assertTrue(output.with_suffix('.logcat.txt').is_file())

    def test_complete_s8_main_keeps_nonce_owner_and_process_death_evidence(self):
        logs = []
        commands = []
        state = {'pid': '111', 'owner': '10000000-0000-4000-8000-000000000008'}
        def process(command, **kwargs):
            device = shlex.split(command[-1]) if 'shell' in command else []
            commands.append(device)
            out = ''
            if device[:2] == ['am', 'start']:
                if 'conversationId' in device:
                    state['owner'] = device[device.index('conversationId') + 1]
                elif '--activity-reorder-to-front' in device:
                    state['pid'] = '222'
                    logs.append((Path(__file__).parent / 'fixtures/s8-route-created-restored-r9.txt').read_text().strip().replace('29702', '222'))
            elif device[:2] == ['am', 'kill']:
                state['pid'] = ''
            elif device[:1] == ['pidof']:
                out = state['pid']
            elif device[:2] == ['am', 'broadcast']:
                nonce = device[device.index('nonce') + 1]
                logs.append(f"snapshot nonce={nonce} runId=none conversation={state['owner']} nodes=80 loading=false")
            elif device[:1] == ['cat']:
                text = 'S8 tail' if state['owner'].endswith('008') else 'S2 fixture'
                out = f'<hierarchy><node text="{text}" /></hierarchy>'
            elif 'logcat' in command:
                out = '\n'.join(logs)
            return subprocess.CompletedProcess(command, 0, stdout=out, stderr='')
        with tempfile.TemporaryDirectory() as directory, patch.object(module.subprocess, 'run', process), patch.object(module, 'time', Clock()):
            output = Path(directory) / 'complete-s8.json'
            self.assertEqual(0, main(['s8-sequence', '--serial', 'mock', '--output', str(output)]))
            evidence = json.loads(output.read_text())
            self.assertEqual('ok', evidence['status'])
            snapshots = [step for step in evidence['steps'] if step.get('snapshot')]
            self.assertEqual(4, len(snapshots))
            self.assertEqual(4, len({step['nonce'] for step in snapshots}))
            for step in snapshots:
                self.assertIn('nonce=' + step['nonce'], step['snapshot'])
                self.assertIn('conversation=' + step['expectedConversation'], step['snapshot'])
            restore = next(step for step in evidence['steps'] if step['step'] == 'process-death-task-restore')
            self.assertTrue(restore['restored'])
            self.assertEqual('111', restore['pidBefore'])
            self.assertIsNone(restore['pidKilled'])
            self.assertEqual('222', restore['pidRestored'])
            self.assertIn('route-created savedState=true', restore['routeProbe'])
            self.assertFalse(any('force-stop' in command for command in commands))


class FailureCaptureTest(unittest.TestCase):
    def test_invalid_final_ack_saves_capture_and_keeps_nonzero_main_exit(self):
        class InvalidFinal(FakeAdb):
            def __call__(self, serial, *args, check=True):
                result = super().__call__(serial, *args, check=check)
                self.logs = [line.replace('status=final', 'status=invalid') for line in self.logs]
                return result
        with tempfile.TemporaryDirectory() as directory, patch.object(module, 'adb', InvalidFinal()), patch.object(module, 'time', Clock()):
            output = Path(directory) / 's4.json'
            with self.assertRaises(SystemExit) as error:
                main(['start', '--serial', 'mock', '--scenario', 'S4', '--run-id', 's4-invalid', '--cold',
                      '--trace-on', '--trace-seconds', '0', '--capture-log', str(output)])
            self.assertNotEqual(0, error.exception.code)
            result = json.loads(output.read_text())
            self.assertEqual('invalid', result['status'])
            self.assertEqual('finalization', result['stage'])
            self.assertTrue(output.with_suffix('.logcat.txt').is_file())

    def test_s7_auxiliary_send_happens_after_fixed_window_and_timeout_is_retained(self):
        clock = Clock()
        class Streaming(FakeAdb):
            send_time = None
            tap_time = None
            def __call__(self, serial, *args, check=True):
                if module.ACTION_SEND in args:
                    self.send_time = clock.now
                    self.logs.append(f's7-send-accepted runId={self.launcher_run} accepted=true')
                if list(args[:5]) == ['shell', 'input', 'tap', '20', '20']: self.tap_time = clock.now
                if args[0] == 'shell' and args[1].startswith('nohup atrace'):
                    return subprocess.CompletedProcess(args, 0, stdout='999', stderr='')
                if args[0] == 'pull' and args[1].endswith('.atrace'):
                    Path(args[2]).write_text('partial failed-run atrace evidence')
                    return subprocess.CompletedProcess(args, 0, stdout='', stderr='')
                return super().__call__(serial, *args, check=check)
        fake = Streaming()
        with tempfile.TemporaryDirectory() as directory, patch.object(module, 'adb', fake), patch.object(module, 'time', clock):
            output = Path(directory) / 's7.json'
            with self.assertRaises(SystemExit):
                main(['start', '--serial', 'mock', '--scenario', 'S7', '--run-id', 's7-timeout', '--cold',
                      '--trace-seconds', '3', '--trace-output', str(Path(directory) / 'failed.atrace'),
                      '--s7-timeout', '2', '--capture-log', str(output)])
            self.assertGreaterEqual(fake.send_time - fake.tap_time, 1.5)
            result = json.loads(output.read_text())
            self.assertEqual('s7-stream-validation', result['stage'])
            self.assertEqual('invalid', result['status'])
            self.assertTrue(Path(result['tracePath']).is_file())
            self.assertFalse(result['traceComplete'])


class DeadlineTest(unittest.TestCase):
    def test_unknown_deadline_reports_duration_without_overbudget(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            m = manifest()
            m['frames'].update(deadlineMs=None, deadlineStatus='unknown', deadlineEvidence=None)
            write_run(root, 'r1', m, metrics())
            result = summary.summarize(root, 1, None, None)
            self.assertEqual('ok', result['status'])
            self.assertEqual(18, result['frames']['max'])
            self.assertNotIn('overBudget', result['frames'])

    def test_parser_main_requires_deadline_choice_and_known_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            trace = Path(directory) / 'trace'
            trace.write_text('\n'.join(BASE))
            output = Path(directory) / 'frames.json'
            args = ['parser', '--path-id', 'native-launcher-stress', '--trace', str(trace), '--pid', '100', '--run-id', 'r1',
                    '--anchor-monotonic-ns', '1000010000000', '--output', str(output)]
            for extra in ([], ['--refresh-hz', '120']):
                with patch.object(sys, 'argv', args + extra), self.assertRaises(SystemExit): frames.main()
            with patch.object(sys, 'argv', args + ['--deadline-unknown']): frames.main()
            self.assertIsNone(json.loads(output.read_text())['deadlineMs'])
            evidence = Path(directory) / 'display.txt'
            evidence.write_text('target display 0 120Hz; mirror display 3 60Hz')
            with patch.object(sys, 'argv', args + ['--refresh-hz', '120', '--display-id', '0', '--deadline-evidence', str(evidence)]): frames.main()
            self.assertAlmostEqual(1000 / 120, json.loads(output.read_text())['deadlineMs'])

    def test_trace_off_external_id_does_not_require_run_marker(self):
        with tempfile.TemporaryDirectory() as directory:
            trace = Path(directory) / 'off.trace'
            trace.write_text('\n'.join(line for line in BASE if 'AmberPerf.Run.' not in line))
            output = Path(directory) / 'off.json'
            args = ['parser', '--path-id', 'native-launcher-stress', '--trace', str(trace), '--pid', '100', '--external-run-id', 'off-1',
                    '--anchor-monotonic-ns', '1000010000000', '--deadline-unknown', '--output', str(output)]
            with patch.object(sys, 'argv', args): frames.main()
            payload = json.loads(output.read_text())
            self.assertEqual('off-1', payload['runId'])
            self.assertEqual('external', payload['runBinding'])

class CollectMainTest(unittest.TestCase):
    def test_unknown_budget_survives_actual_collect_main_and_invalid_capture_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            apk = root / 'app.apk'; apk.write_bytes(b'fixture-apk')
            aapt = root / 'aapt'; aapt.touch()
            trace = root / 'trace.atrace'; trace.write_text('raw trace evidence')
            source = root / 'source.json'
            source.write_text(json.dumps({'head': '1' * 40, 'trackedDiffSha256': 'a' * 64, 'untrackedFiles': {}, 'hostFiles': {n: module.sha256(Path(module.__file__).with_name(n)) for n in ['chat-history-entry-perf.py', 'parse-atrace-frames.py', 'chat_perf_contract.py']}, 'build': {'apks': {'mock': {'sha256': module.sha256(apk)}}}}))
            frame = root / 'frames.json'
            frame.write_text(json.dumps({'runId': 'r1', 'pathId': 'native-launcher-stress', 'parserVersion': module.PARSER_VERSION, 'parserSourceSha256': module.sha256(Path(module.__file__).with_name('parse-atrace-frames.py')), 'pid': 100, 'deadlineStatus': 'unknown',
                                        'deadlineMs': None, 'valuesMs': [100.0], 'windowMs': 1500.0}))
            capture = root / 'capture.json'
            capture_data = {'pathId': 'native-launcher-stress', 'hostVersion': module.HOST_VERSION, 'environmentPre': {}, 'environmentPost': {}, 'runId': 'r1', 'scenario': 'S1', 'package': module.PACKAGE, 'phase': 'cold',
                            'status': 'ok', 'pidMeasured': '100', 'tracePath': str(trace), 'traceSha256': module.sha256(trace)}
            capture.write_text(json.dumps(capture_data))
            def adb(serial, *args, **kwargs):
                if args[0] == 'pull':
                    path = Path(args[-1]); path.mkdir()
                    (path / 'metrics.json').write_text(json.dumps({**metrics(), 'pathId': 'native-launcher-stress'}))
                    # Real collector's required name pattern.
                    (path / 'metrics.json').rename(path / 'metrics-S1.json')
                return subprocess.CompletedProcess(args, 0, stdout='', stderr='')
            info = {'package': module.PACKAGE, 'debuggable': False, 'profileable': True, 'traceEnabled': True,
                    'application': 'app.amber.agent.perffixture.PerfFixtureApplication', 'abi': ['arm64-v8a'], 'baselineProfile': True}
            argv = ['collect', '--serial', 'mock', '--output', str(root / 'result'), '--run-id', 'r1', '--scenario', 'S1',
                    '--phase', 'cold', '--apk', str(apk), '--aapt2', str(aapt), '--source-manifest', str(source),
                    '--frames-json', str(frame), '--capture-log', str(capture), '--fixture-version', 'r9', '--trace-on']
            with patch.object(module, 'adb', adb), patch.object(module, 'apk_info', return_value=info), \
                 patch.object(module, 'installed_apk_hash', return_value=module.sha256(apk)), \
                 patch.object(module, 'apk_signer_sha256', return_value='f' * 64), \
                 patch.object(module, 'device_identity', return_value={'serial': 'mock', 'model': 'fixture', 'sdk': '37'}):
                self.assertEqual(0, main(argv))
                result = json.loads((root / 'result/run-manifest.json').read_text())
                self.assertIsNone(result['frames']['deadlineMs'])
                self.assertEqual('unknown', result['frames']['deadlineStatus'])
                capture_data['status'] = 'invalid'
                capture.write_text(json.dumps(capture_data))
                argv[argv.index('--output') + 1] = str(root / 'invalid-result')
                with self.assertRaises(SystemExit) as error: main(argv)
                self.assertIn('capture is not successful', str(error.exception))
