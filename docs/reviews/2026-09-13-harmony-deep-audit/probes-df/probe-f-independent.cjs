// probe-f-independent.cjs — 独立复核 F 的 WebMount 候选（真实 .ets 逻辑转译 + 内存假端口）
// 约束：不联网、不开浏览器、不读 cookie/storage、不调用模型/真实 SDK 派发。
// 说明：授权站点用合成 example.com，避免真实域名；userinfo 用合成 "u:p" 形状。
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../../..');
const ts = require(path.join(root, 'harmony/chat/node_modules/typescript'));
const cache = new Map();

const AUTHORIZED = [{ id: 'demo', name: 'Demo', url: 'https://example.com/login' }];
const kv = { get: async () => JSON.stringify(AUTHORIZED), put: async () => {} };

function load(relative) {
  const filename = path.join(root, relative);
  if (cache.has(filename)) return cache.get(filename);
  const source = fs.readFileSync(filename, 'utf8');
  const js = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const module = { exports: {} };
  const toolPath = 'harmony/chat/src/main/ets/chat/tool.ts';
  function dependency(name) {
    if (name === '@amber/chat-domain') return { ...load(toolPath), newId: () => 'synthetic-id' };
    if (name.includes('AppContainer')) return { getChatKvStore: () => kv };
    if (name.endsWith('WebMountSites.ets')) return load('harmony/entry/src/main/ets/platform_impl/WebMountSites.ets');
    throw new Error('Unexpected runtime dependency: ' + name);
  }
  vm.runInNewContext('(function(require,module,exports){' + js + '\n})',
    { console, setTimeout, clearTimeout }, { filename })(dependency, module, module.exports);
  cache.set(filename, module.exports);
  return module.exports;
}

const sites = load('harmony/entry/src/main/ets/platform_impl/WebMountSites.ets');
const toolsMod = load('harmony/entry/src/main/ets/platform_impl/WebMountTools.ets');
const evalJsonSemantics = (expr, context) =>
  `(function(){ try { const v = ${expr};`
  + ` return JSON.stringify({ ok: true, value: v === undefined ? null : v }); }`
  + ` catch (e) { return JSON.stringify({ ok: false, error: String(e) }); } })()`;

const makeSession = (context) => {
  const captured = [];
  return {
    captured,
    async evalJson(expr) {
      captured.push(evalJsonSemantics(expr, context));
      return JSON.stringify({ ok: true, value: vm.runInNewContext(expr, context) });
    },
    isReady() { return true; },
    getUrl() { return 'about:blank'; },
  };
};

let pass = 0, fail = 0;
const check = (name, ok, detail) => {
  if (ok) { pass++; console.log(`PASS  ${name}`); }
  else { fail++; console.log(`FAIL  ${name} :: ${detail}`); }
};

