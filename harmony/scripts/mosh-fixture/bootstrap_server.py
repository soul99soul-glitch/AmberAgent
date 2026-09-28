#!/usr/bin/env python3
"""Generated-identity SSH fixture which launches the real, locally built mosh-server.

Never records MOSH CONNECT output or session keys. Runs separately from the E2 server.
"""
import argparse
import asyncio
import importlib.util
from pathlib import Path
import shlex
import sys

source = Path(__file__).resolve().parents[1] / 'ssh-fixture' / 'fixture_server.py'
spec = importlib.util.spec_from_file_location('amber_ssh_fixture', source)
ssh_fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ssh_fixture)

class MoshSession(ssh_fixture.ShellSession):
    def __init__(self, fixture, server):
        super().__init__(fixture)
        self.server = server

    def exec_requested(self, command):
        if 'mosh-server' in command:
            command = command.replace('mosh-server', shlex.quote(str(self.server)), 1)
        return super().exec_requested(command)

class MoshSSHServer(ssh_fixture.TestServer):
    def __init__(self, fixture, server):
        super().__init__(fixture)
        self.server = server

    def session_requested(self):
        return MoshSession(self.fixture, self.server)

async def serve(args):
    server = Path(args.server).resolve()
    if not server.is_file():
        raise RuntimeError('Build the real Mosh host server first')
    root = Path(args.directory)
    config = ssh_fixture.initialize(root)
    fixture = ssh_fixture.Fixture(root, config)
    listener = await ssh_fixture.asyncssh.create_server(
        lambda: MoshSSHServer(fixture, server), args.bind, args.port,
        server_host_keys=[config['hosts'][0]], encoding=None, line_editor=False)
    fixture.log('listening', bind=args.bind, port=args.port, host_key=0)
    print('Mosh fixture ready; generated identities remain in private fixture files', flush=True)
    async with listener:
        await listener.wait_closed()

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--directory', default='/tmp/e3-mosh-fixture')
    parser.add_argument('--bind', default='0.0.0.0')
    parser.add_argument('--port', type=int, default=22225)
    parser.add_argument('--server', required=True)
    asyncio.run(serve(parser.parse_args()))
