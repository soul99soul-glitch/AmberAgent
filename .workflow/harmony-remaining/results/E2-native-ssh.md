# E2 native SSH implementation and verification

2026-09-28. Native worker ownership: `harmony/native/ssh/`, `entry/src/main/cpp/ssh_*`,
and only the SSH addition to `types/libamber_native/index.d.ts`. Root owns CMake,
`napi_init.cpp`, domain/platform/UI integration and devices. E1 tokenizer code was
preserved. No git add/commit, SDK modification, device operation, or user credential
inspection was performed.

## Implemented

All frozen methods: sshProbe/startExec/startPty/cancel/read/write/resize/close.
`RegisterSSH(env, exports)` is the only root registration seam. Binding validates
and copies typed arguments, keeps request IDs distinct from opaque typed handles,
and never exposes native pointers. Errors carry sanitized codes/messages.

Each live connection has one serial native owner. Socket is nonblocking; EAGAIN
uses poll with a separate cancellation wake pipe. Long network reads do not occupy
NAPI async-work threads. `sshRead` only takes the output mutex and copies cached
Uint8Array bytes. Stdout/stderr are both drained; zero/EAGAIN are not EOF. Pending
native output is capped at 1MiB and pauses further reads when full. PTY input
handles partial writes, EAGAIN and original control bytes; resize is serial with
all other channel operations. Exec deadline includes connection startup; PTY has
no fabricated idle timeout.

Probe has no credential or userauth call. Start compares the actual raw-host-key
OpenSSH SHA256 pin before password/from-memory private-key authentication. Native
secret copies are cleansed on authentication completion and all error/destructor
paths. Password/PEM are never persisted by native. Close owns connection teardown;
terminal handles remain readable until runtime release, active release rejects,
unknown/wrong-kind handles fail, and fd/libssh2 cleanup stays on owner. Real
transport loss is disconnected/null; timeout/cancel also retain null exitCode;
channel/auth failures are not relabeled disconnected. TERM is best effort only.

NAPI completion uses API10-compatible thread-safe functions with separate owner
and env references. Cleanup aborts pending TSFN delivery and wakes/cancels owners;
no background callback retains or calls a cached napi_env.

## Locked dependencies and actual cross-build

| Dependency | Archive SHA256 |
| --- | --- |
| libssh2 1.11.1 | d9ec76cbe34db98eec3539fe2c899d26b0c837cb3eb466a56b0f109cabf658f7 |
| OpenSSL 3.5.8 LTS | a8f84a39918ec6415ce765d9b429d313ba97b8143169c172e734b9514464f5b2 |

