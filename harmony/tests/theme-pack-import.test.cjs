// theme-pack-import.test.cjs — 三格式主题包解析/导出的行为测试
// 执行真实生产代码:tokens.ets / theme_packs.ets / theme_pack_import.ets 经 TypeScript
// transpile 后在 vm 沙箱运行(AppStorage/FontWeight/fonts 模块打桩,与 UI 无关的纯逻辑全真实)。
// 基线命令: node --test harmony/tests/theme-pack-import.test.cjs
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('../chat/node_modules/typescript');
const root = path.resolve(__dirname, '../..');

function loadEtsModule(rel, requireStub, sandbox) {
  const source = fs.readFileSync(path.join(root, rel), 'utf8');
  const js = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
    reportDiagnostics: true,
  });
  assert.equal(js.diagnostics.length, 0, `${rel} transpile diagnostics`);
  const wrapped = `(function(require, module, exports) {\n${js.outputText}\n})`;
  const fn = vm.runInContext(wrapped, sandbox);
  const module = { exports: {} };
  fn(requireStub, module, module.exports);
  return module.exports;
}

const sandbox = vm.createContext({
  AppStorage: { get: () => undefined, setOrCreate: () => {} },
  FontWeight: { Bold: 700, Normal: 400, Medium: 500 },
  console,
});
const fontsStub = {
  FONT_SANS: 'AmberSans', FONT_SANS_MEDIUM: 'AmberSansMedium',
  FONT_SANS_SEMIBOLD: 'AmberSansSemiBold', FONT_SANS_BOLD: 'AmberSansBold',
  FONT_MONO: 'JetBrainsMono', FONT_MONO_SEMIBOLD: 'JetBrainsMonoSemiBold',
};
const tokens = loadEtsModule('harmony/entry/src/main/ets/design/tokens.ets',
  (id) => (id === './fonts.ets' ? fontsStub : {}), sandbox);
const packs = loadEtsModule('harmony/entry/src/main/ets/design/theme_packs.ets',
  (id) => (id === './tokens.ets' ? tokens : {}), sandbox);
const importer = loadEtsModule('harmony/entry/src/main/ets/design/theme_pack_import.ets',
  (id) => {
    if (id === './tokens.ets') return tokens;
    if (id === './theme_packs.ets') return packs;
    return {};
  }, sandbox);

const { parseThemePackImport, themePackToPortableJson } = importer;
const { parseThemePackJson, themePackToJson, BUILTIN_PACKS } = packs;

// vm 沙箱产出的对象跨 realm,prototype 不同;JSON 往返(两侧都)回本 realm 再做严格深比较
const sameData = (actual, expected) =>
  assert.deepEqual(JSON.parse(JSON.stringify(actual)), JSON.parse(JSON.stringify(expected)));

const fixture = fs.readFileSync(path.join(__dirname, 'fixtures/cross-platform-v1.json'), 'utf8');

// ── 跨平台 amber.theme.pack v1 ──

test('portable: 共享 fixture 全字段映射(design 5 槽 → 9 槽派生)', () => {
  const r = parseThemePackImport(fixture);
  assert.equal(r.issues.length, 0, `issues: ${r.issues.join(';')}`);
  assert.ok(r.pack);
  assert.equal(r.pack.id, 'cross-platform-v1-garden');
  assert.equal(r.pack.displayName, '山雨入林 · 双端配方');
  assert.equal(r.pack.accent, '#315B42');
  assert.equal(r.pack.accentInk, '#FFFFFF');
  assert.equal(r.pack.dotGrid, false); // paperGrain → 平涂
  assert.equal(r.pack.builtin, false);
  // light:原样 5 槽 + iOS 派生(surface2=midpoint,ink2=ink,muted2=muted,borderSoft=border)
  assert.equal(r.pack.light.bg, '#F5F7F4');
  assert.equal(r.pack.light.surface, '#FFFFFF');
  assert.equal(r.pack.light.surface2, '#FAFBF9'); // midpoint(#F5F7F4,#FFFFFF),逐通道 floor
  assert.equal(r.pack.light.ink, '#17211A');
  assert.equal(r.pack.light.ink2, '#17211A');
  assert.equal(r.pack.light.muted, '#46584B');
  assert.equal(r.pack.light.muted2, '#46584B');
  assert.equal(r.pack.light.border, '#D3DDD4');
  assert.equal(r.pack.light.borderSoft, '#D3DDD4');
  // dark
  assert.equal(r.pack.dark.bg, '#121915');
  assert.equal(r.pack.dark.surface2, '#171F1A'); // midpoint(#121915,#1C2620)
  assert.equal(r.pack.dark.ink2, '#EAF2EB');
  assert.equal(r.display, null); // 跨平台包不触 DisplaySetting
});

