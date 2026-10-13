// MiniApp 揭示动效入口契约:资格来源、资源稳定屏障和生命周期取消。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { actualPage } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');

const entryRoot = path.resolve(__dirname, '..');
const read = (file) => fs.readFileSync(path.join(entryRoot, 'main/ets', file), 'utf8');
const runtime = read('components/InlineMiniAppRuntime.ets');
const runner = read('pages/MiniAppRunnerPage.ets');
const card = read('components/MiniAppChatCard.ets');

test('inline and standalone runners share one app/version key and gate play on stable resources', () => {
  assert.match(runtime, /new UIGenerationMotion\(this\.controller\)/);
  assert.match(runtime, /this\.motion\.play\(`miniapp:\$\{app\.id\}:\$\{app\.version\}`,[\s\S]*false\)/);
  assert.match(runtime, /this\.prefetchRefreshPending/);
  assert.match(runtime, /this\.motion\.cancel\(false\)/);
  assert.match(runtime, /this\.motion\.cancel\(true\)/);

  assert.match(runner, /new UIGenerationMotion\(this\.controller\)/);
  assert.match(runner, /this\.revealEligible = record\.runCount === 0/);
  assert.ok(runner.indexOf('this.revealEligible = record.runCount === 0') < runner.indexOf('platform.repository.markRun(record.id)'));
  assert.match(runner, /this\.motion\.play\(`miniapp:\$\{app\.id\}:\$\{app\.version\}`,[\s\S]*false\)/);
  assert.match(runner, /this\.prefetchRefreshPending/);
  assert.match(runner, /\.onTouch\(\(event: TouchEvent\): void => \{[\s\S]*this\.motion\.cancel\(true\)/);
  assert.match(runner, /const lifecycleRevision: number = \+\+this\.loadRevision/);
  assert.match(runner, /lifecycleRevision !== this\.loadRevision/);
  assert.match(runner, /this\.pageReady = false;[\s\S]*this\.prefetchRefreshPending = false;/);
  assert.match(runtime, /this\.ready = false;[\s\S]*this\.prefetchRefreshPending = false;/);
});

test('chat card carries reveal eligibility only across stream-to-saved-part transition', async () => {
  const app = { id: 'generated', version: 1, htmlContent: '<html></html>' };
  const platform = {
    settingSnapshot: () => ({ showSourceButton: true }),
    repository: { getById: async () => app },
  };
  const makePage = (part, streamText, streamPreviewActive) => {
    const page = actualPage('components/MiniAppChatCard.ets',
      ['refreshStreamPreview', 'loadPreview'], { getMiniAppPlatform: async () => platform });
    Object.assign(page, {
      alive: true,
      previewRevision: 0,
      part,
      streamText,
      streamPreviewActive,
      previewAppId: part === null ? '' : part.appId,
      previewHtml: '',
      previewApp: null,
      revealEligible: false,
      streamCode: '',
      streamPreview: {
        update() {},
        displayText: () => streamText,
        htmlText: () => streamText,
      },
      sourceAllowed: false,
      showCode: false,
    });
    return page;
  };

  const generated = makePage(null, '<html>streaming</html>', false);
  generated.refreshStreamPreview();
  generated.part = { appId: 'generated' };
  generated.loadPreview();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(generated.revealEligible, true);

  const historical = makePage({ appId: 'generated' }, '', false);
  historical.loadPreview();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(historical.revealEligible, false);
});

test('card passes eligibility to the inline runtime and keeps preview script-free while streaming', () => {
  assert.match(card, /revealEligible: boolean/);
  assert.match(card, /revealEligible: this\.revealEligible/);
  assert.match(card, /revealKey: this\.previewApp === null \|\| this\.part === null/);
  assert.match(card, /revealEligible: this\.previewApp !== null && this\.part !== null && this\.revealEligible/);
  assert.match(card, /VisualArtifactPreview\(\{/);
});

test('runner platform resolution cannot load a page after the route is gone', async () => {
  let resolvePlatform;
  const platformPromise = new Promise((resolve) => { resolvePlatform = resolve; });
  let loads = 0;
  const page = actualPage('pages/MiniAppRunnerPage.ets', ['aboutToAppear'], {
    router: { getParams: () => ({ appId: 'gone' }) },
    getMiniAppPlatform: () => platformPromise,
  });
  Object.assign(page, {
    alive: false,
    loadRevision: 0,
    loadApp: () => { loads++; },
  });
  page.aboutToAppear();
  assert.equal(page.alive, true);
  page.alive = false;
  page.loadRevision++;
  resolvePlatform({ settingSnapshot: () => ({ webViewDebugEnabled: false }) });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(loads, 0);
  assert.equal(page.platform, undefined);
});

test('real inline methods defer prefetch and play until foreground, using the main-document adapter', () => {
  const played = [];
  const page = actualPage('components/InlineMiniAppRuntime.ets',
    ['prefetchImagesAndReload', 'playRevealIfReady'], {
      extractHttpsImageUrls: () => [],
      PREFETCH_IMAGE_TOTAL_BYTES: 1024,
      PREFETCH_IMAGE_CONCURRENCY: 1,
      MINI_APP_MAX_IMAGE_BYTES: 128,
    });
  Object.assign(page, {
    alive: true,
    complete: true,
    app: { id: 'inline', version: 1, htmlContent: '<html></html>' },
    platform: {},
    visible: true,
    backgrounded: true,
    runtimeRevision: 1,
    activeRevision: 1,
    activePageRevision: 1,
    prefetchStarted: false,
    prefetchFinished: false,
    prefetchRefreshPending: false,
    ready: true,
    reduceMotion: false,
    revealEligible: true,
    motion: { play: (...args) => played.push(args), cancel() {} },
    isActive: () => !page.backgrounded,
    isCurrent: () => true,
  });
  assert.equal(page.prefetchImagesAndReload(), true);
  assert.equal(page.prefetchStarted, false);

  page.backgrounded = false;
  assert.equal(page.prefetchImagesAndReload(), false);
  page.playRevealIfReady();
  assert.deepEqual(played, [['miniapp:inline:1', true, false, false]]);
});

test('real runner prefetch success settles page before refresh so active resume cannot play old DOM', async () => {
  const cancelled = [];
  let refreshed = 0;
  const page = actualPage('pages/MiniAppRunnerPage.ets',
    ['prefetchImagesAndReload', 'resumeReveal', 'isCurrentLoad'], {
      extractHttpsImageUrls: () => ['https://example.com/asset.png'],
      PREFETCH_IMAGE_TOTAL_BYTES: 1024,
      PREFETCH_IMAGE_CONCURRENCY: 1,
      MINI_APP_MAX_IMAGE_BYTES: 128,
    });
  const app = { id: 'runner', version: 1, htmlContent: '<img src="https://example.com/asset.png">' };
  Object.assign(page, {
    alive: true,
    pageVisible: true,
    appBackgrounded: false,
    pageReady: true,
    prefetchStarted: false,
    prefetchFinished: false,
    prefetchRefreshPending: false,
    loadRevision: 1,
    state: { kind: 'ready', message: '', app },
    platform: {},
    controller: { refresh() { refreshed++; } },
    motion: { play() {}, cancel: (consume) => cancelled.push(consume) },
  });
  page.fetchAndCacheImage = async (_url, used) => {
    used.bytes = 1;
    return true;
  };
  page.prefetchImagesAndReload();
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(page.prefetchFinished, true);
  assert.equal(page.pageReady, false);
  assert.deepEqual(cancelled, [false]);
  assert.equal(refreshed, 1);

  // The refresh is the only path allowed to restore pageReady and trigger play.
  page.resumeReveal();
  assert.equal(refreshed, 1);
});