Archives came from [libssh2 official downloads](https://libssh2.org/download/) and
[OpenSSL official release](https://github.com/openssl/openssl/releases/tag/openssl-3.5.8).
OpenSSL hash matched its official `.sha256` file. BSD/Apache license texts are
tracked; archives and build products are retained locally but gitignored. Build
uses existing E1 SDK paths, OHOS API12 ARM64, PIC, static OpenSSL/libssh2, no zlib,
no dynamic modules/config, and existing ARM Ninja. Only libcrypto is linked;
libssl is an unused build artifact.

Two narrow source fixes are part of this pinned build, separately patch/hash
locked and whole-patched-file hash checked:

1. **Upstream backport**: signature-list allocation fix from official
   [b671ac9bdd7fa4dc321c9c07d553bdea68bed727 / PR1885](https://github.com/libssh2/libssh2/commit/b671ac9bdd7fa4dc321c9c07d553bdea68bed727).
   Real repeated private-key tests intermittently crashed, and ASAN reproduced a
   heap-buffer-overflow in `_libssh2_key_sign_algorithm` (inlined into publickey
   auth). The filtered output follows the server list, whose repeated algorithms
   can exceed the client's supported-list length. Only the upstream allocation
   fix is backported; no switch to master and no crypto rewrite. After this fix
   normal and ASAN authentication regressions pass.
2. **Amber project patch**: actual exit-status receipt flag and read-only public
   `libssh2_channel_exit_status_received`. The upstream
   [get-exit-status API](https://libssh2.org/libssh2_channel_get_exit_status.html)
   can return default0 even when status was never sent. Independent real fixture
   first reproduced `exited/0` without status (red), then `failed/channel_error/null`
   (green). Flag is set only on parsing an actual status packet. No private-layout
   access or sentinel; native preserves all unsigned32 bits, verified with an
   actual RFC4254 wire value `0xffffffff` → JS4294967295.

## Completed evidence

- Host NAPI addon builds and loads eight real exports using the same C++ core,
  real libssh2/OpenSSL (no fake transport). Final `/tmp/e2-host-native-tests.log`:
  **17/17 PASS**. Includes probe auth-count0, wrongpin auth-count0, password stdout
  `你好🙂` via 3-byte reads, stderr, exit7, zero-read-running, deadline250ms→timed_out,
  badpassword, in-memory Ed25519/RSA4096/ECDSA/encryptedRSA, >1024-byte actual RSA
  key, 2MiB retained output under 1MiB queue backpressure, active-release refusal,
  cancellation/null exit, immediate start-cancel, stalled handshake timeout/cancel,
  PTY initial prompt, stty24x80→31x93, Ctrl-C, ANSI color/alt-screen raw bytes,
  typed handles/errors, real TCP proxy interruption→disconnected/null, and twenty
  repeated post-failure authenticated connections.
- `/tmp/e2-no-exit-status-red.log`: real pre-fix `exited/0` (assertion fails).
  Final `/tmp/e2-no-exit-status-green.log`: **2/2 PASS**, missing-status failure and
  unsigned32 full-width status. The independent fixture owns its ephemeral server.
- Final `/tmp/e2-host-asan-tests.log`: **17/17 PASS**, no ASAN error. Core/NAPI are
  ASAN-instrumented, static libraries are release builds; ASAN still intercepts
  the actual libssh2 allocation/copy which previously reproduced the overflow.
  Leak sanitizer was disabled; this is memory-boundary evidence, not a universal
  process leak certification.
- `/tmp/e2-ecdsa-pem-test.log`: actual generated ECDSA PEM fixture key additionally
  authenticated from memory and executed `printf ecdsa-pem-ok`, exit0. This closes
  the iOS-reference PEM format check in addition to OpenSSH ECDSA. Key was exported
  only for the synthetic fixture under `/tmp`; production native creates no PEM
  file. The host suite accepts `AMBER_SSH_TEST_ECDSA_PEM` to repeat this format.
- Final `/tmp/e2-core-lifetime-tests.log`: **4 checks PASS**. Fifty real numeric
  probes and twenty real authenticated exec/release loops return to fd3, thread1,
  owner0, handle0. Test-only500ms DNS delay: cancelled owner/fd return within200ms,
  DNS result completes independently and is reclaimed after return. Invalid
  request/shutdown remain usable. No production resolver fake is used.
- Final `/tmp/e2-env-cleanup-tests.log`: **3 checks PASS**. Ten Node worker env
  terminations during stalled handshakes, ten with live PTYs, and all stalled
  sockets released. Neither pending TSFN nor owner keeps a dead env alive.
- `/tmp/e2-native-deps-ohos.log`: both patched static target archives rebuilt.
  API12 SDK strict syntax `-Wall -Wextra -Werror` passes both C++ files.
- `/tmp/e2-native-target-link.log`: independent SSH-only AArch64 shared target
  links `--no-undefined`. `/tmp/e2-native-target-elf.txt` confirms AArch64,
  RegisterSSH symbol, and DT_NEEDED only libace_napi.z.so/libc++_shared.so/libc.so.
  No JNI/Android, libssl/libcrypto/zlib dynamic dependency or host link path.
- Shell syntax and `git diff --check` pass. Final core/NAPI total is under900
  lines, split by transport ownership vs binding, without a second scheduler.
- Both patch files were also applied to a fresh extraction of the untouched
  release archive under `/tmp/e2-clean-patch-verify`; all five resulting source
  hashes exactly match `PATCHED_SOURCE_SHA256SUMS`.

## Boundaries and root continuation

System getaddrinfo is a blocking POSIX call. Cancellation releases the owner,
socket and request independently within its bounded polling loop; an isolated DNS
worker without secret/session/env lives until that system call returns and then
frees its late results. This is explicitly not instantaneous DNS-thread teardown.
A cancelled client connection does not prove arbitrary remote daemon termination.

These are host/native and target ELF results, not E2 completion. HAP loading,
actual API12 device behavior, secure Asset store, full terminal rendering/keyboard
Tab/arrows, approval/Agent/task/log pipeline, background interrupt, real-device
SSH, and light/dark UI must be verified by root and other owners. ANSI byte
transport is not itself xterm visual acceptance. No server-host-key rotation was
performed here; wrong trusted pin was proven before auth and root owns rotation.

Root CMake/napi_init already have the documented sources/includes/static archive
ordering. After source/vendor changes above, rerun the unified build to package
current archives, then retain the separate load/sign/install/provider/UI gates.

## Root supplemental native cancellation evidence

After domain review identified a queued-write cancellation deadlock, root added
`tests/pty_close.test.cjs` against the actual host NAPI/core and a private loopback
fixture. AsyncSSH resumes input automatically after `session_started`, so the
blocked-input fixture schedules its pause immediately after that automatic resume;
it then genuinely leaves a partial 1MiB write pending. Closing with `cancelled` or
`disconnected` settles within one second while the write is blocked. Both return
null exitCode; the pending writer reports `cancelled` / `network_error` respectively,
matching native reason semantics. `/tmp/e2-native-pty-close.log`: **2/2 PASS**.
No production API or transport behavior was changed for this test.

The native worker's additional `blocked_write_close.test.cjs` initially returned
2/2 but did not prove a queued writer: 1MiB active input exhausted the whole native
input limit, so the second one-byte input was immediately rejected. Independent
review caught this invalid coverage. Root changed the active input to1048575 bytes
and asserts the second writer is still pending before close. The corrected actual
run is **2/2 PASS** in `/tmp/e2-blocked-write-close-corrected.log`: active and queued
inputs both settle, and the released handle is unusable. No product source was
changed. These are native cancellation tests, not device IME evidence.