test('portable: 无 design 时按 paper 画布取色板(white/neutral)', () => {
  const mk = (paper) => JSON.stringify({
    format: 'amber.theme.pack', version: 1, id: `t-${paper}`, displayName: paper,
    paper, accentHex: '#4F86D6', inkHex: '#FFFFFF', canvasStyle: 'dotGrid',
    brandMark: 'systemWordmark', shortcutIconStyle: 'systemOutline', chromeTypeface: 'system',
  });
  const white = parseThemePackImport(mk('white'));
  assert.equal(white.issues.length, 0);
  assert.equal(white.pack.light.bg, '#F5F5F4'); // iOS whiteLight
  assert.equal(white.pack.dark.bg, '#111111'); // iOS whiteDark
  assert.equal(white.pack.light.surface2, '#EEEEED');
  assert.equal(white.pack.dotGrid, true); // dotGrid → 点阵
  const neutral = parseThemePackImport(mk('neutral'));
  assert.equal(neutral.pack.light.bg, '#ECE8E4'); // iOS neutralLight
  assert.equal(neutral.pack.dark.bg, '#0E0D10'); // iOS darkPalette
});

test('portable: design 缺一侧时该侧继承 paper(iOS resolving 语义)', () => {
  const r = parseThemePackImport(JSON.stringify({
    format: 'amber.theme.pack', version: 1, id: 'half-design', displayName: '半套',
    paper: 'notion', accentHex: '#0075DE', inkHex: '#FFFFFF', canvasStyle: 'flat',
    brandMark: 'systemWordmark', shortcutIconStyle: 'systemOutline', chromeTypeface: 'system',
    design: {
      light: {
        background: '#F5F7F4', surface: '#FFFFFF', foreground: '#17211A',
        mutedForeground: '#46584B', border: '#D3DDD4',
      },
      patterns: [],
    },
  }));
  assert.equal(r.issues.length, 0);
  assert.equal(r.pack.light.bg, '#F5F7F4'); // design.light
  assert.equal(r.pack.dark.bg, '#191919'); // notion dark 继承
});

test('portable: 0x 前缀与大小写归一化为 #RRGGBB 大写', () => {
  const r = parseThemePackImport(JSON.stringify({
    format: 'amber.theme.pack', version: 1, id: 'norm', displayName: '归一',
    paper: 'paper', accentHex: '0xb8623a', inkHex: '0xFFFFFF', canvasStyle: 'flat',
    brandMark: 'systemWordmark', shortcutIconStyle: 'systemOutline', chromeTypeface: 'system',
  }));
  assert.equal(r.issues.length, 0);
  assert.equal(r.pack.accent, '#B8623A');
  assert.equal(r.pack.accentInk, '#FFFFFF');
});

test('portable: inkHex 低对比自动改派可读色(不拒绝,防白字看不清)', () => {
  const r = parseThemePackImport(JSON.stringify({
    format: 'amber.theme.pack', version: 1, id: 'low-contrast', displayName: '低对比',
    paper: 'paper', accentHex: '#D9A441', inkHex: '#FFFFFF', canvasStyle: 'flat',
    brandMark: 'systemWordmark', shortcutIconStyle: 'systemOutline', chromeTypeface: 'system',
  }));
  assert.equal(r.issues.length, 0);
  assert.equal(r.pack.accentInk, '#1A1408'); // gold 既定深墨配对
  assert.ok(r.warnings.some((w) => w.includes('对比度不足')));
});

