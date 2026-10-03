import { test } from 'node:test';
import assert from 'node:assert/strict';
import { makeNovelProject, makeNovelChapter } from '../main/ets/novel/models.ts';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { runContinuityAudit, parseAuditReport } from '../main/ets/novel/continuity_audit.ts';
import { defaultGhostwriteDigest } from '../main/ets/novel/ghostwrite.ts';
import { makeAssistantMessage } from '../main/ets/agent/message.ts';
import { emptyNovelStructuredState, parseNovelStateDelta, mergeNovelStateDelta } from '../main/ets/novel/structured_state.ts';
import type { NovelModelRunning, NovelModelRequest, NovelModelEvent } from '../main/ets/novel/model_running.ts';
import type { NovelAuditParsedReference } from '../main/ets/novel/continuity_audit.ts';

const first = makeNovelChapter({ id: 'earlier', ordinal: 1, title: '先文', content: 'Rhea has blue eyes.', now: 1 });
const second = makeNovelChapter({ id: 'later', ordinal: 2, title: '后文', content: 'Rhea has brown eyes.', now: 1 });
const project = { ...makeNovelProject({ id: 'pair', name: '成对取证', now: 1 }), chapters: [first, second] };
const canonical = (): NovelAuditParsedReference => ({ chapterId: first.id,
  sourceDigest: defaultGhostwriteDigest(first.content), quote: first.content, start: 0, end: first.content.length });
const pairIssue = () => ({ severity: 'major' as const, chapterRef: '后文', chapterId: second.id,
  sourceDigest: defaultGhostwriteDigest(second.content), quote: second.content, start: 0, end: second.content.length,
  summary: '眼睛颜色与先文冲突', suggestion: '沿用先文蓝色',
  canonicalReferences: [{ ...canonical(), chapterOrdinal: 999, chapterTitle: '模型伪造标题' }] });
const fakeModel = (respond: (request: NovelModelRequest) => string): NovelModelRunning => ({
  validate: async () => {}, cancel: () => {}, inputBudgetTokens: async () => 32000,
  estimateInputTokens: (system, user) => Math.ceil((system.length + user.length) / 4),
  start(request) {
    const listeners = new Set<(event: NovelModelEvent) => void>();
    setTimeout(() => {
      let text: string;
      try { text = respond(request); }
      catch (error) {
        listeners.forEach(callback => callback({ kind: 'failed', message: String(error) })); return;
      }
      listeners.forEach(callback => callback({ kind: 'snapshot', messages: [makeAssistantMessage(text)],
        generationActive: false, textDeltasLive: false, transport: 'buffered' }));
      listeners.forEach(callback => callback({ kind: 'completed' }));
    }, 0);
    return { subscribe(callback) { listeners.add(callback); return () => listeners.delete(callback); } };
  },
});

test('actual audit validates both sources and derives canonical chapter metadata from the manuscript', async () => {
  const requests: NovelModelRequest[] = [];
  const model = fakeModel(request => {
    requests.push(request);
    return JSON.stringify({ issues: request.operation.kind === 'turn' && request.operation.userPrompt.includes(`chapterId=${second.id}`)
      ? [pairIssue()] : [] });
  });
  const report = await runContinuityAudit(model, project, { kind: 'global' });
  assert.equal(report.coverage.complete, true); assert.equal(report.invalidEvidenceCount, 0);
  assert.equal(report.issues.length, 1);
  assert.deepEqual(report.issues[0].canonicalReferences, [{ ...canonical(), chapterOrdinal: 1, chapterTitle: first.title }]);
  const prompt = requests[1].operation;
  assert.equal(prompt.kind, 'turn');
  if (prompt.kind !== 'turn') throw new Error('Expected turn');
  assert.match(prompt.userPrompt, /前文事实来源/);
  assert.ok(prompt.userPrompt.includes(`"chapterId":"${first.id}"`));
  assert.ok(prompt.userPrompt.includes(`"sourceDigest":"${defaultGhostwriteDigest(first.content)}"`));
  assert.ok(prompt.userPrompt.includes(first.content));
});

test('audit drops fake, old, same-chapter and later canonical anchors instead of publishing an unpaired conflict', async () => {
  for (const reference of [ { ...canonical(), quote: 'Invented evidence.' },
    { ...canonical(), sourceDigest: 'old' },
    { ...canonical(), chapterId: second.id, sourceDigest: defaultGhostwriteDigest(second.content), quote: second.content, end: second.content.length },
    { ...canonical(), chapterId: 'missing' } ]) {
    const model = fakeModel(request => JSON.stringify({ issues: request.operation.kind === 'turn'
      && request.operation.userPrompt.includes(`chapterId=${second.id}`) ? [{ ...pairIssue(), canonicalReferences: [reference] }] : [] }));
    const report = await runContinuityAudit(model, project, { kind: 'global' });
    assert.equal(report.issues.length, 0); assert.equal(report.invalidEvidenceCount, 1); assert.equal(report.ok, false);
  }
  assert.throws(() => parseAuditReport(JSON.stringify({ issues: [{ ...pairIssue(), canonicalReferences: [] }] })), /不能为空/);
  assert.throws(() => parseAuditReport(JSON.stringify({ issues: [{ ...pairIssue(), canonicalReferences: 'bad' }] })), /不能为空/);
});

