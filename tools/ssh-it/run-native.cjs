const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const addon = require('../../harmony/native/ssh/build/host/amber_ssh_host.node');
const compose = path.join(__dirname, 'compose.yml');
const key = fs.readFileSync(path.join(__dirname, 'fixtures/client'), 'utf8');
const fingerprint = service => execFileSync('docker', ['compose', '-f', compose, 'exec', '-T', service,
  'ssh-keygen', '-lf', '/etc/ssh/ssh_host_ed25519_key.pub'], { encoding: 'utf8' }).trim().split(/\s+/)[1];
let next = 0;
const hop = (service, host, port) => ({ profileId: service, host, port, username: 'jumper',
  expectedFingerprintSHA256: fingerprint(service), authMethod: 'privateKey', secret: key, passphrase: null });
(async () => {
  const jump = hop('jump', '127.0.0.1', 2222);
  await addon.sshVerify('docker-auth', { ...jump, connectTimeoutMs: 10000 });
  const denied = await addon.sshStartExec('docker-denied-shell', { ...jump, connectTimeoutMs: 10000 },
    { command: 'id', timeoutMs: 10000 });
  try {
    for (;;) {
      const packet = addon.sshRead(denied, 65536);
      if (packet.state !== 'running') { assert.notEqual(packet.exitCode, 0); break; }
      await new Promise(r => setTimeout(r, 20));
    }
  } finally { await addon.sshClose(denied, 'release'); }
  for (const route of [[jump], [jump, hop('jump2', 'jump2', 22)]]) {
    for (const auth of ['password', 'privateKey']) {
      const conn = { profileId: 'target', host: 'target', port: 22, username: 'deploy',
        expectedFingerprintSHA256: fingerprint('target'), authMethod: auth,
        secret: auth === 'password' ? 'fixture-only-password' : key, passphrase: null,
        connectTimeoutMs: 10000, jumpHops: route };
      const handle = await addon.sshStartExec('docker-' + ++next, conn, { command: 'echo ok', timeoutMs: 10000 });
      let text = '';
      try {
        for (;;) {
          const packet = addon.sshRead(handle, 65536);
          text += packet.chunks.map(c => Buffer.from(c.bytes).toString()).join('');
          if (packet.state !== 'running') { assert.equal(packet.exitCode, 0); break; }
          await new Promise(r => setTimeout(r, 20));
        }
        assert.equal(text.trim(), 'ok');
      } finally { await addon.sshClose(handle, 'release'); }
      await assert.rejects(addon.sshStartExec('docker-pin-' + ++next,
        { ...conn, expectedFingerprintSHA256: jump.expectedFingerprintSHA256 }, { command: 'echo forbidden', timeoutMs: 10000 }),
        { code: 'host_key_changed', hopIndex: route.length + 1 });
    }
  }
  console.log('Docker: shell-denied bastion, one/two jumps, key/password target and pin blocking passed');
})().catch(error => { console.error(error.code || error.message); process.exitCode = 1; });