test('portable: inkHex 缺失自动推导 + 告警', () => {
  const r = parseThemePackImport(JSON.stringify({
    format: 'amber.theme.pack', version: 1, id: 'no-ink', displayName: '缺 ink',
    paper: 'paper', accentHex: '#B8623A', canvasStyle: 'flat',
    brandMark: 'systemWordmark', shortcutIconStyle: 'systemOutline', chromeTypeface: 'system',
  }));
  assert.equal(r.issues.length, 0);
  assert.equal(r.pack.accentInk, '#FFFFFF'); // 策展陶土既定白墨
  assert.ok(r.warnings.some((w) => w.includes('inkHex 缺失')));
});

test('portable: 沉浸色/未知 paper 回退暖纸 + 告警', () => {
  const mk = (paper) => parseThemePackImport(JSON.stringify({
    format: 'amber.theme.pack', version: 1, id: `p-${paper}`, displayName: paper,
    paper, accentHex: '#B8623A', inkHex: '#FFFFFF', canvasStyle: 'flat',
    brandMark: 'systemWordmark', shortcutIconStyle: 'systemOutline', chromeTypeface: 'system',
  }));
  const garnet = mk('garnet');
  assert.equal(garnet.issues.length, 0);
  assert.equal(garnet.pack.light.bg, '#EFE7D6'); // 回退暖纸
  assert.ok(garnet.warnings.some((w) => w.includes('沉浸色')));
  const weird = mk('holographic');
  assert.equal(weird.pack.light.bg, '#EFE7D6');
  assert.ok(weird.warnings.some((w) => w.includes('未知画布')));
});

test('portable: 严格拒绝项(version/id/name/accent/design 对比度)', () => {
  const base = {
    format: 'amber.theme.pack', version: 1, id: 'x', displayName: 'X',
    paper: 'paper', accentHex: '#B8623A', inkHex: '#FFFFFF', canvasStyle: 'flat',
    brandMark: 'systemWordmark', shortcutIconStyle: 'systemOutline', chromeTypeface: 'system',
  };
  const bad = (mutate) => {
    const doc = JSON.parse(JSON.stringify(base));
    mutate(doc);
    const r = parseThemePackImport(JSON.stringify(doc));
    assert.equal(r.pack, null);
    assert.ok(r.issues.length > 0);
    return r;
  };
  bad((d) => { d.version = 2; });
  bad((d) => { delete d.version; });
  bad((d) => { d.id = '  '; });
  bad((d) => { delete d.displayName; });
  bad((d) => { d.accentHex = 'rgb(1,2,3)'; });
  const contrast = bad((d) => {
    d.design = {
      light: {
        background: '#FFFFFF', surface: '#FFFFFF', foreground: '#777777',
        mutedForeground: '#999999', border: '#DDDDDD',
      },
    };
  });
  assert.ok(contrast.issues.some((i) => i.includes('4.5:1')));
});

test('portable: 顶层 format 其他值 / 非 JSON 拒绝', () => {
  const wrong = parseThemePackImport(JSON.stringify({ format: 'other.thing', version: 1 }));
  assert.equal(wrong.pack, null);
  assert.ok(wrong.issues.some((i) => i.includes('other.thing')));
  assert.equal(parseThemePackImport('not json at all').pack, null);
  assert.equal(parseThemePackImport('[1,2]').pack, null);
});

test('portable: id 撞内置主题自动改名', () => {
  const r = parseThemePackImport(JSON.stringify({
    format: 'amber.theme.pack', version: 1, id: 'sit-terracotta', displayName: '冒名',
    paper: 'white', accentHex: '#4F86D6', inkHex: '#FFFFFF', canvasStyle: 'flat',
    brandMark: 'systemWordmark', shortcutIconStyle: 'systemOutline', chromeTypeface: 'system',
  }));
  assert.equal(r.issues.length, 0);
  assert.ok(r.pack.id.startsWith('sit-terracotta-custom-'));
  assert.ok(r.warnings.some((w) => w.includes('内置')));
});

