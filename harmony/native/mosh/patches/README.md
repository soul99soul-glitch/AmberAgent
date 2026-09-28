# Embedded Mosh patches

Upstream: Mosh 1.4.0 official release archive, locked in SHA256SUMS.
Every patch and its resulting source are SHA256 checked by build-deps.sh.
The GPLv3+ and per-file OpenSSL linking exception remain in all modified files.

- `terminaldisplayinit.cc.patch`: `AMBER_MOSH_EMBEDDED` only admits the existing
  `Display(false)` constructor path. Excludes terminfo headers/helpers and rejects
  `Display(true)`; no TERM environment or terminal database is needed. The full
  upstream source remains available, including the ordinary CLI path.
- `fatal_assert.h.patch`: native invariant errors throw an exception inside an
  embedded application rather than calling process-wide `abort()` or writing to
  process stderr. The owner reports a bounded safe code; it never exposes keys.
- `crypto.cc.patch`: the negative decrypted-payload invariant throws in embedded
  builds instead of process-wide `exit(1)`. CLI core-dump functions are not called
  by the embedded driver; no process-wide signal/core-limit hooks are installed.

- `network.h.patch` / `networktransport.h.patch`: read-only observer of upstream
  `last_heard`, updated only after authenticated fresh packet sequence checks.
  Embedded reconnect state does not confuse replayed old SSP datagrams with
  new peer activity; SSP acceptance/crypto/ACK/roaming algorithms are unchanged.

The embedded driver never calls upstream CLI main, frontend stdio/termios, or
server-side fork/exec. Source and patch lock files are sufficient to reconstruct
these dependencies without changing system libraries or the Harmony SDK.
