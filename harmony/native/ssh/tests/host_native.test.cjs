'use strict';
// Run against the generated local fixture. This exercises the real NAPI binding + libssh2 + OpenSSL.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const addon = require('../build/host/amber_ssh_host.node');
const directory = process.env.AMBER_SSH_FIXTURE_DIR || '/tmp/e2-ssh-fixture';
const fixture = JSON.parse(fs.readFileSync(path.join(directory, 'fixture.json'), 'utf8'));
const port = Number(process.env.AMBER_SSH_FIXTURE_PORT || 22224);
const pin = fixture.fingerprints[Number(process.env.AMBER_SSH_FIXTURE_HOST_KEY || 0)];
let id = 0;
const request = () => `host-test-${process.pid}-${++id}`;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const events = () => fs.readFileSync(path.join(directory, 'events.jsonl'), 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
const authCount = () => events().filter(x => x.event.endsWith('_auth') || x.event === 'auth_begin').length;
const connection = (extra = {}) => ({host: '127.0.0.1', port, username: fixture.username,
  expectedFingerprintSHA256: pin, authMethod: 'password', secret: fixture.password,
  passphrase: null, connectTimeoutMs: 5000, ...extra});
const startExec = (command, timeoutMs = 10000, extra = {}) => addon.sshStartExec(request(), connection(extra), {command, timeoutMs});
async function drain(handle, maxBytes = 65536, timeout = 12000) {
  const stdout = [], stderr = [], packets = [];
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const packet = addon.sshRead(handle, maxBytes); packets.push(packet);
    let bytes = 0;
    for (const chunk of packet.chunks) {
      assert(chunk.bytes instanceof Uint8Array);
      (chunk.isStderr ? stderr : stdout).push(Buffer.from(chunk.bytes)); bytes += chunk.bytes.length;
    }
    assert(bytes <= maxBytes);
    if (packet.state !== 'running') return {packet, stdout: Buffer.concat(stdout), stderr: Buffer.concat(stderr), packets};
    await sleep(5);
  }
  throw new Error('Native drain test timed out');
}
async function readUntil(handle, pattern, timeout = 3000) {
  let text = ''; const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const packet = addon.sshRead(handle, 65536);
    for (const chunk of packet.chunks) text += Buffer.from(chunk.bytes).toString();
    if (pattern.test(text)) return text;
    assert.equal(packet.state, 'running'); await sleep(10);
  }
  throw new Error(`PTY expected pattern ${pattern}; received ${JSON.stringify(text)}`);
}
let passed = 0;
async function test(name, action) { await action(); ++passed; console.log(`PASS ${name}`); }
(async () => {
  await test('probe fingerprints and no authentication', async () => {
    const before = authCount();
    const probe = await addon.sshProbe(request(), {host: '127.0.0.1', port, timeoutMs: 5000});
    assert.equal(probe.fingerprintSHA256, pin); assert.equal(probe.hostKeyType, 'ssh-ed25519');
    await sleep(50); assert.equal(authCount(), before);
  });
  await test('wrong pin rejects before authentication', async () => {
    const before = authCount();
    await assert.rejects(startExec('echo MUST_NOT_EXECUTE', 5000, {expectedFingerprintSHA256: fixture.fingerprints[1]}),
      {code: 'host_key_mismatch'});
    await sleep(50); assert.equal(authCount(), before);
  });
  await test('password exec stdout stderr nonzero exit and three-byte read chunks', async () => {
    const handle = await startExec("printf '你好🙂\\n'; printf 'err-line\\n' >&2; exit 7");
    assert.equal(handle.peerAddress, '127.0.0.1');
    const result = await drain(handle, 3);
    assert.equal(result.stdout.toString(), '你好🙂\n'); assert.equal(result.stderr.toString(), 'err-line\n');
    assert.equal(result.packet.state, 'exited'); assert.equal(result.packet.exitCode, 7);
    const closed = await addon.sshClose(handle, 'release'); assert.equal(closed.exitCode, 7);
    assert.throws(() => addon.sshRead(handle, 1), {code: 'unknown_handle'});
  });
  await test('idle read zero is running and deadline is timed_out with null exit', async () => {
    const handle = await startExec('sleep 5', 250);
    const early = addon.sshRead(handle, 32); assert.equal(early.state, 'running'); assert.equal(early.exitCode, null);
    const result = await drain(handle); assert.equal(result.packet.state, 'timed_out'); assert.equal(result.packet.exitCode, null);
    await addon.sshClose(handle, 'release');
  });
  await test('wrong password authenticates but rejects with no command', async () => {
    await assert.rejects(startExec('echo MUST_NOT_EXECUTE', 5000, {secret: 'fixture-invalid'}), {code: 'authentication_failed'});
  });
  const keys = ['ed25519', 'rsa', 'ecdsa', 'rsaEncrypted'];
  if (process.env.AMBER_SSH_TEST_ECDSA_PEM) keys.push('ecdsaPem');
  for (const key of keys) {
    await test(`real in-memory ${key} private-key authentication`, async () => {
      const keyPath = key === 'ecdsaPem' ? process.env.AMBER_SSH_TEST_ECDSA_PEM : fixture.clients[key];
      const secret = fs.readFileSync(keyPath, 'utf8');
      if (key === 'rsa') assert(secret.length > 1024);
      const handle = await startExec('printf private-key-ok', 10000, {authMethod: 'privateKey', secret,
        passphrase: key === 'rsaEncrypted' ? 'e2-fixture-passphrase' : null});
      const result = await drain(handle); assert.equal(result.packet.exitCode, 0); assert.equal(result.stdout.toString(), 'private-key-ok');
      await addon.sshClose(handle, 'release');
    });
  }
  await test('output backpressure retains 2 MiB and read respects limit', async () => {
    const handle = await startExec("/usr/bin/yes A | /usr/bin/head -c 2097152", 20000);
    await sleep(250); const result = await drain(handle, 32768, 20000);
    assert.equal(result.stdout.length, 2097152); assert.equal(result.packet.exitCode, 0);
    await addon.sshClose(handle, 'release');
  });
  await test('active release rejects, cancellation closes with null exit', async () => {
    const handle = await startExec('sleep 30', 60000);
    await assert.rejects(addon.sshClose(handle, 'release'), {code: 'channel_error'});
    const result = await addon.sshClose(handle, 'cancelled'); assert.equal(result.state, 'cancelled'); assert.equal(result.exitCode, null);
    assert.throws(() => addon.sshRead(handle, 64), {code: 'unknown_handle'});
  });
  await test('immediate start cancellation and repeat cancel', async () => {
    const name = request(); const pending = addon.sshStartExec(name, connection(), {command: 'echo MUST_NOT_EXECUTE', timeoutMs: 10000});
    await addon.sshCancel(name); await assert.rejects(pending, {code: 'cancelled'}); await addon.sshCancel(name);
  });
  await test('handshake stalled timeout and cancel wakes poll', async () => {
    const sockets = new Set();
    const server = net.createServer(socket => {sockets.add(socket); socket.on('close', () => sockets.delete(socket));});
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const stalledPort = server.address().port;
    try {
      await assert.rejects(addon.sshProbe(request(), {host: '127.0.0.1', port: stalledPort, timeoutMs: 100}), {code: 'connection_timeout'});
      const name = request(), before = Date.now();
      const pending = addon.sshProbe(name, {host: '127.0.0.1', port: stalledPort, timeoutMs: 10000});
      await sleep(20); await addon.sshCancel(name); await assert.rejects(pending, {code: 'cancelled'});
      assert(Date.now() - before < 500);
    } finally { for (const socket of sockets) socket.destroy(); await new Promise(resolve => server.close(resolve)); }
  });
  await test('PTY initial prompt, resize, raw Ctrl-C and terminal control bytes', async () => {
    const handle = await addon.sshStartPty(request(), connection(), {term: 'xterm-256color', columns: 80, rows: 24});
    await readUntil(handle, /e2-fixture\$ /);
    await addon.sshWrite(handle, Buffer.from('stty size\n')); await readUntil(handle, /24 80/);
    await addon.sshResize(handle, 93, 31); await addon.sshWrite(handle, Buffer.from('stty size\n')); await readUntil(handle, /31 93/);
    await addon.sshWrite(handle, Buffer.from('sleep 30\n')); await sleep(50);
    await addon.sshWrite(handle, Buffer.from([3])); await readUntil(handle, /e2-fixture\$ /);
    await addon.sshWrite(handle, Buffer.from("printf '\\033[31m你好🙂\\033[0m\\033[?1049hALT\\033[?1049l\\n'\n"));
    const output = await readUntil(handle, /ALT/); assert(output.includes('\u001b[31m')); assert(output.includes('你好🙂'));
    assert(output.includes('\u001b[?1049h')); assert(output.includes('\u001b[?1049l'));
    await addon.sshWrite(handle, Buffer.from('exit\n')); const result = await drain(handle); assert.equal(result.packet.state, 'exited');
    await addon.sshClose(handle, 'release');
  });
  await test('typed handle and bad argument errors remain usable', async () => {
    await assert.rejects(addon.sshProbe(request(), {host:'127.0.0.1',port:port+0.5,timeoutMs:100}), {code:'invalid_arguments'});
    const handle = await startExec('sleep 1', 10000);
    assert.throws(() => addon.sshRead({...handle,kind:'pty'}, 32), {code:'unknown_handle'});
    await assert.rejects(addon.sshWrite(handle, Buffer.from('x')), {code:'invalid_arguments'});
    await assert.rejects(addon.sshWrite(handle, 'string'), {code:'invalid_arguments'});
    assert.throws(() => addon.sshRead(handle, 0), {code:'invalid_arguments'});
    await addon.sshClose(handle, 'disconnected');
  });
  await test('real TCP interruption is disconnected with null exit code', async () => {
    const sockets = new Set();
    const proxy = net.createServer(incoming => {
      const outgoing = net.connect(port, '127.0.0.1'); sockets.add(incoming); sockets.add(outgoing);
      incoming.pipe(outgoing); outgoing.pipe(incoming);
      incoming.on('error', () => {}); outgoing.on('error', () => {});
    });
    await new Promise(resolve => proxy.listen(0, '127.0.0.1', resolve));
    let handle;
    try {
      handle = await startExec('sleep 30', 10000, {port:proxy.address().port});
      for (const socket of sockets) socket.destroy();
      const result = await drain(handle); assert.equal(result.packet.state, 'disconnected'); assert.equal(result.packet.exitCode, null);
      await addon.sshClose(handle, 'release'); handle = null;
    } finally {
      if (handle) await addon.sshClose(handle, 'disconnected');
      for (const socket of sockets) socket.destroy(); await new Promise(resolve => proxy.close(resolve));
    }
  });
  await test('twenty sequential real connections after failures', async () => {
    for (let i = 0; i < 20; ++i) {
      const handle = await startExec('printf repeat-ok'); const result = await drain(handle);
      assert.equal(result.stdout.toString(), 'repeat-ok'); assert.equal(result.packet.exitCode, 0); await addon.sshClose(handle, 'release');
    }
  });
  console.log(`REAL NATIVE SSH: ${passed} tests passed`);
})().catch(error => {console.error(error); process.exitCode = 1;});
