const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../../../chat/node_modules/typescript');

function nativePort() {
  const source = fs.readFileSync(path.join(__dirname, '../main/ets/platform_impl/DeepReadGoogleSearch.ets'), 'utf8')
    .replace(/^import[^\n]+\n/gm, '');
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const exports = {};
  const checkWebMountUrl = raw => {
    try {
      const u = new URL(raw);
      return { ok: ['http:', 'https:'].includes(u.protocol) && !u.username && !u.password, hostname: u.hostname, url: u.href };
    } catch { return { ok: false }; }
  };
  vm.runInNewContext(code, { exports, Map, JSON, Math, encodeURIComponent, Error, checkWebMountUrl });
  return exports;
}

function session(result = [{ title: 'Public reference', url: 'https://example.com/reference', snippet: 'Evidence' }]) {
  const calls = [];
  const s = {
    open: async (...args) => calls.push(['open', ...args]),
    evalJson: async script => { calls.push(['evaluate', script]); return JSON.stringify({ ok: true, value: result }); },
    getController: () => ({ stop: () => calls.push(['stop']) }),
  };
  return { s, calls };
}

test('actual Google port opens and extracts its article controller, preserving normalized public URLs', async () => {
  const port = nativePort(), { s, calls } = session();
  port.bindDeepReadGoogleHost('article', s);
  const hits = await port.searchDeepReadGoogle('article', '中文 topic', 5);
  assert.equal(calls[0][1], 'https://www.google.com/search?q=%E4%B8%AD%E6%96%87%20topic&hl=zh-CN');
  assert.equal(hits[0].url, 'https://example.com/reference');
  assert.equal(hits[0].source, 'Google');
  assert.equal(calls.length, 2);
});

test('old article unbind cannot release a replacement host; simultaneous topics use independent controllers', async () => {
  const port = nativePort(), old = session(), current = session(), other = session();
  const binding = port.bindDeepReadGoogleHost('article', old.s);
  const currentBinding = port.bindDeepReadGoogleHost('article', current.s);
  port.bindDeepReadGoogleHost('other', other.s);
  binding.unbind();
  await Promise.all([port.searchDeepReadGoogle('article', 'one', 3), port.searchDeepReadGoogle('other', 'two', 3)]);
  assert.equal(old.calls.length, 0);
  assert.match(current.calls[0][1], /q=one/);
  assert.match(other.calls[0][1], /q=two/);
  currentBinding.unbind();
  await assert.rejects(port.searchDeepReadGoogle('article', 'no host', 3), /浏览器页面/);
});

test('cancellation stops the owning controller and prevents result delivery; detached owner rejects before evaluation', async () => {
  const port = nativePort(), { s, calls } = session();
  let abort;
  const signal = { aborted: false, addEventListener: (_type, cb) => { abort = cb; }, removeEventListener: () => { abort = null; } };
  const binding = port.bindDeepReadGoogleHost('article', s);
  s.open = async () => { signal.aborted = true; abort(); };
  await assert.rejects(port.searchDeepReadGoogle('article', 'cancel', 3, signal), /取消/);
  assert.equal(calls.filter(c => c[0] === 'stop').length, 1);
  assert.equal(calls.filter(c => c[0] === 'evaluate').length, 0);
  assert.equal(abort, null);
  signal.aborted = false;
  s.open = async () => binding.unbind();
  await assert.rejects(port.searchDeepReadGoogle('article', 'detach', 3, signal), /页面已离开/);
});

test('native gate and returned-hit validation reject non-HTTP/userinfo while allowing only Google navigation', async () => {
  const port = nativePort();
  assert.equal(port.googleSearchPageAllowed('https://www.google.com/search?q=topic'), true);
  for (const url of ['https://google.com.evil.test', 'javascript:alert(1)', 'https://user@google.com']) {
    assert.equal(port.googleSearchPageAllowed(url), false);
  }
  const { s } = session([{ title: 'bad', url: 'javascript:alert(1)' }, { title: 'secret', url: 'https://user@example.com' }, { title: 'real', url: 'https://example.com/real' }]);
  port.bindDeepReadGoogleHost('article', s);
  const hits = await port.searchDeepReadGoogle('article', 'topic', 5);
  assert.equal(hits.length, 1);
  assert.equal(hits[0].title, 'real');
});

test('actual DOM extraction reads h3 results, decodes Google redirects and keeps useful public links once', () => {
  const port = nativePort();
  const heading = (title, href) => ({ innerText: title, closest: () => ({ href, parentElement: { innerText: title + ' source snippet' } }) });
  const headings = [heading('A', 'https://example.com/a'), heading('duplicate', 'https://example.com/a'),
    heading('B', 'https://www.google.com/url?q=https%3A%2F%2Fexample.org%2Fb'), heading('internal', 'https://accounts.google.com'),
    heading('not a source', 'javascript:alert(1)')];
  const rows = vm.runInNewContext(port.googleResultsScript(5), { URL, location: { href: 'https://www.google.com/search' }, document: { querySelectorAll: () => headings } });
  assert.deepEqual(Array.from(rows, row => row.url), ['https://example.com/a', 'https://example.org/b']);
  assert.match(rows[0].snippet, /source snippet/);
});

test('a host released during async DOM extraction cannot publish its late result', async () => {
  const port = nativePort(), { s } = session();
  let resolve, started;
  const evaluating = new Promise(r => { started = r; });
  s.evalJson = async () => { started(); return new Promise(r => { resolve = r; }); };
  const binding = port.bindDeepReadGoogleHost('article', s);
  const running = port.searchDeepReadGoogle('article', 'topic', 3);
  await evaluating; binding.unbind();
  resolve(JSON.stringify({ ok: true, value: [{ title: 'late', url: 'https://example.com/late' }] }));
  await assert.rejects(running, /页面已离开/);
});

test('actual independent WebMountSession does not publish Google state into Chat; default session keeps existing publication', () => {
  const source = fs.readFileSync(path.join(__dirname, '../main/ets/platform_impl/WebMountSession.ets'), 'utf8')
    .split('// 全局单会话(MVP;多 tab 会话为后续梯队)')[0].replace(/^import[^\n]+\n/gm, '');
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const exports = {}, published = new Map();
  vm.runInNewContext(code, { exports, AppStorage: { setOrCreate: (key, value) => published.set(key, value) },
    webview: { WebviewController: class {} }, newId: () => 'document', Date, setTimeout, clearTimeout });
  const privateSession = new exports.WebMountSession(false);
  privateSession.setUrlGate(() => true); privateSession.markPageEnd('https://www.google.com/search?q=topic');
  privateSession.setLease('hand'); assert.equal(published.size, 0);
  const chatSession = new exports.WebMountSession();
  chatSession.setUrlGate(() => true); chatSession.markPageEnd('https://example.com'); chatSession.setLease('hand');
  assert.equal(published.get('webmountUrl'), 'https://example.com');
  assert.equal(published.get('webmountReady'), true); assert.equal(published.get('webmountLease'), 'hand');
});
