'use strict';
// Local fixture cancellation while its terminal input is paused.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {spawn} = require('node:child_process');
const path = require('node:path');
const addon = require('../build/host/amber_ssh_host.node');
const config = JSON.parse(fs.readFileSync('/tmp/e2-ssh-fixture/fixture.json'));
const server = spawn(process.env.AMBER_SSH_FIXTURE_PYTHON || '/tmp/e2-ssh-fixture-py312/bin/python',
  [path.join(__dirname, 'no_status_fixture.py')], {stdio:['ignore','pipe','inherit']});
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
(async () => {
  const port = await new Promise((resolve,reject) => {
    server.stdout.once('data',data => resolve(JSON.parse(data).port)); server.once('error',reject);
  });
  try {
    for (const reason of ['cancelled','disconnected']) {
      const handle = await addon.sshStartPty('close-'+reason, {
        host:'127.0.0.1', port, username:config.username, expectedFingerprintSHA256:config.fingerprints[0],
        authMethod:'password', secret:config.password, passphrase:null, connectTimeoutMs:5000,
      }, {term:'xterm-256color',columns:80,rows:24});
      let writing, settled = true;
      for (let batch = 0; batch < 8 && settled; batch++) {
        settled = false;
        writing = addon.sshWrite(handle,new Uint8Array(1048576).fill(65)).then(
          () => {settled=true; return 'resolved';}, error => {settled=true; return error.code;});
        await delay(50);
      }
      assert.equal(settled,false,'paused peer leaves terminal input pending within the fixed test bound');
      const packet = await Promise.race([addon.sshClose(handle,reason),
        delay(1000).then(() => {throw new Error('Close did not wake the local owner');})]);
      assert.equal(packet.state,reason); assert.equal(packet.exitCode,null);
      assert.equal(await writing,reason === 'cancelled' ? 'cancelled' : 'network_error');
      console.log('PASS pending terminal input closes promptly: '+reason);
    }
  } finally {server.kill('SIGTERM');}
})().catch(error => {console.error(error.message);process.exitCode=1;server.kill('SIGTERM');});
