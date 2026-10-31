# Amber SSH ProxyJump & Local Key Integration Test Environment

This Docker Compose setup launches an isolated 3-node SSH topology:

- `jump1` (exposed on `127.0.0.1:2221`, bridging `net_edge` and internal `net_mid`):
  - `ForceCommand /bin/false` (no interactive/exec shell allowed — proves `DirectTcpipProxy` uses SSH `direct-tcpip` channel rather than shell `nc`/`ssh -W`)
  - `AllowTcpForwarding local` with `PermitOpen jump2:22 target:22`
- `jump2` (internal only on `net_mid` + `net_inner`, not exposed to host):
  - `ForceCommand /bin/false`, `PermitOpen target:22`
- `target` (internal only on `net_mid` + `net_inner`, not exposed to host):
  - Ed25519 host key only (`ssh_host_ed25519_key`)
  - Full command execution enabled

## Running the Integration Tests

1. Start the containers:
   ```bash
   docker compose -f tools/ssh-it/compose.yml up -d --build --wait
   ```
2. Run the gated integration test suite:
   ```bash
   HOME=/Users/arquiel JAVA_HOME=/opt/homebrew/opt/openjdk@21 AMBER_SSH_IT=1 ./gradlew :feature:terminal:testDebugUnitTest --tests "app.amber.feature.terminal.SshProxyJumpDockerTest"
   ```
3. Tear down:
   ```bash
   docker compose -f tools/ssh-it/compose.yml down -v
   ```

> Note: When `AMBER_SSH_IT` is not set to `1`, `SshProxyJumpDockerTest` is skipped via `Assume.assumeTrue`. Meanwhile, `SshProxyJumpClientTest` runs automatically in standard local unit test runs using loopback `sshd` processes when `/usr/sbin/sshd` is available.

The fixture uses the disposable `testuser` / `amber-it-pass` password on all hops.
Each container creates its own host keys at startup. The gated JVM test probes
and trusts each hop, executes through two shell-disabled jumps, and checks
`PermitOpen` rejection. Target containers publish no host port. The separate
`SshProxyJumpClientTest` uses a newly generated Ed25519 client key against
loopback-only native sshd processes and tests a second-hop fingerprint mismatch.

On 2026-11-01 the implementation host had no Docker CLI (including the usual
Docker Desktop binary locations). Docker was not run. The Compose configuration
is supplied for a Docker-enabled host; it is not claimed as an integration pass.
