#!/usr/bin/env python3
"""Loopback-only SSH jump fixture; generated test identities, no user accounts."""
import asyncio
import json
import signal
import sys
from pathlib import Path
import asyncssh

root = Path(sys.argv[1])
public_key = asyncssh.read_public_key(str(root / 'client.pub'))
events = root / 'events.jsonl'

def log(hop, event):
    with events.open('a') as out:
        out.write(json.dumps({'hop': hop, 'event': event}) + '\n')

class Server(asyncssh.SSHServer):
    def __init__(self, hop, destinations):
        self.hop = hop
        self.destinations = destinations
    def connection_made(self, conn):
        log(self.hop, 'connected')
    def connection_lost(self, exc):
        log(self.hop, 'disconnected')
    def begin_auth(self, username):
        log(self.hop, 'auth')
        return True
    def password_auth_supported(self):
        return self.hop == 'target'
    def public_key_auth_supported(self):
        return True
    def validate_password(self, username, password):
        return username == 'fixture' and password == 'fixture-only-password'
    def validate_public_key(self, username, key):
        return username == 'fixture' and key == public_key
    def connection_requested(self, dest_host, dest_port, orig_host, orig_port):
        log(self.hop, 'forward')
        if (dest_host, dest_port) not in self.destinations:
            raise asyncssh.ChannelOpenError(asyncssh.OPEN_ADMINISTRATIVELY_PROHIBITED, 'Administratively prohibited')
        return True
    def session_requested(self):
        log(self.hop, 'session')
        return Session() if self.hop == 'target' else False

class Session(asyncssh.SSHServerSession):
    def connection_made(self, channel):
        self.channel = channel
        self.command = ''
    def exec_requested(self, command):
        self.command = command
        return True
    def session_started(self):
        if self.command == 'sleep':
            return
        if self.command == 'large':
            self.channel.write(b'x' * 400000)
        else:
            self.channel.write(b'ok\n')
        self.channel.exit(0)

async def main():
    listeners = []
    endpoints = []
    config = {}
    for hop in ['target', 'jump3', 'jump2', 'jump1']:
        key = asyncssh.generate_private_key('ssh-ed25519')
        destinations = endpoints[:]
        listener = await asyncssh.create_server(lambda h=hop, d=destinations: Server(h, d),
            '127.0.0.1', 0, server_host_keys=[key], encoding=None)
        listeners.append(listener)
        port = listener.get_port()
        endpoints.append(('127.0.0.1', port))
        config[hop] = {'host': '127.0.0.1', 'port': port, 'fingerprint': key.get_fingerprint('sha256')}
    print(json.dumps(config), flush=True)
    done = asyncio.Event()
    asyncio.get_running_loop().add_signal_handler(signal.SIGTERM, done.set)
    await done.wait()
    for listener in listeners:
        listener.close()
    await asyncio.gather(*(listener.wait_closed() for listener in listeners))

asyncio.run(main())
