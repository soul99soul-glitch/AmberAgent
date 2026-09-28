# Amber native Remote SSH

The C++ owner and Node-API adapter live in `entry/src/main/cpp/ssh_core.*` and
`ssh_napi.*`. One owner serializes all libssh2 operations per connection;
`sshRead` drains the bounded byte queue without network I/O. Cancellation wakes
nonblocking socket polling. TSFN delivers completed promises, and env cleanup
cancels owners without retaining an expired `napi_env`.

## Dependencies and reproducible build

- [libssh2 1.11.1](https://libssh2.org/download/libssh2-1.11.1.tar.gz), BSD-3-Clause.
- [OpenSSL 3.5.8 LTS](https://github.com/openssl/openssl/releases/download/openssl-3.5.8/openssl-3.5.8.tar.gz), Apache-2.0.
- Exact archives are checked against `SHA256SUMS`; they are retained in the
  local ignored `archives/` directory. Licenses are tracked under `licenses/`.
- Two small libssh2 patches are separately locked in `PATCH_SHA256SUMS`; the
  complete modified source files are locked in `PATCHED_SOURCE_SHA256SUMS`.
  See [patch provenance](patches/README.md).

Run `scripts/build-deps.sh ohos`. It uses the existing SDK clang/sysroot, target
`aarch64-linux-ohos12.0.0`, API 12, PIC, existing ARM CMake/Ninja, and four build
jobs. Override `OHOS_BASE_SDK_HOME`, `AMBER_NATIVE_CMAKE`, `AMBER_NATIVE_NINJA`, or
`AMBER_SSH_BUILD_JOBS` when needed. It changes no SDK/global config.

Only `build/ohos/install/lib/libssh2.a` and `libcrypto.a` are linked into the app;
`libssl.a`, which OpenSSL also builds, is unused. No host library, Android/JNI,
zlib, dynamically loaded provider, or dynamic OpenSSL is used. OpenSSL's default
provider is built into libcrypto. App CMake is owned by the root integration.

## Real host tests

`scripts/build-host-addon.sh` builds the same C++ sources and real static libraries
for the local arm64 Mac. Set `AMBER_SSH_NODE_HOME` to an installed Node distribution
with headers; the current default is the already installed Node 26.8.1.

After root starts `harmony/scripts/ssh-fixture/fixture_server.py` using generated
test identities, run:

```sh
node tests/host_native.test.cjs
node tests/no_exit_status.test.cjs
node tests/env_cleanup.test.cjs
node tests/pty_close.test.cjs
node tests/blocked_write_close.test.cjs
```

Run from this directory. Main fixture defaults to `/tmp/e2-ssh-fixture`, port
22224, key0; `AMBER_SSH_FIXTURE_DIR`, `AMBER_SSH_FIXTURE_PORT`, and
`AMBER_SSH_FIXTURE_HOST_KEY` adjust the first test. Its first two tests inspect
fixture auth counts, so no other client should authenticate during that short
window. `no_exit_status.test.cjs` creates and terminates its own independent SSH
server on an OS-assigned port; it does not restart the root fixture. It uses
`AMBER_SSH_FIXTURE_PYTHON` (default `/tmp/e2-ssh-fixture-py312/bin/python`).
An additional generated test ECDSA PEM key can be passed with
`AMBER_SSH_TEST_ECDSA_PEM`; production native still authenticates only from memory.

`tests/core_lifetime.cpp`, compiled with `AMBER_SSH_TESTING`, checks exact owner,
handle, fd and host thread counts after real connections. Its test-only resolver
delay proves that cancelling an owner does not wait for synchronous system DNS.
The independent resolver holds only hostname/service/address results, no
credential, libssh2 handle, NAPI env, or callback, and frees late results. The
system DNS call's own thread ends when getaddrinfo returns; this is distinct from
immediate owner/fd release. PTY operations and byte reads never use that resolver.

No user SSH account, private key, password, or device is accessed by these tests.
Host tests do not establish HAP loading, Asset storage, ArkUI behavior, real-device
execution, or end-to-end Agent approval. Root records those separate gates.
