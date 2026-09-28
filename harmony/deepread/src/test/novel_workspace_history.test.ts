import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  assertWorkspaceCasMatch, classifyReceiptReplay, isWorkspacePlotStale,
  parseDurableWorkspaceProposal, parseWorkspaceCommit, refreshWorkspacePlotState,
  resolveDurableWorkspaceProposal, syncWorkspacePlot, unresolvedFromOrdinal,
  validateWorkspaceProposalPatch,
} from '../main/ets/novel/workspace_history.ts';
import type { DurableWorkspaceProposal, WorkspaceCas, WorkspaceCommit } from '../main/ets/novel/workspace_history.ts';

const cas: WorkspaceCas = { branchId: 'main', head: 'c1', treeDigest: 'tree-1' };

const commit = (receipt: string): WorkspaceCommit => ({
  version: 1,
  commitId: `commit-${receipt}`,
  parent: null,
  branchId: 'main',
  treeDigest: 'tree-1',
  mutation: 'manual_edit',
  receipt,
  changedPaths: ['branches/main/chapters/002-turn.md'],
  changedChapterOrdinal: 2,
  createdAt: 10,
});

const proposal = (): DurableWorkspaceProposal => ({
  proposalId: 'proposal-1',
  status: 'pending',
  expectedCas: cas,
  patches: [{ operation: 'write', path: 'branches/main/chapters/003-next.md', content: 'new prose' }],
  createdAt: 10,
  resolvedAt: null,
});

test('CAS rejects any branch, head, or tree drift before a write', () => {
  assert.doesNotThrow(() => assertWorkspaceCasMatch(cas, { branchId: 'main', head: 'c1', treeDigest: 'tree-1' }));
  assert.throws(() => assertWorkspaceCasMatch(cas, { branchId: 'main', head: 'c2', treeDigest: 'tree-1' }));
  assert.throws(() => assertWorkspaceCasMatch(cas, { branchId: 'other', head: 'c1', treeDigest: 'tree-1' }));
});

test('commit parser and receipt classifier preserve exactly-once replay semantics', () => {
  const parsed = parseWorkspaceCommit(JSON.stringify(commit('command-1')));
  assert.equal(parsed.commitId, 'commit-command-1');
  assert.equal(parsed.receipt, 'command-1');
  assert.equal(classifyReceiptReplay([parsed], 'command-1'), 'replay');
  assert.equal(classifyReceiptReplay([parsed], 'command-2'), 'new');
  assert.throws(() => classifyReceiptReplay([parsed, parsed], 'command-1'));
  assert.throws(() => parseWorkspaceCommit(JSON.stringify({ ...commit('r'), changedPaths: ['.amber/receipt.json'] })));
  assert.throws(() => parseWorkspaceCommit(JSON.stringify({ ...commit('r'), commitId: '' })));
});

test('durable proposal validates controlled paths and resolves approve, deny, and replay idempotently', () => {
  const accepted = resolveDurableWorkspaceProposal(proposal(), true, 11);
  assert.equal(accepted.changed, true);
  assert.equal(accepted.proposal.status, 'accepted');
  assert.equal(accepted.proposal.resolvedAt, 11);

  const acceptedReplay = resolveDurableWorkspaceProposal(accepted.proposal, false, 12);
  assert.equal(acceptedReplay.changed, false);
  assert.equal(acceptedReplay.proposal.status, 'accepted');
  assert.equal(acceptedReplay.proposal.resolvedAt, 11);

  const denied = resolveDurableWorkspaceProposal(proposal(), false, 11);
  assert.equal(denied.proposal.status, 'rejected');
  assert.equal(denied.changed, true);
  assert.throws(() => parseDurableWorkspaceProposal(JSON.stringify({
    ...proposal(), patches: [{ operation: 'write', path: 'setting/world/x.md', content: 'bypass' }],
  })));
});

test('proposal permits current chapter plan patches without opening other settings or branches', () => {
  const path = 'branches/main/plan/this-chapter.md';
  assert.deepEqual(validateWorkspaceProposalPatch({ operation: 'write', path, content: '本章目标' }, 'main'),
    { operation: 'write', path, content: '本章目标' });
  assert.deepEqual(validateWorkspaceProposalPatch({ operation: 'delete', path, content: null }, 'main'),
    { operation: 'delete', path, content: null });
  for (const forbidden of [
    'branches/other/plan/this-chapter.md',
    'branches/main/plan/future.md',
    'branches/main/setting/preferences.md',
    'branches/main/plan/../this-chapter.md',
  ]) {
    assert.throws(() => validateWorkspaceProposalPatch({
      operation: 'write', path: forbidden, content: '不应写入',
    }, 'main'));
  }
});

test('middle edit marks itself and later chapters unresolved, but last chapter does not', () => {
  assert.equal(unresolvedFromOrdinal(2, 5), 2);
  assert.equal(unresolvedFromOrdinal(5, 5), null);
  assert.throws(() => unresolvedFromOrdinal(6, 5));
});

test('plot state is stale until its synced digest matches the active tree and explicit sync clears it', () => {
  assert.equal(isWorkspacePlotStale('tree-2', 'tree-1'), true);
  assert.equal(refreshWorkspacePlotState('tree-2', 'tree-1').stale, true);
  const synced = syncWorkspacePlot('tree-2');
  assert.deepEqual(synced, { sourceTreeDigest: 'tree-2', syncedTreeDigest: 'tree-2', stale: false });
});
