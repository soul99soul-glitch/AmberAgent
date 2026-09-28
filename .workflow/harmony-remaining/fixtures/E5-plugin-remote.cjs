// One host group over the actual platform modules and WebMountSession class.
// Only Harmony SDK transport/controller events are deterministic shims.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const test = require('node:test');
const repo = path.resolve(__dirname, '../../..');
const ts = require(path.join(repo, 'harmony/chat/node_modules/typescript'));
const platform = path.join(repo, 'harmony/entry/src/main/ets/platform_impl');

function load(file, kits, cache = new Map()) {
  if (!path.extname(file)) file += '.ets';
  if (cache.has(file)) return cache.get(file);
  const module = { exports: {} }; cache.set(file, module.exports);
  let source = fs.readFileSync(file, 'utf8');
  // UI component syntax follows this marker; retain the complete actual class.
  if (file.endsWith('/WebMountSession.ets')) source = source.slice(0, source.indexOf('let globalSession:'));
  const code = ts.transpileModule(source, { compilerOptions: {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS,
  } }).outputText;
  vm.runInNewContext(code, { module, exports: module.exports, require(name) {
    if (kits[name] !== undefined) return kits[name];
    if (name.startsWith('.')) return load(path.resolve(path.dirname(file), name), kits, cache);
    throw new Error('Unexpected dependency: ' + name);
  }, Uint8Array, ArrayBuffer, Error, Promise, Map, Set, Math, Number, JSON, String,
  Date, RegExp, Object, console, setTimeout, clearTimeout, AppStorage: { setOrCreate() {} } });
  return module.exports;
}

function setup() {
  const state = { queue: [], calls: [], cancels: 0, closes: 0 };
  const kits = {
    '@kit.ArkTS': { url: { URL: { parseURL: (raw, base) => {
      const parsed = new URL(raw, base); Object.defineProperty(parsed, 'params', { value: parsed.searchParams }); return parsed;
    } }, URLParams: URLSearchParams }, util: {
      TextEncoder: { create: () => ({ encodeInto: value => new TextEncoder().encode(value) }) },
      TextDecoder: { create: (encoding, options) => ({ decodeToString: value => new TextDecoder(encoding, options).decode(value) }) },
    } },
    '@kit.RemoteCommunicationKit': { rcp: {
      Request: class Request {
        constructor(url, method, headers, content, cookies, range, configuration) {
          Object.assign(this, { url, method, headers, content, configuration });
        }
      },
      createSession() { return {
        fetch(request) {
          state.calls.push(request);
          const next = state.queue.shift();
          if (!next) throw new Error('Unexpected request');
          assert.equal(request.configuration.transfer.autoRedirect, false);
          if (next.pending) return new Promise(() => {});
          const events = request.configuration.tracing.httpEventsHandler;
          for (const bytes of next.chunks ?? [new TextEncoder().encode(next.body ?? '')]) {
            events.onDataReceive(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength));
          }
          return Promise.resolve({ statusCode: next.status ?? 200, headers: next.headers ?? {} });
        },
        cancel() { state.cancels++; }, close() { state.closes++; },
      }; },
    } },
    '@kit.ArkWeb': { webview: { WebviewController: class { loadUrl() {} } } },
    '@kit.ArkUI': {}, '@kit.ImageKit': {},
    '@kit.PerformanceAnalysisKit': { hilog: { warn() {} } },
  };
  const model = load(path.join(repo, 'harmony/chat/src/main/ets/chat/plugins/models.ts'), kits);
  kits['@amber/chat-domain'] = { PluginHttpError: model.PluginHttpError, newId: () => 'fixture' };
  const http = load(path.join(platform, 'PluginHttpPort.ets'), kits).createEntryPluginHttpPort();
  const Session = load(path.join(platform, 'WebMountSession.ets'), kits).WebMountSession;
  const session = new Session();
  const web = load(path.join(platform, 'PluginWebMountGate.ets'), kits).createEntryPluginWebMountPort(session);
  const request = (input = {}, method = 'GET') => ({
    url: 'https://api.example.test/fixed?base=1', method, input,
    networkDomains: ['example.test'], timeoutMs: 1000, maxOutputChars: 1000,
  });
  return { state, http, session, web, request };
}