// ── Android 旧 token 包 ──

test('legacy: 全 token 包映射(SAGE 色板 + alpha 剥离 + 显示偏好)', () => {
  const r = parseThemePackImport(JSON.stringify({
    schemaVersion: 1, id: 'legacy-sage', name: '旧鼠尾草',
    colors: { baseFamily: 'SAGE', accent: '#805E9C6E' },
    fonts: { chatFontFamily: 'serif', fontSizeRatio: '1.2' },
    layout: { showUserAvatar: 'true', showAssistantBubble: 'true' },
  }));
  assert.equal(r.issues.length, 0, `issues: ${r.issues.join(';')}`);
  assert.equal(r.pack.accent, '#5E9C6E'); // #AARRGGBB 剥 alpha
  assert.ok(r.warnings.some((w) => w.includes('透明度')));
  assert.equal(r.pack.light.bg, '#F0F2EA'); // Android AmberSage
  assert.equal(r.pack.dark.bg, '#131711'); // Android AmberSageDark
  assert.equal(r.pack.dotGrid, false);
  sameData(r.display, {
    chatFontFamily: 'SERIF', fontSizeRatio: 1.2,
    showUserAvatar: true, showAssistantBubble: true, amberBaseFamily: 'SAGE',
  });});

test('legacy: WARM 默认与缺省 token', () => {
  const r = parseThemePackImport(JSON.stringify({ schemaVersion: 1, id: 'legacy-warm', name: '旧暖纸' }));
  assert.equal(r.issues.length, 0);
  assert.equal(r.pack.light.bg, '#EFE7D6'); // WARM = 点阵·陶土色板
  assert.equal(r.pack.accent, '#B8623A');
  assert.equal(r.pack.dotGrid, true);
  assert.equal(r.display, null); // 无 colors/fonts/layout → 不动显示设置
});

test('legacy: 拒绝项(schemaVersion/builtin id/非法 token 值)', () => {
  const bad = (doc) => {
    const r = parseThemePackImport(JSON.stringify(doc));
    assert.equal(r.pack, null);
    assert.ok(r.issues.length > 0);
    return r;
  };
  bad({ schemaVersion: 2, id: 'x', name: 'X' });
  bad({ schemaVersion: 1, id: 'builtin:WARM', name: 'X' });
  bad({ schemaVersion: 1, id: 'x', name: 'X', colors: { baseFamily: 'COOL' } });
  bad({ schemaVersion: 1, id: 'x', name: 'X', colors: { accent: '#12345' } });
  bad({ schemaVersion: 1, id: 'x', name: 'X', fonts: { fontSizeRatio: '3.0' } });
  bad({ schemaVersion: 1, id: 'x', name: 'X', layout: { showUserAvatar: 'yes' } });
});

test('legacy: 内嵌跨平台配方优先 + 身份不一致拒绝', () => {
  const doc = JSON.parse(fixture);
  doc.id = 'wrap';
  doc.displayName = '外套';
  const ok = parseThemePackImport(JSON.stringify({
    schemaVersion: 1, id: 'wrap', name: '外套', document: doc,
    fonts: { chatFontFamily: 'monospace' },
  }));
  assert.equal(ok.issues.length, 0, `issues: ${ok.issues.join(';')}`);
  assert.equal(ok.pack.light.bg, '#F5F7F4'); // 用内嵌 design 而非 WARM
  assert.equal(ok.display.chatFontFamily, 'MONO'); // 旧字体 token 仍落地
  const mismatch = parseThemePackImport(JSON.stringify({
    schemaVersion: 1, id: 'wrap', name: '别名下套', document: doc,
  }));
  assert.equal(mismatch.pack, null);
  assert.ok(mismatch.issues.some((i) => i.includes('不一致')));
});

// ── 鸿蒙原生格式(向后兼容) ──

