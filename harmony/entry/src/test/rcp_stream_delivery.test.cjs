// Run with: node --test harmony/entry/src/test/rcp_stream_delivery.test.cjs
// Execute the actual platform adapter; control only NetworkKit event delivery.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../../chat/node_modules/typescript');
const sourceRoot = path.resolve(__dirname, '../../..');

function loadSource(filename, imports = {}) {
  const exports = {};
  const source = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  vm.runInNewContext(source, { exports, require: name => {
    if (Object.hasOwn(imports, name)) return imports[name];
    throw new Error('Unexpected dependency ' + name);
  }, Error, Promise, Object, String, Number, ArrayBuffer, Uint8Array }, { filename });
  return exports;
}
const statusHelpers = loadSource(path.join(sourceRoot, 'deepread/src/main/ets/platform/http_response_status.ts'));

function fixture() {
  const handlers = {};
  let complete;
  let destroys = 0;
  const native = {
    on: (name, callback) => { handlers[name] = callback; },
    destroy: () => { destroys++; },
    requestInStream: () => new Promise(resolve => { complete = resolve; }),
  };
  const port = loadSource(path.join(sourceRoot, 'entry/src/main/ets/platform_impl/RcpHttpClient.ets'), {
    '@kit.NetworkKit': { http: { createHttp: () => native,
      RequestMethod: { POST: 'POST' }, HttpDataType: { ARRAY_BUFFER: 'buffer' } } },
    '@kit.ArkTS': { util: { TextDecoder: { create: () => ({
      decodeToString: bytes => new TextDecoder().decode(bytes),
    }) } } },
    '@kit.PerformanceAnalysisKit': { hilog: { info() {}, warn() {}, error() {} } },
    '@amber/deepread-domain': statusHelpers,
  }).createRcpHttpClient();
  const delivered = [];
  let dataEnds = 0;
  const running = port.fetchStream({ url: 'http://fixture.test/stream', method: 'POST', headers: {}, body: '{}' }, {
    onChunk: (bytes, end) => { if (!end) delivered.push(new TextDecoder().decode(bytes)); },
    onDataEnd: () => { dataEnds++; },
  });
  return {
    delivered, running,
    headers: value => handlers.headersReceive(value),
    chunk: value => handlers.dataReceive(new TextEncoder().encode(value).buffer),
    finish: status => { handlers.dataEnd(); complete(status); },
    destroys: () => destroys, dataEnds: () => dataEnds,
  };
}
const delta = value => `data: {"choices":[{"delta":{"content":"${value}"}}]}\n\n`;

for (const headers of [ {}, { 'Content-Type': 'text/plain' }, { 'content-type': 'application/octet-stream' } ]) {
  test('actual SSE with absent/wrong MIME delivers before HTTP completion: ' + JSON.stringify(headers), async () => {
    const f = fixture(); f.headers(headers);
    f.chunk(delta('first'));
    assert.equal(f.delivered.length, 1, 'first event must be delivered while requestInStream is still pending');
    f.chunk(delta('second'));
    assert.equal(f.delivered.length, 2, 'second dataReceive remains incremental');
    f.finish(200);
    assert.equal((await f.running).status, 200);
    assert.equal(f.destroys(), 1);
    assert.equal(f.dataEnds(), 1);
  });
}

test('BOM/initial CRLF and split SSE field prefix identify one continuous stream', async () => {
  const f = fixture(); f.headers({});
  f.chunk('\uFEFF\r\nda');
  assert.equal(f.delivered.length, 0, 'incomplete prefix needs more bytes');
  f.chunk('ta: {"choices":[{"delta":{"content":"first"}}]}\n\n');
  assert.equal(f.delivered.length, 2, 'once recognized, earlier fragments and the latest fragment drain');
  f.chunk(delta('second'));
  assert.equal(f.delivered.length, 3);
  f.finish(200); await f.running;
});

test('recognized HTTP failure holds SSE-looking body until the real error response', async () => {
  const f = fixture(); f.headers({ 'HTTP/1.1 401 Unauthorized': '', 'content-type': 'text/event-stream' });
  f.chunk(delta('must-not-display'));
  assert.equal(f.delivered.length, 0);
  f.finish(401);
  const response = await f.running;
  assert.equal(response.status, 401);
  assert.match(response.body, /must-not-display/);
});

test('unknown status does not become success after body-based SSE identification', async () => {
  const f = fixture(); f.headers({});
  f.chunk('data: {"error":{"message":"Unauthorized"}}\n\n');
  f.finish(401);
  assert.equal((await f.running).status, 401, 'no synthetic 200 from MIME or protocol sniffing');
});

test('ordinary JSON and text containing data: retain HTTP-gated delivery', async () => {
  for (const body of ['{"error":{"message":"data: Unauthorized"}}', 'gateway error: data: not an SSE stream']) {
    const f = fixture(); f.headers({});
    f.chunk(body);
    assert.equal(f.delivered.length, 0);
    f.finish(401);
    const response = await f.running;
    assert.equal(response.status, 401);
    assert.equal(response.body, body);
  }
});

test('trusted MIME preserves existing direct delivery before status is known', async () => {
  const f = fixture(); f.headers({ 'Content-Type': 'text/event-stream; charset=utf-8' });
  f.chunk(delta('first')); assert.equal(f.delivered.length, 1);
  f.finish(200); assert.equal((await f.running).status, 200);
});

test('body-confirmed SSE delivery survives late non-SSE response headers', async () => {
  const f = fixture();
  f.chunk(delta('first'));
  assert.equal(f.delivered.length, 1);
  f.headers({ 'content-type': 'text/plain' });
  f.chunk(delta('second'));
  assert.equal(f.delivered.length, 2);
  f.finish(200); await f.running;
});

test('prefix probe is bounded and never scans later text for an SSE marker', async () => {
  const f = fixture(); f.headers({});
  const prefix = '\n'.repeat(1024);
  f.chunk(prefix); f.chunk(delta('outside-prefix'));
  assert.equal(f.delivered.length, 0);
  f.finish(401);
  assert.equal((await f.running).body, prefix + delta('outside-prefix'));
});
