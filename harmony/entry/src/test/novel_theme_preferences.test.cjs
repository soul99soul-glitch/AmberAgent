const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const base = path.join(__dirname, '../main/ets');

function compile(file, imports, suffix = '') {
  const source = fs.readFileSync(path.join(base, file), 'utf8') + suffix;
  const code = ts.transpileModule(source, { compilerOptions: {
    target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS,
  } }).outputText;
  const result = { exports: {} };
  new Function('require', 'exports', 'module', code)(id => {
    if (!(id in imports)) throw new Error('Unexpected dependency ' + id);
    return imports[id];
  }, result.exports, result);
  return result.exports;
}
function fixture(kind = 'novel') {
  const themes = compile('design/novel_themes.ets', {});
  const applied = [];
  const packs = compile('design/theme_packs.ets', {
    './tokens.ets': { applyTheme: dark => applied.push(['mode', dark]),
      setAccentColor: color => applied.push(['accent', color]),
      applyPaperPalette: palette => applied.push(['paper', palette.bg]),
      applyAmoledBlack: () => applied.push(['amoled']), BG: '#FFFFFF' },
    '../platform_impl/ProductIdentity.ets': { getProductKind: () => kind },
    './novel_themes.ets': themes,
  }, '\nexport { ThemePackStore };');
  return { ...packs, ...themes, applied, store: new packs.ThemePackStore() };
}
const kv = values => ({ get: async key => values[key] ?? null, put: async () => {} });

test('fresh novel uses Sujian; other products keep their default; upgrade preserves legacy selection', async () => {
  const novel = fixture(); await novel.store.hydrate(kv({}));
  assert.equal(novel.store.current().id, 'novel-sujian');
  for (const kind of ['agent', 'deepread']) {
    const f = fixture(kind); await f.store.hydrate(kv({}));
    assert.equal(f.store.current().id, 'sit-terracotta');
    assert.equal(f.store.all().some(p => p.id.startsWith('novel-')), false);
  }
  const legacy = fixture(); await legacy.store.hydrate(kv({ theme_pack_id: 'pi-steel' }));
  assert.equal(legacy.store.current().id, 'pi-steel');
});

test('all eight theme identities survive hydration and use their own accent', async () => {
  const f = fixture();
  assert.deepEqual(f.NOVEL_THEMES.map(p => p.displayName), ['素笺', '宣纸', '稿纸', '竹青', '胭脂', '藕荷', '墨白', '绛夜']);
  for (const theme of f.NOVEL_THEMES) {
    const store = new f.ThemePackStore(); await store.hydrate(kv({ theme_pack_id: theme.id }));
    assert.equal(store.current().id, theme.id);
    f.applyThemePack(store.current(), false);
    assert.deepEqual(f.applied.at(-1), ['accent', theme.accent]);
  }
});

test('fixed papers retain exact canvas and system-bar appearance even with AMOLED enabled', async () => {
  const f = fixture(); await f.store.hydrate(kv({ theme_amoled_black: 'true' }));
  for (const [id, dark, bg] of [['novel-ouhe', false, '#E7D5D2'], ['novel-jiangye', true, '#5B1A1C']]) {
    const theme = f.NOVEL_THEMES.find(p => p.id === id);
    for (const systemDark of [false, true]) {
      f.applied.length = 0;
      assert.equal(f.resolveThemeDark(theme, systemDark), dark);
      f.applyThemePack(theme, systemDark);
      assert.deepEqual(f.applied, [['mode', dark], ['paper', bg], ['accent', theme.accent]]);
    }
  }
  const normal = f.NOVEL_THEMES[0];
  assert.equal(f.resolveThemeDark(normal, false), false);
  assert.equal(f.resolveThemeDark(normal, true), true);
});

test('novel selection becomes current only after persistence succeeds; failure keeps the saved theme', async () => {
  const f = fixture(); await f.store.hydrate(kv({}));
  const original = f.store.current();
  let resolve, reject;
  let written;
  const selecting = f.store.select({ put: async (key, value) => {
    written = [key, value]; await new Promise((done, fail) => { resolve = done; reject = fail; });
  } }, f.NOVEL_THEMES[1]);
  assert.deepEqual(written, ['theme_pack_id', 'novel-xuanzhi']);
  assert.equal(f.store.current(), original);
  reject(new Error('disk unavailable'));
  await assert.rejects(selecting, /disk unavailable/);
  assert.equal(f.store.current(), original);
  const success = f.store.select({ put: async () => new Promise(done => { resolve = done; }) }, f.NOVEL_THEMES[7]);
  resolve(); await success;
  assert.equal(f.store.current().id, 'novel-jiangye');
});

test('matching an imported recipe uses the visible accent, so a custom accent can be replaced by the novel recipe', async () => {
  const f = fixture(); const theme = f.NOVEL_THEMES[1];
  const current = { ...theme, id: 'imported-paper' };
  const pageSource = fs.readFileSync(path.join(base, 'pages/NovelAppearancePage.ets'), 'utf8');
  const method = pageSource.slice(pageSource.indexOf('  private isSelected('), pageSource.indexOf('  @Builder\n  private ModeChoice'))
    .replace(/private /g, '');
  const code = ts.transpileModule('class Page {' + method + '} return Page;', {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const evaluate = (accent, ink) => {
    const Page = new Function('themePackStore', 'ACCENT', 'ACCENT_INK', code)(
      { current: () => current }, accent, ink);
    const page = new Page(); page.selectedId = current.id;
    return page.isSelected(theme);
  };
  assert.equal(evaluate('#5E9C6E', '#0F150E'), false);
  assert.equal(evaluate(theme.accent, theme.accentInk), true);
  current.dotGrid = true;
  assert.equal(evaluate(theme.accent, theme.accentInk), false);
});
