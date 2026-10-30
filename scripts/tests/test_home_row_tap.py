"""Run the actual test touch selector/sequence with javac/java; no adb or Android device."""
import os
import shutil
from pathlib import Path
import subprocess
import tempfile
import unittest

JDK = (Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME")
       else Path(shutil.which("javac") or "javac").parent)
SOURCE = Path(__file__).resolve().parents[2] / 'app/src/androidTestPerffixture/java/app/amber/agent/perffixture/HomeRowTapContract.java'

HARNESS = r'''package app.amber.agent.perffixture;
import java.util.*;
public class TapHarness {
 static final String PKG=HomeRowTapContract.PACKAGE, ID="perf_home_row_10000000-0000-4000-8000-000000000001";
 static HomeRowTapContract.Bounds b(int l,int t,int r,int d) {return new HomeRowTapContract.Bounds(l,t,r,d);}
 static HomeRowTapContract.Node node(String pkg,String desc,boolean visible,boolean enabled,int window,HomeRowTapContract.Bounds bounds) {
  return new HomeRowTapContract.Node(pkg,desc,visible,enabled,false,window,new int[]{64},bounds);
 }
 static HomeRowTapContract.Node row=node(PKG,ID,true,true,7,b(-20,-20,120,120));
 static HomeRowTapContract.Node root=node(PKG,null,true,true,7,b(0,0,100,200));
 static HomeRowTapContract.Owner owner(String pkg,String activity,int pid,int window,int display,boolean home,boolean focused,boolean shown) {
  return new HomeRowTapContract.Owner(pkg,activity,pid,window,display,home,focused,shown,b(0,0,100,200),b(0,0,100,200));
 }
 static HomeRowTapContract.Owner own=owner(PKG,"42-1",42,7,0,true,true,true);
 static HomeRowTapContract.Point select(List<HomeRowTapContract.Node> nodes,List<HomeRowTapContract.Node> parents,
   HomeRowTapContract.Node r,boolean active,boolean focused,int display,HomeRowTapContract.Owner o) {
  return HomeRowTapContract.select(ID,nodes,parents,r,active,focused,display,o,42,"42-1");
 }
 static void reject(Runnable fn,String reason) {
  try {fn.run();throw new IllegalStateException("expected rejection: "+reason);}
  catch(AssertionError expected) {if(!expected.getMessage().contains(reason))throw expected;}
 }
 public static void main(String[] args) {
  if(args[0].equals("selection")) {
   // No ACTION_CLICK, clickable=false. Physical input still valid; intersect all viewport/ancestor clips.
   HomeRowTapContract.Node parent=node(PKG,null,true,true,7,b(0,30,80,180));
   HomeRowTapContract.Point p=select(List.of(row),List.of(parent),root,true,true,0,own);
   if(p.x!=40f||p.y!=75f||p.visibleBounds.top!=30||p.visibleBounds.right!=80)throw new AssertionError("wrong clipped center");
   HomeRowTapContract.Node partlyOffscreen=node(PKG,ID,true,true,7,b(-100,180,200,300));
   HomeRowTapContract.Point edge=select(List.of(partlyOffscreen),List.of(),root,true,true,0,own);
   if(edge.x!=50f||edge.y!=190f)throw new AssertionError("offscreen center not clipped");
   HomeRowTapContract.Node pixel=node(PKG,ID,true,true,7,b(99,199,100,200));
   HomeRowTapContract.Point small=select(List.of(pixel),List.of(),root,true,true,0,own);
   if(small.x!=99.5f||small.y!=199.5f)throw new AssertionError("edge coordinate outside pixel");
  } else if(args[0].equals("rejections")) {
   reject(() -> select(List.of(),List.of(),root,true,true,0,own),"exactly one");
   reject(() -> select(List.of(row,row),List.of(),root,true,true,0,own),"exactly one");
   for(HomeRowTapContract.Node bad:List.of(node("other",ID,true,true,7,b(0,0,40,40)),node(PKG,ID,true,true,8,b(0,0,40,40))))
    reject(() -> select(List.of(bad),List.of(),root,true,true,0,own),"package or window");
   reject(() -> select(List.of(node(PKG,"wrong",true,true,7,b(0,0,40,40))),List.of(),root,true,true,0,own),"stable row ID");
   for(HomeRowTapContract.Node bad:List.of(node(PKG,ID,false,true,7,b(0,0,40,40)),node(PKG,ID,true,false,7,b(0,0,40,40)),node(PKG,ID,true,true,7,b(0,0,0,40))))
    reject(() -> select(List.of(bad),List.of(),root,true,true,0,own),"invisible, disabled or empty");
   for(HomeRowTapContract.Node bad:List.of(node(PKG,ID,true,true,7,b(101,0,120,40)),node(PKG,ID,true,true,7,b(0,201,40,220))))
    reject(() -> select(List.of(bad),List.of(),root,true,true,0,own),"fully clipped");
   reject(() -> select(List.of(row),List.of(node(PKG,null,true,false,7,b(0,0,100,200))),root,true,true,0,own),"disabled");
   reject(() -> select(List.of(row),List.of(node(PKG,null,true,true,7,b(130,0,150,20))),root,true,true,0,own),"fully clipped");
   reject(() -> select(List.of(row),List.of(),root,false,true,0,own),"window/display mismatch");
   reject(() -> select(List.of(row),List.of(),root,true,false,0,own),"window/display mismatch");
   reject(() -> select(List.of(row),List.of(),root,true,true,2,own),"window/display mismatch");
   reject(() -> select(List.of(row),List.of(),node("other",null,true,true,7,b(0,0,100,200)),true,true,0,own),"foreground root");
   reject(() -> select(List.of(row),List.of(),root,true,true,0,owner("other","42-1",42,7,0,true,true,true)),"owner package");
   reject(() -> select(List.of(row),List.of(),root,true,true,0,owner(PKG,"42-2",42,7,0,true,true,true)),"PID/Activity");
   reject(() -> select(List.of(row),List.of(),root,true,true,0,owner(PKG,"42-1",43,7,0,true,true,true)),"PID/Activity");
   for(HomeRowTapContract.Owner bad:List.of(owner(PKG,"42-1",42,7,0,false,true,true),owner(PKG,"42-1",42,7,0,true,false,true),owner(PKG,"42-1",42,7,0,true,true,false)))
    reject(() -> select(List.of(row),List.of(),root,true,true,0,bad),"focused/visible target Home");
   reject(() -> select(List.of(row),List.of(),root,true,true,0,owner(PKG,"42-1",42,7,2,true,true,true)),"non-default owner display");
   reject(() -> select(List.of(row),List.of(),root,true,true,0,owner(PKG,"42-1",42,-1,0,true,true,true)),"unknown/empty");
  } else if(args[0].equals("input")) {
   HomeRowTapContract.Point p=select(List.of(row),List.of(),root,true,true,0,own);
   List<Integer> actions=new ArrayList<>();
   HomeRowTapContract.tap(p,(a,x,y) -> {actions.add(a);if(x!=50||y!=60)throw new AssertionError("point changed");return true;});
   if(!actions.equals(List.of(0,1)))throw new AssertionError(actions.toString());
   actions.clear();reject(() -> HomeRowTapContract.tap(p,(a,x,y) -> {actions.add(a);return false;}),"DOWN");
   if(!actions.equals(List.of(0)))throw new AssertionError(actions.toString());
   actions.clear();reject(() -> HomeRowTapContract.tap(p,(a,x,y) -> {actions.add(a);return a!=1;}),"UP");
   if(!actions.equals(List.of(0,1,3)))throw new AssertionError(actions.toString());
   try {HomeRowTapContract.tap(p,(a,x,y) -> a==0);throw new IllegalStateException("should reject");}
   catch(AssertionError e) {if(!e.getMessage().contains("UP")||e.getSuppressed().length!=1)throw e;}
   actions.clear();RuntimeException primary=new SecurityException("synthetic denial");
   try {HomeRowTapContract.tap(p,(a,x,y) -> {actions.add(a);if(a==1)throw primary;return true;});throw new AssertionError();}
   catch(RuntimeException e) {if(e!=primary)throw e;}
   if(!actions.equals(List.of(0,1,3)))throw new AssertionError(actions.toString());
  } else throw new AssertionError("unknown harness case");
  System.out.println("tap-"+args[0]+"-ok");
 }
}'''


class HomeRowTapRegression(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='home-tap-regression-')
        cls.root = Path(cls.temp.name)
        harness = cls.root/'TapHarness.java'; harness.write_text(HARNESS)
        subprocess.run([str(JDK/'javac'), '-d', str(cls.root), str(SOURCE), str(harness)], check=True, capture_output=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def run_case(self, case):
        result = subprocess.run([str(JDK/'java'), '-cp', str(self.root), 'app.amber.agent.perffixture.TapHarness', case],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(f'tap-{case}-ok', result.stdout)

    def test_clipped_unique_visible_target_without_action_click(self):
        self.run_case('selection')

    def test_wrong_package_owner_visibility_window_bounds_and_duplicates_fail_closed(self):
        self.run_case('rejections')

    def test_real_adapter_touch_sequence_rejection_and_cancel_keep_failure(self):
        self.run_case('input')
