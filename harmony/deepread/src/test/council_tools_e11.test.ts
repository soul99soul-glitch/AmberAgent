import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createModelCouncilManager } from '../main/ets/council/manager.ts';
import type { ModelCouncilManager } from '../main/ets/council/manager.ts';
import { makeRuntimeSetting, makeSeat } from '../main/ets/council/models.ts';
import type { CouncilApprovalRequest, CouncilToolSnapshot } from '../main/ets/council/models.ts';
import type { CouncilGenerateRequest, ModelCouncilTextRunner } from '../main/ets/council/runner.ts';
import { makeModelConfig } from '../main/ets/domain/model_config.ts';
import { makeUIMessage } from '../main/ets/agent/message.ts';
import type { UIMessagePartTool } from '../main/ets/agent/message.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';

const pool = [makeModelConfig({ id: 'fixed', baseUrl: 'https://seat.invalid', apiKey: 'seat', model: 'seat-model' })];
const seats = ['a', 'b'].map(id => makeSeat({ seatId: id, name: id, role: id, modelId: 'fixed' }));
const input = { mode: 'compare' as const, objective: 'actual evidence', seats };
const makeSnapshot = (req: CouncilGenerateRequest, hashes: string[]): CouncilToolSnapshot => {
  assert.ok(req.key);
  const parts: UIMessagePartTool[] = hashes.map((hash, index) => ({
    type: 'tool', toolCallId: `parent-${index}`, toolName: 'plugin__proof', input: '{}', output: [],
    approvalState: { type: 'pending' }, metadata: { child: hash },
  }));
  const message = makeUIMessage('assistant', parts, { id: `message-${req.key.seatId}` });
  const conversationId = `seat-${req.key.seatId}`;
  return { key: req.key, conversationId, messages: [message], sources: [],
    pending: parts.map((part, partIndex) => ({ key: req.key!, conversationId, messageId: message.id,
      partIndex, toolCallId: part.toolCallId, subjectHash: hashes[partIndex], part })) };
};

