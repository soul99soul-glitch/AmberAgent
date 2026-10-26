const { test } = require('node:test');
const assert = require('node:assert/strict');
const { actualPage, loadPureModule, entryRoot } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');
const path = require('node:path');
const { MessageRowViewState } = loadPureModule(path.join(entryRoot, 'components/MessageRowViewState.ets'));

const settle = () => new Promise(resolve => setImmediate(resolve));
function deferred() {
  let resolve;
  const promise = new Promise(yes => { resolve = yes; });
  return { promise, resolve };
}

function subAgent(viewState, runId, read) {
  const card = actualPage('components/SubAgentResultCard.ets',
    ['aboutToAppear', 'aboutToDisappear', 'refreshResult', 'toggle', 'saveExpandedState'], {
      subAgentFinalTextFromTools: () => 'short result', loadSubAgentHistoricalText: read,
      MOTION_OVERLAY: 200, Curve: { EaseOut: 'ease' },
    });
  Object.assign(card, { viewState, runId, tools: [], expanded: false, resultText: '', loading: false,
    alive: false, readSequence: 0, onToggleResult() {} });
  card.getUIContext = () => ({ animateTo: (_options, update) => update() });
  return card;
}

test('a remounted expanded subagent rereads full output while another run keeps its own state', async () => {
  const state = new MessageRowViewState();
  state.subAgentResultExpanded.set('run-one', true);
  const request = deferred(), reads = [];
  const card = subAgent(state, 'run-one', (id, tools) => { reads.push([id, tools]); return request.promise; });
  card.aboutToAppear();
  assert.equal(card.expanded, true, 'lazy remount must restore the user expansion');
  assert.equal(reads.length, 1, 'restored expansion must use the existing full-history reader');
  request.resolve('complete historical result'); await settle();
  assert.equal(card.resultText, 'complete historical result');
  const other = subAgent(state, 'run-two', () => { throw new Error('collapsed run should not read history'); });
  other.aboutToAppear();
  assert.equal(other.expanded, false);
});

for (const kind of ['tool', 'council']) {
  test(`${kind} expansion survives lazy remount and stays isolated by stable child identity`, () => {
    const state = new MessageRowViewState();
    const make = (id, viewState = state) => {
      const file = kind === 'tool' ? 'ChatMessageTools' : 'ChatMessageCouncilStep';
      const save = kind === 'tool' ? 'saveOutputState' : 'saveObjectiveState';
      const toggle = kind === 'tool' ? 'toggleOutput' : 'toggleObjective';
      const card = actualPage(`components/${file}.ets`, ['aboutToAppear', 'aboutToDisappear', save, toggle], {
        MOTION_OVERLAY: 200, Curve: { EaseOut: 'ease' },
      });
      Object.assign(card, { viewState, part: { toolCallId: id }, runId: id,
        outputExpanded: false, objectiveExpanded: false });
      card.getUIContext = () => ({ animateTo: (_options, update) => update() });
      card.aboutToAppear();
      return { card, toggle, field: kind === 'tool' ? 'outputExpanded' : 'objectiveExpanded' };
    };
    const first = make('one');
    first.card[first.toggle](); first.card.aboutToDisappear();
    const restored = make('one'), other = make('two');
    assert.equal(restored.card[restored.field], true);
    assert.equal(other.card[other.field], false);
    restored.card[restored.toggle](); restored.card.aboutToDisappear();
    const collapsed = make('one');
    assert.equal(collapsed.card[collapsed.field], false);
    const standalone = make('one', null);
    standalone.card[standalone.toggle](); standalone.card.aboutToDisappear();
    assert.equal(standalone.card[standalone.field], true, 'standalone hosts keep local interaction');
  });
}

test('subagent user toggles survive destruction and a detached read cannot write back', async () => {
  const state = new MessageRowViewState();
  const request = deferred();
  const card = subAgent(state, 'run', () => request.promise);
  card.aboutToAppear(); card.toggle();
  assert.equal(state.subAgentResultExpanded.get('run'), true);
  card.aboutToDisappear();
  request.resolve('late result'); await settle();
  assert.equal(card.resultText, 'short result');
  const next = subAgent(state, 'run', () => Promise.resolve('full result'));
  next.aboutToAppear(); await settle();
  assert.equal(next.expanded, true);
  next.toggle();
  assert.equal(state.subAgentResultExpanded.get('run'), false);
});
