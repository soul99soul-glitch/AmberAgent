# E3 Python native gate

2026-09-28. Ownership: native/python/, new cpp/python_core.* and python_napi.*,
rawfile/python/. Read E3-contract, E3-runtime-design and harmony-arkts skill.
No root CMake/registration/declarations/DI/routes/SSH source, devices or Git commit
were operated by this worker. Other workers' changes are preserved.

## Outcome

Real CPython **3.14.7** is compiled into a host NAPI addon and an **OHOS API12
AArch64 static archive + full NAPI ELF**. Target smoke passes `--no-undefined`;
NEEDED only `libace_napi.z.so`, `libc++_shared.so`, `libc.so`.
No remote Python, JS evaluator, Android/JNI binary or executable launch is used.
Root still owns HAP wiring, signing and actual API24/26 device acceptance.

## Evidence and precise gates

| Gate | Result / log |
|---|---|
| Official source | Python.org release 3.14.7 (2026-08-05), XZ SHA256 `3b48dac8fb59f62eaa67ac83c1eb12bda1b7a08406dd286e252c11a66be27f81` |
| Host CPython + addon | PASS `/tmp/e3-python-host-build.log`, `/tmp/e3-python-addon-build.log` |
| Real consumer tests | **23/23 PASS** `/tmp/e3-python-native-test.log`; Unicode stdin/json/math, all exposed modules, fresh globals/modules/stdio, exceptions, direct filesystem/network/process denial, combined UTF8 output, entered-loop timeout and caught-cancel interruption, queued cancellation/ID handling |
| Env lifetime | **3/3 PASS** `/tmp/e3-python-env-test.log`: active env termination, owner reinitialization, concurrent environments independent shutdown |
| OHOS archive | PASS `/tmp/e3-python-ohos-build.log`; actual target clang wrapper/sysroot/API12, matching host 3.14.7 frozen generation |
| OHOS full NAPI ELF | PASS `/tmp/e3-python-target-smoke.log`; ELF64 AArch64, complete --no-undefined link |
| Focused finalizer fix | **2/2 PASS** `/tmp/e3-python-finalizer-green.log`: warm user `__del__` cancellation and dynamic `type` timeout, visible body markers and successor7/8; only these affected cases rerun |
| Finalizer diagnostics | PASS `/tmp/e3-python-finalizer-diagnostic.log`; normal finalizer exception in bounded result, `/tmp/e3-python-finalizer-process-stderr.log` empty |
| Independent review | **PASS**, no open P1/P2: `E3-review-python-native.md`; original warm finalizer independently settles cancelled with BODY_MARKER, successor7 and process stderr0 |

An early handler red/green experiment **failed to reproduce** the guessed hazard:
plain C trace still stopped execution. Source verification distinguishes
`Python/sysmodule.c:trace_trampoline` (Python callback failure clears tracing)
from `Python/legacy_tracing.c:call_trace_func` (our C callback remains installed).
The unnecessary handler AST rewrite was removed. Its early
`/tmp/e3-python-handler-red-green.log` failure is not counted as passing evidence.
Final loop tests require visible `entered-loop` before termination, so a startup
cancel cannot satisfy the bytecode-cancellation gate.

## Integration handoff

- Add `entry/src/main/cpp/python_core.cpp` + `python_napi.cpp`, include
  `native/python/build/ohos/install/include/python3.14`, link
  `native/python/build/ohos/install/lib/libpython3.14.a`, call `RegisterPython`.
- The exact frozen NAPI signatures in E3-contract are implemented; cancel is
  **synchronous void** and only marks actual native work. Execute Promise returns
  after fresh subinterpreter cleanup, with correct null cancellation exit code.
- Two rawfiles only: `python/python314.zip`, `python/amber_python.py`. Deploy as
  `{resourceRoot}/python314.zip` and `{resourceRoot}/amber_python.py`; the platform
  owns fixed private resourceRoot and no Agent argument can choose it.
- `RESOURCE_SHA256SUMS` locks actual packaged files. ZIP_STORED avoids zlib and
  includes upstream LICENSE.txt. Repackage via scripts/package-resources.py.
- Source/stdio is never emitted by internal diagnostics; returned stdout/stderr
  is the user-visible execution result, bounded to 128Ki UTF8 bytes combined.
- One shared process owner serializes all commands, creates isolated PyConfig
  once and a fresh subinterpreter each execution. Module/stdio/globals changes
  cannot persist. Env cleanup cancels its own queued/running controls and waits;
  the last owner joins/finalizes before a later environment may initialize.

## Concrete build fixes

1. SDK `toolchains/diff` returned success for missing files; Autoconf reported
   pyconfig.h unchanged while none existed. Only Python build-script PATH now
   prioritizes host system tools. No SDK or user configuration was changed.
2. Cross-run getaddrinfo/IPv6 probe cannot execute target binary on host; this
   interpreter does not expose sockets, so `--disable-ipv6` is the official
   configure switch. No fabricated positive feature-cache entry was introduced.
3. API12 libintl.h is an empty compatibility header. One guarded `_locale` patch
   skips its undeclared gettext methods; actual 10-error build went green.
   Archive/patch/patched source SHA256 are locked, licenses retained.
4. binascii is explicitly static without the optional system-zlib CRC flag;
   bytecode zip is uncompressed. No additional zlib target dependency.

## Honest limits

Restrictions are not an OS security sandbox or hard memory bound. Bytecode
trace cancellation cannot hard-kill a blocked/expensive C helper. We disable
fork/exec/threads/third-party native imports instead of claiming a process-kill
mechanism unsupported on phone. No perpetual background continuation; root's
lifecycle adapter cancels foreground work and does not replay it.

References: [Python 3.14.7 release](https://www.python.org/downloads/release/python-3147/),
[cross compilation](https://docs.python.org/3.14/using/configure.html#cross-compiling-options),
[isolated PyConfig](https://docs.python.org/3.14/c-api/init_config.html#isolated-configuration),
[subinterpreter config](https://docs.python.org/3.14/c-api/subinterpreters.html).

## Independent P1 followup: user finalizer during GC

Reviewer independently warmed the real interpreter, then created a user class
with `__del__: while True`. The body completed, cancellation was sent, but the
Promise remained pending after 4s because helper/core had disabled trace before
namespace/module destruction (`/tmp/e3-review-python-destructor-warm.log`).
Early 200ms startup timeout results were explicitly discarded as proof.

Fix is local to the exact cleanup boundary: no helper/core early trace disable;
retain C trace through `Py_EndInterpreter`. After stop, trace checks whether the
frame or its ancestors belong to `<embedded-python>` before interrupting. This
stops real user finalizers and calls below them while allowing internal
helper/stdlib/CPython cleanup to complete. Frame/code references are balanced.
The next main-interpreter thread has no trace; teardown clears the subinterpreter
trace normally. No AST finalizer-name ban or dynamic-type restriction is added.

The known class finalizer and equivalent dynamically constructed `type` path
now settle cancelled/timed_out, with body-complete evidence and successor7/8
working. These were the only cancellation regressions rerun; baseline23/env3
were not repeated. A small app-owned native `sys.unraisablehook` prevents GC
interruption diagnostics falling back to process stderr after streams are
cleared: stop diagnostics are quiet, ordinary finalizer errors go into the
bounded execution result. Target strict ELF is rebuilt after this exact fix. Independent reviewer reran
the original warm fixture: REQUEST_SETTLED cancelled / BODY_MARKER_CONFIRMED /
SUCCESSOR completed7 / PROCESS_STDERR_BYTES0, then marked native review PASS
with no open P1/P2 in E3-review-python-native.md. No wider suites were repeated.
