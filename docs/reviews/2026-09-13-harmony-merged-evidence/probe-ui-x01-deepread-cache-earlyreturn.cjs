// probe-ui-x01-deepread-cache-earlyreturn.cjs — new UI-X01 probe.
//
// METHOD: extracts the REAL `loadArticle`, `applyOutput`, `phaseLabel`, `progressLabel`,
// `stageLabel` methods and the involved fields of `DeepReadArticlePage` VERBATIM from
// harmony/entry/src/main/ets/pages/DeepReadArticlePage.ets via the TypeScript AST
// (probe-lib.cjs). Nothing in the code under test is re-typed. `struct` -> `class` is a
// parse-only substitution; `build()` and all ArkUI-only members are excluded.
//
// Only platform edges are mocked: router/promptAction, hilog, the repository (cache),
// the KV store (instrumented to record which keys are read), the scheduler, and the
// deepread-domain helper exports are the REAL sources.
//
// Safety note: this probe performs no UI rendering, no device access, no network, and
// touches no real file. It only exercises the page's real control flow headlessly.

const path = require('node:path');
const assert = require('node:assert/strict');
const {
  REPO_ROOT, parsePage, findClass, memberText, fieldDecl, interfaceText, createLoader, sha,
} = require('./probe-lib.cjs');

const DR = path.join(REPO_ROOT, 'harmony/deepread/src/main/ets');

// ---- real deepread-domain pieces (helpers + progress + enums) ----
const loader = createLoader(Object.assign((name, load) => {
  if (name.endsWith('models.ts')) return load(path.join(DR, 'domain/models.ts'));
  if (name.endsWith('enums.ts')) return load(path.join(DR, 'domain/enums.ts'));
  if (name.endsWith('helpers.ts')) return load(path.join(DR, 'domain/helpers.ts'));
  if (name.endsWith('deep_read_progress.ts')) return load(path.join(DR, 'platform/deep_read_progress.ts'));
  if (name.endsWith('repository.ts')) return load(path.join(DR, 'platform/repository.ts'));
  if (name === '@kit.PerformanceAnalysisKit') return { hilog: { info() {}, warn() {}, error() {} } };
  if (name === '@kit.ArkUI') return { router: { getParams: () => ({}) }, promptAction: { showToast() {} } };
  if (name === '@kit.AbilityKit') return { common: {} };
  if (name === '@kit.ArkWeb') return { webview: { WebviewController: class {} } };
  if (name === '@amber/deepread-domain') return domainMock;
  if (name.endsWith('di/AppContainer.ets')) {
    return { getRepository, getChatKvStore, getDeepReadScheduler };
  }
  // ArkUI component / design / platform modules used only by the excluded build():
  return {};
}, {
  sandboxGlobals: {
    console, setTimeout, clearTimeout, Date, JSON, Math, Number, Object, Array, Promise,
  },
}));

const helpers = loader.load(path.join(DR, 'domain/helpers.ts'));
const progressMod = loader.load(path.join(DR, 'platform/deep_read_progress.ts'));
const enums = loader.load(path.join(DR, 'domain/enums.ts'));

const domainMock = {
  ...helpers,
  ...progressMod,
  STAGE_ORDER: enums.STAGE_ORDER,
};

// ---- instrumented KV + fake repository/scheduler injected into the page's import ----
const kvReads = [];
const KV = {
  deepread_font_scale: '125',
  deepread_font_mode: 'serif',
  deepread_template_id: 'editorial_slant',
  deepread_first_use_confirmed: 'true',
};
const kvGet = async (k) => { kvReads.push(k); return KV[k] ?? null; };

let repositoryGetResult = null;
function getRepository() {
  return {
    get: async () => repositoryGetResult,
    observe: () => ({ subscribe: () => () => {}, getCurrent: () => undefined }),
  };
}
function getChatKvStore() { return { get: kvGet, put: async () => {}, delete: async () => {} }; }
function getDeepReadScheduler() {
  return Promise.resolve({
    run: async () => ({ ok: false, output: null, error: 'aborted' }),
    observeRunning: () => ({ subscribe: () => () => {}, getCurrent: () => false }),
    abort() {},
    isRunning: () => false,
  });
}

// ---- assemble the headless class from ORIGINAL source via the TS AST ----
const PAGE = path.join(REPO_ROOT, 'harmony/entry/src/main/ets/pages/DeepReadArticlePage.ets');
const { raw, sf } = parsePage(PAGE);
const cls = findClass(sf, 'DeepReadArticlePage');
assert.ok(cls !== null, 'DeepReadArticlePage class node found');

const header = raw.slice(0, raw.indexOf('interface ArticleParams'));
const articleParams = interfaceText(sf, 'ArticleParams');

