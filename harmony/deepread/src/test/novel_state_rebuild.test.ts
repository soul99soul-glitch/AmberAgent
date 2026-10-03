import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelProject, makeNovelChapter, makeNovelMaterial } from '../main/ets/novel/models.ts';
import { makeAssistantMessage, makeUserMessage } from '../main/ets/agent/message.ts';
import { defaultGhostwriteDigest } from '../main/ets/novel/ghostwrite.ts';
import type { NovelModelRunning, NovelModelRequest, NovelModelEvent } from '../main/ets/novel/model_running.ts';

const tick = (): Promise<void> => new Promise(resolve => setTimeout(resolve, 0));
const fixture = async (large: boolean = false) => {
  const files = createMemoryFileStore();
  const repository = createFileNovelRepository(files);
  const character = { ...makeNovelMaterial({ kind: 'character', title: 'Rhea', content: '船长', aliases: ['CaptainRhea'], now: 1 }), id: 'rhea' };
  const first = { ...makeNovelChapter({ title: '一', content: 'Rhea raised the lamp.', now: 1 }), id: 'first', ordinal: 1 };
  const second = { ...makeNovelChapter({ title: '二', content: large ? 'CaptainRhea opened the gate. '.repeat(1000) : 'CaptainRhea met Rider at the gate.', now: 1 }), id: 'second', ordinal: 2 };
  const project = await repository.createProject({ ...makeNovelProject({ name: '状态', now: 1 }),
    chapters: [first, second], materials: [character], baseMaterials: [character], materialOverrides: [], hiddenMaterialIds: [] });
  return { files, repository, project, first, second, character };
};

const stateModel = (failSecond: boolean = false, budget: number = 32768) => {
  const requests: NovelModelRequest[] = [];
  let secondFailed: boolean = false;
  const model: NovelModelRunning = {
    validate: async () => {}, inputBudgetTokens: async () => budget,
    estimateInputTokens: (system, user) => Math.ceil((system.length + user.length) / 4),
    cancel: () => {},
    start(request) {
      requests.push(request);
      const subscribers = new Set<(event: NovelModelEvent) => void>();
      setTimeout(async () => {
        if (request.taskOptions === undefined) {
          assert.equal(request.operation.kind, 'turn');
          if (request.operation.kind !== 'turn') throw new Error('Expected turn');
          const messages = request.history.concat([makeUserMessage(request.operation.userPrompt), makeAssistantMessage('Rhea continued onward.')]);
          await request.checkpoint(messages);
          subscribers.forEach(callback => callback({ kind: 'snapshot', messages,
            generationActive: false, textDeltasLive: false, transport: 'buffered' }));
          subscribers.forEach(callback => callback({ kind: 'completed' }));
          return;
        }
        const input = JSON.parse(request.operation.kind === 'turn' ? request.operation.userPrompt : '');
        const text = input.chapterId === 'second' && failSecond && !secondFailed
          ? (secondFailed = true, 'invalid json')
          : JSON.stringify({ protocolVersion: 'amber.novel.state.v1', chapterId: input.chapterId,
            sourceDigest: input.sourceDigest, events: [{ id: 'event', chapterId: input.chapterId,
              sourceDigest: input.sourceDigest, quote: input.content, summary: '正文事件', entityRefs: ['rhea'] }],
            unresolvedIdentityNames: input.content.includes('Rider') ? ['Rider'] : [] });
        subscribers.forEach(callback => callback({ kind: 'snapshot', messages: [makeAssistantMessage(text)],
          generationActive: false, textDeltasLive: false, transport: 'buffered' }));
        subscribers.forEach(callback => callback({ kind: 'completed' }));
      }, 0);
      return { subscribe(callback) { subscribers.add(callback); return () => subscribers.delete(callback); } };
    },
  };
  return { model, requests };
};

