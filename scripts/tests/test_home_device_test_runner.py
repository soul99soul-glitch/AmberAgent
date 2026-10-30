import os
import shutil
import importlib.util
import json
import shlex
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('home_runner', SCRIPTS / 'run-home-device-test.py')
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)
JDK = (Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME")
       else Path(shutil.which("javac") or "javac").parent)


class HomeDeviceRunnerTest(unittest.TestCase):
    def test_actual_stage_helper_bounds_noncooperative_call_and_preserves_stage_failure(self):
        source = SCRIPTS.parent / 'app/src/androidTestPerffixture/java/app/amber/agent/perffixture/HomeTestStages.java'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            harness = root / 'StagesHarness.java'
            harness.write_text('''package app.amber.agent.perffixture;
import java.util.*;
import java.util.concurrent.locks.LockSupport;
public class StagesHarness {
 public static void main(String[] args) {
  List<String> events = new ArrayList<>();
  try (HomeTestStages stages = new HomeTestStages(2000, (s,e) -> events.add(s+":"+e))) {
   if (!"ready".equals(stages.step("settings-ready", 200, () -> "ready"))) throw new AssertionError();
   try { stages.step("seed-io-readback", 200, () -> {throw new IllegalStateException("synthetic");}); throw new RuntimeException(); }
   catch (AssertionError e) { if (!(e.getCause() instanceof IllegalStateException) || !e.getMessage().contains("seed-io-readback")) throw e; }
   long start=System.nanoTime();
   try { stages.step("activity-launch", 30, () -> {while(true) {Thread.interrupted(); LockSupport.parkNanos(1000000);}}); throw new RuntimeException(); }
   catch (AssertionError e) { if (!e.getMessage().contains("activity-launch") || (System.nanoTime()-start)>1000000000L) throw e; }
   try { stages.step("activity-close", 30, () -> null); throw new RuntimeException(); }
   catch (AssertionError e) { if (!e.getMessage().contains("previous stage timed out")) throw e; }
  }
  if (!events.contains("activity-launch:timeout") || !events.contains("seed-io-readback:failed")) throw new AssertionError(events.toString());
  try (HomeTestStages stages = new HomeTestStages(30, (s,e) -> {})) {
   try { stages.step("overall", 5000, () -> {Thread.sleep(5000); return null;}); throw new RuntimeException(); }
   catch (AssertionError expected) { if (!expected.getMessage().contains("overall")) throw expected; }
  }
  System.out.println("bounded-stages-ok");
 }
}''')
            subprocess.run([str(JDK/'javac'), '-d', str(root), str(source), str(harness)], check=True, capture_output=True)
            result = subprocess.run([str(JDK/'java'), '-cp', str(root), 'app.amber.agent.perffixture.StagesHarness'],
                                    check=True, capture_output=True, text=True, timeout=5)
            self.assertIn('bounded-stages-ok', result.stdout)

    def test_actual_shell_launch_contract_and_lifecycle_matching_fail_closed(self):
        base = SCRIPTS.parent / 'app/src/androidTestPerffixture/java/app/amber/agent/perffixture'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            harness = root/'LaunchHarness.java'
            harness.write_text(r'''package app.amber.agent.perffixture;
import java.util.concurrent.*;
public class LaunchHarness {
 static void fails(Runnable action) {
  try {action.run(); throw new IllegalStateException("should fail");} catch (AssertionError expected) {}
 }
 public static void main(String[] args) throws Exception {
  String component=HomeLaunchContract.COMPONENT;
  String ok="Starting: Intent { cmp="+component+" }\nStatus: ok\nLaunchState: COLD\nActivity: "+component+"\nTotalTime: 200\nWaitTime: 201\nComplete\n";
  HomeLaunchContract.validateShellResult(ok, "");
  for (String bad:new String[]{"", "Status: ok\nComplete", ok.replace(component,"other/.Activity"), ok.replace("Complete",""), ok+"Warning: Activity not started, intent delivered", "Error: Activity not started, unknown error code 102"}) {
   fails(() -> HomeLaunchContract.validateShellResult(bad, ""));
  }
  fails(() -> HomeLaunchContract.validateShellResult(ok,"SecurityException: denied"));
  try {new HomeLaunchContract<Object>(22,"bad token; touch /tmp/no"); throw new AssertionError();} catch(IllegalArgumentException expected) {}
  Object activity=new Object(); Object recreated=new Object();
  HomeLaunchContract<Object> contract=new HomeLaunchContract<>(22,"test-token");
  if (!contract.command().equals("am start -W -n "+component+" --es perfPathId home-first-first-history --es homeTestToken test-token")) throw new AssertionError(contract.command());
  contract.created(activity,22,component,"old-token");
  contract.resumed(activity,22,"other/.Activity","test-token");
  try {contract.awaitResumed(20); throw new AssertionError();} catch(TimeoutException expected) {}
  Thread events=new Thread(() -> {contract.created(activity,22,component,"test-token");contract.resumed(activity,22,component,"test-token");});
  events.start();
  if (contract.awaitResumed(500)!=activity) throw new AssertionError();
  events.join();
  contract.paused(activity); fails(() -> contract.requireResumed());
  contract.resumed(activity,22,component,"test-token");
  if (contract.requireResumed()!=activity) throw new AssertionError();
  try {contract.awaitDestroyed(20); throw new AssertionError();} catch(TimeoutException expected) {}
  contract.destroyed(recreated); if(contract.requireResumed()!=activity) throw new AssertionError();
  contract.destroyed(activity);contract.awaitDestroyed(20);fails(() -> contract.requireResumed());
  HomeLaunchContract<Object> wrongPid=new HomeLaunchContract<>(22,"test-token");
  wrongPid.created(activity,23,component,"test-token"); fails(() -> {try {wrongPid.awaitResumed(1000);}catch(Exception e){throw new RuntimeException(e);}});
  HomeLaunchContract<Object> noCreated=new HomeLaunchContract<>(22,"test-token");
  noCreated.resumed(activity,22,component,"test-token");fails(() -> noCreated.requireResumed());
  HomeLaunchContract<Object> changed=new HomeLaunchContract<>(22,"test-token");
  changed.created(activity,22,component,"test-token");changed.resumed(activity,22,component,"test-token");
  changed.created(recreated,22,component,"test-token");fails(() -> changed.requireResumed());
  System.out.println("launch-lifecycle-contract-ok");
 }
}''')
            subprocess.run([str(JDK/'javac'), '-d', str(root), str(base/'HomeLaunchContract.java'), str(harness)],
                           check=True,capture_output=True)
            result=subprocess.run([str(JDK/'java'),'-cp',str(root),'app.amber.agent.perffixture.LaunchHarness'],
                                  check=True,capture_output=True,text=True,timeout=5)
            self.assertIn('launch-lifecycle-contract-ok',result.stdout)

    def test_real_host_process_flow_success_shell_zero_failure_missing_checkpoint_and_timeout(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fake = root / 'fake_adb.py'
            fake.write_text('''import sys,pathlib,time,json
root=pathlib.Path(sys.argv[1]); mode=sys.argv[2]; args=sys.argv[3:]
with (root/'calls.jsonl').open('a') as f:f.write(json.dumps(args)+'\\n')
if 'instrument' in args:
 token=args[args.index('homeTestToken')+1]; (root/'token').write_text(token)
 if mode=='timeout':
  print('test started',flush=True); time.sleep(20)
 elif mode=='shell-zero-fail':print('FAILURES!!!\\nINSTRUMENTATION_CODE: 0')
 else:print('OK (1 test)\\nINSTRUMENTATION_CODE: -1')
elif 'logcat' in args:
 token=(root/'token').read_text()
 print('token=old stage=test event=ok monoMs=1')
 print('token='+token+' stage=activity-launch event=begin monoMs=2')
 if mode=='ok':print('token='+token+' stage=test event=ok monoMs=3')
elif 'force-stop' in args:
 assert json.loads(next(root.glob('result-*/result.json')).read_text())['status']=='timeout'
''')
            for mode, expected in [('ok','ok'), ('shell-zero-fail','failed'), ('no-checkpoint','failed'), ('timeout','timeout')]:
                with self.subTest(mode=mode):
                    work=root/mode;work.mkdir()
                    out=work/'result-1'
                    result=runner.run([sys.executable,str(fake),str(work),mode], 'fake-device', out, .2 if mode=='timeout' else 3)
                    self.assertEqual(expected,result['status'])
                    self.assertEqual(expected=='ok',result['deviceTestPassed'])
                    self.assertNotIn('token=old', (out/'checkpoints.log').read_text())
                    calls=[json.loads(line) for line in (work/'calls.jsonl').read_text().splitlines()]
                    self.assertEqual(mode=='timeout', any('force-stop' in call for call in calls))
                    self.assertFalse(any('install' in call or 'clear' in call for call in calls))
                    if mode=='timeout':self.assertEqual(0,result['timeoutCleanupExitCode'])
                    with self.assertRaises(FileExistsError): runner.run([], 'fake-device', out)

    def test_cli_arguments_preserve_spaces_and_failure_exit_status(self):
        with tempfile.TemporaryDirectory(prefix='home test ') as directory:
            root = Path(directory)
            fake = root / 'fake adb.py'
            fake.write_text("""import sys,pathlib
root=pathlib.Path(sys.argv[1]); mode=sys.argv[2]; args=sys.argv[3:]
assert args[args.index('-s')+1]=='only-fixture-device'
if 'instrument' in args:
 (root/'token').write_text(args[args.index('homeTestToken')+1])
 print('OK (1 test)' if mode=='ok' else 'FAILURES!!!')
elif 'logcat' in args:
 print('token='+(root/'token').read_text()+' stage=test event=ok monoMs=1')
""")
            for mode in ['ok','failure']:
                work = root/mode; work.mkdir()
                result = subprocess.run([sys.executable,str(SCRIPTS/'run-home-device-test.py'),
                    '--adb',shlex.join([sys.executable,str(fake),str(work),mode]),
                    '--serial','only-fixture-device','--output-dir',str(work/'result')],
                    text=True,capture_output=True,timeout=5)
                self.assertEqual(0 if mode=='ok' else 1,result.returncode, result.stderr)
                self.assertEqual(mode=='ok', json.loads(result.stdout)['deviceTestPassed'])

    def test_shell_zero_is_not_success(self):
        for text in ['app.Test:', 'Process crashed', 'OK (1 test)\nFAILURES!!!', 'OK (1 test)\nINSTRUMENTATION_STATUS_CODE: -2']:
            self.assertEqual('failed',runner.classify(text,0,False))
        self.assertEqual('failed',runner.classify('OK (1 test)',1,False))
        self.assertEqual('timeout',runner.classify('OK (1 test)',0,True))
