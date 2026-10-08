const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const ENTRY = path.resolve(__dirname, '../../../entry/src/main/ets');
const transpile = source => ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
}).outputText;
const block = (source, start) => {
  assert.ok(start >= 0);
  let depth = 1, end = source.indexOf('{', start) + 1;
  for (; depth && end < source.length; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  return source.slice(start, end);
};

for (const title of ['文章 A', '完整文章标题'.repeat(100)]) {
test(`single DeepRead activity retains its ${title.length}-character title through actual article entry`, async () => {
  const storage = new Map();
  const AppStorage = { get: key => storage.get(key), setOrCreate: (key, value) => storage.set(key, value) };
  AppStorage.setOrCreate('abilityContext', { applicationInfo: { name: 'app.amber.deepread.reader' } });
  const log = { info() {}, warn() {}, error() {} };
  let snapshot;
  const imports = {
    '@kit.ArkTS': { util: { TextEncoder: { create: () => ({ encodeInto: text => new TextEncoder().encode(text) }) } } },
    '@kit.AbilityKit': { UIAbility: class {}, wantAgent: {
      getWantAgent: async info => info,
      OperationType: { START_ABILITY: 0 }, WantAgentFlags: { UPDATE_PRESENT_FLAG: 3 },
    } },
    '@kit.NotificationKit': { notificationManager: {
      SlotType: { LIVE_VIEW: 4 }, ContentType: { NOTIFICATION_CONTENT_SYSTEM_LIVE_VIEW: 5 },
    } },
    '@kit.PerformanceAnalysisKit': { hilog: log },
    '../platform_impl/DeepReadMotionPreferences.ets': { DeepReadMotionPreferences: class {
      start() {} refresh() {} stop() {}
    } },
  };
  const load = (relative, extra = '') => {
    const exports = {};
    const file = path.join(ENTRY, relative);
    vm.runInNewContext(transpile(fs.readFileSync(file, 'utf8') + extra), {
      exports, require: name => imports[name] || {}, AppStorage,
      Promise, Error, Map, JSON, String, Number, Date, setTimeout, clearTimeout,
    }, { filename: file });
    return exports;
  };
  const identity = load('platform_impl/ProductIdentity.ets');
  imports['./ProductIdentity.ets'] = identity;
  imports['./GenerationProgressTracker.ets'] = load('platform_impl/GenerationProgressTracker.ets');
  imports['./BackgroundGenerationKeepAlive.ets'] = {
    reportGenerationProgress: (_context, value) => { snapshot = value; },
  };
  const activity = load('platform_impl/DeepReadGenerationActivity.ets');
  activity.setDeepReadGenerationActivity('topic-A', 1, true, title);
  activity.beginDeepReadGenerationStep('topic-A', 1, []);
  activity.observeDeepReadGenerationSnapshot('topic-A', 1,
    [{ id: 'body', role: 'assistant', parts: [{ type: 'text', text: '正文' }] }]);
  const keepAlive = load('platform_impl/BackgroundGenerationKeepAlive.ets',
    '\nexports.buildActivityWantAgentForTest = buildActivityWantAgent;');
  const info = await keepAlive.buildActivityWantAgentForTest(snapshot);
  assert.equal(info.wants[0].bundleName, 'app.amber.deepread.reader');

  const entry = load('entryability/EntryAbility.ets');
  const routes = [];
  entry.markWantRoutingReady({ pushUrl: async options => { routes.push(options); } });
  new entry.default().routeFromWant(info.wants[0]);
  await Promise.resolve();
  assert.equal(routes[0].url, 'pages/DeepReadArticlePage');

  const source = fs.readFileSync(path.join(ENTRY, 'pages/DeepReadArticlePage.ets'), 'utf8');
  const method = block(source, source.indexOf('  aboutToAppear(): void {'));
  const clearMotion = block(source, source.indexOf('  private clearEditorialMotion(): void {'));
  const exports = {};
  vm.runInNewContext(transpile(`class Page { ${method} ${clearMotion} }\nexports.Page = Page;`), {
    exports, router: { getParams: () => routes[0].params }, hilog: log, DOMAIN: 0, TAG: 'test',
    getProductKind: identity.getProductKind,
  });
  let articleLoads = 0;
  const page = new exports.Page();
  Object.assign(page, { pageToken: 0, webSession: 0, workspaceRequest: 0,
    motionCycle: 0, completionStamp: 0,
    loadArticle: async () => { articleLoads++; } });
  page.aboutToAppear();
  assert.equal(articleLoads, 1, `article entry rejected the activity Want: ${page.errorMsg}`);
  assert.equal(page.title, title);

  activity.setDeepReadGenerationActivity('topic-A', 1, false, title);
  assert.equal(snapshot.generationActive, false);
  assert.equal(snapshot.title, title);
});
}

test('display titles reject nonstrings while stable topic IDs retain their length limit', async () => {
  const storage = new Map();
  const exports = {};
  const filename = path.join(ENTRY, 'entryability/EntryAbility.ets');
  vm.runInNewContext(transpile(fs.readFileSync(filename, 'utf8')), {
    exports, require: name => name === '@kit.AbilityKit' ? { UIAbility: class {} }
      : name === '../platform_impl/DeepReadMotionPreferences.ets' ? { DeepReadMotionPreferences: class {
        start() {} refresh() {} stop() {}
      } }
      : name === '@kit.PerformanceAnalysisKit' ? { hilog: { info() {}, warn() {}, error() {} } } : {},
    AppStorage: { get: key => storage.get(key), setOrCreate: (key, value) => storage.set(key, value) },
    Promise, Error,
  }, { filename });
  const routes = [];
  exports.markWantRoutingReady({ pushUrl: async options => { routes.push(options); } });
  const entry = new exports.default();
  entry.routeFromWant({ parameters: { topicId: 'topic'.repeat(130), title: '合法标题' } });
  await Promise.resolve();
  assert.equal(routes.length, 0, 'oversized stable IDs remain rejected');
  entry.routeFromWant({ parameters: { topicId: 'valid-topic', title: { text: '不是字符串' } } });
  await Promise.resolve();
  assert.equal(routes[0].params.title, '');
  assert.equal(routes[0].params.topicId, 'valid-topic');
});