test('native: 9 槽原生格式 + accent_ink 往返', () => {
  const palette = {
    bg: '#EFE7D6', surface: '#FFFDF7', surface2: '#F0EBE2',
    ink: '#1B1813', ink2: '#5B5449', muted: '#746D62', muted2: '#918A80',
    border: '#DBCEBC', borderSoft: '#ECE3D6',
  };
  const r = parseThemePackImport(JSON.stringify({
    format: 'amber-theme-pack', version: 1, id: 'native-x', name: '原生',
    accent: '#9277C4', accent_ink: '#FFFAF0', dot_grid: true, light: palette, dark: palette,
  }));
  assert.equal(r.issues.length, 0);
  assert.equal(r.pack.accentInk, '#FFFAF0');
  assert.equal(r.pack.light.surface2, '#F0EBE2'); // 原生 9 槽原样保留
  // KV 持久化往返(themePackToJson → parseThemePackJson)
  const roundTrip = parseThemePackJson(themePackToJson(r.pack));
  assert.equal(roundTrip.accentInk, '#FFFAF0');
  assert.equal(roundTrip.dotGrid, true);
});

test('native: 无 format 的 AI 生成文档仍走宽松通道', () => {
  const palette = {
    bg: '#101010', surface: '#181818', surface2: '#202020',
    ink: '#EEEEEE', ink2: '#BBBBBB', muted: '#999999', muted2: '#666666',
    border: '#333333', borderSoft: '#282828',
  };
  const r = parseThemePackImport(JSON.stringify({
    name: '深夜', accent: '#CC5522', dot_grid: false, light: palette, dark: palette,
  }));
  assert.equal(r.issues.length, 0);
  assert.ok(r.pack.id.startsWith('custom-'));
  assert.equal(r.pack.accentInk, undefined); // 无 accent_ink → 应用时自动推导
});

// ── 导出(跨平台 v1 + 鸿蒙扩展段无损回读) ──

test('export: 内置包导出为合法 amber.theme.pack v1', () => {
  const json = JSON.parse(themePackToPortableJson(BUILTIN_PACKS[0]));
  assert.equal(json.format, 'amber.theme.pack');
  assert.equal(json.version, 1);
  assert.equal(json.id, 'sit-terracotta');
  assert.equal(json.paper, 'paper');
  assert.equal(json.canvasStyle, 'dotGrid');
  assert.equal(json.brandMark, 'paintAMBER');
  assert.equal(json.shortcutIconStyle, 'pixelSit');
  assert.equal(json.chromeTypeface, 'rounded');
  assert.equal(json.accentHex, '#B8623A');
  assert.equal(json.inkHex, '#FFFFFF');
  assert.equal(json.design.light.background, '#EFE7D6');
  assert.equal(json.design.light.foreground, '#1B1813');
  assert.deepEqual(json.design.patterns, []); // iOS Codable 要求 design 内含 patterns
  assert.equal(json.harmony.dotGrid, true);
  assert.equal(json.harmony.light.surface2, '#F0EBE2');
  const pi = JSON.parse(themePackToPortableJson(BUILTIN_PACKS[1]));
  assert.equal(pi.paper, 'pi');
  assert.equal(pi.canvasStyle, 'lineGrid');
  assert.equal(pi.inkHex, '#FAF9F7'); // steelBlue 奶油墨
});

test('export: 鸿蒙扩展段无损回读(导出 → 再导入 = 色板逐值相等)', () => {
  const original = BUILTIN_PACKS[2]; // notion-blue
  const text = themePackToPortableJson(original);
  const r = parseThemePackImport(text);
  assert.equal(r.issues.length, 0);
  sameData(r.pack.light, original.light);
  sameData(r.pack.dark, original.dark);
  assert.equal(r.pack.dotGrid, original.dotGrid);
  assert.equal(r.pack.accentInk, '#FFFFFF');
  // 再导入 id 撞内置 → 自动改名(导入包不允许覆盖内置)
  assert.ok(r.pack.id.startsWith('notion-blue-custom-'));
});