test('state rebuild persists first chunk and retry from a cold creation skips it', async () => {
  const f = await fixture(); const runtime = stateModel(true);
  const creation = createNovelCreation({ repository: f.repository, modelRunning: runtime.model });
  const observed: number[] = [];
  const unsubscribe = creation.observeStateOperation(f.project.id, operation => { if (operation) observed.push(operation.cursor); });
  const failed = await creation.startStateRebuild(f.project.id);
  assert.equal(failed.status, 'failed'); assert.equal(failed.cursor, 1);
  assert.match(failed.error!, /JSON/);
  assert.equal((await f.repository.loadProject(f.project.id)).structuredState, undefined, 'partial draft never becomes confirmed facts');
  const coldRepo = createFileNovelRepository(f.files);
  const cold = createNovelCreation({ repository: coldRepo, modelRunning: runtime.model });
  const complete = await cold.retryStateRebuild(f.project.id);
  assert.equal(complete.id, failed.id); assert.equal(complete.status, 'completed'); assert.equal(complete.cursor, 2);
  assert.deepEqual(runtime.requests.map(request => JSON.parse(request.operation.kind === 'turn' ? request.operation.userPrompt : '').chapterId), ['first', 'second', 'second']);
  const view = await cold.readStructuredState(f.project.id);
  assert.equal(view.experiences[0].events.length, 2);
  assert.deepEqual(view.project.structuredState?.unresolvedIdentityNames, ['Rider']);
  assert.equal(runtime.requests.every(request => request.taskOptions?.reasoningEnabled === false), true);
  assert.ok(observed.includes(1)); unsubscribe();
});

test('state planning and progress commits remain distinct when the wall clock does not advance', async () => {
  const f = await fixture(); const runtime = stateModel();
  const creation = createNovelCreation({ repository: f.repository, modelRunning: runtime.model });
  const clock = Date.now; const fixedTime = clock();
  Date.now = (): number => fixedTime;
  try {
    const operation = await creation.startStateRebuild(f.project.id);
    assert.equal(operation.status, 'completed', operation.error ?? '');
    assert.equal(operation.targets.length, 2);
    assert.equal(operation.cursor, 2);
    assert.equal(runtime.requests.length, 2, 'The planned targets must reach the model, not replay the initial empty plan');
    const cold = createNovelCreation({ repository: createFileNovelRepository(f.files), modelRunning: runtime.model });
    const state = await cold.readStructuredState(f.project.id);
    assert.equal(state.experiences[0].events.length, 2);
    assert.deepEqual(state.project.structuredState?.unresolvedIdentityNames, ['Rider']);
  } finally { Date.now = clock; }
});

test('state rebuild splits oversized chapters by the actual model budget and accumulates all source chunks', async () => {
  const f = await fixture(true); const runtime = stateModel(false, 2000);
  const creation = createNovelCreation({ repository: f.repository, modelRunning: runtime.model });
  const operation = await creation.startStateRebuild(f.project.id);
  assert.equal(operation.status, 'completed'); assert.ok(operation.targets.length > 2);
  const secondTargets = operation.targets.filter(target => target.chapterId === 'second');
  assert.equal(secondTargets[0].start, 0); assert.equal(secondTargets.at(-1)?.end, f.second.content.length);
  secondTargets.forEach((target, index) => { if (index > 0) assert.equal(target.start, secondTargets[index - 1].end); });
  assert.equal((await creation.readStructuredState(f.project.id)).experiences[0].events.length, operation.targets.length);
});

test('manual earlier edit stays deterministic and a successful full rescue unlocks actual ordinary writing', async () => {
  const f = await fixture(); const runtime = stateModel();
  const creation = createNovelCreation({ repository: f.repository, modelRunning: runtime.model });
  await creation.saveChapter(f.project.id, f.first.id, f.first.title, 'Rhea returned before the gate opened.');
  assert.equal(runtime.requests.length, 0, 'manual edit never invokes a model');
  assert.equal((await creation.workspaceStatus(f.project.id)).unresolvedFromOrdinal, 2);
  const complete = await creation.startStateRebuild(f.project.id);
  assert.equal(complete.status, 'completed');
  const status = await creation.workspaceStatus(f.project.id);
  assert.equal(status.unresolvedFromOrdinal, null); assert.equal(status.plotStale, false);
  await new Promise<void>((resolve, reject) => creation.generate(f.project.id, '继续', 'write', 'whole_chapter', 'prose_whole_chapter')
    .subscribe(event => { if (event.kind === 'completed') resolve(); if (event.kind === 'failed') reject(new Error(event.message)); }));
  assert.equal(runtime.requests.at(-1)?.taskOptions, undefined, 'normal writing keeps its own provider reasoning settings');
});

