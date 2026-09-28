#!/usr/bin/env python3
"""Local E2 test server. Uses generated fixture identities, never user SSH keys.

Requires AsyncSSH 2.24.0 and bcrypt, Python >=3.10. Server/session callbacks:
https://asyncssh.readthedocs.io/en/latest/#server-examples
https://asyncssh.readthedocs.io/en/latest/api.html#sshserversession
"""
import argparse
import asyncio
import fcntl
import json
import os
from pathlib import Path
import secrets
import signal
import struct
import subprocess
import termios
import time

import asyncssh


def initialize(root: Path) -> dict:
    root.mkdir(parents=True, exist_ok=True)
    config_path = root / 'fixture.json'
    if config_path.exists():
        return json.loads(config_path.read_text())
    clients = {}
    for name, algorithm, options in [
        ('ed25519', 'ssh-ed25519', {}),
        ('rsa', 'ssh-rsa', {'key_size': 4096}),
        ('ecdsa', 'ecdsa-sha2-nistp256', {}),
    ]:
        key = asyncssh.generate_private_key(algorithm, **options)
        key_path = root / f'client-{name}.key'
        key_path.write_bytes(key.export_private_key('openssh'))
        key_path.chmod(0o600)
        (root / f'client-{name}.pub').write_bytes(key.export_public_key())
        clients[name] = str(key_path)
        if name == 'rsa':
            encrypted = root / 'client-rsa-encrypted.key'
            encrypted.write_bytes(key.export_private_key('openssh', passphrase='e2-fixture-passphrase'))
            encrypted.chmod(0o600)
            clients['rsaEncrypted'] = str(encrypted)
    hosts = []
    fingerprints = []
    for i in range(2):
        key = asyncssh.generate_private_key('ssh-ed25519')
        path = root / f'host-{i}.key'
        path.write_bytes(key.export_private_key('openssh'))
        path.chmod(0o600)
        hosts.append(str(path))
        fingerprints.append(key.get_fingerprint('sha256'))
    config = {'username': 'amber-fixture', 'password': secrets.token_hex(24),
              'clients': clients, 'hosts': hosts, 'fingerprints': fingerprints}
    config_path.write_text(json.dumps(config, indent=2))
    config_path.chmod(0o600)
    (root / 'work').mkdir(exist_ok=True)
    return config


class Fixture:
    def __init__(self, root: Path, config: dict):
        self.root = root
        self.config = config
        self.counter = 0
        self.public_keys = [asyncssh.read_public_key(str(root / f'client-{name}.pub'))
                            for name in ('ed25519', 'rsa', 'ecdsa')]

    def log(self, event: str, **fields):
        self.counter += 1
        record = {'seq': self.counter, 'time': time.time(), 'event': event, **fields}
        with (self.root / 'events.jsonl').open('a') as out:
            out.write(json.dumps(record, ensure_ascii=False) + '\n')


