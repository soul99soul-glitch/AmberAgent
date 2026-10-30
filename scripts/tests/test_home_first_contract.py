import json
import tempfile
import unittest
import subprocess
from pathlib import Path
from unittest.mock import patch
from test_chat_history_entry_perf import FakeAdb, module
from test_chat_perf_protocols import main, Clock
from test_summarize_chat_history_entry_perf import module as summary, manifest, metrics, write_run
from test_parse_atrace_frames import module as frames, line
from chat_perf_contract import environment_gate, environment_snapshot, parse_battery, PARSER_VERSION

HOME = 'home-first-first-history'
NATIVE = 'native-launcher-stress'

class HomeDevice(FakeAdb):
    def __init__(self, trace=True):
        super().__init__(trace_enabled=trace)
        self.home = False; self.visited = False; self.activity = 'none'; self.armed = False
        self.clock = Clock(); self.entry_at = None; self.auxiliary_at = None
    def __call__(self, serial, *args, check=True):
        result = super().__call__(serial, *args, check=check)
        cmd = list(args)
        if cmd[:3] == ['shell', 'am', 'start'] and 'perfPathId' in cmd:
            self.home = True; self.visited = False; self.activity = self.pid + '-1'
        elif cmd[:3] == ['shell', 'input', 'keyevent'] and 'KEYCODE_BACK' in cmd:
            self.home = True
        elif cmd[:3] == ['shell', 'am', 'broadcast']:
            action = cmd[cmd.index('-a') + 1]
            rest = cmd[cmd.index('-n') + 2:]; extras = {rest[i+1]:rest[i+2] for i in range(0,len(rest),3)}
            if action == module.ACTION_HOME_STATE:
                data = {'pathId': HOME, 'pid': int(self.pid), 'activityId': self.activity,
                        'home': self.home, 'topRoute': 'Home' if self.home else 'Chat', 'topConversation': None if self.home else module.fixture_conversation('S1'), 'rowVisible': self.home, 'focused': True, 'chatVisited': self.visited, 'targetVisited': self.visited,
                        'lastEntryActivity': self.activity if self.visited else None,
                        'lastEntryConversation': module.fixture_conversation('S1') if self.visited else None,
                        'thermalStatus': 0, 'displayId': 0, 'appConfiguration': {'fontScale':1.0}}
                self.logs.append('home-state nonce=' + extras['nonce'] + ' stateJson=' + json.dumps(data))
            if action == module.ACTION_HOME_ARM:
                self.launcher_run = extras['runId']; self.launcher_scenario = extras['scenario']; self.armed = True
                self.logs.append(f'home-armed runId={self.launcher_run} accepted=true reason=ok')
        elif cmd[:2] == ['shell', 'cat'] and self.home:
            result.stdout = '<hierarchy><node content-desc="perf_home_row_' + module.fixture_conversation('S1') + '" enabled="true" clickable="true" bounds="[10,10][30,30]" /></hierarchy>'
        if cmd[:5] == ['shell','input','tap','20','20'] and self.armed:
            # Device input delivery is delayed; host/device epochs are unrelated.
            self.clock.sleep(.08)
            self.entry_at = self.clock.now
            self.logs.append(f'home-entry runId={self.launcher_run} pathId={HOME} scenario=S1 phase=' + ('warm' if self.visited else 'cold') +
                             f' activityId={self.activity} pid={self.pid} t0MonoNs={self.t0} priorChatVisited=' + ('true' if self.visited else 'false') +
                             f' current={self.launcher_run if self.trace_enabled else "null"}')
            self.home = False; self.visited = True; self.armed = False
        if module.ACTION_INTERACTION in cmd: self.auxiliary_at = self.clock.now
        return result