test('E11 saved publication, exact child decisions, evidence, failure and cancellation isolation', async () => {
  assert.equal(makeRuntimeSetting().toolMode, 'off');
  const files = createMemoryFileStore();
  let releaseSave!: () => void;
  const saveBarrier = new Promise<void>(resolve => { releaseSave = resolve; });
  let publications = 0;
  let effects = 0;
  let stale: CouncilApprovalRequest | null = null;
  let synthesisEvidence = '';
  const completedRequests: CouncilGenerateRequest[] = [];
  const runner: ModelCouncilTextRunner = { generate: async req => {
    completedRequests.push(req);
    assert.equal(req.model.model, 'seat-model');
    if (req.key === undefined) {
      assert.equal(req.toolMode, 'off');
      synthesisEvidence = req.userPrompt;
      return { text: 'verdict', warnings: [] };
    }
    assert.equal(req.toolMode, 'search');
    if (req.key.seatId === 'b') return { text: 'peer complete', warnings: [] };
    const first = makeSnapshot(req, ['child-1', 'other-parent']);
    // This runner fixture represents C's source barrier. Actual FileStore/tool
    // integration is C's separate fixture, not claimed by this manager group.
    await saveBarrier;
    req.onTools!(first);
    const v1 = await req.requestApproval!(first.pending[0], req.signal!);
    const v2 = await req.requestApproval!(first.pending[1], req.signal!);
    assert.equal(v1.kind, 'approved');
    assert.equal(v2.kind, 'denied');
    effects++;
    const next = makeSnapshot(req, ['child-2']);
    req.onTools!(next);
    const v3 = await req.requestApproval!(next.pending[0], req.signal!);
    assert.equal(v3.kind, 'answered');
    assert.equal(v3.answer, 'human answer');
    effects++;
    const part = next.messages[0].parts[0] as UIMessagePartTool;
    part.approvalState = { type: 'answered', answer: 'human answer' };
    part.output = [{ type: 'text', text: 'verified measurement 42', metadata: null }];
    next.pending = [];
    next.sources = [{ title: 'Official', url: 'https://evidence.invalid/proof', service: 'search' }];
    req.onTools!(next);
    return { text: 'supported answer', warnings: [], toolMessages: next.messages, sources: next.sources };
  } };
  const manager = createModelCouncilManager({ runner, fileStore: files, modelPool: pool,
    setting: makeRuntimeSetting({ toolMode: 'search', seatTimeoutMs: 1000, totalTimeoutMs: 2000 }) });
  const run = manager.start(input);
  manager.subscribeTools(run.runId, snapshot => {
    publications++;
    if (snapshot.pending.length === 2) {
      stale = snapshot.pending[0];
      const forged = { ...snapshot.pending[0], partIndex: 1 };
      assert.equal(manager.submitToolVerdict(forged, { kind: 'approved', reason: '', answer: null }), false);
      assert.equal(manager.submitToolVerdict(snapshot.pending[1], { kind: 'denied', reason: 'no', answer: null }), true);
      assert.equal(manager.submitToolVerdict(snapshot.pending[0], { kind: 'approved', reason: '', answer: null }), true);
      assert.equal(manager.submitToolVerdict(snapshot.pending[0], { kind: 'approved', reason: '', answer: null }), false);
    } else if (snapshot.pending.length === 1) {
      assert.ok(stale);
      assert.equal(manager.submitToolVerdict(stale, { kind: 'approved', reason: '', answer: null }), false);
      assert.equal(manager.submitToolVerdict(snapshot.pending[0], { kind: 'answered', reason: '', answer: 'human answer' }), true);
    }
  });
  assert.equal(publications, 0);
  assert.equal(effects, 0);
  releaseSave();
  const done = await manager.wait(run.runId, 2000);
  assert.equal(done?.status, 'completed');
  assert.ok(completedRequests.every(req => req.signal?.aborted), 'terminal signal releases actual native owners');
  assert.equal(effects, 2);
  assert.ok(synthesisEvidence.includes('verified measurement 42'));
  assert.ok(synthesisEvidence.includes('https://evidence.invalid/proof'));
  assert.ok(done?.turns.find(turn => turn.seatId === 'a')?.toolMessages?.length);
  assert.equal(manager.toolSnapshot(run.runId, 1, 'a')?.pending.length, 0);
  const archived = await files.readText(run.transcriptPath);
  assert.ok(archived?.includes('verified measurement 42'));
  assert.ok(archived?.includes('https://evidence.invalid/proof'));
  const copy = manager.toolSnapshot(run.runId, 1, 'a')!;
  copy.messages[0].parts = [];
  assert.equal(manager.toolSnapshot(run.runId, 1, 'a')?.messages[0].parts.length, 1);

  let failedPublications = 0;
  const failedRequests: CouncilGenerateRequest[] = [];
  const sourceFailure: ModelCouncilTextRunner = { generate: async req => {
    failedRequests.push(req);
    if (req.key?.seatId === 'a') throw new Error('source save failed');
    return { text: 'peer', warnings: [] };
  } };
  const failed = createModelCouncilManager({ runner: sourceFailure, fileStore: null, modelPool: pool,
    setting: makeRuntimeSetting({ toolMode: 'full' }) });
  const failedRun = failed.start(input);
  failed.subscribeTools(failedRun.runId, () => { failedPublications++; });
  const partial = await failed.wait(failedRun.runId, 2000);
  assert.equal(partial?.status, 'partial_failed');
  assert.ok(failedRequests.every(req => req.signal?.aborted), 'save/hash failures release the same owner signal');
  assert.equal(failedPublications, 0);
  assert.ok(partial?.turns.some(turn => turn.status === 'completed'));

  const late: CouncilGenerateRequest[] = [];
  const pendingBySeat: CouncilToolSnapshot[] = [];
  let cancelled!: ModelCouncilManager;
  const hanging: ModelCouncilTextRunner = { generate: async req => {
    late.push(req);
    const snapshot = makeSnapshot(req, ['one', 'two']);
    req.onTools!(snapshot);
    await Promise.all(snapshot.pending.map(request => req.requestApproval!(request, req.signal!)));
    return { text: 'must not finish', warnings: [] };
  } };
  cancelled = createModelCouncilManager({ runner: hanging, fileStore: null, modelPool: pool,
    setting: makeRuntimeSetting({ toolMode: 'full', seatTimeoutMs: 1000, totalTimeoutMs: 2000 }) });
  const cancelledRun = cancelled.start(input);
  cancelled.subscribeTools(cancelledRun.runId, snapshot => {
    if (snapshot.pending.length > 0) {
      pendingBySeat.push(snapshot);
      if (pendingBySeat.length === 2) cancelled.cancel(cancelledRun.runId);
    }
  });
  const stopped = await cancelled.wait(cancelledRun.runId, 2000);
  assert.equal(stopped?.status, 'cancelled');
  assert.equal(stopped?.turns.length, 2);
  assert.ok(late.every(req => req.signal?.aborted));
  for (const snapshot of pendingBySeat) {
    assert.equal(cancelled.submitToolVerdict(snapshot.pending[0], { kind: 'approved', reason: '', answer: null }), false);
    assert.equal(cancelled.toolSnapshot(cancelledRun.runId, 1, snapshot.key.seatId)?.pending.length, 0);
  }
  const prior = JSON.stringify(cancelled.snapshot(cancelledRun.runId));
  late[0].onChunk('late text');
  late[0].onTools!(pendingBySeat[0]);
  assert.equal(JSON.stringify(cancelled.snapshot(cancelledRun.runId)), prior);

  const timed: ModelCouncilTextRunner = { generate: async req => {
    if (req.key?.seatId !== 'a') return { text: 'peer survives', warnings: [] };
    const snapshot = makeSnapshot(req, ['deadline']);
    req.onTools!(snapshot);
    await req.requestApproval!(snapshot.pending[0], req.signal!);
    return { text: 'late', warnings: [] };
  } };
  const deadline = createModelCouncilManager({ runner: timed, fileStore: null, modelPool: pool,
    setting: makeRuntimeSetting({ toolMode: 'full', seatTimeoutMs: 20, totalTimeoutMs: 1000 }) });
  const deadlineRun = deadline.start(input);
  const deadlineDone = await deadline.wait(deadlineRun.runId, 2000);
  assert.equal(deadlineDone?.status, 'partial_failed');
  assert.equal(deadlineDone?.turns.find(turn => turn.seatId === 'a')?.status, 'timed_out');
  assert.equal(deadlineDone?.turns.find(turn => turn.seatId === 'b')?.status, 'completed');

  const unfinished: ModelCouncilTextRunner = { generate: async req => {
    const snapshot = makeSnapshot(req, ['unresolved']);
    req.onTools!(snapshot);
    return { text: 'text with pending action', warnings: [], toolMessages: snapshot.messages };
  } };
  const unresolved = createModelCouncilManager({ runner: unfinished, fileStore: null, modelPool: pool,
    setting: makeRuntimeSetting({ toolMode: 'search' }) });
  const unresolvedRun = unresolved.start(input);
  assert.equal((await unresolved.wait(unresolvedRun.runId, 2000))?.status, 'failed');
  const empty = createModelCouncilManager({ runner: { generate: async () => ({ text: ' ', warnings: [] }) },
    fileStore: null, modelPool: pool, setting: makeRuntimeSetting() });
  assert.equal((await empty.wait(empty.start(input).runId, 2000))?.status, 'failed');

  const archiveFailure = createMemoryFileStore();
  archiveFailure.appendText = async () => { throw new Error('disk IO'); };
  const archiveManager = createModelCouncilManager({ runner: { generate: async () => ({ text: 'valid', warnings: [] }) },
    fileStore: archiveFailure, modelPool: pool, setting: makeRuntimeSetting() });
  const archiveRun = archiveManager.start(input);
  const archiveDone = await archiveManager.wait(archiveRun.runId, 2000);
  assert.equal(archiveDone?.status, 'completed');
  assert.ok(archiveDone?.result?.warnings.some(warning => warning.includes('disk IO')));
});
