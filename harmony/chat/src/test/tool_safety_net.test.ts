import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import {
  WORKSPACE_UNDO_MAX_BYTES,
  WORKSPACE_UNDO_TTL_MS,
  buildWorkspaceDiffPreview,
  buildMemoryDiffPreview,
  WorkspaceUndoStore,
  markInterruptedSideEffectToolsUnknown,
  applyToolOutcomeResolutionToConversation,
  readToolOutcomeState,
  extractUndoReceiptFromToolPart,
} from '../main/ets/chat/tool_safety_net.ts';
import { PosixWorkspaceManager, type WorkspaceFsPort } from '../main/ets/chat/workspace.ts';
import { makeConversation, patchConversation, toMessageNode } from '../main/ets/chat/conversation.ts';
import { makeUIMessage } from '../main/ets/chat/message.ts';

const makeMemPort = (): { port: WorkspaceFsPort; files: Map<string, Uint8Array>; dirs: Set<string> } => {
  const files = new Map<string, Uint8Array>();
  const dirs = new Set<string>(['/ws']);
  const port: WorkspaceFsPort = {
    exists: (p) => files.has(p) || dirs.has(p),
    isDirectory: (p) => dirs.has(p),
    isFile: (p) => files.has(p),
    listNames: () => [],
    readText: (p) => Buffer.from(files.get(p) ?? new Uint8Array()).toString('utf8'),
    writeText: (p, text, append) => {
      const next = Buffer.from(text, 'utf8');
      if (append && files.has(p)) {
        files.set(p, new Uint8Array(Buffer.concat([Buffer.from(files.get(p)!), next])));
      } else {
        files.set(p, new Uint8Array(next));
      }
    },
    readBytes: (p) => new Uint8Array(files.get(p) ?? new Uint8Array()),
    writeBytes: (p, bytes, append) => {
      if (append && files.has(p)) {
        files.set(p, new Uint8Array(Buffer.concat([Buffer.from(files.get(p)!), Buffer.from(bytes)])));
      } else {
        files.set(p, new Uint8Array(bytes));
      }
    },
    fileSize: (p) => files.get(p)?.length ?? 0,
    mkdirs: (p) => { dirs.add(p); },
    rename: (from, to) => {
      const data = files.get(from);
      if (data) { files.set(to, data); files.delete(from); }
    },
    copyFile: (from, to) => {
      const data = files.get(from);
      if (data) files.set(to, new Uint8Array(data));
    },
    deleteRecursively: (p) => {
      const had = files.delete(p) || dirs.delete(p);
      return had;
    },
  };
  return { port, files, dirs };
};

test('P1-3: Diff preview formats workspace write/edit/move and memory create/edit/delete cleanly', () => {
  const editDiff = buildWorkspaceDiffPreview(
    'file_edit',
    { path: 'notes/plan.md', old_text: 'step 1\nstep 2', new_text: 'step 1\nstep 2 done' },
    () => '# Header\nstep 1\nstep 2\n# Footer',
  );
  assert.ok(editDiff?.includes('--- /workspace/notes/plan.md'));
  assert.ok(editDiff?.includes('-step 2'));
  assert.ok(editDiff?.includes('+step 2 done'));

  const moveDiff = buildWorkspaceDiffPreview(
    'file_move',
    { source_path: 'a.txt', target_path: 'archive/a.txt' },
    () => null,
  );
  assert.ok(moveDiff?.includes('-a.txt'));
  assert.ok(moveDiff?.includes('+archive/a.txt'));

  const memEditDiff = buildMemoryDiffPreview(
    'memory_tool',
    { action: 'edit', id: 7, content: 'Prefers concise Chinese' },
    (id) => (id === 7 ? 'Prefers Chinese' : null),
  );
  assert.ok(memEditDiff?.includes('-Prefers Chinese'));
  assert.ok(memEditDiff?.includes('+Prefers concise Chinese'));
});

