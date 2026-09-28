# Embedded CPython 3.14.7

This is local CPython linked statically into `libamber_native.so`, not a remote
Python command or an ArkWeb JavaScript evaluator. Source release and checksum
are from [Python.org](https://www.python.org/downloads/release/python-3147/).

## Reproducible builds

```sh
harmony/native/python/scripts/build-host-addon.sh
node --test harmony/native/python/tests/host_native.test.cjs harmony/native/python/tests/cancellation_handler.test.cjs
node --test harmony/native/python/tests/env_cleanup.test.cjs
# Only when changing cleanup/cancellation:
node --test harmony/native/python/tests/finalizer_cancel.test.cjs
harmony/native/python/scripts/build-target-smoke.sh
python3 harmony/native/python/scripts/package-resources.py
```

`build-deps.sh` downloads the pinned source into ignored `build/`, verifies the
archive/patch/source SHA256 lists, and produces:

- `build/ohos/install/lib/libpython3.14.a`
- `build/ohos/install/include/python3.14/`
- host equivalent and `build/host/python.exe` with the **same** 3.14.7 version.

[CPython cross compilation](https://docs.python.org/3.14/using/configure.html#cross-compiling-options)
requires a matching build Python. Configure uses the supported `linux-musl`
classification; compiler, linker and sysroot are always **OHOS API 12 aarch64**.
This does not produce a GNU/Linux executable. There are no fabricated positive
configure cache entries. `_dev_ptmx/_dev_ptc` are disabled; `--disable-ipv6`
avoids a run-only IPv6 probe for an interpreter with no exposed network module.
The host build locally prioritizes `/usr/bin:/bin`: the installed SDK's `diff`
returns success for missing files and breaks Autoconf header generation.

The single vendor patch disables GNU gettext APIs for OHOS API 12 because its
`libintl.h` is an empty compatibility header. Locale functions themselves remain
compiled. Static `binascii` uses its own CRC implementation, and the stdlib ZIP is
uncompressed, so no zlib dependency is added. Python/PSF notices are preserved in
`licenses/Python-3.14.7.txt`; the deterministic stdlib ZIP includes the same
`LICENSE.txt` as well as upstream source files.

## App integration

Root adds `python_core.cpp` / `python_napi.cpp`, the target include/archive above,
and calls `RegisterPython(env, exports)` from the existing NAPI registration.
The resulting target smoke ELF passes `--no-undefined`; direct NEEDED libraries
are `libace_napi.z.so`, `libc++_shared.so`, `libc.so`.

Only these rawfiles are deployed into a fixed app-private directory:

```
rawfile/python/python314.zip -> resourceRoot/python314.zip
rawfile/python/amber_python.py -> resourceRoot/amber_python.py
```

`resourceRoot` is configured by the platform adapter, never supplied by Agent
input. The same process owner accepts one immutable root until the last native
environment shuts down. `RESOURCE_SHA256SUMS` locks the packaged inputs.

```ts
pythonVersion(): string;
pythonExecute(requestId: string, options: {
  source: string; stdin: string; timeoutMs: number; resourceRoot: string;
}): Promise<{
  status: 'completed' | 'failed' | 'cancelled' | 'timed_out';
  exitCode: number | null; stdout: string; stderr: string; errorCode: string | null;
}>;
pythonCancel(requestId: string): void;
```

Source/stdin/combined output limits are 256/64/128 KiB **UTF-8 bytes**.
Timeout is 1–60000 ms, default 15000. Unknown cancellation is harmless. Active
request IDs cannot be reused; settled IDs can. Invalid arguments reject with
`invalid_arguments`, duplicate active IDs with `duplicate_request`. Execution
failures return results with `python_exception`, `output_limit`,
`initialization_failed`, `runtime_error`, `cancelled` or `timed_out`.

## Isolation and cancellation

All native environments share one serial owner thread. No long execution holds
the NAPI async pool. [Isolated PyConfig](https://docs.python.org/3.14/c-api/init_config.html#isolated-configuration)
uses fixed stdlib/helper paths, no environment/site/bytecode writes/native signal
handlers. Each command creates and destroys a **fresh subinterpreter**; module,
global and stdio changes do not survive it. Fork/exec/threads are disabled in its
[PyInterpreterConfig](https://docs.python.org/3.14/c-api/subinterpreters.html).

The helper allows the modules listed in `amber_python.py` (json, math, statistics,
text processing, etc). `stdin` and `input()` receive the supplied string. Direct
filesystem/open, network, subprocess, package installation, third-party native
extensions and reflective/private access are unavailable. File operations belong
to the existing Workspace read/write tools.

Cancellation and timeout are **cooperative Python-bytecode interruption**. The
C `PyEval_SetTrace` callback continues checking real cancellation/deadline after
an exception is caught; unlike the `sys.settrace` Python trampoline, it is not
removed when returning an error. Actual tests require an `entered-loop` output
marker before cancellation, so cancelling interpreter startup cannot masquerade
as loop interruption. BaseException/bare-except and successor execution pass.
No AST handler rewriting is needed. Tracing remains active through object/module
destruction; after stop it interrupts only user frames or calls beneath them,
so internal helper/CPython cleanup can finish. App-owned unraisable diagnostics
stay in the bounded result rather than falling back to process stderr. A Promise
settles only after execution and
subinterpreter destruction, and environment cleanup cancels and joins its work.
The last environment destroys the common owner before a new owner initializes.

These restrictions are **not an OS security sandbox or hard memory limit**.
A blocking/expensive C helper cannot be forcibly killed from this phone thread;
a timeout is not a claim of general hard cancellation. No bundled executable,
process-global shell/stdio redirection, signal handler, `fork` or thread kill is
used. API12 compile/link is separate from root's API24/26 device verification.