const FIELDS = [
  'topicId', 'title', 'sourceUrl', 'output', 'running', 'errorMsg', 'currentStage',
  'cancelled', 'confirmed', 'progress', 'historyExpired', 'templateId', 'fontScale',
  'fontSerif', 'pageAlive', 'pageToken', 'unsubscribeOutput', 'unsubscribeRunning', 'scheduler',
];
const fields = FIELDS.map((n) => fieldDecl(sf, cls, n));
const methods = ['loadArticle', 'applyOutput', 'phaseLabel', 'progressLabel', 'stageLabel']
  .map((n) => memberText(sf, cls, n));

const loadArticleSrc = methods[0];
assert.ok(loadArticleSrc.includes('isComplete(cached.output)'), 'loadArticle slice is real');
assert.ok(loadArticleSrc.includes('deepread_font_scale'), 'loadArticle carries the font read');
const fontIdx = loadArticleSrc.indexOf('deepread_font_scale');
const earlyRetIdx = loadArticleSrc.indexOf('isComplete(cached.output)');
assert.ok(earlyRetIdx >= 0 && fontIdx > earlyRetIdx,
  'ORDER: complete-cache early return precedes the font/template reads');
console.log('[extract] loadArticle sha256/16:', sha(loadArticleSrc), 'len', loadArticleSrc.length);
console.log('[extract] offset(isComplete gate)=', earlyRetIdx, '< offset(font read)=', fontIdx);

const assembled = [
  header,
  articleParams,
  'class DeepReadArticlePage {',
  ...fields,
  ...methods,
  '}',
  'module.exports = { DeepReadArticlePage };',
].join('\n');

const dir = path.join(REPO_ROOT, 'docs/reviews/2026-09-13-harmony-merged-evidence/.probe-tmp');
require('node:fs').mkdirSync(dir, { recursive: true });
const tmp = path.join(dir, 'DeepReadArticlePage.headless.ets');
require('node:fs').writeFileSync(tmp, assembled);
const pageModule = loader.load(tmp);

const completeOutput = () => ({
  generationComplete: true, generationPhase: 'COMPLETE',
  sectionStates: {
    OVERVIEW: { status: 'READY' }, NARRATIVE: { status: 'READY' },
    ANALYSIS: { status: 'READY' }, EXTENDED_READING: { status: 'READY' },
  },
});
const partialOutput = () => ({
  generationComplete: false, generationPhase: 'COLLECTING',
  sectionStates: { OVERVIEW: { status: 'READY' }, NARRATIVE: { status: 'READY' },
    ANALYSIS: { status: 'PENDING' }, EXTENDED_READING: { status: 'PENDING' } },
});

(async () => {
  // T1: completed cached article reopened (force=false) while user prefers 125%/serif/editorial
  kvReads.length = 0;
  repositoryGetResult = { output: completeOutput() };
  let page = new pageModule.DeepReadArticlePage();
  page.topicId = 't1'; page.title = 'T'; page.pageAlive = true; page.pageToken = 1;
  await page.loadArticle(1, false);
  console.log('[T1] complete-cache reopen -> kv reads:', JSON.stringify(kvReads),
    '| fontScale:', page.fontScale, '| fontSerif:', page.fontSerif, '| templateId:', page.templateId);
  assert.deepEqual(kvReads, [], 'NO display settings read on complete reopen');
  assert.equal(page.fontScale, 1.0, 'stays default, 125% preference ignored');
  assert.equal(page.fontSerif, false, 'stays non-serif, serif preference ignored');
  assert.equal(page.templateId, 'none', 'stays none, editorial_slant preference ignored');
  console.log('[T1] CONFIRMED: completed article reopen ignores font scale / serif / template.');

  // T2: partial cached article — same preferences ARE applied
  kvReads.length = 0;
  repositoryGetResult = { output: partialOutput() };
  page = new pageModule.DeepReadArticlePage();
  page.topicId = 't2'; page.title = 'T'; page.pageAlive = true; page.pageToken = 1;
  await page.loadArticle(1, false);
  console.log('[T2] partial-cache reopen -> kv reads:', JSON.stringify(kvReads),
    '| fontScale:', page.fontScale, '| fontSerif:', page.fontSerif, '| templateId:', page.templateId);
  assert.deepEqual(kvReads,
    ['deepread_font_scale', 'deepread_font_mode', 'deepread_template_id', 'deepread_first_use_confirmed']);
  assert.equal(page.fontScale, 1.25);
  assert.equal(page.fontSerif, true);
  assert.equal(page.templateId, 'editorial_slant');
  console.log('[T2] CONFIRMED: only the non-complete path loads display settings.');

  console.log('\nAssertions passed. Real page methods, headless; no UI rendering/device/network/real files.');
})().catch((e) => { console.error(e); process.exitCode = 1; });