class HomeFlowTest(unittest.TestCase):
    def test_real_main_cold_warm_on_off_share_activity_and_delayed_device_t0_guard(self):
        for trace in (True, False):
            device = HomeDevice(trace)
            with tempfile.TemporaryDirectory() as d, patch.object(module, 'adb', device), patch.object(module, 'time', device.clock):
                results=[]
                for phase in ('cold','warm'):
                    capture=Path(d)/(phase+'.json')
                    self.assertEqual(0, main(['start','--path-id',HOME,'--serial','mock','--scenario','S1','--run-id','home-'+phase,
                                             '--'+phase,'--trace-on' if trace else '--trace-off','--trace-seconds','0','--capture-log',str(capture)]))
                    data=json.loads(capture.read_text());results.append(data)
                    self.assertGreaterEqual(device.auxiliary_at-device.entry_at,1.5)
                    self.assertEqual(500,data['homeReady']['quietMs'])
                    self.assertIn('environmentPre',data);self.assertIn('environmentPost',data)
                self.assertEqual(results[0]['homeBefore']['activityId'],results[1]['homeBefore']['activityId'])
                self.assertEqual(results[0]['pidMeasured'],results[1]['pidMeasured'])
                self.assertFalse(results[0]['homeBefore']['chatVisited'])
                self.assertTrue(results[1]['homeBefore']['chatVisited'])
                launches=[c for c in device.calls if list(c[:3])==['shell','am','start']]
                self.assertEqual(1,len(launches));self.assertNotIn(module.LAUNCH_ACTIVITY,launches[0])
    def test_actual_semantics_locator_rejects_title_only_duplicate_and_disabled(self):
        target='perf_home_row_'+module.fixture_conversation('S1')
        good=f'<hierarchy><node content-desc="{target}" enabled="true" bounds="[10,10][30,30]" /></hierarchy>'
        self.assertEqual((20,20),module.home_row_bounds(good,'S1'))
        for xml in [good.replace('true','false'),'<hierarchy><node text="Perf S1" /></hierarchy>',good.replace('</hierarchy>',good[11:])]:
            with self.assertRaises(SystemExit):module.home_row_bounds(xml,'S1')

    def test_actual_post_failures_preserve_snapshot_and_distinguish_stale_home_from_pid_change(self):
        for failure in ('stale-home', 'changed-pid'):
            class InvalidPost(HomeDevice):
                def __call__(self, serial, *args, **kwargs):
                    result = super().__call__(serial, *args, **kwargs)
                    if module.ACTION_HOME_STATE in args and self.visited and not self.home:
                        prefix, payload = self.logs[-1].split(' stateJson=', 1)
                        state = json.loads(payload)
                        if failure == 'stale-home': state['home'] = True
                        else: state['pid'] += 1
                        self.logs[-1] = prefix + ' stateJson=' + json.dumps(state)
                    return result
            device = InvalidPost()
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as d, patch.object(module, 'adb', device), patch.object(module, 'time', device.clock):
                path = Path(d)/'failed.json'
                with self.assertRaises(SystemExit) as caught:
                    main(['start','--path-id',HOME,'--serial','mock','--scenario','S1','--run-id','post-'+failure,'--cold',
                          '--trace-on','--trace-seconds','0','--capture-log',str(path)])
                capture = json.loads(path.read_text())
                self.assertEqual('invalid',capture['status'])
                self.assertIn('homeAfter',capture)
                fields = [m['field'] for m in capture['homePostMismatches']]
                self.assertEqual(['home'] if failure == 'stale-home' else ['pid'],fields)
                self.assertIn(fields[0] + ' expected=', str(caught.exception))
                self.assertEqual(capture['homeBefore']['activityId'],capture['homeAfter']['activityId'])
                self.assertTrue(path.with_suffix('.logcat.txt').is_file())


    def test_actual_a1_post_snapshot_is_stale_home_not_changed_identity(self):
        golden=json.loads((Path(__file__).parent/'fixtures/home-post-stale-a1.json').read_text())
        differences=module.home_post_mismatches(golden['before'],golden['after'],golden['measuredPid'],golden['conversation'])
        fields=[m['field'] for m in differences]
        self.assertIn('home',fields)
        self.assertNotIn('pid',fields)
        self.assertNotIn('activityId',fields)
        # A1 did not expose these new A2 fields. They remain unknown, not faked.
        self.assertIn('topRoute',fields)
        self.assertIn('topConversation',fields)


