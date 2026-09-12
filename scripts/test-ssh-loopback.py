#!/usr/bin/env python3
"""Run the opt-in SshClient integration tests against a temporary local sshd.

The fixture is deliberately self-contained: all keys, authorized_keys, config, and logs live
under a temporary directory and the daemon listens only on 127.0.0.1. No ~/.ssh files are read.

Examples:
  scripts/test-ssh-loopback.py
  scripts/test-ssh-loopback.py --serve-only --serve-seconds 600

The second form keeps the temporary daemon and keys alive for manual Android/emulator testing.
It prints the fixture properties path, which can be passed to the JVM test through
AMBER_SSH_LOOPBACK_FIXTURE. The temporary directory is removed when the command exits.
"""

from __future__ import annotations

import argparse
import getpass
import os
from pathlib import Path
import random
import signal
import socket
import subprocess
import sys
import tempfile
import time


SSHD = "/usr/sbin/sshd"
SSH_KEYGEN = "/usr/bin/ssh-keygen"
DEFAULT_PASSPHRASE = "amberagent-loopback-passphrase"
TEST_CLASS = "app.amber.feature.terminal.SshClientLoopbackTest"


def run_checked(command: list[str], *, input_text: str | None = None) -> None:
    """Run a local setup command without printing private material or its arguments."""
    result = subprocess.run(
        command,
        input=input_text,
        text=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        # ssh-keygen/sshd diagnostics do not contain key contents, but avoid echoing the full
        # command because the encrypted passphrase is supplied as an argument to ssh-keygen.
        detail = result.stderr.strip().splitlines()[-1:] or ["setup command failed"]
        raise RuntimeError(detail[0])


def supported_sshd_options(config: Path) -> set[str]:
    """Return option names printed by sshd -T, while tolerating older OpenSSH builds."""
    result = subprocess.run(
        [SSHD, "-T", "-f", str(config)],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        return set()
    return {
        line.split(None, 1)[0].lower()
        for line in result.stdout.splitlines()
        if line.strip()
    }


def choose_port() -> int:
    """Choose a high loopback port and leave binding to sshd."""
    for _ in range(20):
        candidate = random.SystemRandom().randrange(20_000, 60_000)
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            try:
                probe.bind(("127.0.0.1", candidate))
            except OSError:
                continue
            return candidate
    raise RuntimeError("could not find a free high loopback port")


def generate_key(path: Path, *, comment: str, passphrase: str, pem: bool = False) -> None:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    format_args = ["-m", "PEM"] if pem else []
    run_checked(
        [
            SSH_KEYGEN,
            "-q",
            "-t",
            "ecdsa",
            "-b",
            "256",
            *format_args,
            "-N",
            passphrase,
            "-C",
            comment,
            "-f",
            str(path),
        ]
    )
    path.chmod(0o600)
    public_key = Path(f"{path}.pub")
    public_key.chmod(0o600)


def wait_for_port(process: subprocess.Popen[bytes], port: int) -> None:
    deadline = time.monotonic() + 8.0
    last_error: OSError | None = None
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError(f"sshd exited during startup with status {process.returncode}")
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=0.25):
                return
        except OSError as error:
            last_error = error
            time.sleep(0.05)
    raise RuntimeError(f"sshd did not listen on 127.0.0.1:{port}: {last_error}")


def write_properties(path: Path, values: dict[str, str]) -> None:
    # java.util.Properties accepts this simple form for the generated Unix paths and values.
    # Escape backslashes/newlines anyway so the fixture stays parseable if a path changes.
    def escape(value: str) -> str:
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r")

    path.write_text(
        "".join(f"{key}={escape(value)}\n" for key, value in values.items()),
        encoding="utf-8",
    )
    path.chmod(0o600)


def stop_process(process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        return
    try:
        if process.pid is not None:
            os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return
    try:
        process.wait(timeout=3.0)
    except subprocess.TimeoutExpired:
        try:
            if process.pid is not None:
                os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait(timeout=3.0)


def build_fixture(root: Path) -> tuple[int, Path, Path]:
    keys = root / "keys"
    host_key = keys / "host_ecdsa"
    client_key = keys / "client_ecdsa"
    wrong_key = keys / "wrong_client_ecdsa"
    encrypted_key = keys / "encrypted_client_ecdsa"
    generate_key(host_key, comment="amberagent loopback host", passphrase="")
    generate_key(client_key, comment="amberagent loopback client", passphrase="")
    generate_key(wrong_key, comment="amberagent loopback wrong client", passphrase="")
    generate_key(
        encrypted_key,
        comment="amberagent loopback encrypted client",
        passphrase=DEFAULT_PASSPHRASE,
        pem=True,
    )

    authorized_keys = root / "authorized_keys"
    authorized_keys.write_text(
        Path(f"{client_key}.pub").read_text(encoding="utf-8")
        + Path(f"{encrypted_key}.pub").read_text(encoding="utf-8"),
        encoding="utf-8",
    )
    authorized_keys.chmod(0o600)

    port = choose_port()
    config = root / "sshd_config"
    pid_file = root / "sshd.pid"
    log_file = root / "sshd.log"
    username = getpass.getuser()
    config.write_text(
        "\n".join(
            [
                f"Port {port}",
                "ListenAddress 127.0.0.1",
                f"HostKey {host_key}",
                f"PidFile {pid_file}",
                f"AuthorizedKeysFile {authorized_keys}",
                f"AllowUsers {username}",
                "PubkeyAuthentication yes",
                "PasswordAuthentication no",
                "KbdInteractiveAuthentication no",
                "ChallengeResponseAuthentication no",
                "UsePAM no",
                "StrictModes no",
                "AllowTcpForwarding no",
                "AllowAgentForwarding no",
                "X11Forwarding no",
                "PermitTunnel no",
                "PermitUserEnvironment no",
                "LogLevel VERBOSE",
                "PrintMotd no",
                "\n",
            ]
        ),
        encoding="utf-8",
    )
    config.chmod(0o600)
    properties = root / "fixture.properties"
    write_properties(
        properties,
        {
            "host": "127.0.0.1",
            "port": str(port),
            "username": username,
            "client_private_key": str(client_key),
            "wrong_private_key": str(wrong_key),
            "encrypted_private_key": str(encrypted_key),
            "encrypted_passphrase": DEFAULT_PASSPHRASE,
            "sshd_log": str(log_file),
        },
    )
    return port, config, properties


def start_sshd(config: Path, log_file: Path) -> subprocess.Popen[bytes]:
    log_handle = log_file.open("ab")
    try:
        process = subprocess.Popen(
            [SSHD, "-D", "-e", "-f", str(config)],
            stdin=subprocess.DEVNULL,
            stdout=log_handle,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
    finally:
        log_handle.close()
    return process


def new_log_path() -> Path:
    descriptor, name = tempfile.mkstemp(
        prefix="amberagent-ssh-loopback-",
        suffix=".log",
        dir="/tmp",
    )
    os.close(descriptor)
    return Path(name)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--serve-only",
        action="store_true",
        help="start sshd and keep the temporary fixture for manual testing",
    )
    parser.add_argument(
        "--serve-seconds",
        type=float,
        default=600.0,
        help="lifetime for --serve-only (default: 600 seconds)",
    )
    parser.add_argument(
        "--gradle-task",
        default=":feature:terminal:testDebugUnitTest",
        help="Gradle task to run in test mode",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not Path(SSHD).is_file() or not os.access(SSHD, os.X_OK):
        raise RuntimeError(f"required executable is unavailable: {SSHD}")
    if not Path(SSH_KEYGEN).is_file() or not os.access(SSH_KEYGEN, os.X_OK):
        raise RuntimeError(f"required executable is unavailable: {SSH_KEYGEN}")
    if args.serve_only and args.serve_seconds <= 0:
        raise ValueError("--serve-seconds must be positive")

    repo_root = Path(__file__).resolve().parents[1]
    with tempfile.TemporaryDirectory(prefix="amberagent-ssh-loopback-") as temporary:
        root = Path(temporary)
        port, config, properties = build_fixture(root)
        if "persourcepenalties" in supported_sshd_options(config):
            with config.open("a", encoding="utf-8") as config_file:
                config_file.write("PerSourcePenalties no\n")
        log_file = root / "sshd.log"
        run_checked([SSHD, "-t", "-f", str(config)])
        process = start_sshd(config, log_file)
        try:
            wait_for_port(process, port)
            print(f"SSH loopback listening on 127.0.0.1:{port}", flush=True)
            if args.serve_only:
                print(f"AMBER_SSH_LOOPBACK_FIXTURE={properties}", flush=True)
                print(f"Temporary fixture expires in {args.serve_seconds:g}s", flush=True)
                deadline = time.monotonic() + args.serve_seconds
                while time.monotonic() < deadline:
                    time.sleep(min(1.0, deadline - time.monotonic()))
                return 0
            output_log = new_log_path()
            env = os.environ.copy()
            env["AMBER_SSH_LOOPBACK_FIXTURE"] = str(properties)
            command = [str(repo_root / "gradlew"), args.gradle_task, "--tests", TEST_CLASS]
            print(f"SSH loopback fixture: {properties}", flush=True)
            print(f"SSH daemon log: {log_file}", flush=True)
            print(f"Gradle log: {output_log}", flush=True)
            with output_log.open("wb") as output:
                result = subprocess.run(
                    command,
                    cwd=repo_root,
                    env=env,
                    stdout=output,
                    stderr=subprocess.STDOUT,
                )
            print(f"Gradle exit status: {result.returncode}", flush=True)
            return result.returncode
        finally:
            stop_process(process)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130)
    except (OSError, RuntimeError, ValueError) as error:
        print(f"test-ssh-loopback.py: {error}", file=sys.stderr)
        raise SystemExit(1)
