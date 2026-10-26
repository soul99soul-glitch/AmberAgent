const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
const { loadPureModule, entryRoot } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');
const { MessageRowViewState } = loadPureModule(path.join(entryRoot, 'components/MessageRowViewState.ets'));
const { MiniAppStreamingCodePreview } = loadPureModule(path.resolve(__dirname,
  '../../../chat/src/main/ets/chat/miniapp/miniapp_streaming_preview.ts'));
const settle = () => new Promise(resolve => setImmediate(resolve));

function card(state, key, { sourceAllowed = true, onModify = () => false } = {}) {
  const source = fs.readFileSync(path.join(entryRoot, 'components/MiniAppChatCard.ets'), 'utf8');
  const start = source.indexOf('export struct MiniAppChatCard {');
  const body = source.slice(start, source.indexOf('  @Builder', start))
    .replace('export struct MiniAppChatCard', 'class MiniAppChatCard')
    .replace(/@(?:State|Prop|StorageProp|Watch)(?:\([^\n]*?\))?\s*/g, '');
  const code = ts.transpileModule(body + '\n}\nreturn new MiniAppChatCard();', {
    compilerOptions: { target: ts.ScriptTarget.ES2022 },
  }).outputText;
  const app = { id: 'app', title: 'Example', version: 3, htmlContent: '<main>Example</main>' };
  const platform = { settingSnapshot: () => ({ showSourceButton: sourceAllowed }),
    repository: { getById: async () => app } };
  const instance = new Function('MiniAppStreamingCodePreview', 'Scroller', 'ACCENT', 'getMiniAppPlatform',
    'hilog', 'HILOG_DOMAIN', 'HILOG_TAG', code)(MiniAppStreamingCodePreview, class {}, '#0088ff',
    async () => platform, { warn() {} }, 1, 'fixture');
  Object.assign(instance, { viewState: state, viewStateKey: key, part: { appId: 'app' }, onModify });
  instance.aboutToAppear();
  return { instance, app };
}

test('miniapp source choice and unfinished modification survive lazy row destruction by stable card key', async () => {
  const state = new MessageRowViewState();
  const first = card(state, 'message#miniapp-0').instance;
  await settle();
  first.toggleCode();
  first.modifyRequest = '保留这段修改草稿';
  first.aboutToDisappear();
  const remounted = card(state, 'message#miniapp-0').instance;
  await settle();
  assert.equal(remounted.showCode, true);
  assert.equal(remounted.modifyRequest, '保留这段修改草稿');
  remounted.beginModify(); await settle();
  assert.equal(remounted.modifyRequest, '保留这段修改草稿');
  assert.equal(remounted.modifyDialogOpen, true);
  const other = card(state, 'message#miniapp-1').instance;
  await settle();
  assert.equal(other.showCode, false);
  assert.equal(other.modifyRequest, '');
});

test('successful modification consumes the saved draft while rejected handoff retains it', async () => {
  const state = new MessageRowViewState();
  const { instance, app } = card(state, 'message#miniapp-0');
  await settle();
  instance.modifyApp = app;
  instance.modifyDialogOpen = true;
  instance.modifyRequest = '  增加统计  ';
  instance.confirmModify();
  assert.equal(instance.modifyDialogOpen, true);
  assert.equal(instance.modifyRequest, '  增加统计  ');
  let prompt = '';
  instance.onModify = value => { prompt = value; return true; };
  instance.confirmModify();
  assert.match(prompt, /用户修改意见：\n增加统计/);
  assert.equal(instance.modifyDialogOpen, false);
  assert.equal(state.miniAppModifyRequest.get('message#miniapp-0'), '');
  instance.aboutToDisappear();
  const next = card(state, 'message#miniapp-0').instance;
  await settle(); next.beginModify(); await settle();
  assert.equal(next.modifyRequest, '');
});

test('restoring source preference waits for current source permission and cannot bypass a disabled setting', async () => {
  const state = new MessageRowViewState();
  state.miniAppShowCode.set('message#miniapp-0', true);
  const denied = card(state, 'message#miniapp-0', { sourceAllowed: false }).instance;
  assert.equal(denied.showCode, false, 'source must remain hidden while permission is unresolved');
  await settle();
  assert.equal(denied.sourceAllowed, false);
  assert.equal(denied.showCode, false);
  denied.toggleCode();
  assert.equal(denied.showCode, false);
  const allowed = card(state, 'message#miniapp-0').instance;
  await settle();
  assert.equal(allowed.sourceAllowed, true);
  assert.equal(allowed.showCode, true);
});

test('typing persists the draft before the card can be removed from the viewport', async () => {
  const state = new MessageRowViewState();
  const instance = card(state, 'message#miniapp-0').instance;
  await settle();
  instance.updateModifyRequest('新的即时草稿');
  assert.equal(state.miniAppModifyRequest.get('message#miniapp-0'), '新的即时草稿');
  instance.modifyDialogOpen = false;
  instance.beginModify(); await settle();
  assert.equal(instance.modifyRequest, '新的即时草稿');
});

test('hosts without a row snapshot keep the existing fresh modification behavior', async () => {
  const { instance } = card(null, '');
  await settle();
  instance.modifyRequest = 'previous closed dialog';
  instance.beginModify(); await settle();
  assert.equal(instance.modifyRequest, '');
});