test('P1-3: .undo/ snapshots support rollback, reject SHA-256 external tampering, enforce 5 MiB / limit 3 / 24h TTL', async () => {
  let now = 10_000_000;
  let seq = 0;
  const { port, files } = makeMemPort();
  const undoStore = new WorkspaceUndoStore({
    port,
    rootAbs: '/ws',
    sha256Hex: (b) => crypto.createHash('sha256').update(b).digest('hex'),
    nowMs: () => now,
    makeId: () => `snap-${++seq}`,
  });
  const mgr = new PosixWorkspaceManager({ port, rootAbs: '/ws', undoStore });

  const e1 = await mgr.writeText('notes/a.md', 'v1');
  assert.equal(e1.undoSnapshotId, 'snap-1');
  const u1 = undoStore.undoLastChange('notes/a.md', 'snap-1');
  assert.equal(u1.ok, true);
  assert.equal(port.exists('/ws/notes/a.md'), false);

  await mgr.writeText('notes/a.md', 'v1');
  await mgr.editText('notes/a.md', 'v1', 'v2', false);
  await mgr.editText('notes/a.md', 'v2', 'v3', false);
  await mgr.editText('notes/a.md', 'v3', 'v4', false);
  assert.equal(undoStore.listSnapshots('notes/a.md').length, 3);

  files.set('/ws/notes/a.md', Buffer.from('external edit'));
  const conflict = undoStore.undoLastChange('notes/a.md');
  assert.equal(conflict.ok, false);
  assert.equal(conflict.conflict, true);

  await mgr.writeText('notes/b.md', 'before');
  const editRes = await mgr.editText('notes/b.md', 'before', 'after', false);
  assert.ok(editRes.undoSnapshotId);
  const restored = undoStore.undoLastChange('notes/b.md', editRes.undoSnapshotId);
  assert.equal(restored.ok, true);
  assert.equal(port.readText('/ws/notes/b.md'), 'before');

  const big = 'x'.repeat(WORKSPACE_UNDO_MAX_BYTES + 16);
  const bigEntry = await mgr.writeText('data/big.txt', big);
  assert.equal(bigEntry.undoSnapshotId, undefined);

  now += WORKSPACE_UNDO_TTL_MS + 1000;
  const expiredUndo = undoStore.undoLastChange('notes/a.md');
  assert.equal(expiredUndo.ok, false);
  assert.equal(undoStore.listSnapshots().length, 0);
});

test('P1-3: interrupted side-effect tool becomes outcome_unknown and supports 3-state reconciliation', () => {
  const runningToolMsg = makeUIMessage('assistant', [
    {
      type: 'tool',
      toolCallId: 'tc-write-1',
      toolName: 'file_write',
      input: '{"path":"notes/a.md","content":"hi"}',
      output: [],
      approvalState: { type: 'approved' },
      metadata: null,
    },
    {
      type: 'tool',
      toolCallId: 'tc-pending-2',
      toolName: 'file_edit',
      input: '{"path":"notes/a.md","old_text":"a","new_text":"b"}',
      output: [],
      approvalState: { type: 'pending' },
      metadata: null,
    },
  ]);

  const marked = markInterruptedSideEffectToolsUnknown(runningToolMsg);
  assert.equal(marked.changed, true);
  const part1 = marked.message.parts[0];
  assert.equal(part1.type, 'tool');
  if (part1.type === 'tool') {
    assert.equal(readToolOutcomeState(part1), 'outcome_unknown');
    assert.equal(part1.approvalState.type, 'denied');
  }
  const part2 = marked.message.parts[1];
  if (part2.type === 'tool') {
    assert.equal(readToolOutcomeState(part2), null);
    assert.equal(part2.approvalState.type, 'pending');
  }

  let conv = patchConversation(makeConversation('conv-p1-3'), {
    messageNodes: [toMessageNode(marked.message, 'node-1')],
  });
  conv = applyToolOutcomeResolutionToConversation(conv, 'tc-write-1', 'applied');
  const resolvedPart = conv.messageNodes[0].messages[0].parts[0];
  if (resolvedPart.type === 'tool') {
    assert.equal(readToolOutcomeState(resolvedPart), 'reconciled_applied');
  }

  const receipt = extractUndoReceiptFromToolPart({
    type: 'tool',
    toolCallId: 'tc-3',
    toolName: 'file_write',
    input: '{}',
    output: [{ type: 'text', text: '{"path":"notes/a.md","size_bytes":2,"undo_snapshot_id":"snap-9"}', metadata: null }],
    approvalState: { type: 'approved' },
    metadata: null,
  });
  assert.deepEqual(receipt, { snapshotId: 'snap-9', path: 'notes/a.md' });
});
