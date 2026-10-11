// Run with: node --test harmony/entry/src/test/rcp_enterprise_ca.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../../chat/node_modules/typescript');

const sourceRoot = path.resolve(__dirname, '../../..');
const statusHelpers = loadSource(path.join(sourceRoot, 'deepread/src/main/ets/platform/http_response_status.ts'));

function loadSource(filename, imports = {}) {
  const exports = {};
  const source = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  vm.runInNewContext(source, {
    exports,
    require: name => {
      if (Object.hasOwn(imports, name)) return imports[name];
      throw new Error('Unexpected dependency ' + name);
    },
    Error, Promise, Object, String, Number, ArrayBuffer, Uint8Array,
  }, { filename });
  return exports;
}

function fixture() {
  const handlers = {};
  const calls = [];
  let complete;
  const native = {
    on: (name, callback) => { handlers[name] = callback; },
    destroy: () => {},
    request: (url, options) => {
      calls.push(['fetch', url, options]);
      return Promise.resolve({ responseCode: 401, result: '{}', header: {} });
    },
    requestInStream: (url, options) => {
      calls.push(['stream', url, options]);
      return new Promise(resolve => { complete = resolve; });
    },
  };
  const port = loadSource(path.join(sourceRoot, 'entry/src/main/ets/platform_impl/RcpHttpClient.ets'), {
    '@kit.NetworkKit': { http: { createHttp: () => native,
      RequestMethod: { GET: 'GET' }, HttpDataType: { STRING: 'string', ARRAY_BUFFER: 'buffer' } } },
    '@kit.ArkTS': { util: { TextDecoder: { create: () => ({
      decodeToString: bytes => new TextDecoder().decode(bytes),
    }) } } },
    '@kit.PerformanceAnalysisKit': { hilog: { info() {}, warn() {}, error() {} } },
    '@amber/deepread-domain': statusHelpers,
    './ModelEnterpriseCa.ets': {
      modelEnterpriseCaPathForUrl: url => url.includes('api.deepseek.com') ? '/private/model-enterprise-ca.pem' : undefined,
    },
  }).createRcpHttpClient();
  return { port, handlers, calls, complete: () => complete };
}

test('fetch sets caPath only for the explicitly allowed model host', async () => {
  const f = fixture();
  const response = await f.port.fetch({ url: 'https://api.deepseek.com/v1/models', method: 'GET', headers: {} });
  assert.equal(response.status, 401);
  assert.equal(f.calls[0][2].caPath, '/private/model-enterprise-ca.pem');
  const ordinary = fixture();
  await ordinary.port.fetch({ url: 'https://api.tavily.com/search', method: 'GET', headers: {} });
  assert.equal(ordinary.calls[0][2].caPath, undefined);
});

test('fetchStream uses the same caPath before registering stream events', async () => {
  const f = fixture();
  const running = f.port.fetchStream({ url: 'https://api.deepseek.com/v1/chat/completions', method: 'GET', headers: {} }, {
    onChunk: () => {},
  });
  assert.equal(f.calls[0][2].caPath, '/private/model-enterprise-ca.pem');
  f.handlers.headersReceive({ 'content-type': 'text/event-stream' });
  f.handlers.dataEnd();
  f.complete()(200);
  assert.equal((await running).status, 200);
});

test('fetch preserves the SDK BusinessError code and message', async () => {
  const f = fixture();
  f.port = loadSource(path.join(sourceRoot, 'entry/src/main/ets/platform_impl/RcpHttpClient.ets'), {
    '@kit.NetworkKit': { http: { createHttp: () => ({
      request: () => Promise.reject({ code: 2300060, message: 'Invalid SSL peer certificate' }),
      destroy: () => {},
    }), RequestMethod: { GET: 'GET' }, HttpDataType: { STRING: 'string', ARRAY_BUFFER: 'buffer' } } },
    '@kit.ArkTS': { util: { TextDecoder: { create: () => ({ decodeToString: () => '' }) } } },
    '@kit.PerformanceAnalysisKit': { hilog: { info() {}, warn() {}, error() {} } },
    '@amber/deepread-domain': statusHelpers,
    './ModelEnterpriseCa.ets': { modelEnterpriseCaPathForUrl: () => undefined },
  }).createRcpHttpClient();
  await assert.rejects(
    f.port.fetch({ url: 'https://api.deepseek.com/v1/models', method: 'GET', headers: {} }),
    error => error instanceof Error && error.message.includes('2300060: Invalid SSL peer certificate'),
  );
});

test('fetchStream preserves the SDK BusinessError code and message', async () => {
  const native = {
    requestInStream: () => Promise.reject({ code: 2300060, message: 'Invalid SSL peer certificate' }),
    destroy: () => {}, on: () => {},
  };
  const port = loadSource(path.join(sourceRoot, 'entry/src/main/ets/platform_impl/RcpHttpClient.ets'), {
    '@kit.NetworkKit': { http: { createHttp: () => native,
      RequestMethod: { GET: 'GET' }, HttpDataType: { STRING: 'string', ARRAY_BUFFER: 'buffer' } } },
    '@kit.ArkTS': { util: { TextDecoder: { create: () => ({ decodeToString: () => '' }) } } },
    '@kit.PerformanceAnalysisKit': { hilog: { info() {}, warn() {}, error() {} } },
    '@amber/deepread-domain': statusHelpers,
    './ModelEnterpriseCa.ets': { modelEnterpriseCaPathForUrl: () => undefined },
  }).createRcpHttpClient();
  await assert.rejects(
    port.fetchStream({ url: 'https://api.deepseek.com/v1/chat/completions', method: 'GET', headers: {} }, {
      onChunk: () => {},
    }),
    error => error instanceof Error && error.message.includes('2300060: Invalid SSL peer certificate'),
  );
});