test('state operation refuses late results after real manual source mutation and keeps the edited manuscript', async () => {
  const f = await fixture(); const runtime = stateModel();
  let request: NovelModelRequest | null = null; let callback: ((event: NovelModelEvent) => void) | null = null;
  const held: NovelModelRunning = { ...runtime.model, start(value) { request = value;
    return { subscribe(listener) { callback = listener; return () => { callback = null; }; } }; } };
  const creation = createNovelCreation({ repository: f.repository, modelRunning: held });
  const pending = creation.startStateRebuild(f.project.id);
  while (request === null) await tick();
  await creation.saveChapter(f.project.id, f.first.id, f.first.title, '作者已经改过此章。');
  const heldOperation = (request as NovelModelRequest).operation;
  const input = JSON.parse(heldOperation.kind === 'turn' ? heldOperation.userPrompt : '');
  const text = JSON.stringify({ protocolVersion: 'amber.novel.state.v1', chapterId: input.chapterId,
    sourceDigest: input.sourceDigest, events: [], unresolvedIdentityNames: [] });
  callback!({ kind: 'snapshot', messages: [makeAssistantMessage(text)], generationActive: false, textDeltasLive: false, transport: 'buffered' });
  callback!({ kind: 'completed' });
  const failed = await pending;
  assert.equal(failed.status, 'failed'); assert.equal(failed.cursor, 0);
  assert.equal((await f.repository.loadProject(f.project.id)).chapters[0].content, '作者已经改过此章。');
  assert.equal((await f.repository.loadProject(f.project.id)).structuredState, undefined);
  await assert.rejects(creation.retryStateRebuild(f.project.id), /正文已变化/);
});

test('late state output cannot cross a branch checkout, and the original branch retains explicit retry progress', async () => {
  const f = await fixture(); const runtime = stateModel();
  const original = await f.repository.workspaceStatus(f.project.id);
  await f.repository.createBranch(f.project.id, '支线', original.cas, 'test-fork');
  const branch = await f.repository.workspaceStatus(f.project.id);
  await f.repository.switchBranch(f.project.id, original.activeBranchId, branch.cas);
  let request: NovelModelRequest | null = null;
  let callback: ((event: NovelModelEvent) => void) | null = null;
  const held: NovelModelRunning = { ...runtime.model, start(value) {
    request = value; return { subscribe(listener) { callback = listener; return () => { callback = null; }; } };
  } };
  const creation = createNovelCreation({ repository: f.repository, modelRunning: held });
  const pending = creation.startStateRebuild(f.project.id);
  while (request === null) await tick();
  const running = await f.repository.workspaceStatus(f.project.id);
  await f.repository.switchBranch(f.project.id, branch.activeBranchId, running.cas);
  const operation = (request as NovelModelRequest).operation;
  const input = JSON.parse(operation.kind === 'turn' ? operation.userPrompt : '');
  callback!({ kind: 'snapshot', messages: [makeAssistantMessage(JSON.stringify({
    protocolVersion: 'amber.novel.state.v1', chapterId: input.chapterId, sourceDigest: input.sourceDigest,
    events: [], unresolvedIdentityNames: [],
  }))], generationActive: false, textDeltasLive: false, transport: 'buffered' });
  callback!({ kind: 'completed' });
  await assert.rejects(pending, /工作区已变化|分支/);
  const other = await f.repository.readWorkspaceSnapshot(f.project.id);
  assert.equal(other.project.stateOperation, undefined); assert.equal(other.project.structuredState, undefined);
  await f.repository.switchBranch(f.project.id, original.activeBranchId, other.status.cas);
  const cold = createNovelCreation({ repository: createFileNovelRepository(f.files), modelRunning: runtime.model });
  assert.equal((await cold.readStructuredState(f.project.id)).operation?.cursor, 0);
  assert.equal((await cold.retryStateRebuild(f.project.id)).status, 'completed');
});

