import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelProject, makeNovelChapter, makeNovelMaterial, makeNovelMessage } from '../main/ets/novel/models.ts';
import type { NovelNativeBackupImport } from '../main/ets/novel/native_backup.ts';

const setup = async () => {
  const store = createMemoryFileStore();
  const repo = createFileNovelRepository(store);
  await repo.createProject({ ...makeNovelProject({ id: 'history', name: '历史', now: 1000 }),
    creationMode: 'quickStart',
    quickStartSeed: { genre: '奇幻', coreIdea: '种子', world: '原始世界', characters: '角色', direction: '方向' },
    polishPreference: '保留对白',
    branchSettings: { ...makeNovelProject({ id: 'unused', name: 'unused', now: 1000 }).branchSettings, thisChapterPlan: '继续冲突' },
    chapters: [makeNovelChapter({ id: 'c1', title: '第一章', content: '原文', now: 1000 })],
    materials: [makeNovelMaterial({ id: 'm1', title: '世界', kind: 'world', content: '原始世界', now: 1000 })],
  });
  return { store, repo };
};
const edit = async (repo: ReturnType<typeof createFileNovelRepository>, body: string, receipt: string) => {
  await repo.commitProject('history', (await repo.workspaceStatus('history')).cas, receipt, 'manual_edit', p => ({
    ...p, chapters: [{ ...p.chapters[0], content: body }], updatedAt: p.updatedAt + 1,
  }));
};

test('two edits can undo twice across reload without toggling undo itself', async () => {
  const { store, repo } = await setup();
  await edit(repo, '第一改', 'edit-1');
  await edit(repo, '第二改', 'edit-2');
  assert.equal((await repo.undo('history', (await repo.workspaceStatus('history')).cas, 'undo-1')).chapters[0].content, '第一改');
  const reloaded = createFileNovelRepository(store);
  assert.equal((await reloaded.workspaceStatus('history')).canUndo, true);
  assert.equal((await reloaded.undo('history', (await reloaded.workspaceStatus('history')).cas, 'undo-2')).chapters[0].content, '原文');
  assert.equal((await reloaded.workspaceStatus('history')).canUndo, false);
  await assert.rejects(reloaded.undo('history', (await reloaded.workspaceStatus('history')).cas, 'undo-3'), /没有可撤销/);
});

test('a new edit after undo preserves another reachable undo step', async () => {
  const { repo } = await setup();
  await edit(repo, '第一改', 'edit-1');
  await edit(repo, '第二改', 'edit-2');
  await repo.undo('history', (await repo.workspaceStatus('history')).cas, 'undo-1');
  await edit(repo, '第三改', 'edit-3');
  assert.equal((await repo.undo('history', (await repo.workspaceStatus('history')).cas, 'undo-2')).chapters[0].content, '第一改');
  assert.equal((await repo.undo('history', (await repo.workspaceStatus('history')).cas, 'undo-3')).chapters[0].content, '原文');
});

test('fork selected checkpoint uses its manuscript materials and conversation, not later content', async () => {
  const { repo } = await setup();
  const original = (await repo.workspaceStatus('history')).cas;
  await repo.commitProject('history', original, 'later', 'manual_edit', p => ({
    ...p, chapters: [{ ...p.chapters[0], content: '后来正文' }],
    materials: [{ ...p.materials[0], content: '后来世界' }],
    messages: [makeNovelMessage({ id: 'later-msg', role: 'user', mode: 'discuss', content: '后来对话', createdAt: 1001 })],
  }));
  const sourceBranch = (await repo.workspaceStatus('history')).activeBranchId;
  const fork = await repo.forkFromHistory('history', original.head, '从原始检查点', (await repo.workspaceStatus('history')).cas, 'fork-old');
  assert.equal(fork.chapters[0].content, '原文');
  assert.equal(fork.materials[0].content, '原始世界');
  assert.equal(fork.messages.length, 0);
  assert.equal(fork.creationMode, 'quickStart');
  assert.equal(fork.quickStartSeed!.coreIdea, '种子');
  assert.equal(fork.polishPreference, '保留对白');
  assert.equal((await repo.workspaceStatus('history')).canUndo, false);
  const latest = await repo.switchBranch('history', sourceBranch, (await repo.workspaceStatus('history')).cas);
  assert.equal(latest.chapters[0].content, '后来正文');
  assert.equal(latest.messages[0].id, 'later-msg');
});

test('target undo persists checkpoint selection and supports forking the restored state', async () => {
  const { repo } = await setup();
  await edit(repo, '第一改', 'edit-1');
  const checkpoint = (await repo.workspaceStatus('history')).cas;
  await edit(repo, '第二改', 'edit-2');
  await edit(repo, '第三改', 'edit-3');
  const restored = await repo.undoToCheckpoint('history', checkpoint.head, (await repo.workspaceStatus('history')).cas, 'restore');
  assert.equal(restored.chapters[0].content, '第一改');
  const fork = await repo.createBranch('history', '恢复后分支', (await repo.workspaceStatus('history')).cas, 'fork-restored');
  assert.equal(fork.chapters[0].content, '第一改');
  assert.equal((await repo.workspaceStatus('history')).canUndo, false);
});