class ShellSession(asyncssh.SSHServerSession):
    def __init__(self, fixture: Fixture):
        self.fixture = fixture
        self.channel = None
        self.command = None
        self.term = None
        self.size = (80, 24, 0, 0)
        self.process = None
        self.master = None
        self.input_fd = None
        self.read_fds = []
        self.pending = bytearray()
        self.closed = False

    def connection_made(self, channel):
        self.channel = channel

    def pty_requested(self, term_type, term_size, term_modes):
        self.term = term_type
        self.size = term_size
        return True

    def terminal_size_changed(self, width, height, pixwidth, pixheight):
        self.size = (width, height, pixwidth, pixheight)
        self.fixture.log('resize', columns=width, rows=height)
        if self.master is not None:
            fcntl.ioctl(self.master, termios.TIOCSWINSZ,
                        struct.pack('HHHH', height, width, pixheight, pixwidth))

    def exec_requested(self, command):
        self.command = command
        return True

    def shell_requested(self):
        return True

    def session_started(self):
        asyncio.create_task(self.run())

    async def run(self):
        env = {'PATH': '/usr/bin:/bin:/usr/sbin:/sbin:/opt/homebrew/bin',
               'LANG': 'en_US.UTF-8', 'TERM': self.term or 'xterm-256color',
               'PS1': 'e2-fixture$ '}
        argv = ['/bin/sh', '-c', self.command] if self.command is not None else ['/bin/sh', '-i']
        options = {'cwd': str(self.fixture.root / 'work'), 'env': env}
        if self.term:
            self.master, slave = os.openpty()
            width, height, pw, ph = self.size
            fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', height, width, ph, pw))

            def setup_tty():
                os.setsid()
                fcntl.ioctl(slave, termios.TIOCSCTTY, 0)

            self.process = subprocess.Popen(argv, stdin=slave, stdout=slave, stderr=slave,
                                            preexec_fn=setup_tty, **options)
            os.close(slave)
            self.input_fd = self.master
            self.add_output(self.master, False)
        else:
            self.process = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                            stderr=subprocess.PIPE, start_new_session=True, **options)
            self.input_fd = self.process.stdin.fileno()
            self.add_output(self.process.stdout.fileno(), False)
            self.add_output(self.process.stderr.fileno(), True)
        os.set_blocking(self.input_fd, False)
        self.fixture.log('process_start', pid=self.process.pid, command=self.command,
                         pty=bool(self.term), columns=self.size[0], rows=self.size[1])
        if self.pending:
            self.flush_input()
        code = await asyncio.to_thread(self.process.wait)
        # Deliver final buffered stdout/stderr before the SSH exit notification.
        for fd, stderr in list(self.read_fds):
            self.read_output(fd, stderr)
        self.fixture.log('process_exit', pid=self.process.pid, exit_code=code)
        self.cleanup()
        if not self.closed:
            self.channel.exit(code if code >= 0 else 128 - code)

    def add_output(self, fd, stderr):
        os.set_blocking(fd, False)
        self.read_fds.append((fd, stderr))
        asyncio.get_running_loop().add_reader(fd, self.read_output, fd, stderr)

    def read_output(self, fd, stderr):
        while True:
            try:
                data = os.read(fd, 1024)
            except BlockingIOError:
                return
            except OSError:
                data = b''  # PTY EIO is the terminal's EOF on macOS.
            if not data:
                asyncio.get_running_loop().remove_reader(fd)
                return
            if not self.closed:
                if stderr:
                    self.channel.write_stderr(data)
                else:
                    self.channel.write(data)

    def data_received(self, data, datatype):
        self.pending.extend(data)
        self.flush_input()

    def flush_input(self):
        if self.input_fd is None or self.closed:
            return
        while self.pending:
            try:
                written = os.write(self.input_fd, self.pending)
            except BlockingIOError:
                asyncio.get_running_loop().add_writer(self.input_fd, self.flush_input)
                return
            except OSError:
                self.pending.clear()
                break
            del self.pending[:written]
        asyncio.get_running_loop().remove_writer(self.input_fd)

    def signal_received(self, name):
        self.fixture.log('signal', signal=name)
        if self.process and self.process.poll() is None:
            code = getattr(signal, 'SIG' + name, None)
            if code is not None:
                os.killpg(self.process.pid, code)

    def cleanup(self):
        loop = asyncio.get_running_loop()
        for fd, _ in self.read_fds:
            loop.remove_reader(fd)
        if self.input_fd is not None:
            loop.remove_writer(self.input_fd)
        if self.master is not None:
            os.close(self.master)
            self.master = None

    def connection_lost(self, exc):
        self.closed = True
        if self.process and self.process.poll() is None:
            os.killpg(self.process.pid, signal.SIGTERM)
        self.cleanup()


class TestServer(asyncssh.SSHServer):
    def __init__(self, fixture):
        self.fixture = fixture

    def connection_made(self, connection):
        self.fixture.log('connection')

    def begin_auth(self, username):
        self.fixture.log('auth_begin', username=username)
        return True

    def password_auth_supported(self):
        return True

    def public_key_auth_supported(self):
        return True

    def validate_password(self, username, password):
        ok = username == self.fixture.config['username'] and secrets.compare_digest(
            password, self.fixture.config['password'])
        self.fixture.log('password_auth', accepted=ok)
        return ok

    def validate_public_key(self, username, key):
        ok = username == self.fixture.config['username'] and any(
            key == known for known in self.fixture.public_keys)
        self.fixture.log('public_key_auth', accepted=ok, algorithm=key.get_algorithm())
        return ok

    def session_requested(self):
        return ShellSession(self.fixture)


async def serve(args):
    root = Path(args.directory)
    config = initialize(root)
    fixture = Fixture(root, config)
    listener = await asyncssh.create_server(lambda: TestServer(fixture), args.bind, args.port,
                                            server_host_keys=[config['hosts'][args.host_key]],
                                            encoding=None, line_editor=False)
    fixture.log('listening', bind=args.bind, port=args.port, host_key=args.host_key)
    print(json.dumps({'port': args.port, 'fingerprint': config['fingerprints'][args.host_key],
                      'config': str(root / 'fixture.json')}, ensure_ascii=False), flush=True)
    async with listener:
        await listener.wait_closed()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', default='/tmp/e2-ssh-fixture')
    parser.add_argument('--bind', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=22224)
    parser.add_argument('--host-key', type=int, choices=(0, 1), default=0)
    asyncio.run(serve(parser.parse_args()))