class EnvironmentTest(unittest.TestCase):
    def test_unknown_is_not_zero_or_formally_qualified_and_thresholds_are_inclusive(self):
        self.assertEqual('unknown', environment_gate(parse_battery(''),None)['status'])
        self.assertEqual('pause', environment_gate({'temperature':420},0)['status'])
        self.assertEqual('pause', environment_gate({'temperature':419},2)['status'])
        self.assertEqual('observed-ok', environment_gate({'temperature':419},1)['status'])
        self.assertFalse(environment_gate({'temperature':300},0)['formalEnvironmentComplete'])
    def test_actual_main_pre_gate_pauses_and_persists_failure_before_entry(self):
        device=HomeDevice()
        original=device.__call__
        def adb(serial,*args,**kwargs):
            if args[:3]==('shell','dumpsys','battery'):
                return subprocess.CompletedProcess(args,0,stdout='  temperature: 420\n  level: 50\n  USB powered: true\n',stderr='')
            return original(serial,*args,**kwargs)
        with tempfile.TemporaryDirectory() as d, patch.object(module,'adb',adb),patch.object(module,'time',device.clock):
            capture=Path(d)/'hot.json'
            with self.assertRaises(SystemExit):main(['start','--path-id',HOME,'--serial','mock','--scenario','S1','--run-id','hot','--cold','--trace-seconds','0','--capture-log',str(capture)])
            data=json.loads(capture.read_text());self.assertEqual('invalid',data['status']);self.assertEqual('pause',data['environmentPre']['gate']['status'])
            self.assertIsNone(device.entry_at)

class OuterFrameTest(unittest.TestCase):
    def test_nested_real_resync_shape_is_preserved_but_not_double_counted(self):
        # Contract golden reconstructed from historical interval shape; not an old device capture.
        events=[line('app',100,100,1000.0,'B|100|AmberPerf.EntryRequested'),line('app',100,100,1000.0001,'E'),
                line('app',100,100,1000.01,'B|100|Choreographer#doFrame 211745978'),
                line('app',100,100,1000.011,'B|100|Choreographer#doFrame - resynced'),
                line('app',100,100,1000.04,'E'),line('app',100,100,1000.041,'E')]
        with tempfile.TemporaryDirectory() as d:
            trace=Path(d)/'nested.trace';trace.write_text('\n'.join(events))
            result=frames.extract(trace,100,0,1500,anchor_marker='AmberPerf.EntryRequested')
            self.assertEqual([31.0],result['valuesMs']);self.assertEqual(2,len(result['rawFrames']));self.assertEqual(1,len(result['nestedFrames']))
            self.assertEqual(PARSER_VERSION,result['parserVersion'])
    def test_summary_rejects_mixed_path_and_old_semantics(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);a=manifest('a');b=manifest('b');b['pathId']=HOME
            write_run(root,'a',a,metrics('a'));write_run(root,'b',b,{**metrics('b'), 'pathId': HOME})
            self.assertIn('mixed pathId',summary.summarize(root,1,None,None)['reason'])
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);a=manifest('a');a.pop('parserVersion');write_run(root,'a',a,metrics('a'))
            self.assertEqual('invalid',summary.summarize(root,1,None,None)['status'])

    def test_partial_overlap_is_diagnosed_instead_of_aggregated(self):
        events=[{'tid':100,'kind':k,'name':name,'ts':t,'line':i} for i,(k,name,t) in enumerate([
            ('B','AmberPerf.EntryRequested',1000.0),('E','',1000.001),
            ('B','Choreographer#doFrame',1000.01),('E','',1000.05),
            ('B','Choreographer#doFrame',1000.03),('E','',1000.06)])]
        with patch.object(frames,'_events',return_value=events):
            with self.assertRaises(frames.TraceError):frames.extract(Path('not-read'),100,0,1500,anchor_marker='AmberPerf.EntryRequested')