test('cancel releases the state subscription and cannot commit a late snapshot', async () => {
  const f = await fixture(); const runtime = stateModel();
  const subscribers = new Set<(event: NovelModelEvent) => void>(); let entered: boolean = false; let cancelled: number = 0;
  const held: NovelModelRunning = { ...runtime.model, cancel() { cancelled++; },
    start() { entered = true; return { subscribe(listener) { subscribers.add(listener); return () => subscribers.delete(listener); } }; } };
  const creation = createNovelCreation({ repository: f.repository, modelRunning: held });
  const pending = creation.startStateRebuild(f.project.id); while (!entered) await tick();
  const writing = await new Promise<NovelModelEvent | { kind: 'failed'; message: string }>(resolve =>
    creation.generate(f.project.id, '继续', 'write', 'whole_chapter').subscribe(event => {
      if (event.kind === 'failed') resolve(event);
    }));
  assert.equal(writing.kind, 'failed');
  await creation.cancelStateRebuild(f.project.id);
  const operation = await pending;
  assert.equal(operation.status, 'cancelled'); assert.equal(operation.cursor, 0);
  assert.equal(subscribers.size, 0); assert.equal(cancelled, 1);
  assert.equal((await f.repository.loadProject(f.project.id)).structuredState, undefined);
});

test('a partial failed rescue preserves the existing stale writing guard', async () => {
  const f = await fixture(); const runtime = stateModel(true);
  const creation = createNovelCreation({ repository: f.repository, modelRunning: runtime.model });
  await creation.saveChapter(f.project.id, f.first.id, '一', 'Rhea changed the earlier scene.');
  const failed = await creation.startStateRebuild(f.project.id);
  assert.equal(failed.status, 'failed'); assert.equal(failed.cursor, 1);
  const status = await creation.workspaceStatus(f.project.id);
  assert.equal(status.unresolvedFromOrdinal, 2); assert.equal(status.plotStale, true);
  assert.equal((await f.repository.loadProject(f.project.id)).structuredState, undefined);
});

test('full rescue recomputes pending identities from current model evidence', async () => {
  const f = await fixture(); const runtime = stateModel();
  const creation = createNovelCreation({ repository: f.repository, modelRunning: runtime.model });
  await creation.startStateRebuild(f.project.id);
  assert.deepEqual((await creation.readStructuredState(f.project.id)).project.structuredState?.unresolvedIdentityNames, ['Rider']);
  const model: NovelModelRunning = { ...runtime.model, start(request) {
    const listeners = new Set<(event: NovelModelEvent) => void>();
    setTimeout(() => {
      const input = JSON.parse(request.operation.kind === 'turn' ? request.operation.userPrompt : '');
      const text = JSON.stringify({ protocolVersion: 'amber.novel.state.v1', chapterId: input.chapterId,
        sourceDigest: input.sourceDigest, events: [], unresolvedIdentityNames: [] });
      listeners.forEach(callback => callback({ kind: 'snapshot', messages: [makeAssistantMessage(text)],
        generationActive: false, textDeltasLive: false, transport: 'buffered' }));
      listeners.forEach(callback => callback({ kind: 'completed' }));
    }, 0);
    return { subscribe(callback) { listeners.add(callback); return () => listeners.delete(callback); } };
  } };
  const rebuilt = createNovelCreation({ repository: f.repository, modelRunning: model });
  await rebuilt.startStateRebuild(f.project.id);
  assert.deepEqual((await rebuilt.readStructuredState(f.project.id)).project.structuredState?.unresolvedIdentityNames, []);
});