test('branch rename main assignment and delete preserve branch data and enforce exact restrictions', async () => {
  const { repo } = await setup();
  const main = (await repo.workspaceStatus('history')).activeBranchId;
  await repo.createBranch('history', '支线', (await repo.workspaceStatus('history')).cas, 'fork');
  const branch = (await repo.workspaceStatus('history')).activeBranchId;
  const cas = (await repo.workspaceStatus('history')).cas;
  await assert.rejects(repo.deleteBranch('history', branch, cas, 'delete-current'), /当前分支/);
  await assert.rejects(repo.deleteBranch('history', main, cas, 'delete-main'), /主线/);
  await assert.rejects(repo.renameBranch('history', branch, '  ', cas, 'rename-empty'), /不能为空/);
  await repo.renameBranch('history', branch, '重命名支线', cas, 'rename');
  await repo.setMainBranch('history', branch, (await repo.workspaceStatus('history')).cas, 'set-main');
  const deleted = await repo.deleteBranch('history', main, (await repo.workspaceStatus('history')).cas, 'delete-old-main');
  assert.equal(deleted.branches.filter(b => b.isMain && b.lifecycle === 'active').length, 1);
  assert.equal(deleted.branches.find(b => b.id === main)!.lifecycle, 'deleted');
  assert.equal((await repo.workspaceStatus('history')).activeBranchName, '重命名支线');
  await assert.rejects(repo.switchBranch('history', main, (await repo.workspaceStatus('history')).cas), /分支不存在/);
});

test('history restoration rejects stale CAS and active jobs and does not mutate manuscripts', async () => {
  const { repo } = await setup();
  const original = (await repo.workspaceStatus('history')).cas;
  await edit(repo, '修改后', 'edit-1');
  await assert.rejects(repo.undoToCheckpoint('history', original.head, original, 'stale'), /工作区已变化/);
  const current = (await repo.workspaceStatus('history')).cas;
  await repo.startGhostwriteJob('history', current, 'job', 'plan', 1, 1001);
  for (const action of [
    () => repo.undo('history', current, 'blocked-undo'),
    () => repo.forkFromHistory('history', original.head, '禁止分叉', current, 'blocked-fork'),
    () => repo.renameBranch('history', current.branchId, '禁止改名', current, 'blocked-rename'),
    () => repo.setMainBranch('history', current.branchId, current, 'blocked-main'),
  ]) await assert.rejects(action(), /未结束/);
  assert.equal((await repo.loadProject('history')).chapters[0].content, '修改后');
});

test('native full backup retains multi-undo cursor history and original immutable metadata', async () => {
  const { repo } = await setup();
  await edit(repo, '第一改', 'edit-1');
  await edit(repo, '第二改', 'edit-2');
  await repo.undo('history', (await repo.workspaceStatus('history')).cas, 'undo-1');
  const snapshot = await repo.nativeBackupSnapshot('history');
  const input: NovelNativeBackupImport = { projectId: 'history', files: snapshot.files,
    manifest: { ...snapshot.metadata, format: 'amber.novel.native-backup', version: 1, checksumAlgorithm: 'fnv1a32', entries: [] } };
  const target = createFileNovelRepository(createMemoryFileStore());
  await target.installNativeBackup(input, await target.inspectNativeRestore(input));
  assert.equal((await target.workspaceHistory('history')).length, 4);
  const restored = await target.undo('history', (await target.workspaceStatus('history')).cas, 'undo-2');
  assert.equal(restored.chapters[0].content, '原文');
  assert.equal(restored.creationMode, 'quickStart');
  assert.deepEqual(restored.quickStartSeed, { genre: '奇幻', coreIdea: '种子', world: '原始世界', characters: '角色', direction: '方向' });
  assert.equal(restored.polishPreference, '保留对白');
});


test('creation metadata rejects incomplete seeds and mutation while allowing preference edits', async () => {
  const { repo } = await setup();
  const cas = (await repo.workspaceStatus('history')).cas;
  await assert.rejects(repo.commitProject('history', cas, 'mutate-seed', 'compat_update', p => ({
    ...p, quickStartSeed: { ...p.quickStartSeed!, coreIdea: '覆盖种子' },
  })), /不可修改/);
  await assert.rejects(repo.commitProject('history', cas, 'missing-seed', 'compat_update', p => ({
    ...p, quickStartSeed: null,
  })), /缺少完整/);
  const updated = await repo.commitProject('history', cas, 'change-preference', 'project_setup_change', p => ({
    ...p, polishPreference: '保留作者语气',
  }));
  assert.equal(updated.polishPreference, '保留作者语气');
  await repo.undoToCheckpoint('history', cas.head, (await repo.workspaceStatus('history')).cas, 'restore-pre-preference');
  assert.equal((await repo.loadProject('history')).polishPreference, '保留作者语气');
});


test('discussion checkpoints and branch metadata do not consume an extra manuscript undo step', async () => {
  const { repo } = await setup();
  await edit(repo, '第一改', 'edit-1');
  await edit(repo, '第二改', 'edit-2');
  await repo.commitProject('history', (await repo.workspaceStatus('history')).cas, 'discussion', 'transcript_checkpoint', p => ({
    ...p, messages: [makeNovelMessage({ id: 'discussion', role: 'user', mode: 'discuss', content: '讨论', createdAt: 1002 })],
  }));
  await repo.renameBranch('history', 'main', '作者主线', (await repo.workspaceStatus('history')).cas, 'rename');
  assert.equal((await repo.undo('history', (await repo.workspaceStatus('history')).cas, 'undo-1')).chapters[0].content, '第一改');
  assert.equal((await repo.undo('history', (await repo.workspaceStatus('history')).cas, 'undo-2')).chapters[0].content, '原文');
});