test('paired conflict repair sends the real earlier canon and approval changes only the later chapter', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore()); await repository.createProject(project);
  let repairPrompt: string = '';
  const model = fakeModel(request => {
    repairPrompt = request.operation.kind === 'turn' ? request.operation.userPrompt : '';
    const input = JSON.parse(repairPrompt);
    return JSON.stringify({ protocolVersion: 'amber.novel.continuity-repair.v1', chapterId: input.chapterId,
      sourceDigest: input.sourceDigest, start: input.issue.start, end: input.issue.end, replacement: 'Rhea still has blue eyes.' });
  });
  const creation = createNovelCreation({ repository, modelRunning: model });
  const proposal = await creation.repairContinuityIssue(project.id, pairIssue());
  const input = JSON.parse(repairPrompt);
  assert.deepEqual(input.canonicalReferences, [{ ...canonical(), chapterOrdinal: 1, chapterTitle: first.title }]);
  assert.equal(input.chapterId, second.id); assert.equal(proposal.patches.length, 1);
  await creation.resolveWorkspaceProposal(project.id, proposal.proposalId, true);
  const saved = await repository.loadProject(project.id);
  assert.equal(saved.chapters[0].content, first.content); assert.equal(saved.chapters[1].content, 'Rhea still has blue eyes.');
  assert.equal(saved.chapterVersions.filter(version => version.chapterId === first.id).length, 0);
  assert.equal(saved.chapterVersions.filter(version => version.chapterId === second.id).length, 1);
});

test('inverted pair cannot repair the earlier chapter, and stale earlier evidence is rejected before model invocation', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore()); await repository.createProject(project);
  let calls: number = 0;
  const model = fakeModel(() => { calls++; return ''; });
  const creation = createNovelCreation({ repository, modelRunning: model });
  await assert.rejects(creation.repairContinuityIssue(project.id, { ...pairIssue(), chapterId: first.id,
    sourceDigest: defaultGhostwriteDigest(first.content), quote: first.content, end: first.content.length,
    canonicalReferences: [{ chapterId: second.id, sourceDigest: defaultGhostwriteDigest(second.content),
      quote: second.content, start: 0, end: second.content.length, chapterOrdinal: 2, chapterTitle: second.title }] }), /较晚章/);
  await creation.saveChapter(project.id, first.id, first.title, 'Rhea has green eyes.');
  await assert.rejects(creation.repairContinuityIssue(project.id, pairIssue()), /前文证据|来源正文/);
  assert.equal(calls, 0); assert.deepEqual(await creation.workspaceProposals(project.id), []);
  assert.equal((await repository.loadProject(project.id)).chapters[1].content, second.content);
});

test('remote verified event input supplies its exact nonzero range so a later audit can return paired evidence without guessing', async () => {
  const quote = 'Rhea has blue eyes.';
  const earlier = { ...first, content: `The door opened. ${quote}` };
  const middle = makeNovelChapter({ id: 'middle', ordinal: 2, title: '中章', content: 'A quiet trip.', now: 1 });
  const later = { ...second, ordinal: 3 };
  const sourceDigest = defaultGhostwriteDigest(earlier.content);
  const state = mergeNovelStateDelta(emptyNovelStructuredState(), parseNovelStateDelta(JSON.stringify({
    protocolVersion: 'amber.novel.state.v1', chapterId: earlier.id, sourceDigest,
    events: [{ id: 'eyes', chapterId: earlier.id, sourceDigest, quote, summary: 'Rhea有蓝色眼睛', entityRefs: [] }],
    unresolvedIdentityNames: [],
  }), earlier, []), earlier, []);
  const manuscript = { ...project, chapters: [earlier, middle, later], structuredState: state };
  let actualReference: NovelAuditParsedReference | null = null;
  const model = fakeModel(request => {
    if (request.operation.kind !== 'turn' || !request.operation.userPrompt.includes(`chapterId=${later.id}`)) {
      return '{"issues":[]}';
    }
    const sourceLine = request.operation.userPrompt.match(/已取证早章事件来源：\n([^\n]+)/)?.[1];
    assert.ok(sourceLine, 'actual request must supply ranges for verified remote events');
    const sources = JSON.parse(sourceLine) as NovelAuditParsedReference[];
    actualReference = sources.find(source => source.chapterId === earlier.id)!;
    assert.equal(actualReference.start, 17); assert.equal(actualReference.end, earlier.content.length);
    assert.equal(actualReference.quote, quote); assert.equal(actualReference.sourceDigest, sourceDigest);
    return JSON.stringify({ issues: [{ ...pairIssue(), canonicalReferences: [actualReference] }] });
  });
  const report = await runContinuityAudit(model, manuscript, { kind: 'global' });
  assert.equal(report.coverage.complete, true); assert.equal(report.invalidEvidenceCount, 0);
  assert.equal(report.issues.length, 1); assert.equal(report.issues[0].canonicalReferences?.[0].start, 17);
});
