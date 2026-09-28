'use strict';
const fs=require('node:fs'),assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const path=require('node:path');
const addon=require('../build/host/amber_ssh_host.node');
const cfg=JSON.parse(fs.readFileSync('/tmp/e2-ssh-fixture/fixture.json'));
const python=process.env.AMBER_SSH_FIXTURE_PYTHON||'/tmp/e2-ssh-fixture-py312/bin/python';
const server=spawn(python,[path.join(__dirname,'no_status_fixture.py')],{stdio:['ignore','pipe','inherit']});
(async()=>{
 const port=await new Promise((resolve,reject)=>{server.stdout.once('data',d=>resolve(JSON.parse(d).port));server.once('error',reject);});
 try {
  for (const command of ['fixture-no-exit-status','fixture-full-exit-status']) {
  const handle=await addon.sshStartExec(command,{host:'127.0.0.1',port,
   username:cfg.username,expectedFingerprintSHA256:cfg.fingerprints[0],authMethod:'password',
   secret:cfg.password,passphrase:null,connectTimeoutMs:5000},{command,timeoutMs:5000});
  let packet,text='';const until=Date.now()+5000;
  do{packet=addon.sshRead(handle,65536);for(const chunk of packet.chunks)text+=Buffer.from(chunk.bytes).toString();
   if(packet.state==='running')await new Promise(r=>setTimeout(r,5));
  }while(packet.state==='running'&&Date.now()<until);
  await addon.sshClose(handle,'release');
  assert.equal(text,'output-without-exit-status\n');
  console.log('OBSERVED',packet.state,'exitCode='+packet.exitCode);
  if (command === 'fixture-no-exit-status') {
   assert.equal(packet.state,'failed');assert.equal(packet.exitCode,null);assert.equal(packet.errorCode,'channel_error');
   console.log('PASS real EOF/close without exit-status is unknown failure, never exited 0');
  } else {
   assert.equal(packet.state,'exited');assert.equal(packet.exitCode,0xffffffff);assert.equal(packet.errorCode,null);
   console.log('PASS complete unsigned 32-bit exit status is preserved without sentinel collision');
  }
  }
 }finally{server.kill('SIGTERM');}
})().catch(e=>{console.error(e.message);process.exitCode=1;server.kill('SIGTERM');});