test('export: 自定义包导出带 design + 扩展段', () => {
  const custom = {
    id: 'my-pack', displayName: '我的', accent: '#4F86D6', accentInk: '#FFFFFF',
    dotGrid: true, builtin: false,
    light: {
      bg: '#F5F7F4', surface: '#FFFFFF', surface2: '#FAFBF9',
      ink: '#17211A', ink2: '#17211A', muted: '#46584B', muted2: '#46584B',
      border: '#D3DDD4', borderSoft: '#D3DDD4',
    },
    dark: {
      bg: '#121915', surface: '#1C2620', surface2: '#171F1A',
      ink: '#EAF2EB', ink2: '#EAF2EB', muted: '#B4C5B7', muted2: '#B4C5B7',
      border: '#39473D', borderSoft: '#39473D',
    },
  };
  const json = JSON.parse(themePackToPortableJson(custom));
  assert.equal(json.paper, 'paper');
  assert.equal(json.brandMark, 'systemWordmark');
  assert.equal(json.design.dark.background, '#121915');
  const r = parseThemePackImport(JSON.stringify(json));
  assert.equal(r.issues.length, 0);
  assert.equal(r.pack.id, 'my-pack'); // 非内置 id 不改名
  sameData(r.pack.light, custom.light);
});

// ── tokens.ets accentInk 覆写语义 ──

test('themes: 思考框淡底随内置主题色变化,浅深模式均保持文字可读', () => {
  for (const dark of [false, true]) {
    const backgrounds = BUILTIN_PACKS.map((pack) => {
      packs.applyThemePack(pack, dark);
      const bg = tokens.parseHexRgb(tokens.ACCENT_LIGHT);
      assert.ok(tokens.contrastRatio(bg, tokens.parseHexRgb(tokens.INK2)) >= 4.5,
        `${pack.id} dark=${dark}: 思考文字对比度`);
      return tokens.ACCENT_LIGHT;
    });
    assert.equal(new Set(backgrounds).size, BUILTIN_PACKS.length,
      `dark=${dark}: 不同强调色不应共用陶土淡底`);
  }
});

test('themes: 导入主题淡底随自定义强调色和纸色变化,切换模式后重新派生', () => {
  const green = parseThemePackImport(fixture).pack;
  const purple = { ...green, id: 'custom-purple', accent: '#7842AB' };
  for (const dark of [false, true]) {
    packs.applyThemePack(green, dark);
    const greenBg = tokens.ACCENT_LIGHT;
    const greenRgb = tokens.parseHexRgb(greenBg);
    assert.ok(((greenRgb >> 8) & 255) > (greenRgb & 255), '绿色淡底应保留绿色倾向');
    packs.applyThemePack(purple, dark);
    assert.notEqual(tokens.ACCENT_LIGHT, greenBg, '导入强调色变化应改变淡底');
    const purpleRgb = tokens.parseHexRgb(tokens.ACCENT_LIGHT);
    assert.ok((purpleRgb & 255) > ((purpleRgb >> 8) & 255), '紫色淡底应保留紫色倾向');
    assert.ok(tokens.contrastRatio(purpleRgb, tokens.parseHexRgb(tokens.INK2)) >= 4.5);
  }
  packs.applyThemePack(purple, false);
  const lightBg = tokens.ACCENT_LIGHT;
  tokens.applyTheme(true);
  assert.notEqual(tokens.ACCENT_LIGHT, lightBg, '直接切换深色也应重新派生');
  packs.applyThemePack(purple, false);
  const otherPaper = { ...purple, light: { ...purple.light, surface: '#EFE7D6' } };
  packs.applyThemePack(otherPaper, false);
  assert.notEqual(tokens.ACCENT_LIGHT, lightBg, '同一强调色应适应主题纸色');
});

