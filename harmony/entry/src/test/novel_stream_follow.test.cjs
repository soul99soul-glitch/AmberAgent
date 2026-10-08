const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { actualPage } = require('../../../deepread/src/test/deepread_ui_fixture.cjs');
const source = fs.readFileSync(path.join(__dirname, '../main/ets/pages/NovelWorkspacePage.ets'), 'utf8');
const ts = require('../../../chat/node_modules/typescript');
const ScrollSource = { DRAG: 1, FLING: 2, SCROLLER_ANIMATION: 3 };
const ScrollState = { Idle: 0, Scroll: 1, Fling: 2 };
function timelineCallback(name) {
  const start = source.indexOf(name + ': (');
  const arrow = source.indexOf('=> {', start);
  let end = arrow + 4, depth = 1;
  for (; depth; end++) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
  }
  const parameters = source.slice(start + name.length + 2, arrow).replace(/:\s*void\s*$/, '');
  const code = ts.transpileModule('return function' + parameters + source.slice(arrow + 3, end),
    { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText;
  return new Function('ScrollSource', 'ScrollState', 'TouchType', code)(ScrollSource, ScrollState, { Down: 0, Up: 1, Cancel: 2 });
}

async function fixture() {
  const follow = await import('../../../chat/src/main/ets/chat/timeline_follow.ts');
  const timers = [], edges = [], requests = [], cancellations = [];
  const methods = ['scheduleNovelScrollToBottom', 'followNovelStreamingUpdate', 'finishNovelGenerationFollow',
    'reconcileNovelUserScroll', 'attachNovelRun', 'clearNovelRunUi', 'activeRunMessage', 'continueNovelTool'];
  for (const name of ['requestNovelNativeFollow', 'measuredNovelBottomTarget', 'novelTimelineItemCount',
    'settleNovelRunUi', 'novelTimelineMessages', 'novelTimelineMessageLive', 'savedTimelineMessage', 'novelTimelineMessageUi']) {
    if (source.includes(' ' + name + '(')) methods.push(name);
  }
  let frame;
  const page = actualPage('pages/NovelWorkspacePage.ets', methods, { ...follow, Edge: { Bottom: 'bottom' },
    setTimeout: callback => { timers.push(callback); return timers.length; },
    NativeTimelineFollow: class {
      constructor(_context, _scroller, allowed, target) { this.allowed = allowed; this.target = target; }
      request() { if (this.allowed()) requests.push(this.target()); }
      cancel() { cancellations.push(true); }
    },
    discussionArchiveAtMessage: (archives, message) => archives.find(a => a.sourceMessageIds[0] === message.id) ?? null,
    discussionMessageVisible: (archives, expanded, message) => message.mode !== 'discuss' ||
      !archives.some(a => a.sourceMessageIds.includes(message.id) && !expanded.includes(a.id)),
    orphanSettingProposals: (proposals, messages) => proposals.filter(p => !messages.some(m => m.id === p.sourceMessageId)),
    makeNovelMessage: init => ({ ...init, collectedChapterId: null }),
    getNovelCreation: () => ({ continueTool: () => ({ id: 'tool-run', subscribe: () => () => {} }) }),
    novelMessageUi: message => message.uiMessage,
  });
  Object.assign(page, { pageAlive: true, studioPageVisible: true, studioBackgrounded: false, tab: 0,
    followMode: 'following', atBottomApprox: true, userTouchActive: false, jumpButtonVisible: false,
    novelScrollToken: 0, novelViewportHeight: 400, novelLastVisibleIndex: 0, nativeFollow: null,
    initialScrollArmed: false,
    project: { messages: [], settingProposals: [], discussionArchives: [] }, expandedArchiveIds: [],
    activeRunId: 'run', activeMessages: [{ id: 'assistant', role: 'assistant', parts: [{ type: 'text', text: 'body' }] }],
    activeHistoryCount: 0, activeQuickStart: false, failedMessages: [], completedPreview: [], ghostwriteJob: null, ghostwriteReport: null,
    pendingUserText: '', ordinaryRecoveryRecord: () => null,
    activeRunTimelineMessages() { return this.activeMessages; }, settingProposalsAt: () => [],
    getUIContext: () => ({}), reloadToken: 0, composerDraftOwner: 'project:main', composeMode: 'whole_chapter',
    beginNovelGenerationFollow: () => {}, currentUnsub: null,
    chatScroller: { isAtEnd: () => false, currentOffset: () => ({ yOffset: 60 }),
      getItemRect: () => ({ y: 200, height: 250 }), scrollEdge: edge => edges.push(edge) },
  });
  return { page, timers, edges, requests, cancellations,
    run: { id: 'run', subscribe: callback => { frame = callback; return () => {}; } },
    emit: event => frame(event), flush: () => { while (timers.length) timers.shift()(); } };
}

test('novel stream growth requests native motion instead of jumping to the edge for every snapshot', async () => {
  const f = await fixture();
  f.page.followNovelStreamingUpdate(); f.flush();
  assert.equal(f.edges.length, 0, 'text growth must not teleport the whole viewport');
  assert.deepEqual(f.requests, [110], 'follow target uses the measured last row and viewport');
  f.page.followMode = 'paused'; f.page.atBottomApprox = false;
  f.page.followNovelStreamingUpdate(); f.flush();
  assert.equal(f.requests.length, 1, 'reading older content stays paused');
  f.page.userTouchActive = true; f.page.followMode = 'following';
  f.page.followNovelStreamingUpdate(); f.flush();
  assert.equal(f.requests.length, 1, 'touch owns the viewport');
});

test('terminal generation keeps the visible body until persisted messages replace it', async () => {
  const f = await fixture();
  let release;
  f.page.reload = async () => {
    f.page.reloadToken++;
    await new Promise(resolve => { release = resolve; });
    f.page.project.messages = [{ id: 'assistant', role: 'assistant', content: 'body' }];
    f.page.clearNovelRunUi();
  };
  f.page.attachNovelRun(f.run, 0, 'prompt', 1, 'writing');
  f.emit({ kind: 'snapshot', messages: [{ id: 'assistant', role: 'assistant', parts: [{ type: 'text', text: 'body' }] }],
    textDeltasLive: true, transport: 'live' });
  f.emit({ kind: 'completed' });
  assert.equal(f.page.activeRunId, 'run', 'old history must not replace the still-visible generated body');
  assert.equal(f.page.activeMessages.length, 1);
  release(); await new Promise(setImmediate);
  assert.equal(f.page.activeRunId, '');
  assert.equal(f.page.project.messages[0].content, 'body');
  assert.equal(f.page.followMode, 'idle');
});

test('late completion cannot change the follow mode of a newly attached run', async () => {
  const f = await fixture(); let release;
  f.page.reload = async () => { f.page.reloadToken++; await new Promise(resolve => { release = resolve; }); };
  f.page.attachNovelRun(f.run, 0, 'prompt', 1, 'writing');
  f.emit({ kind: 'completed' });
  f.page.activeRunId = 'next-run'; f.page.followMode = 'following';
  release(); await new Promise(setImmediate);
  assert.equal(f.page.activeRunId, 'next-run');
  assert.equal(f.page.followMode, 'following');
});

test('a paused reader stays in place when generation completes', async () => {
  const f = await fixture();
  f.page.reload = async () => { f.page.reloadToken++; f.page.clearNovelRunUi(); };
  f.page.attachNovelRun(f.run, 0, 'prompt', 1, 'writing');
  f.page.followMode = 'paused'; f.page.atBottomApprox = false;
  f.emit({ kind: 'completed' }); await new Promise(setImmediate); f.flush();
  assert.equal(f.page.followMode, 'paused');
  assert.equal(f.page.jumpButtonVisible, true);
  assert.equal(f.requests.length, 0);
  assert.equal(f.edges.length, 0);
});

test('a terminal event replayed during subscribe does not restore the obsolete subscription', async () => {
  const f = await fixture(); let release; let unsubscribed = 0;
  f.page.reload = async () => {
    f.page.reloadToken++;
    await new Promise(resolve => { release = resolve; });
    if (f.page.currentUnsub === null) f.page.clearNovelRunUi();
  };
  f.page.attachNovelRun({ id: 'run', subscribe: callback => {
    callback({ kind: 'completed' }); return () => { unsubscribed++; };
  } }, 0, 'prompt', 1, 'writing');
  assert.equal(f.page.currentUnsub, null, 'a cached terminal is not an active subscription');
  assert.equal(unsubscribed, 1);
  release(); await new Promise(setImmediate);
  assert.equal(f.page.activeRunId, '');
});

test('the measured tail accounts for archived discussion, proposal cards, and quick-start or failed rows', async () => {
  const f = await fixture(); const p = f.page;
  p.activeRunId = ''; p.activeMessages = [];
  p.project.messages = [{ id: 'a', mode: 'discuss' }, { id: 'b', mode: 'discuss' }, { id: 'prose', mode: 'write' }];
  p.project.discussionArchives = [{ id: 'archive', sourceMessageIds: ['a', 'b'] }];
  p.project.settingProposals = [{ sourceMessageId: 'orphan' }];
  p.settingProposalsAt = message => message.id === 'prose' ? [{ id: 'proposal' }] : [];
  assert.equal(p.novelTimelineItemCount(), 4, 'archive, prose, attached proposal, orphan');
  p.expandedArchiveIds = ['archive'];
  assert.equal(p.novelTimelineItemCount(), 6);
  p.failedMessages = [{ id: 'unsaved' }];
  assert.equal(p.novelTimelineItemCount(), 7);
  p.activeRunId = 'quick'; p.activeHistoryCount = 0; p.activeQuickStart = true;
  assert.equal(p.novelTimelineItemCount(), 2, 'orphan and quick-start status, no duplicate unsaved body');
  p.activeMessages = [{ id: 'thinking', role: 'assistant' }];
  assert.equal(p.novelTimelineItemCount(), 3);
});

test('a terminal read failure preserves the last visible body while releasing the ended run', async () => {
  const f = await fixture();
  f.page.reload = async () => { f.page.reloadToken++; f.page.errorMsg = 'project read failed'; };
  f.page.attachNovelRun(f.run, 0, 'prompt', 1, 'writing');
  f.emit({ kind: 'snapshot', messages: [{ id: 'assistant', role: 'assistant', parts: [{ type: 'text', text: 'saved body' }] }],
    textDeltasLive: true, transport: 'live' });
  f.emit({ kind: 'completed' }); await new Promise(setImmediate);
  assert.equal(f.page.activeRunId, '', 'read failure cannot lock subsequent operations');
  assert.equal(f.page.completedPreview[0].parts[0].text, 'saved body');
  assert.equal(f.page.errorMsg, 'project read failed');
  assert.equal(f.page.failedMessages.length, 0, 'a read failure must not relabel persisted text as unsaved');
});

test('continuing an earlier discussion uses its mode even when the last message and composer are writing', async () => {
  const f = await fixture(); const p = f.page;
  p.hasBlockingNovelRun = () => false; p.resolveNovelToolId = () => 'ask';
  p.project.messages = [
    { id: 'discussion', mode: 'discuss', runKind: null,
      uiMessage: { parts: [{ type: 'tool', toolCallId: 'ask' }] } },
    { id: 'latest-prose', mode: 'write', runKind: 'prose_whole_chapter', uiMessage: { parts: [] } },
  ];
  p.continueNovelTool('ask', { kind: 'approved' });
  assert.equal(p.activeRunKind, 'discussion');
  assert.equal(p.activeHistoryCount, 0);
});

test('a native animation ending between growing frames cannot pause novel follow', async () => {
  const f = await fixture();
  f.page.nativeFollow = { isRunning: () => false, cancel: () => {} };
  f.page.novelUserScrolling = false;
  timelineCallback('onTimelineScrollStop').call(f.page);
  assert.equal(f.page.followMode, 'following', 'programmatic completion is not a user gesture');
  for (const source of [ScrollSource.DRAG, ScrollSource.FLING]) {
    timelineCallback('onTimelineWillScroll').call(f.page, -5, ScrollState.Scroll, source);
    timelineCallback('onTimelineScroll').call(f.page, -5, ScrollState.Scroll);
    assert.equal(f.page.followMode, 'paused', 'real movement still pauses follow');
    timelineCallback('onTimelineScrollStop').call(f.page);
    assert.equal(f.page.novelUserScrolling, false);
  }
});

test('the growing and saved body occupy the same timeline item and resolve fresh same-ID snapshots', async () => {
  const f = await fixture(); const p = f.page;
  p.projectMessageUi = message => message.uiMessage;
  const seed = p.novelTimelineMessages()[0];
  assert.equal(seed.id, 'assistant');
  assert.equal(p.novelTimelineMessageLive(seed), true);
  const current = { ...seed.uiMessage, parts: [{ type: 'text', text: 'complete body' }] };
  p.activeMessages = [current];
  assert.equal(p.novelTimelineMessageUi(seed), current, 'retained item reads the current live snapshot');
  p.project.messages = [{ ...seed, uiMessage: current, granularity: 'chapter' }];
  p.clearNovelRunUi();
  const saved = p.novelTimelineMessages()[0];
  assert.equal(saved.id, seed.id);
  assert.equal(saved.collectedChapterId, seed.collectedChapterId);
  assert.equal(p.novelTimelineMessageLive(seed), false);
  assert.equal(p.novelTimelineMessageUi(seed), current, 'the same item now resolves the saved transcript');
  assert.equal(p.savedTimelineMessage(seed).granularity, 'chapter');
});