test('one bounded group: fixed HTTPS endpoint, checked redirects/output/cancel and intersected WebMount scope', async () => {
  const { state, http, session, web, request } = setup();
  state.queue.push({ status: 307, headers: { location: '/done' } }, { body: '{"ok":true}' });
  const response = await http.execute(request({ url: 'https://outside.test/stolen', method: 'DELETE', authorization: 'data' }));
  assert.equal(response.body, '{"ok":true}'); assert.equal(response.status, 200);
  assert.equal(state.calls.length, 2);
  const first = state.calls[0]; const endpoint = new URL(first.url);
  assert.equal(endpoint.hostname, 'api.example.test'); assert.equal(endpoint.pathname, '/fixed');
  assert.equal(endpoint.searchParams.get('url'), 'https://outside.test/stolen');
  assert.equal(first.method, 'GET'); assert.equal(first.headers.authorization, undefined);
  assert.equal(state.calls[1].url, 'https://api.example.test/done');

  state.queue.push({ status: 302, headers: { location: 'https://outside.test/steal' } });
  let calls = state.calls.length;
  await assert.rejects(http.execute(request()), error => error.code === 'network_domain_denied');
  assert.equal(state.calls.length, calls + 1);
  calls = state.calls.length;
  for (const target of ['http://api.example.test/x', 'https://user:secret@api.example.test/x']) {
    await assert.rejects(http.execute({ ...request(), url: target }), error => error.code === 'remote_url_invalid');
  }
  assert.equal(state.calls.length, calls);

  state.queue.push({ status: 200, body: 'null' });
  await http.execute(request({ url: 'https://outside.test/x', headers: { Authorization: 'data' } }, 'POST'));
  const posted = state.calls.at(-1);
  assert.equal(posted.method, 'POST'); assert.equal(posted.url, request().url);
  assert.deepEqual(JSON.parse(posted.content), { url: 'https://outside.test/x', headers: { Authorization: 'data' } });
  assert.equal(posted.headers.Authorization, undefined);
  state.queue.push({ status: 303, headers: { location: '/rewrite' } });
  calls = state.calls.length;
  await assert.rejects(http.execute(request({}, 'POST')), error => error.code === 'redirect_method_change' && error.mayHaveApplied);
  assert.equal(state.calls.length, calls + 1);

  state.queue.push({ chunks: [new Uint8Array(4001)] });
  await assert.rejects(http.execute(request()), error => error.code === 'output_limit');
  const aborted = new AbortController(); aborted.abort(); calls = state.calls.length;
  await assert.rejects(http.execute(request({}, 'POST'), aborted.signal), error => error.code === 'cancelled' && !error.mayHaveApplied);
  assert.equal(state.calls.length, calls);
  state.queue.push({ pending: true });
  const active = new AbortController();
  const pending = http.execute(request({}, 'POST'), active.signal); active.abort();
  await assert.rejects(pending, error => error.code === 'cancelled' && error.mayHaveApplied);
  state.queue.push({ pending: true });
  await assert.rejects(http.execute({ ...request(), timeoutMs: 10 }), error => error.code === 'timed_out');
  assert.equal(state.closes, 8); // Every created session, including cancellation, closes.

  session.setUrlGate(target => new URL(target).hostname.endsWith('example.test'));
  session.markPageEnd('https://api.example.test/current');
  let release;
  const scoped = web.withScope(['api.example.test'], async () => {
    session.setUrlGate(() => true); // The actual guardPage refresh must not replace plugin gate.
    assert.equal(session.shouldBlockUrl('https://outside.test/redirect'), true);
    assert.equal(session.shouldBlockUrl('https://api.example.test/done'), false);
    session.setUrlGate(() => false);
    assert.equal(session.shouldBlockUrl('https://api.example.test/done'), true);
    session.setUrlGate(() => true);
    await new Promise(resolve => { release = resolve; });
    session.markPageEnd('https://api.example.test/done'); return 'done';
  });
  await assert.rejects(web.withScope(['api.example.test'], async () => 'overwrite'), /busy/);
  release(); assert.equal(await scoped, 'done');
  assert.equal(session.shouldBlockUrl('https://outside.test/after'), true); // Late navigation remains gated.
  session.setUrlGate(() => true); // An explicit ordinary action takes over the page.
  assert.equal(session.shouldBlockUrl('https://outside.test/after'), false);

  session.markPageEnd('https://api.example.test/current');
  await assert.rejects(web.withScope(['api.example.test'], async () => {
    session.markPageEnd('https://outside.test/late'); return 'not-success';
  }), /network_domain_denied/);
  assert.equal(session.getUrl(), '');
  session.markPageEnd('https://api.example.test/recovered');
  assert.equal(await web.withScope(['api.example.test'], async () => 7), 7);
  session.setUrlGate(() => true); session.markPageEnd('https://outside.test/ordinary');
  assert.equal(await web.withScope(['api.example.test'], async () => {
    session.markPageEnd('https://api.example.test/new-target'); return 8;
  }, false), 8);
});
