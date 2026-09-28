#!/usr/bin/env python3
"""Independent real SSH fixture: closes a channel without exit-status."""
import asyncio
import json
from pathlib import Path
import secrets
import asyncssh
from asyncssh.packet import UInt32
config = json.loads(Path('/tmp/e2-ssh-fixture/fixture.json').read_text())
class Session(asyncssh.SSHServerSession):
    def connection_made(self, channel):
        self.channel = channel
    def exec_requested(self, command):
        self.command = command
        return command in ('fixture-no-exit-status', 'fixture-full-exit-status')
    def pty_requested(self, term_type, term_size, term_modes):
        return True
    def shell_requested(self):
        self.command = 'fixture-blocked-write'
        return True
    def session_started(self):
        if self.command == 'fixture-blocked-write':
            # Stop consumption/window adjustment so the actual peer's 16KiB
            # receive window fills and libssh2 writes return EAGAIN.
            # AsyncSSH resumes the channel after session_started() returns.
            asyncio.get_running_loop().call_soon(self.channel.pause_reading)
            self.channel.write(b'blocked-fixture-ready\r\n')
            return
        self.channel.write(b'output-without-exit-status\n')
        self.channel.write_eof()
        if self.command == 'fixture-full-exit-status':
            # AsyncSSH's public exit() truncates to shell's 8-bit status. Send
            # the actual RFC4254 uint32 packet to test every protocol bit.
            self.channel._send_request(b'exit-status', UInt32(0xffffffff))
            self.channel.close()
        else:
            self.channel.close()  # Deliberately no channel.exit()/exit-status request.
class Server(asyncssh.SSHServer):
    def begin_auth(self, username):
        return True
    def password_auth_supported(self):
        return True
    def validate_password(self, username, password):
        return username == config['username'] and secrets.compare_digest(password, config['password'])
    def session_requested(self):
        return Session()
async def main():
    listener = await asyncssh.create_server(Server, '127.0.0.1', 0,
        server_host_keys=[config['hosts'][0]], encoding=None, window=16384)
    print(json.dumps({'port': listener.get_port()}), flush=True)
    async with listener:
        await listener.wait_closed()
asyncio.run(main())