test('tokens: setAccentColor 第二参确立/清除 ink 覆写,applyTheme 保持覆写', () => {
  const { setAccentColor, applyTheme, ACCENT_INK } = tokens;
  void ACCENT_INK;
  setAccentColor('#B8623A');
  assert.equal(tokens.ACCENT_INK, '#FFFFFF'); // 策展陶土既定白墨
  setAccentColor('#315B42', '#FFF8E7');
  assert.equal(tokens.ACCENT_INK, '#FFF8E7'); // 包覆写
  applyTheme(true); // 模式切换不丢覆写
  assert.equal(tokens.ACCENT_INK, '#FFF8E7');
  applyTheme(false);
  setAccentColor('#315B42'); // 裸调清覆写 → 对比度自动推导(深绿 → 白)
  assert.equal(tokens.ACCENT_INK, '#FFFFFF');
  setAccentColor('#D9A441'); // gold 既定深墨
  assert.equal(tokens.ACCENT_INK, '#1A1408');
  setAccentColor('#6B8CAD'); // steelBlue → iOS 奶油墨(对比度公式会给黑,配对优先)
  assert.equal(tokens.ACCENT_INK, '#FAF9F7');
});

// ── design 纹理层(patterns / gradient;老花包回归) ──

const espresso = fs.readFileSync(path.join(__dirname, 'fixtures/espresso-monogram.json'), 'utf8');

test('textures: 老花包 patterns/gradient/designDark 全量解析', () => {
  const r = parseThemePackImport(espresso);
  assert.equal(r.issues.length, 0, `issues: ${r.issues.join(';')}`);
  assert.ok(r.pack);
  assert.equal(r.pack.displayName, '咖啡 · 老花');
  assert.equal(r.pack.accent, '#6B4A2F');
  assert.equal(r.pack.accentInk, '#FFF8F0'); // 0x 前缀归一化
  assert.equal(r.pack.dotGrid, false); // canvasStyle flat
  assert.equal(r.pack.designDark, true); // 带 design.dark
  // 三层纹理逐值
  assert.equal(r.pack.patterns.length, 3);
  sameData(r.pack.patterns[0], { kind: 'diagonal', color: '#8B6A4E', opacity: 0.045, spacing: 34, size: 0.7 });
  sameData(r.pack.patterns[1], { kind: 'crosses', color: '#9C7C4A', opacity: 0.055, spacing: 26, size: 3.5 });
  sameData(r.pack.patterns[2], { kind: 'rings', color: '#7A5A3E', opacity: 0.05, spacing: 52, size: 3 });
  // 渐变双 palette 各 4 stops + 角度
  sameData(r.pack.gradient, {
    colors: ['#EADCCB', '#F5EDE2', '#FAF3E9', '#FDF9F3'],
    darkColors: ['#2E2118', '#251A11', '#1E140C', '#1A120C'],
    angle: 90,
  });
  // canvasStyle flat + design 双 palette → 色板走 design 而非 paper
  assert.equal(r.pack.light.bg, '#F3EAE0');
  assert.equal(r.pack.dark.bg, '#1A120C');
  // canvasScope appWide → 作用域入包(设置树/其余集成面可见纹理)
  assert.equal(r.pack.canvasScope, 'appWide');
});

test('textures: 老花包导出往返(patterns/gradient 无损)', () => {
  const first = parseThemePackImport(espresso);
  assert.ok(first.pack);
  const reimported = parseThemePackImport(themePackToPortableJson(first.pack));
  assert.equal(reimported.issues.length, 0, `issues: ${reimported.issues.join(';')}`);
  assert.equal(reimported.pack.canvasScope, 'appWide'); // 作用域随导出往返
  sameData(reimported.pack.patterns, first.pack.patterns);
  sameData(reimported.pack.gradient, first.pack.gradient);
  sameData(reimported.pack.light, first.pack.light);
  // KV 持久化往返(原生文档)
  const kv = parseThemePackJson(themePackToJson(first.pack));
  sameData(kv.patterns, first.pack.patterns);
  sameData(kv.gradient, first.pack.gradient);
  assert.equal(kv.designDark, true);
});