(async () => {
  // ---------- F-01：wm_click 的 error 文案未转义 target ----------
  {
    const ctx = { window: {}, document: { querySelector: () => null } };
    const tools = toolsMod.createWebMountTools(makeSession(ctx), false);
    check('F01a includeEval=false removes wm_eval', tools.every(t => t.name !== 'wm_eval'), 'wm_eval present');

    const injectPayloads = [
      `[data-x="x'+(window.__p1=1)+'"]`,
      `[data-x="x'+(globalThis.__p2=2)+'"]`,
      `'+(window.__p3=3)+'`,
    ];
    for (let i = 0; i < injectPayloads.length; i++) {
      const c = { window: {}, document: { querySelector: () => null } };
      const tool = toolsMod.createWebMountTools(makeSession(c), false).find(t => t.name === 'wm_click');
      let threw = null;
      try { await tool.execute({ target: injectPayloads[i] }); } catch (e) { threw = e; }
      const hit = c.window.__p1 === 1 || c.window.__p2 === 2 || c.window.__p3 === 3
        || c.__p1 === 1 || c.__p2 === 2 || c.__p3 === 3;
      check(`F01b payload[${i}] executes injected expression (eval disabled)`, hit && threw === null,
        `hit=${hit} threw=${threw}`);
    }
    // 负例：反斜杠转义引号 → 不逃逸
    {
      const c = { window: {}, document: { querySelector: () => null } };
      const tool = toolsMod.createWebMountTools(makeSession(c), false).find(t => t.name === 'wm_click');
      await tool.execute({ target: `a\\'+(window.__p4=4)+\\'` });
      check('F01b-neg escaped-quote payload does NOT escape the string', c.window.__p4 === undefined,
        JSON.stringify(Object.keys(c.window)));
    }
    // wm_type 有 JSON.stringify → 无同类注入
    {
      const c = { window: {}, document: { querySelector: () => null } };
      const typeTool = toolsMod.createWebMountTools(makeSession(c), false).find(t => t.name === 'wm_type');
      await typeTool.execute({ selector: `x'+(window.__t=9)+'`, text: `y'+(window.__t2=9)+'` });
      check('F01c wm_type JSON-encodes selector/text (no injection)',
        c.window.__t === undefined && c.window.__t2 === undefined, JSON.stringify(c.window));
    }
    // wm_click 无 isReady 门；wm_extract 有
    {
      const notReady = makeSession({ window: {}, document: { querySelector: () => null } });
      notReady.isReady = () => false;
      const click2 = toolsMod.createWebMountTools(notReady, false).find(t => t.name === 'wm_click');
      let ran = false;
      const orig = notReady.evalJson;
      notReady.evalJson = async (e) => { ran = true; return orig(e); };
      await click2.execute({ target: '[data-x="x"]' });
      check('F01d wm_click has no isReady gate', ran === true, 'evalJson not called');

      const extractTool = toolsMod.createWebMountTools(notReady, false).find(t => t.name === 'wm_extract');
      check('F01d2 wm_extract DOES gate on isReady (contrast)', await (async () => {
        try { await extractTool.execute({}); return false; }
        catch (e) { return /no page loaded/.test(String(e)); }
      })(), 'wm_extract did not throw');
    }
  }

  // ---------- F-02：hostOf/siteAllowsUrl userinfo 误解析 ----------
  {
    // hostOf = authority 中第一个 ':' 之前。因此仅当 userinfo 含 ':' 时，
    // 前缀 `example.com` 才被当作 host；无冒号 userinfo 不会被误判（非普遍绕过）。
    const cases = [
      ['userinfo with colon (bare)', 'https://example.com:pw@outside.invalid/p', true],
      ['userinfo empty password', 'https://example.com:@outside.invalid/p', true],
      ['userinfo numeric port-like', 'https://example.com:8443@outside.invalid/p', true],
      ['userinfo WITHOUT colon', 'https://example.com@outside.invalid/p', false],
      ['scheme uppercase, no colon', 'HTTPS://example.com@outside.invalid/p', false],
      ['subdomain disguise', 'https://x.example.com.evil.invalid/p', false],
      ['plain evil', 'https://evil.invalid/p', false],
      ['no scheme', 'example.com@outside.invalid/p', false],
    ];
    for (const [label, url, expected] of cases) {
      const got = sites.siteAllowsUrl(AUTHORIZED, url).valueOf();
      check(`F02 gate(${label}) === ${expected}`, got === expected, `got=${got} hostOf=${sites.hostOf(url)}`);
    }
    const deceptive = 'https://example.com:pw@outside.invalid/private';
    check('F02b real URL host is outside.invalid while gate host is example.com',
      new URL(deceptive).hostname === 'outside.invalid' && sites.hostOf(deceptive) === 'example.com',
      `urlHost=${new URL(deceptive).hostname} gateHost=${sites.hostOf(deceptive)}`);

    // wm_open 通过 gate 后原样交给 session.open
    const seen = [];
    const session = {
      async open(u) { seen.push(u); },
      async evalJson() { return JSON.stringify({ ok: true, value: null }); },
      isReady() { return true; }, getUrl() { return ''; },
    };
    const openTool = toolsMod.createWebMountTools(session, false).find(t => t.name === 'wm_open');
    await openTool.execute({ url: deceptive });
    check('F02d wm_open forwards raw userinfo URL to session.open (no normalization)',
      seen[0] === deceptive, `seen=${seen[0]}`);

    // 反证：真正未授权 host 被拒（gate 对正常 URL 有效）
    check('F02e gate rejects genuinely unauthorized host',
      sites.siteAllowsUrl(AUTHORIZED, 'https://outside.invalid/p') === false, 'evil accepted');
  }

  console.log(`\n=== probe-f-independent summary: pass=${pass} fail=${fail} ===`);
  if (fail > 0) process.exitCode = 1;
})().catch(e => { console.error(e); process.exitCode = 1; });
