'use strict';
const {test, after} = require('node:test');
const assert = require('node:assert/strict');
const {Worker} = require('node:worker_threads');
const {spawnSync} = require('node:child_process');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const native = require(root + '/build/host/amber_mosh_host.node');
const servers = new Set();
const handles = new Set();
function options(port) {
    const server = root + '/build/host/official-package/edu.mit.mosh.mosh.pkg/Payload/local/bin/mosh-server';
    const result = spawnSync(server, ['new','-s','-i','127.0.0.1','-p',String(port),'-l','LANG=en_US.UTF-8','-l','LC_ALL=en_US.UTF-8','--','/bin/sh'], {encoding:'utf8',env:{...process.env,LANG:'en_US.UTF-8',LC_ALL:'en_US.UTF-8'}});
    assert.equal(result.status,0); const match = result.stdout.match(/MOSH CONNECT (\d+) ([A-Za-z0-9+/]{22})/); assert.ok(match);
    const pid = (result.stdout+result.stderr).match(/mosh-server detached, pid = (\d+)/); if(pid) servers.add(+pid[1]);
    return {peerAddress:'127.0.0.1',port:+match[1],sessionKey:match[2],columns:80,rows:24,connectTimeoutMs:3000};
}
function worker(input) {
    return new Worker(`const {parentPort,workerData}=require('node:worker_threads'); const n=require(workerData.addon); const p=n.moshStart('worker',workerData.options); parentPort.postMessage('registered'); p.then(h=>parentPort.postMessage('connected'));`, {eval:true,workerData:{addon:root+'/build/host/amber_mosh_host.node',options:input}});
}
function message(w, expected) { return new Promise((resolve,reject)=>{ w.on('error',reject); w.on('message',value=>{if(value===expected)resolve();}); }); }
after(async()=>{for(const h of handles){try{await native.moshClose(h,'release');}catch{}}for(const pid of servers){try{process.kill(pid,'SIGTERM');}catch{}}});
test('20 environments terminated with pending UDP requests do not hang or poison owner', async()=>{
    for(let i=0;i<20;i++) { const w=worker({peerAddress:'127.0.0.1',port:65500,sessionKey:'AAAAAAAAAAAAAAAAAAAAAA',columns:80,rows:24,connectTimeoutMs:60000}); await message(w,'registered'); const begin=Date.now(); await w.terminate(); assert.ok(Date.now()-begin<1000); }
    const h=await native.moshStart('after-env',options(61501)); handles.add(h); await native.moshWrite(h,Buffer.from("printf 'ENV-OWNER-OK\\n'\n"));
    let text='';for(let i=0;i<100;i++){text+=Buffer.from(native.moshRead(h,1048576).bytes).toString();if(text.includes('ENV-OWNER-OK\r\n'))break;await new Promise(r=>setTimeout(r,20));}
    assert.match(text,/ENV-OWNER-OK\r\n/);await native.moshClose(h,'release');handles.delete(h);
});
test('one active worker environment cleanup preserves another environment SSP owner/session', async()=>{
    const own=await native.moshStart('parent-live',options(61502));handles.add(own);
    const w=worker(options(61503));await message(w,'connected');await w.terminate();
    await native.moshWrite(own,Buffer.from("printf 'OTHER-ENV-SURVIVES\\n'\n"));let text='';
    for(let i=0;i<100;i++){text+=Buffer.from(native.moshRead(own,1048576).bytes).toString();if(text.includes('OTHER-ENV-SURVIVES\r\n'))break;await new Promise(r=>setTimeout(r,20));}
    assert.match(text,/OTHER-ENV-SURVIVES\r\n/);await native.moshClose(own,'release');handles.delete(own);
});
test('worker-only addon load/unload cannot destroy the owner on itself', () => {
    // Parent in this subprocess never loads the addon: no parent environment
    // holds an Engine reference. This reproduces the independent review abort.
    const source = `const {Worker}=require('node:worker_threads');
      (async()=>{for(let i=0;i<2;i++){
        const w=new Worker(\`const {parentPort,workerData}=require('node:worker_threads');
          const n=require(workerData); n.moshStart('pending',{peerAddress:'127.0.0.1',port:65500,
          sessionKey:'AAAAAAAAAAAAAAAAAAAAAA',columns:80,rows:24,connectTimeoutMs:60000}); parentPort.postMessage('ready');\`,
          {eval:true,workerData:process.argv[1]});
        await new Promise((r,j)=>{w.once('message',r);w.once('error',j)}); await w.terminate();
      }console.log('worker-only-lifetime-pass');})().catch(e=>{console.error(e.code||'failed');process.exit(1)});`;
    const result = spawnSync(process.execPath, ['-e',source,root+'/build/host/amber_mosh_host.node'], {encoding:'utf8',timeout:15000});
    assert.equal(result.signal,null);assert.equal(result.status,0,result.stderr);
    assert.match(result.stdout,/worker-only-lifetime-pass/);
});
