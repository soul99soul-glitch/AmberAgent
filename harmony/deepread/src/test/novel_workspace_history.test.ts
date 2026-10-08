import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import {
  classifyReceiptReplay, parseWorkspaceCommit, validateWorkspaceProposalPatch,
} from '../main/ets/novel/workspace_history.ts';
import type { WorkspaceCommit } from '../main/ets/novel/workspace_history.ts';

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
