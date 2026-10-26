const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const ts = require('../../../chat/node_modules/typescript');
require('../../../chat/node_modules/tsx/dist/cjs/index.cjs');
const models = require('../../../deepread/src/main/ets/novel/models.ts');
const { canCloneCollectedMessage } = require('../../../deepread/src/main/ets/novel/collected_candidates.ts');
const { actualPage, method } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');

function discussion(project) {
  const filename = path.join(__dirname, '../main/ets/platform_impl/novel/NovelDiscussionSession.ets');
  const code = ts.transpileModule(fs.readFileSync(filename, 'utf8'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS },
  }).outputText;
  const exported = {};
  new Function('exports', 'require', 'Observed', 'Scroller', code)(exported,
    name => name === '@amber/deepread-domain' ? models : {}, constructor => constructor, class {});
  const session = new exported.NovelDiscussionSession();
  session.start({ currentProject: () => project, currentStatus: () => ({ activeBranchId: 'main' }) });
  return session;
}

function collectedMessage(text) {
  return models.makeNovelMessage({ id: 'assistant', role: 'assistant', mode: 'write', content: text,
    collectedChapterId: 'chapter', runKind: 'prose_whole_chapter', createdAt: 1 });
}

test('retained collected-message footer resolves the canonical source after the view prop loses non-enumerable accessors', () => {
  const canonical = models.makeNovelProject({ id: 'project', name: 'NovelPhaseCheck', now: 1 });
  canonical.messages = [collectedMessage('已经收录的正文')];
  const content = Object.getOwnPropertyDescriptor(canonical.messages[0], 'content');
  assert.equal(content.enumerable, false);
  assert.equal(typeof content.get, 'function');
  // V1 @Prop transfers enumerable data to its local display copy; derived getters are not part of that snapshot.
  const displayed = JSON.parse(JSON.stringify(canonical));
  const seed = displayed.messages[0];
  assert.equal(Object.getOwnPropertyDescriptor(seed, 'content'), undefined);
  const session = discussion(canonical);
  const view = actualPage('components/novel/NovelDiscussionView.ets', ['novelTimelineMessageFooter'], {});
  Object.assign(view, { session, project: displayed, activeRunFooter() { throw Error('unexpected active footer'); } });
  const delivered = [], actions = [];
  const viewSource = fs.readFileSync(path.join(__dirname, '../main/ets/components/novel/NovelDiscussionView.ets'), 'utf8');
  const footerSource = method(viewSource, 'novelMessageFooter');
  const cloneCallback = footerSource.match(/\.onClick\(\(\): void => \{ (this\.onCloneCollected\(message\);) \}\)/);
  assert.ok(cloneCallback, 'actual clone action must remain bound to the footer message');
  const click = new Function('message', cloneCallback[1]);
  view.onCloneCollected = message => actions.push(message);
  view.novelMessageFooter = message => {
    assert.equal(canCloneCollectedMessage(view.project, message, 'main'), true);
    delivered.push(message);
    click.call(view, message);
  };
  view.novelTimelineMessageFooter(seed);
  assert.equal(delivered[0], canonical.messages[0], 'eligibility and clone callbacks must receive the canonical message');

  canonical.messages = [collectedMessage('同一个消息 ID 的新正文')];
  view.project = JSON.parse(JSON.stringify(canonical));
  view.novelTimelineMessageFooter(seed);
  assert.equal(delivered[1], canonical.messages[0], 'a retained row must resolve the latest canonical message by ID');
  assert.equal(delivered[1].content, '同一个消息 ID 的新正文');
  assert.deepEqual(actions, delivered, 'the actual clone action must forward the same canonical sources');
});
