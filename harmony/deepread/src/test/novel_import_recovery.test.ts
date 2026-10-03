import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelProject, makeNovelChapter } from '../main/ets/novel/models.ts';
import { makeNovelNativeBackupImport } from '../main/ets/novel/native_backup.ts';
const base = 'amberagent/novel-workspace';
const setup = async () => {
  const fs = createMemoryFileStore(); const repo = createFileNovelRepository(fs);
  await repo.createProject({ ...makeNovelProject({ id: 'p', name: '小说', now: 1000 }),
    chapters: [makeNovelChapter({ id: 'c', title: '开篇', content: '完整旧正文\n'.repeat(1000), now: 1000 })] });
  await repo.commitProject('p', (await repo.workspaceStatus('p')).cas, 'initial-plan', 'branch_settings_change', p =>
    ({ ...p, branchSettings: { ...p.branchSettings, thisChapterPlan: '本章进入山林' } }));
  return { fs, repo };
};

test('recovery reads entire canonical old text without changing corrupt live bytes and rejects stale previews', async () => {
  const { fs, repo } = await setup();
  const chapter = `${base}/p/branches/main/chapters/001-开篇.md`;
  await fs.writeText(chapter, '损坏现场');
  const preview = (await repo.inspectProjectRecovery('p'))!;
  const before = await fs.readText(chapter);
  const recovered = await repo.readProjectRecovery(preview);
  assert.equal(recovered.chapters[0].content, '完整旧正文\n'.repeat(1000));
  assert.equal(await fs.readText(chapter), before);
  await fs.writeBytes(`${base}/p/unknown.bin`, new Uint8Array([255]));
  await assert.rejects(repo.readProjectRecovery(preview), /重新预览/);
});

test('public replacement requires exact preview, retains old tree, and refuses active jobs', async () => {
  const { fs, repo } = await setup(); const plan = await repo.publicExportPlan('p');
  await assert.rejects(repo.installWorkspacePlan(plan), /已存在/);
  let preview = await repo.inspectWorkspaceRestore(plan);
  await repo.commitProject('p', (await repo.workspaceStatus('p')).cas, 'edit', 'manual_edit', p =>
    ({ ...p, chapters: p.chapters.map(c => ({ ...c, content: '当前现场' })) }));
  await assert.rejects(repo.installWorkspacePlan(plan, preview), /重新预览/);
  preview = await repo.inspectWorkspaceRestore(plan);
  const restored = await repo.installWorkspacePlan(plan, preview);
  assert.equal(restored.chapters[0].content, '完整旧正文\n'.repeat(1000));
  const retained = await fs.list(`${base}/.retained/p`);
  assert.equal(await fs.readText(`${base}/.retained/p/${retained[0]}/branches/main/chapters/001-开篇.md`), '当前现场');
  await repo.startGhostwriteJob('p', (await repo.workspaceStatus('p')).cas, 'job', 'plan', 1, 2000);
  await assert.rejects(repo.installWorkspacePlan(plan, await repo.inspectWorkspaceRestore(plan)), /暂停/);
});

test('native keepBoth preserves branches, undo cursor, paused frozen plans, proposals and unknown bytes', async () => {
  const { fs, repo } = await setup();
  await repo.commitProject('p', (await repo.workspaceStatus('p')).cas, 'edit', 'manual_edit', p =>
    ({ ...p, chapters: p.chapters.map(c => ({ ...c, content: 'edited p stays text' })) }));
  await repo.undo('p', (await repo.workspaceStatus('p')).cas, 'undo-1');
  await repo.createBranch('p', '副线', (await repo.workspaceStatus('p')).cas, 'branch');
  await repo.createProposal('p', (await repo.workspaceStatus('p')).cas, 'proposal',
    [{ operation: 'write', path: `branches/${(await repo.workspaceStatus('p')).activeBranchId}/plan/this-chapter.md`, content: '下一章' }], 2000);
  let job = await repo.startGhostwriteJob('p', (await repo.workspaceStatus('p')).cas, 'job', 'plan', 1, 2001);
  job = await repo.claimGhostwriteJob('p', 'job', 'claim', 2002, 60000);
  await repo.pauseGhostwriteJob('p', 'job', { token: job.claim!.token, epoch: job.claim!.epoch }, 2003);
  await fs.writeBytes(`${base}/p/opaque.bin`, new Uint8Array([255, 0, 128, 99]));
  await fs.writeText(`${base}/p/unknown.json`, '{"projectId":"p"}');
  await fs.writeBytes(`${base}/p/.amber/commits/future-opaque.json`, new Uint8Array([255, 0, 200]));
  const original = await repo.nativeBackupSnapshot('p');
  const input = makeNovelNativeBackupImport(original.metadata, original.files);
  const copied = await repo.copyNativeBackup(input, 'copy');
  assert.equal(copied.projectId, 'copy');
  assert.deepEqual(copied.files.find(f => f.path === 'opaque.bin')!.bytes, new Uint8Array([255, 0, 128, 99]));
  assert.equal(new TextDecoder().decode(copied.files.find(f => f.path === 'unknown.json')!.bytes), '{"projectId":"p"}');
  assert.deepEqual(copied.files.find(f => f.path === '.amber/commits/future-opaque.json')!.bytes, new Uint8Array([255, 0, 200]));
  await repo.installNativeBackup(copied, await repo.inspectNativeRestore(copied));
  assert.equal((await repo.loadProject('copy')).id, 'copy');
  const copiedJob = await repo.loadGhostwriteJob('copy', 'job');
  assert.equal(copiedJob.projectId, 'copy');
  assert.equal(copiedJob.frozenPlan!.expectedCas.treeDigest, copiedJob.expectedCas.treeDigest);
  assert.equal((await repo.workspaceProposals('copy'))[0].proposalId, 'proposal');
  assert.equal((await repo.workspaceStatus('copy')).branches.length, 2);
  const copiedSnapshot = await repo.nativeBackupSnapshot('copy');
  assert.deepEqual(copiedSnapshot.metadata.state, original.metadata.state);
  assert.deepEqual((await repo.nativeBackupSnapshot('p')).files, original.files);
});

test('restore latest uses actual deletion time instead of project updated time', async () => {
  const { fs, repo } = await setup();
  await repo.createProject(makeNovelProject({ id: 'old', name: '最后删除', now: 1 }));
  await repo.deleteProject('p');
  await repo.deleteProject('old');
  await fs.writeText(`${base}/.deleted/p/.amber/deleted-at.json`, '{"deletedAt":1000}');
  await fs.writeText(`${base}/.deleted/old/.amber/deleted-at.json`, '{"deletedAt":2000}');
  assert.equal((await repo.restorePrevious())!.id, 'old');
  assert.equal((await repo.restorePrevious())!.id, 'p');
});
