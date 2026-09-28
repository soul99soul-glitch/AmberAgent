# Embedded Mosh 1.4.0

Real Mosh SSP encrypted UDP client for HarmonyOS API12 arm64, alongside E2 SSH.
The domain layer authenticates a strictly pinned SSH target and parses server
bootstrap output in memory; native receives that authenticated socket's numeric
peer IP, UDP port and ephemeral key. No second DNS lookup or SSH reconnect is
used for Mosh roaming.

## Reproducible build

```
harmony/native/mosh/scripts/build-deps.sh host
harmony/native/mosh/scripts/build-host-addon.sh
harmony/native/mosh/scripts/get-host-server.sh
node --test harmony/native/mosh/tests/host_native.test.cjs
node --test harmony/native/mosh/tests/env_cleanup.test.cjs
harmony/native/mosh/scripts/build-target-smoke.sh
```

Sources: official [Mosh 1.4.0 release](https://github.com/mobile-shell/mosh/releases/tag/mosh-1.4.0)
and [Protobuf 21.12 release](https://github.com/protocolbuffers/protobuf/releases/tag/v21.12).
Archive, patch and patched-source SHA256 locks are checked before every build.
Matching host protoc generates the three original Mosh LITE_RUNTIME messages;
OHOS links static libprotobuf-lite. libcrypto is the E2 locked OpenSSL 3.5.8 static
archive. Zlib is the API12 system `libz.so`. Protobuf zlib support itself is off.
No package manager install or SDK alteration is performed. Optional official
macOS Mosh package is extracted privately for host server tests and hash locked
in HOST_SERVER_SHA256SUMS; it is not installed or bundled into the HAP.

Target artifacts: `build/ohos/install/lib/libamber_mosh.a`, `libprotobuf-lite.a`;
`build/ohos/install/include` plus `include/mosh/{crypto,network,statesync,terminal,util}`.
Consumer uses SYSTEM includes (upstream headers carry compatibility warnings),
`AMBER_MOSH_EMBEDDED=1`, and links existing libcrypto, z, ace_napi, c++ shared/c.
`build-target-smoke.sh` requires no undefined symbols and prints actual AArch64 ELF
NEEDED entries. Cross compilation proves linking at API12, not device execution.

## Runtime and limits

`RegisterMosh` exposes the frozen E3 start/cancel/read/write/resize/close API.
All environments and all sessions use one process-lifetime owner thread because
upstream timestamp, crypto sequence and compressor caches are process globals.
An idle owner waits on a condition; environment teardown closes only that
environment's sessions and completes pending work before returning. It never
joins/detaches itself, spawns a second owner, or calls a frontend main function.

Read copies synchronized cached VT bytes. A terminal state appears only after
the final cached byte has been delivered; close returns all pending cache bytes. Input is accepted at most 64 KiB per
call and 64 KiB unacknowledged state, output cache at most 1 MiB. Display back
pressure retains the previous frame and later diffs to the newest server frame;
Mosh presents synchronized screen state rather than claiming every remote byte
is a persistent command log. Dimensions are 1..1000 per side, at most 30000 cells.
Sixteen native sessions may be registered at once. Release makes a handle
unavailable; silent pending starts are removed after actual failure/cancellation.

First valid authenticated UDP response must arrive before connectTimeoutMs
(1..60000). `lastHeardMs` is elapsed milliseconds since successfully authenticated
SSP receive, initially null. `reconnecting` starts at 5000 ms, above upstream's
3000 ms idle ACK interval; a valid packet returns the same session to `running`.
A read-only upstream last_heard observer excludes unauthenticated packets and
old-sequence replays from this timestamp; they do not kill the session.
Datagrams truncated before authentication are discarded without ending SSP.
Transient network receive errors preserve the transport for recovery.

Local close attempts the real SSP shutdown exchange for up to 200 ms, then
releases sockets and key-owner state. Remote daemon termination cannot be
promised if the final shutdown ACK cannot arrive. Background interruption and
cold-start policy belong to the shared domain/runtime consumers: no auto replay
or restoration without an ephemeral key. Mosh provides no per-command exit code.

Native errors expose fixed codes and fixed messages, never upstream `what()` or
bootstrap/key text. API bounds and errors are in `mosh_core.h`/`mosh_napi.cpp`.
UTF8 locale is per owner thread with uselocale, never global setlocale/setenv.
Official macOS host server needs en_US.UTF-8; Linux servers normally use C.UTF-8.

Licenses are preserved in `licenses/`; patches retain upstream OpenSSL exceptions.
This is GPLv3+ code in the repository's existing AGPL open-source product line;
no claim is made that it can be distributed as an unconditionally closed product.
