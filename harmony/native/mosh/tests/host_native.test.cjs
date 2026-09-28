'use strict';
const {test, after} = require('node:test');
const assert = require('node:assert/strict');
const {spawnSync} = require('node:child_process');
const dgram = require('node:dgram');
const path = require('node:path');
const root = path.resolve(__dirname, '..');
const native = require(root + '/build/host/amber_mosh_host.node');
const server = process.env.AMBER_MOSH_SERVER || root + '/build/host/official-package/edu.mit.mosh.mosh.pkg/Payload/local/bin/mosh-server';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const handles = new Set();
const serverPids = new Set();
let nextPort = 61300;
function bootstrap() {
    const proc = spawnSync(server, ['new', '-s', '-i', '127.0.0.1', '-p', String(nextPort++), '-l', 'LANG=en_US.UTF-8', '-l', 'LC_ALL=en_US.UTF-8', '--', '/bin/sh'], {
        encoding: 'utf8', env: {...process.env, LANG: 'en_US.UTF-8', LC_ALL: 'en_US.UTF-8'}
    });
    // Bootstrap plaintext contains the ephemeral key: parse in memory, never log.
    assert.equal(proc.status, 0, 'official local mosh-server must start');
    const connect = proc.stdout.match(/MOSH CONNECT (\d+) ([A-Za-z0-9+/]{22})/);
    assert.ok(connect, 'server must emit valid connect record');
    const pid = (proc.stdout + proc.stderr).match(/mosh-server detached, pid = (\d+)/);
    if (pid) serverPids.add(+pid[1]);
    return {peerAddress: '127.0.0.1', port: +connect[1], sessionKey: connect[2], columns: 80, rows: 24, connectTimeoutMs: 4000};
}
async function start(options = bootstrap()) {
    const handle = await native.moshStart('host-' + crypto.randomUUID(), options);
    handles.add(handle); return handle;
}
async function release(handle) {
    const packet = await native.moshClose(handle, 'release');
    handles.delete(handle); return packet;
}
async function until(handle, expected, timeout = 6000) {
    const deadline = Date.now() + timeout; let text = ''; let packet;
    while (Date.now() < deadline) {
        packet = native.moshRead(handle, 1048576);
        text += Buffer.from(packet.bytes).toString('utf8');
        if (expected.test(text)) return {text, packet};
        await sleep(30);
    }
    assert.fail('expected authenticated remote terminal output did not arrive');
}
function send(handle, command) { return native.moshWrite(handle, Buffer.from(command)); }
async function proxy(port) {
    const front = dgram.createSocket('udp4'); let back = dgram.createSocket('udp4');
    let client; let captured; let drop = false; const sourcePorts = [];
    await new Promise(resolve => front.bind(0, '127.0.0.1', resolve));
    async function bindBack() {
        await new Promise(resolve => back.bind(0, '127.0.0.1', resolve));
        sourcePorts.push(back.address().port);
        back.on('message', bytes => { if (!drop && client) { captured=Buffer.from(bytes); front.send(bytes, client.port, client.address); } });
    }
    await bindBack();
    front.on('message', (bytes, rinfo) => { client = rinfo; if (!drop) back.send(bytes, port, '127.0.0.1'); });
    return {port: front.address().port, setDrop(value) { drop = value; }, sourcePorts,
        injectOversize() { assert.ok(client); front.send(Buffer.alloc(4096),client.port,client.address); },
        replayOld() { assert.ok(captured && client); const bytes=Buffer.from(captured); const timer=setInterval(()=>front.send(bytes,client.port,client.address),200); return ()=>clearInterval(timer); },
        async roam() { const old = back; back = dgram.createSocket('udp4'); await bindBack(); old.close(); },
        close() { front.close(); back.close(); }
    };
}
after(async () => {
    for (const handle of handles) { try { await release(handle); } catch {} }
    // Only PIDs emitted by this test's locally spawned servers, no process scans.
    for (const pid of serverPids) { try { process.kill(pid, 'SIGTERM'); } catch {} }
});
test('strict native argument/key/dimension validation', async () => {
    const options = {peerAddress:'127.0.0.1',port:65500,sessionKey:'AAAAAAAAAAAAAAAAAAAAAA',columns:80,rows:24,connectTimeoutMs:100};
    for (const patch of [{peerAddress:'localhost'}, {port:0}, {sessionKey:'not-a-valid-key'}, {sessionKey:'AAAAAAAAAAAAAAAAAAAAAB'}, {rows:1000,columns:1000}, {connectTimeoutMs:60001}])
        await assert.rejects(native.moshStart('invalid', {...options,...patch}), {code:'invalid_arguments'});
    assert.throws(() => native.moshRead({id:'missing',kind:'ssh'}, 1), {code:'unknown_handle'});
});
test('first UDP silence rejects at actual deadline; cancel settles and allows later run', async () => {
    const options = {peerAddress:'127.0.0.1',port:65500,sessionKey:'AAAAAAAAAAAAAAAAAAAAAA',columns:80,rows:24,connectTimeoutMs:150};
    const begin = Date.now(); await assert.rejects(native.moshStart('timeout', options), {code:'connect_timeout'});
    assert.ok(Date.now()-begin >= 140 && Date.now()-begin < 1000);
    const pending = native.moshStart('cancel', {...options,connectTimeoutMs:3000});
    assert.equal(native.moshCancel('cancel'), undefined);
    await assert.rejects(pending, {code:'cancelled'});
    const handle = await start(); await send(handle, "printf 'AFTER-CANCEL\\n'\n");
    await until(handle, /AFTER-CANCEL\r?\n/); await release(handle);
});
test('real SSP input, Unicode VT frames, resize, Ctrl-C and release', async () => {
    const handle = await start();
    await send(handle, "printf 'E3-中文-é-🙂\\n'; stty size\n");
    const first = await until(handle, /24 80\r?\n/);
    assert.match(first.text, /中文/); assert.match(first.text, /🙂/);
    assert.equal(first.packet.state, 'running'); assert.equal(typeof first.packet.lastHeardMs, 'number');
    await native.moshResize(handle,91,31); await sleep(100); await send(handle,'stty size\n');
    await until(handle, /31 91\r?\n/);
    await send(handle,'sleep 30\n'); await sleep(100); await native.moshWrite(handle, Buffer.from([3]));
    await send(handle,"printf 'AFTER-CTRL-C\\n'\n"); await until(handle,/AFTER-CTRL-C\r?\n/);
    const packet = await release(handle); assert.equal(packet.state,'closed');
    assert.throws(() => native.moshRead(handle,1), {code:'unknown_handle'});
});
test('real dropped encrypted UDP and changed server-facing source port preserve PID and variable, one bootstrap', async () => {
    const options = bootstrap(); const bridge = await proxy(options.port); let handle;
    try {
        handle = await start({...options,port:bridge.port});
        await send(handle, "E3_KEEP=kept; printf 'BEFORE_PID_%s_VAR_%s\\n' \"$$\" \"$E3_KEEP\"\n");
        const before = await until(handle,/BEFORE_PID_\d+_VAR_kept/);
        const pid = before.text.match(/BEFORE_PID_(\d+)_VAR_kept/)[1];
        bridge.injectOversize(); await sleep(50);
        assert.ok(!['failed','closed'].includes(native.moshRead(handle,1048576).state), 'unauthenticated oversized UDP cannot terminate SSP');
        bridge.setDrop(true); const stopReplay=bridge.replayOld();
        try { await sleep(5200); } finally { stopReplay(); }
        assert.equal(native.moshRead(handle,1048576).state,'reconnecting');
        await send(handle,"printf 'AFTER_PID_%s_VAR_%s\\n' \"$$\" \"$E3_KEEP\"\n");
        await bridge.roam(); assert.notEqual(bridge.sourcePorts[0],bridge.sourcePorts[1]);
        bridge.setDrop(false); const after = await until(handle,/AFTER_PID_\d+_VAR_kept/,10000);
        assert.match(after.text,new RegExp('AFTER_PID_'+pid+'_VAR_kept'));
        assert.equal(after.packet.state,'running');
        // Only the original server/options are ever bootstrapped in this test.
    } finally { if(handle) await release(handle); bridge.close(); }
});
test('unacknowledged input remains bounded and close wins over queued writes', async () => {
    const options = bootstrap(); const bridge = await proxy(options.port); let handle;
    try {
        handle = await start({...options,port:bridge.port}); bridge.setDrop(true);
        await native.moshWrite(handle,Buffer.alloc(65500,120));
        await assert.rejects(native.moshWrite(handle,Buffer.alloc(1000,120)),{code:'queue_full'});
        const closing = native.moshClose(handle,'cancelled');
        await assert.rejects(native.moshWrite(handle,Buffer.from('must-not-run\n')), {code:'session_closed'});
        assert.equal((await closing).state,'cancelled'); await release(handle);
    } finally { if(handles.has(handle)) await release(handle); bridge.close(); }
});

test('final VT state appears after every cached byte; close returns pending bytes', async () => {
    const handle = await start();
    await send(handle, "printf 'VT-tail-\\342\\234\\223\\n'; exit\n");
    await sleep(250);
    let packets = 0; const bytes = []; let packet;
    for (let i=0;i<20000;i++) {
        packet = native.moshRead(handle,1); bytes.push(...packet.bytes); packets++;
        if(packet.state !== 'running' && packet.state !== 'reconnecting') break;
        if(!packet.bytes.length) await sleep(10);
    }
    assert.equal(packet.state,'closed'); assert.ok(packets>1);
    assert.match(Buffer.from(bytes).toString('utf8'),/VT-tail-✓/);
    assert.equal(native.moshRead(handle,1).bytes.length,0);
    await release(handle);
    const second = await start(); await send(second,"printf 'CLOSE-pending-bytes\\n'\n"); await sleep(150);
    const closed = await release(second);
    assert.equal(closed.state,'closed'); assert.match(Buffer.from(closed.bytes).toString('utf8'),/CLOSE-pending-bytes\r?\n/);
});
