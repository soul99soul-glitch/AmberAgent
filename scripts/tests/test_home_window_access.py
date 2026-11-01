"""Exercise the actual Android window adapter against local public-API doubles.

This verifies API calls/order, dynamic returned window data and scoped flags;
it is not a platform or hardware success claim and does not call adb.
"""
import os
import shutil
from pathlib import Path
import subprocess
import tempfile
import unittest

JDK = (Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME")
       else Path(shutil.which("javac") or "javac").parent)
SOURCE = Path(__file__).resolve().parents[2] / 'app/src/androidTestPerffixture/java/app/amber/agent/perffixture/HomeWindowAccess.java'
STUBS = {
'android/accessibilityservice/AccessibilityServiceInfo.java': '''package android.accessibilityservice;
public class AccessibilityServiceInfo {public static final int FLAG_RETRIEVE_INTERACTIVE_WINDOWS=64; public int flags;public int feedbackType=42;public int getCapabilities(){return 1;}}''',
'android/util/SparseArray.java': '''package android.util;
import java.util.*; public class SparseArray<T>{private final List<T> values=new ArrayList<>();public int size(){return values.size();}public T valueAt(int i){return values.get(i);}public void put(int key,T value){values.add(value);}}''',
'android/view/accessibility/AccessibilityWindowInfo.java': '''package android.view.accessibility;
public class AccessibilityWindowInfo {final int id,display;final boolean active,focused;public AccessibilityWindowInfo(int id,int display,boolean active,boolean focused){this.id=id;this.display=display;this.active=active;this.focused=focused;} public int getId(){return id;}public int getDisplayId(){return display;}public boolean isActive(){return active;}public boolean isFocused(){return focused;}}''',
'android/view/accessibility/AccessibilityNodeInfo.java': '''package android.view.accessibility;
import android.app.UiAutomation; public class AccessibilityNodeInfo {public UiAutomation automation;public AccessibilityWindowInfo value;public AccessibilityNodeInfo(UiAutomation a){automation=a;}public AccessibilityWindowInfo getWindow(){automation.calls.add("node.window");automation.requireRetrieval();return value;}}''',
'android/app/UiAutomation.java': '''package android.app;
import android.accessibilityservice.AccessibilityServiceInfo;import android.view.accessibility.AccessibilityWindowInfo;import android.util.SparseArray;import java.util.*;
public class UiAutomation {
 public int flags=18,sets=0;public boolean missing,ignoreEnable,apiUnsupported,restoreThrow,restoreStall;
 public List<String> calls=Collections.synchronizedList(new ArrayList<>());
 public List<AccessibilityWindowInfo> current=new ArrayList<>(); public SparseArray<List<AccessibilityWindowInfo>> all=new SparseArray<>();
 public AccessibilityServiceInfo getServiceInfo(){calls.add("getInfo");if(missing)return null;AccessibilityServiceInfo i=new AccessibilityServiceInfo();i.flags=flags;return i;}
 public void setServiceInfo(AccessibilityServiceInfo i){calls.add("setFlags:"+i.flags);sets++;if(i.feedbackType!=42)throw new AssertionError("other setting changed");
  if(sets>1&&restoreThrow)throw new IllegalStateException("restore unavailable");
  if(sets>1&&restoreStall){long until=System.nanoTime()+2000000000L;while(System.nanoTime()<until){try{Thread.sleep(10);}catch(InterruptedException ignored){}}}
  if(!(ignoreEnable&&sets==1))flags=i.flags;}
 public void requireRetrieval(){if(apiUnsupported)throw new UnsupportedOperationException("unavailable");if((flags&64)==0)throw new AssertionError("retrieval called before flag enabled");}
 public List<AccessibilityWindowInfo> getWindows(){calls.add("getWindows");requireRetrieval();return current;}
 public SparseArray<List<AccessibilityWindowInfo>> getWindowsOnAllDisplays(){calls.add("getWindowsOnAllDisplays");requireRetrieval();return all;}
}''',
}
HARNESS = r'''package app.amber.agent.perffixture;
import android.app.UiAutomation;import android.view.accessibility.*;import java.util.*;
public class WindowHarness {
 static HomeWindowAccess scope(UiAutomation a,List<String> audit){return new HomeWindowAccess(()->a,(e,s)->audit.add(e));}
 static void check(boolean yes,String why){if(!yes)throw new AssertionError(why);}
 public static void main(String[] args) {
  List<String> audit=new ArrayList<>();UiAutomation a=new UiAutomation();AccessibilityNodeInfo root=new AccessibilityNodeInfo(a);
  if(args[0].equals("chain")) {
   try(HomeWindowAccess access=scope(a,audit)) {
    access.enable();check(access.flagsBefore==18&&access.flagsEnabled==82,"exact flags/readback");
    a.current.add(new AccessibilityWindowInfo(503,0,true,true));a.all.put(2,List.of(new AccessibilityWindowInfo(504,2,true,true)));
    HomeWindowAccess.Snapshot unknown=access.query(root,504,0);
    check(!unknown.ready()&&unknown.matched==null,"no guessed window/display/true");
    a.all.put(0,List.of(new AccessibilityWindowInfo(504,0,false,false)));
    HomeWindowAccess.Snapshot real=access.query(root,504,0);
    check(real.ready()&&real.matched.id==504&&real.matched.displayId==0,"match actual owner identity");
    check(!real.matched.active&&!real.matched.focused,"do not replace actual false with true");
    check(real.matched.source.equals("getWindowsOnAllDisplays"),"lookup did not use platform all-display result");
    check(a.calls.contains("node.window")&&a.calls.contains("getWindows")&&a.calls.contains("getWindowsOnAllDisplays"),"platform chain missing");
    check(a.calls.indexOf("setFlags:82")<a.calls.indexOf("node.window"),"init order");
   }
   check(a.flags==18&&audit.equals(List.of("initialize","restore")),"exact restoration/audit");
   check(a.sets==2,"unexpected flag mutations");
  } else if(args[0].equals("finally")) {
   RuntimeException primary=new IllegalStateException("synthetic navigation failure");
   HomeWindowAccess access=scope(a,audit);
   try(HomeWindowAccess ignored=access){access.enable();throw primary;}catch(RuntimeException e){check(e==primary,"masked original");}
   check(a.flags==18&&access.flagsRestored==18,"exception finally did not restore");access.close();check(a.sets==2,"close not idempotent");
   a=new UiAutomation();a.flags=82;HomeWindowAccess already=scope(a,new ArrayList<>());already.enable();already.close();check(a.flags==82,"original retrieval flag lost");
  } else if(args[0].equals("negative")) {
   a.missing=true;HomeWindowAccess missing=scope(a,audit);
   try {missing.enable();throw new AssertionError("must report unsupported");}catch(UnsupportedOperationException expected){}finally{missing.close();}
   a=new UiAutomation();a.ignoreEnable=true;HomeWindowAccess ignored=scope(a,audit);
   try {ignored.enable();throw new AssertionError("ignored flag accepted");}catch(UnsupportedOperationException expected){}finally{ignored.close();}
   check(a.flags==18,"ignored init restore");
   a=new UiAutomation();root=new AccessibilityNodeInfo(a);HomeWindowAccess conflict=scope(a,audit);conflict.enable();
   root.value=new AccessibilityWindowInfo(504,0,false,false);a.current.add(new AccessibilityWindowInfo(504,0,true,true));
   check(!conflict.query(root,504,0).ready(),"conflicting cache observations accepted");
   a.apiUnsupported=true;HomeWindowAccess.Snapshot unavailable=conflict.query(root,504,0);check(!unavailable.ready()&&unavailable.errors.size()==3,"unsupported API hidden/defaulted");conflict.close();
   a=new UiAutomation();a.restoreThrow=true;HomeWindowAccess badRestore=scope(a,audit);RuntimeException primary=new IllegalStateException("synthetic");
   try(HomeWindowAccess ignoredScope=badRestore){badRestore.enable();throw primary;}
   catch(RuntimeException e){check(e==primary&&e.getSuppressed().length==1,"restore error masked primary or swallowed");}
   check(badRestore.restoreError!=null&&badRestore.flagsRestored==null,"invented restored flags");
   a=new UiAutomation();a.restoreStall=true;HomeWindowAccess stalled=scope(a,audit);stalled.enable();long start=System.nanoTime();
   try {stalled.close();throw new AssertionError("restore timeout swallowed");}catch(AssertionError e){check(e.getMessage().contains("restoration failed/bounded timeout"),"wrong timeout");}
   check(System.nanoTime()-start<1700000000L,"unbounded restore");
  } else throw new AssertionError("case");
  System.out.println("window-"+args[0]+"-ok");
 }
}'''


class HomeWindowAccessRegression(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='home-window-api-chain-')
        cls.root = Path(cls.temp.name)
        files = []
        for name, source in STUBS.items():
            path = cls.root/name; path.parent.mkdir(parents=True, exist_ok=True); path.write_text(source); files.append(str(path))
        harness = cls.root/'WindowHarness.java'; harness.write_text(HARNESS)
        result = subprocess.run([str(JDK/'javac'), '-d', str(cls.root), str(SOURCE), str(harness), *files], capture_output=True, text=True)
        if result.returncode: raise AssertionError(result.stdout + result.stderr)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_case(self, case):
        result = subprocess.run([str(JDK/'java'), '-cp', str(self.root), 'app.amber.agent.perffixture.WindowHarness', case],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(f'window-{case}-ok', result.stdout)

    def test_real_adapter_calls_service_and_all_window_apis_with_dynamic_results(self): self.run_case('chain')
    def test_original_flags_restore_after_navigation_failure_and_already_enabled(self): self.run_case('finally')
    def test_unknown_unsupported_conflicting_and_restore_failure_timeout_fail_closed(self): self.run_case('negative')
