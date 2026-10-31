# Harmony SSH isolated integration fixture

The host addon builds the exact `ssh_core.cpp`, `ssh_jump.cpp`, `ssh_keys.cpp` and
`ssh_napi.cpp` shipped in the HAP. No real SSH account or server is used.

Run from the repository root with Docker available:

```sh
HOME=/Users/arquiel harmony/native/ssh/scripts/build-host-addon.sh
./tools/ssh-it/setup.sh
docker compose -f tools/ssh-it/compose.yml up -d --build
# Wait for the jump container to report "Server listening" in its logs.
node tools/ssh-it/run-native.cjs
docker compose -f tools/ssh-it/compose.yml down
```

Only jump port 2222 is published, bound to loopback. The target and second jump
are on an internal network. Jump accounts cannot execute a shell; the client's
bridge uses direct-tcpip. Fixture credentials are generated locally and ignored
by Git. The target's fixed password is test-only. This fixture has no local
port-forward feature or remote service on port 8080.

The Docker script checks one/two jumps, password/key target auth and changed
host-key blocking. `ForceCommand /bin/false` produces a **failed exit code** for
an exec channel even if the server accepts channel creation; inspect that exit
state when checking the denied-shell case manually.

Without Docker, run the loopback AsyncSSH fixture (also uses the same C++ addon):

```sh
python3 -m venv harmony/native/ssh/build/host/test-venv
harmony/native/ssh/build/host/test-venv/bin/pip install 'asyncssh==2.21.1'
HOME=/Users/arquiel AMBER_SSH_FIXTURE_PYTHON="$PWD/harmony/native/ssh/build/host/test-venv/bin/python" \
  node --test harmony/native/ssh/tests/jump_native.test.cjs
```

This verifies real generated keys against ssh-keygen, encrypted OpenSSH import,
1–3 jumps, no-shell authentication, 400 KB output, pre-auth pin blocking at hop 2,
forward denial, target probing without authentication, and connection cleanup.
Host tests do not establish Asset/HUKS behavior, ArkUI appearance or device acceptance.