test('author create/ignore/merge identities really persist and become later request input', async () => {
  for (const action of ['create', 'ignore', 'merge'] as const) {
    const f = await fixture(); const runtime = stateModel();
    const creation = createNovelCreation({ repository: f.repository, modelRunning: runtime.model });
    await creation.startStateRebuild(f.project.id);
    const view = await creation.readStructuredState(f.project.id);
    await creation.resolveIdentityClarification(f.project.id, 'Rider', action, action === 'merge' ? 'rhea' : null, view.cas);
    const cold = createNovelCreation({ repository: createFileNovelRepository(f.files), modelRunning: runtime.model });
    const resolved = await cold.readStructuredState(f.project.id);
    assert.deepEqual(resolved.project.structuredState?.unresolvedIdentityNames, []);
    assert.equal(resolved.project.structuredState?.identityClarifications[0].action, action);
    if (action === 'create') assert.ok(resolved.project.materials.some(material => material.title === 'Rider'));
    if (action === 'merge') assert.ok(resolved.project.materials.find(material => material.id === 'rhea')?.aliases?.includes('Rider'));
    await new Promise<void>((resolve, reject) => cold.generate(f.project.id, '继续', 'write', 'whole_chapter', 'prose_whole_chapter')
      .subscribe(event => { if (event.kind === 'completed') resolve(); if (event.kind === 'failed') reject(new Error(event.message)); }));
    assert.match(runtime.requests.at(-1)!.systemPrompt, new RegExp(`作者身份确认：Rider / ${action}`));
    await cold.startStateRebuild(f.project.id);
    assert.equal((await cold.readStructuredState(f.project.id)).project.structuredState?.unresolvedIdentityNames.length, 0);
    const operation = runtime.requests.at(-1)!.operation;
    assert.match(operation.kind === 'turn' ? operation.userPrompt : '', /Rider/);
  }
});

test('audit evidence produces a real frozen-CAS patch proposal and only approval alters source text', async () => {
  for (const accept of [false, true]) {
    const f = await fixture();
    const model: NovelModelRunning = { validate: async () => {}, cancel: () => {}, start(request) {
      const input = JSON.parse(request.operation.kind === 'turn' ? request.operation.userPrompt : '');
      const subscribers = new Set<(event: NovelModelEvent) => void>();
      setTimeout(() => {
        const text = JSON.stringify({ protocolVersion: 'amber.novel.continuity-repair.v1', chapterId: input.chapterId,
          sourceDigest: input.sourceDigest, start: input.issue.start, end: input.issue.end, replacement: 'CaptainRhea greeted Rider' });
        subscribers.forEach(callback => callback({ kind: 'snapshot', messages: [makeAssistantMessage(text)],
          generationActive: false, textDeltasLive: false, transport: 'buffered' }));
        subscribers.forEach(callback => callback({ kind: 'completed' }));
      }, 0);
      return { subscribe(callback) { subscribers.add(callback); return () => subscribers.delete(callback); } };
    } };
    const creation = createNovelCreation({ repository: f.repository, modelRunning: model });
    const quote = 'CaptainRhea met Rider';
    const proposal = await creation.repairContinuityIssue(f.project.id, { severity: 'major', chapterRef: '二', chapterId: f.second.id,
      sourceDigest: defaultGhostwriteDigest(f.second.content), quote, start: 0, end: quote.length,
      summary: '缺少约定的相认', suggestion: '改为相认' });
    assert.equal((await f.repository.loadProject(f.project.id)).chapters[1].content, f.second.content);
    assert.equal(proposal.status, 'pending'); assert.equal(proposal.review?.previews[0].oldText, quote);
    await creation.resolveWorkspaceProposal(f.project.id, proposal.proposalId, accept);
    const saved = await f.repository.loadProject(f.project.id);
    assert.equal(saved.chapters[1].content, accept ? 'CaptainRhea greeted Rider at the gate.' : f.second.content);
    if (accept) assert.ok(saved.chapterVersions.some(version => version.chapterId === f.second.id && version.content === f.second.content));
  }
});