test('textures: patterns 严格校验拒绝项(两端协议对齐)', () => {
  const base = JSON.parse(espresso);
  const bad = (mutate, expectMsg) => {
    const doc = JSON.parse(JSON.stringify(base));
    mutate(doc);
    const r = parseThemePackImport(JSON.stringify(doc));
    assert.equal(r.pack, null, `应拒绝:${expectMsg}`);
    assert.ok(r.issues.some((i) => i.includes(expectMsg)), `issues 应含「${expectMsg}」:${r.issues.join(';')}`);
  };
  bad((d) => { d.design.patterns = new Array(4).fill(d.design.patterns[0]); }, '最多支持 3 层');
  bad((d) => { d.design.patterns[0].kind = 'hexagons'; }, '不受支持');
  bad((d) => { d.design.patterns[0].color = '#12345'; }, '必须是 RGB 十六进制颜色');
  bad((d) => { d.design.patterns[0].opacity = 0.5; }, '0 到 0.3');
  bad((d) => { d.design.patterns[0].spacing = 5; }, '12 到 120');
  bad((d) => { d.design.patterns[0].size = 9; }, '0.5 到 8');
  bad((d) => { d.design.gradient.colors = ['#EADCCB']; }, '2 到 4 个颜色');
  bad((d) => { delete d.design.gradient.darkColors; }, '2 到 4 个颜色');
  bad((d) => { d.design.gradient.colors[0] = '#2A1D16'; }, '对比度'); // 与 foreground 同色
  bad((d) => { d.design.gradient.angle = '90'; }, '有限角度');
  bad((d) => { delete d.design.dark; }, '需要同时提供 light 和 dark');
});

test('textures: AMOLED 跳过带 design.dark 的包(Android 同规则)', async () => {
  const kv = { get: async () => null, put: async () => {} };
  const { setAmoledBlack, applyThemePack, BUILTIN_PACKS: builtins } = packs;
  const { BG } = tokens;
  void BG;
  await setAmoledBlack(kv, true, true); // 内置包 + AMOLED → 纯黑
  assert.equal(tokens.BG, '#000000');
  const r = parseThemePackImport(espresso);
  applyThemePack(r.pack, true); // 老花带 design.dark → AMOLED 不覆写
  assert.equal(tokens.BG, '#1A120C');
  applyThemePack(builtins[0], true); // 切回内置 → AMOLED 恢复纯黑
  assert.equal(tokens.BG, '#000000');
  await setAmoledBlack(kv, false, true); // 收尾:关掉 AMOLED 模块态
});

test('textures: 无纹理的包 patterns/gradient 为空(访问器退化)', () => {
  // 内置包导出 patterns:[]、无 gradient → 再导入应为"无纹理"(designDark 因导出恒带 design 而为 true)
  const r = parseThemePackImport(themePackToPortableJson(BUILTIN_PACKS[0]));
  assert.equal(r.issues.length, 0);
  assert.ok(r.pack);
  assert.equal(r.pack.patterns, undefined);
  assert.equal(r.pack.gradient, undefined);
  assert.equal(r.pack.designDark, true);
  const { currentCanvasGradient, currentCanvasGradientAngle, currentCanvasPatterns } = packs;
  assert.equal(typeof currentCanvasGradientAngle(), 'number');
  assert.ok(Array.isArray(currentCanvasPatterns()));
  assert.ok(currentCanvasGradient(false) === null || Array.isArray(currentCanvasGradient(false)));
});

test('textures: canvasScope 未知值回退 homeOnly + 告警;内置导出带 shell', () => {
  const doc = JSON.parse(espresso);
  doc.canvasScope = 'everywhere';
  const r = parseThemePackImport(JSON.stringify(doc));
  assert.equal(r.issues.length, 0);
  assert.equal(r.pack.canvasScope, undefined); // 未知值 → 默认 homeOnly 语义
  assert.ok(r.warnings.some((w) => w.includes('canvasScope')));
  const json = JSON.parse(themePackToPortableJson(BUILTIN_PACKS[0]));
  assert.equal(json.canvasScope, 'shell'); // iOS 内置 sit-terracotta 作用域
  const notion = JSON.parse(themePackToPortableJson(BUILTIN_PACKS[2]));
  assert.equal(notion.canvasScope, 'homeOnly');
});
