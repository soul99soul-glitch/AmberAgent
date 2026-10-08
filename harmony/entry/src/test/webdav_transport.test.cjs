// Verify the real RCP adapter preserves request/response bytes and releases native sessions.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../../chat/node_modules/typescript');
const filename = path.resolve(__dirname, '../main/ets/platform_impl/WebDavTransport.ets');
function fixture(failure = false) {
  const exports = {}, requests = [];
  let closed = 0;
  const output = Uint8Array.from({ length: 256 }, (_, i) => i);
  class Request {
    constructor(url, method, headers, content) { Object.assign(this, { url, method, headers, content }); }
  }
  const session = {
    fetch: async request => { requests.push(request); if (failure) throw new Error('network_failure');
      return { statusCode: 200, body: output.buffer }; },
    close: () => { closed++; },
  };
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), { compilerOptions: {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS,
  } }).outputText;
  vm.runInNewContext(code, { exports, Uint8Array, ArrayBuffer, Error, Promise,
    require: name => { if (name === '@kit.RemoteCommunicationKit') return { rcp: { Request, createSession: () => session } };
      throw new Error('Unexpected dependency: ' + name); },
  });
  return { port: exports.createWebDavTransport(), requests, closed: () => closed, output };
}
test('real DAV transport uploads exactly the Uint8Array view and downloads every byte', async () => {
  const f = fixture();
  const backing = new Uint8Array(258); backing.set(f.output, 1);
  const response = await f.port.fetch({ url: 'https://dav.test/backup', method: 'PUT',
    headers: { Authorization: 'Basic dTpw' }, body: backing.subarray(1, 257) });
  assert.ok(f.requests[0].content instanceof ArrayBuffer);
  assert.deepEqual(new Uint8Array(f.requests[0].content), f.output);
  assert.deepEqual(response.body, f.output); assert.equal(f.closed(), 1);
});
for (const method of ['PROPFIND', 'MKCOL']) {
  test('real DAV transport forwards custom method ' + method, async () => {
    const f = fixture(); await f.port.fetch({ url: 'https://dav.test/backup/', method, headers: { Depth: '1' } });
    assert.equal(f.requests[0].method, method); assert.equal(f.closed(), 1);
  });
}
test('failed native request closes its session and reports the original failure', async () => {
  const f = fixture(true);
  await assert.rejects(() => f.port.fetch({ url: 'https://dav.test/backup/', method: 'GET', headers: {} }), /network_failure/);
  assert.equal(f.closed(), 1);
});