test('all audited repairs use original ranges and one atomic proposal, while overlap is rejected before model work', async () => {
  const f = await fixture(); const runtime = stateModel(); let calls: number = 0;
  const model: NovelModelRunning = { ...runtime.model, start(request) {
    calls++;
    const input = JSON.parse(request.operation.kind === 'turn' ? request.operation.userPrompt : '');
    const listeners = new Set<(event: NovelModelEvent) => void>();
    setTimeout(() => {
      const text = JSON.stringify({ protocolVersion: 'amber.novel.continuity-repair.v1', chapterId: input.chapterId,
        sourceDigest: input.sourceDigest, start: input.issue.start, end: input.issue.end,
        replacement: input.issue.quote === 'met' ? 'greeted' : 'Friend' });
      listeners.forEach(callback => callback({ kind: 'snapshot', messages: [makeAssistantMessage(text)],
        generationActive: false, textDeltasLive: false, transport: 'buffered' }));
      listeners.forEach(callback => callback({ kind: 'completed' }));
    }, 0);
    return { subscribe(callback) { listeners.add(callback); return () => listeners.delete(callback); } };
  } };
  const creation = createNovelCreation({ repository: f.repository, modelRunning: model });
  const evidence = (quote: string) => ({ severity: 'major' as const, chapterRef: '二', chapterId: f.second.id,
    sourceDigest: defaultGhostwriteDigest(f.second.content), quote, start: f.second.content.indexOf(quote),
    end: f.second.content.indexOf(quote) + quote.length, summary: '连续性错误', suggestion: '保留先文修复后文' });
  await assert.rejects(creation.repairContinuityIssues(f.project.id, [evidence('met'), evidence('met Rider')]), /重叠/);
  assert.equal(calls, 0);
  const proposal = await creation.repairContinuityIssues(f.project.id, [evidence('met'), evidence('Rider')]);
  assert.equal(proposal.patches.length, 1); assert.equal(proposal.review?.previews.length, 2);
  assert.equal((await f.repository.loadProject(f.project.id)).chapters[1].content, f.second.content);
  await creation.resolveWorkspaceProposal(f.project.id, proposal.proposalId, true);
  const saved = await f.repository.loadProject(f.project.id);
  assert.equal(saved.chapters[0].content, f.first.content);
  assert.equal(saved.chapters[1].content, 'CaptainRhea greeted Friend at the gate.');
  assert.equal(saved.chapterVersions.filter(version => version.chapterId === f.second.id).length, 1);
});

test('cancelling a batch after one result releases the next request and publishes no partial proposal', async () => {
  const f = await fixture(); const runtime = stateModel(); let count: number = 0; let cancelCount: number = 0;
  const listeners = new Set<(event: NovelModelEvent) => void>();
  const model: NovelModelRunning = { ...runtime.model, cancel() { cancelCount++; }, start(request) {
    count++;
    if (count === 1) setTimeout(() => {
      const input = JSON.parse(request.operation.kind === 'turn' ? request.operation.userPrompt : '');
      const text = JSON.stringify({ protocolVersion: 'amber.novel.continuity-repair.v1', chapterId: input.chapterId,
        sourceDigest: input.sourceDigest, start: input.issue.start, end: input.issue.end, replacement: 'greeted' });
      listeners.forEach(callback => callback({ kind: 'snapshot', messages: [makeAssistantMessage(text)],
        generationActive: false, textDeltasLive: false, transport: 'buffered' }));
      listeners.forEach(callback => callback({ kind: 'completed' }));
    }, 0);
    return { subscribe(callback) { listeners.add(callback); return () => listeners.delete(callback); } };
  } };
  const creation = createNovelCreation({ repository: f.repository, modelRunning: model });
  const evidence = (quote: string) => ({ severity: 'major' as const, chapterRef: '二', chapterId: f.second.id,
    sourceDigest: defaultGhostwriteDigest(f.second.content), quote, start: f.second.content.indexOf(quote),
    end: f.second.content.indexOf(quote) + quote.length, summary: '连续性错误', suggestion: '修复' });
  const pending = creation.repairContinuityIssues(f.project.id, [evidence('met'), evidence('Rider')]);
  const rejection = assert.rejects(pending, /取消/);
  while (count < 2) await tick();
  await creation.cancelContinuityRepair(f.project.id); await rejection;
  assert.equal(cancelCount, 1); assert.equal(listeners.size, 0);
  assert.deepEqual(await creation.workspaceProposals(f.project.id), []);
  assert.equal((await f.repository.loadProject(f.project.id)).chapters[1].content, f.second.content);
});
