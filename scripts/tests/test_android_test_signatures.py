import os
import shutil
import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location('signatures', Path(__file__).resolve().parents[1] / 'check-android-test-signatures.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
JDK = (Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME")
       else Path(shutil.which("javac") or "javac").parent)

class CompiledJUnitSignatureTest(unittest.TestCase):
    def test_compiled_void_passes_and_nonvoid_runner_error_is_detected(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder); annotation = root / 'org/junit/Test.java'; annotation.parent.mkdir(parents=True)
            # Only the runtime annotation shape is needed by this bytecode validator.
            annotation.write_text('package org.junit; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Test { long timeout() default 0L; }')
            good = root / 'Good.java'; bad = root / 'Bad.java'
            good.write_text('public class Good { @org.junit.Test public final void test() {} @org.junit.Test(timeout=110000L) public final void boundedTest() {} }')
            bad.write_text('public class Bad { @org.junit.Test(timeout=110000L) public final Object test() { return new Object(); } }')
            subprocess.run([str(JDK/'javac'), str(annotation), str(good), str(bad)], check=True, capture_output=True)
            report, _ = module.inspect_class(root/'Good.class', str(JDK/'javap'))
            self.assertEqual(2, len(report['tests'])); self.assertEqual('ok', report['status']); self.assertEqual('()V', report['tests'][0]['descriptor'])
            report, _ = module.inspect_class(root/'Bad.class', str(JDK/'javap'))
            self.assertEqual('invalid', report['status']); self.assertIn('()V', report['errors'][0])
            self.assertEqual('()Ljava/lang/Object;', report['tests'][0]['descriptor'])
