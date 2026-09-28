'use strict';
const fs=require('node:fs'),assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const path=require('node:path');
const addon=require('../build/host/amber_ssh_host.node');
const cfg=JSON.parse(fs.readFileSync('/tmp/e2-ssh-fixture/fixture.json'));
const python=process.env.AMBER_SSH_FIXTURE_PYTHON||'/tmp/e2-ssh-fixture-py312/bin/python';
const server=spawn(python,[path.join(__dirname,'no_status_fixture.py')],{stdio:['ignore','pipe','inherit']});
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
(async()=>{
 const port=await new Promise((resolve,reject)=>{server.stdout.once('data',d=>resolve(JSON.parse(d).port));server.once('error',reject);});
 try {
  for(const reason of ['cancelled','disconnected']){
   const handle=await addon.sshStartPty('blocked-'+reason,{host:'127.0.0.1',port,
    username:cfg.username,expectedFingerprintSHA256:cfg.fingerprints[0],authMethod:'password',
    secret:cfg.password,passphrase:null,connectTimeoutMs:5000},{term:'xterm-256color',columns:80,rows:24});
   let text='',deadline=Date.now()+3000;
   while(!text.includes('blocked-fixture-ready')){
    const p=addon.sshRead(handle,65536);for(const c of p.chunks)text+=Buffer.from(c.bytes).toString();
    assert.equal(p.state,'running');assert(Date.now()<deadline);await sleep(5);
   }
   let writeSettled=false,writeError;
   const writing=addon.sshWrite(handle,new Uint8Array(1024*1024-1).fill(65)).then(
    ()=>{writeSettled=true;throw new Error('fixture unexpectedly consumed blocked PTY input');},
    error=>{writeSettled=true;writeError=error;});
   await sleep(100);assert.equal(writeSettled,false,'real write must be stalled by peer receive window');
   let pendingSettled=false,pendingError;
   const pending=addon.sshWrite(handle,new Uint8Array(1)).then(
    ()=>{pendingSettled=true;throw new Error('queued write unexpectedly completed');},
    error=>{pendingSettled=true;pendingError=error;});
   await sleep(50);assert.equal(pendingSettled,false,'queued writer must still be pending before close');
   const before=Date.now();
   const packet=await addon.sshClose(handle,reason);
   await Promise.race([Promise.all([writing,pending]),sleep(1000).then(()=>{throw new Error('close did not settle blocked writers');})]);
   assert(Date.now()-before<1000);assert.equal(packet.state,reason);assert.equal(packet.exitCode,null);
   assert.equal(writeError.code,reason==='cancelled'?'cancelled':'network_error');
   assert.equal(pendingError.code,'channel_error');assert.equal(pendingSettled,true);
   assert.throws(()=>addon.sshRead(handle,65536),{code:'unknown_handle'});
   console.log(`PASS real 16KiB peer window stalls 1048575-byte write; close(${reason}) wakes active/queued writers and returns ${reason}/null in <1s`);
  }
 }finally{server.kill('SIGTERM');}
})().catch(e=>{console.error(e);process.exitCode=1;server.kill('SIGTERM');});
