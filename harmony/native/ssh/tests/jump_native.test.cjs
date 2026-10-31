const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawn, execFileSync } = require('node:child_process');
const { once } = require('node:events');
const { createInterface } = require('node:readline');
const addon = require('../build/host/amber_ssh_host.node');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
let request = 0;
const id = () => `jump-test-${++request}`;

test('real native keys, jump chain, pre-auth pinning and close', { timeout: 45000 }, async t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'amber-ssh-jump-'));
  const generated = await addon.sshGenerateKey({ name: 'fixture', type: 'ed25519' });
  fs.writeFileSync(path.join(root, 'client.pub'), generated.publicKey);
  fs.writeFileSync(path.join(root, 'encrypted.key'), generated.privateKey, { mode: 0o600 });
  execFileSync('/usr/bin/ssh-keygen', ['-p', '-P', '', '-N', 'fixture-only-passphrase', '-f', path.join(root, 'encrypted.key')], { stdio: 'ignore' });
  const server = spawn(process.env.AMBER_SSH_FIXTURE_PYTHON || '/tmp/e2-ssh-fixture-py312/bin/python',
    [path.join(__dirname, 'jump_fixture.py'), root], { stdio: ['ignore', 'pipe', 'pipe'] });
  let stderr = '';
  server.stderr.on('data', chunk => { stderr += chunk; });
  const lines = createInterface({ input: server.stdout });
  try {
    const [line] = await Promise.race([once(lines, 'line'), once(server, 'exit').then(() => { throw Error(stderr); })]);
    const config = JSON.parse(line);
    const endpoint = name => config[name];
    const hop = name => ({ profileId: name, ...endpoint(name), username: 'fixture',
      expectedFingerprintSHA256: endpoint(name).fingerprint, authMethod: 'privateKey', secret: generated.privateKey, passphrase: null });
    const connection = (names = ['jump1'], authMethod = 'password') => ({ ...hop('target'),
      authMethod, secret: authMethod === 'password' ? 'fixture-only-password' : generated.privateKey,
      connectTimeoutMs: 5000, jumpHops: names.map(hop) });
    const events = () => fs.existsSync(path.join(root, 'events.jsonl'))
      ? fs.readFileSync(path.join(root, 'events.jsonl'), 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse) : [];
    async function execute(conn, command = 'echo ok') {
      const handle = await addon.sshStartExec(id(), conn, { command, timeoutMs: 5000 });
      let output = '';
      try {
        for (let i = 0; i < 500; i++) {
          const packet = addon.sshRead(handle, 65536);
          output += packet.chunks.map(chunk => Buffer.from(chunk.bytes).toString()).join('');
          if (packet.state !== 'running') { assert.equal(packet.exitCode, 0); return output; }
          await delay(10);
        }
        throw Error('native command did not settle');
      } finally { await addon.sshClose(handle, 'release'); }
    }
    await t.test('Ed25519 and P-256 public keys match ssh-keygen; encrypted import checks passphrase', async () => {
      for (const type of ['ed25519', 'ecdsa-p256']) {
        const key = await addon.sshGenerateKey({ name: 'format-check', type });
        const file = path.join(root, type + '.key'); fs.writeFileSync(file, key.privateKey, { mode: 0o600 });
        const derived = execFileSync('/usr/bin/ssh-keygen', ['-y', '-P', '', '-f', file], { encoding: 'utf8' }).trim().split(' ').slice(0, 2).join(' ');
        assert.equal(key.publicKey.split(' ').slice(0, 2).join(' '), derived);
        fs.writeFileSync(file + '.pub', key.publicKey);
        assert.ok(execFileSync('/usr/bin/ssh-keygen', ['-lf', file + '.pub'], { encoding: 'utf8' }).includes(key.fingerprintSHA256));
        assert.equal((await addon.sshInspectKey({ name: 'again', privateKey: key.privateKey, passphrase: null })).fingerprintSHA256, key.fingerprintSHA256);
      }
      const privateKey = fs.readFileSync(path.join(root, 'encrypted.key'), 'utf8');
      const inspected = await addon.sshInspectKey({ name: 'encrypted', privateKey, passphrase: 'fixture-only-passphrase' });
      assert.equal(inspected.hasPassphrase, true);
      await assert.rejects(addon.sshInspectKey({ name: 'bad-pass', privateKey, passphrase: 'wrong' }), { code: 'invalid_passphrase' });
      await assert.rejects(addon.sshInspectKey({ name: 'missing', privateKey, passphrase: null }), { code: 'passphrase_required' });
    });
    await t.test('authentication-only verification works on a shell-denied bastion', async () => {
      const conn = { ...hop('jump1'), connectTimeoutMs: 5000 };
      const before = events().filter(e => e.hop === 'jump1' && e.event === 'session').length;
      await addon.sshVerify(id(), conn);
      assert.equal(events().filter(e => e.hop === 'jump1' && e.event === 'session').length, before);
      await assert.rejects(addon.sshStartExec(id(), conn, { command: 'id', timeoutMs: 5000 }), { code: 'channel_error' });
    });
    await t.test('one to three jump hosts, key/password targets and large output', async () => {
      for (const names of [['jump1'], ['jump1', 'jump2'], ['jump1', 'jump2', 'jump3']]) {
        for (const auth of ['password', 'privateKey']) assert.equal(await execute(connection(names, auth)), 'ok\n');
      }
      assert.equal((await execute(connection(['jump1', 'jump2', 'jump3']), 'large')).length, 400000);
    });
    await t.test('second-hop pin change sends no second-hop auth and never connects target', async () => {
      const before = events().length;
      const conn = connection(['jump1', 'jump2']); conn.jumpHops[1].expectedFingerprintSHA256 = config.target.fingerprint;
      await assert.rejects(addon.sshStartExec(id(), conn, { command: 'echo forbidden', timeoutMs: 5000 }),
        { code: 'host_key_changed', hopIndex: 2, hopCount: 3, profileId: 'jump2' });
      const fresh = events().slice(before);
      assert.equal(fresh.filter(e => e.hop === 'jump2' && e.event === 'auth').length, 0);
      assert.equal(fresh.filter(e => e.hop === 'target' && e.event === 'connected').length, 0);
    });
    await t.test('forward denial names the failing hop', async () => {
      const conn = connection(); conn.host = 'localhost';
      await assert.rejects(addon.sshStartExec(id(), conn, { command: 'echo forbidden', timeoutMs: 5000 }),
        { code: 'jump_forward_denied', hopIndex: 1 });
    });
    await t.test('probe through a jump never authenticates target; cancelling a job closes all hops', async () => {
      const beforeAuth = events().filter(e => e.hop === 'target' && e.event === 'auth').length;
      const conn = connection(['jump1', 'jump2', 'jump3']);
      const probe = await addon.sshProbe(id(), { host: conn.host, port: conn.port, timeoutMs: 5000, jumpHops: conn.jumpHops });
      assert.equal(probe.fingerprintSHA256, config.target.fingerprint);
      assert.equal(events().filter(e => e.hop === 'target' && e.event === 'auth').length, beforeAuth);
      const handle = await addon.sshStartExec(id(), conn, { command: 'sleep', timeoutMs: 10000 });
      assert.equal((await addon.sshClose(handle, 'cancelled')).state, 'cancelled');
      await delay(150);
      for (const name of ['jump1', 'jump2', 'jump3', 'target']) {
        assert.equal(events().filter(e => e.hop === name && e.event === 'connected').length,
          events().filter(e => e.hop === name && e.event === 'disconnected').length, name + ' leaked a connection');
      }
    });
  } finally {
    lines.close(); if (server.exitCode === null) { server.kill('SIGTERM'); await once(server, 'exit'); }
    fs.rmSync(root, { recursive: true, force: true });
  }
});
