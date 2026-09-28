'use strict';
const assert = require('node:assert/strict');
const {Worker, isMainThread, parentPort, workerData} = require('node:worker_threads');
const net = require('node:net');
const fs = require('node:fs');
const path = require('node:path');
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
if (!isMainThread) {
  const addon = require('../build/host/amber_ssh_host.node');
  if (workerData.mode === 'pending') {
    addon.sshProbe(`pending-${workerData.id}`, {host:'127.0.0.1',port:workerData.port,timeoutMs:300000}).catch(() => {});
    parentPort.postMessage('ready');
  } else {
    const fixture = JSON.parse(fs.readFileSync(path.join(workerData.directory, 'fixture.json')));
    addon.sshStartPty(`pty-${workerData.id}`, {host:'127.0.0.1',port:workerData.port,
      username:fixture.username,expectedFingerprintSHA256:fixture.fingerprints[0],authMethod:'password',
      secret:fixture.password,passphrase:null,connectTimeoutMs:10000}, {term:'xterm-256color',columns:80,rows:24})
      .then(() => parentPort.postMessage('ready')).catch(error => parentPort.postMessage(error.code));
  }
  setInterval(() => {}, 1000);
} else {
  (async () => {
    const sockets = new Set();
    const server = net.createServer(socket => {sockets.add(socket); socket.on('close', () => sockets.delete(socket));});
    await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
    try {
      for (const mode of ['pending','live']) {
        for (let i=0;i<10;i++) {
          const worker = new Worker(__filename,{workerData:{mode,id:i,
            port:mode==='pending'?server.address().port:22224,directory:'/tmp/e2-ssh-fixture'}});
          const ready=await new Promise((resolve,reject)=>{worker.once('message',resolve);worker.once('error',reject);});
          assert.equal(ready,'ready'); const before=Date.now(); await worker.terminate(); assert(Date.now()-before<1000);
        }
        console.log(`PASS ten NAPI env terminations with ${mode === 'pending' ? 'pending handshake' : 'live PTY owner'}`);
      }
      await sleep(100); assert.equal(sockets.size,0); console.log('PASS all stalled handshake sockets released');
    } finally {for (const socket of sockets) socket.destroy();await new Promise(resolve => server.close(resolve));}
  })().catch(error=>{console.error(error);process.exitCode=1;});
}
