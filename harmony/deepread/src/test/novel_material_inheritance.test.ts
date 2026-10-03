import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelProject, makeNovelMaterial } from '../main/ets/novel/models.ts';
import { materialOrigin } from '../main/ets/novel/material_inheritance.ts';
import type { NovelNativeBackupImport } from '../main/ets/novel/native_backup.ts';

const setup = async () => {
  const store = createMemoryFileStore();
  const repo = createFileNovelRepository(store);
  const base = { ...makeNovelMaterial({ id: 'world', kind: 'world', title: '世界', content: '共享世界一', now: 1000 }),
    aliases: ['别名'], tags: ['时代'], customKind: '', injectionMode: 'smart' as const };
  await repo.createProject({ ...makeNovelProject({ id: 'inheritance', name: '继承', now: 1000 }), materials: [base] });
  const main = (await repo.workspaceStatus('inheritance')).activeBranchId;
  await repo.createBranch('inheritance', '支线', (await repo.workspaceStatus('inheritance')).cas, 'fork');
  const alternate = (await repo.workspaceStatus('inheritance')).activeBranchId;
  return { store, repo, base, main, alternate };
};
const cas = async (repo: ReturnType<typeof createFileNovelRepository>) => (await repo.workspaceStatus('inheritance')).cas;

test('shared material content and metadata update every inheriting branch and its real checkout', async () => {
  const { repo, base, main } = await setup();
  await repo.saveSharedMaterial('inheritance', { ...base, content: '共享世界二', aliases: ['新别名'], tags: ['新时代'] }, await cas(repo), 'shared-edit');
  assert.equal((await repo.loadProject('inheritance')).materials[0].content, '共享世界二');
  assert.equal((await repo.workspaceStatus('inheritance')).plotStale, false);
  await repo.switchBranch('inheritance', main, await cas(repo));
  const project = await repo.loadProject('inheritance');
  assert.equal(project.materials[0].content, '共享世界二');
  assert.deepEqual(project.materials[0].aliases, ['新别名']);
  assert.deepEqual(project.materials[0].tags, ['新时代']);
  assert.equal(materialOrigin(project, base.id), 'shared');
  assert.equal((await repo.workspaceStatus('inheritance')).plotStale, false);
});

test('branch override is isolated and restore inheritance reads the current shared base', async () => {
  const { repo, base, main, alternate } = await setup();
  await repo.commitProject('inheritance', await cas(repo), 'override', 'material_edit', p => ({
    ...p, materials: [{ ...p.materials[0], content: '支线独有世界' }],
  }));
  assert.equal(materialOrigin(await repo.loadProject('inheritance'), base.id), 'override');
  await repo.switchBranch('inheritance', main, await cas(repo));
  await repo.saveSharedMaterial('inheritance', { ...base, content: '最新共享世界' }, await cas(repo), 'shared-new');
  await repo.switchBranch('inheritance', alternate, await cas(repo));
  const override = await repo.loadProject('inheritance');
  assert.equal(override.materials[0].content, '支线独有世界');
  assert.equal(override.baseMaterials![0].content, '最新共享世界');
  const inherited = await repo.restoreMaterialInheritance('inheritance', base.id, await cas(repo), 'inherit');
  assert.equal(inherited.materials[0].content, '最新共享世界');
  assert.equal(materialOrigin(inherited, base.id), 'shared');
  assert.equal((await repo.workspaceStatus('inheritance')).plotStale, false);
});

test('branch hiding a shared material stays hidden across shared updates and can restore inheritance', async () => {
  const { repo, base, main, alternate } = await setup();
  await repo.commitProject('inheritance', await cas(repo), 'hide', 'material_delete', p => ({ ...p, materials: [] }));
  await repo.switchBranch('inheritance', main, await cas(repo));
  await repo.saveSharedMaterial('inheritance', { ...base, content: '共享世界更新' }, await cas(repo), 'shared-edit');
  await repo.switchBranch('inheritance', alternate, await cas(repo));
  assert.equal((await repo.loadProject('inheritance')).materials.length, 0);
  const restored = await repo.restoreMaterialInheritance('inheritance', base.id, await cas(repo), 'restore-hidden');
  assert.equal(restored.materials[0].content, '共享世界更新');
});

test('shared edit undo and native backup restore preserve branch overrides and metadata', async () => {
  const { repo, base, main } = await setup();
  await repo.commitProject('inheritance', await cas(repo), 'override', 'material_edit', p => ({
    ...p, materials: [{ ...p.materials[0], content: '支线覆盖' }],
  }));
  await repo.saveSharedMaterial('inheritance', { ...base, content: '共享更新' }, await cas(repo), 'shared-edit');
  await repo.undo('inheritance', await cas(repo), 'undo-shared');
  assert.equal((await repo.loadProject('inheritance')).baseMaterials![0].content, '共享世界一');
  assert.equal((await repo.loadProject('inheritance')).materials[0].content, '支线覆盖');
  const saved = await repo.nativeBackupSnapshot('inheritance');
  const input: NovelNativeBackupImport = { projectId: 'inheritance', files: saved.files,
    manifest: { ...saved.metadata, format: 'amber.novel.native-backup', version: 1, checksumAlgorithm: 'fnv1a32', entries: [] } };
  const target = createFileNovelRepository(createMemoryFileStore());
  await target.installNativeBackup(input, await target.inspectNativeRestore(input));
  assert.equal((await target.loadProject('inheritance')).materials[0].content, '支线覆盖');
  await target.restoreMaterialInheritance('inheritance', base.id, await cas(target), 'inherit-restored');
  const inherited = await target.switchBranch('inheritance', main, await cas(target));
  assert.equal(inherited.materials[0].content, '共享世界一');
  assert.deepEqual(inherited.materials[0].aliases, ['别名']);
  assert.equal(inherited.materials[0].injectionMode, 'smart');
});

test('shared mutation rejects stale CAS and a nonterminal durable job on another branch', async () => {
  const { store, repo, base, main, alternate } = await setup();
  const outdated = await cas(repo);
  await repo.commitProject('inheritance', outdated, 'override', 'material_edit', p => ({
    ...p, materials: [{ ...p.materials[0], content: '覆盖' }],
  }));
  await assert.rejects(repo.saveSharedMaterial('inheritance', base, outdated, 'stale'), /工作区已变化/);
  await repo.switchBranch('inheritance', main, await cas(repo));
  await repo.commitProject('inheritance', await cas(repo), 'plan', 'branch_settings_change', p => ({
    ...p, branchSettings: { ...p.branchSettings, thisChapterPlan: '持久任务的冻结目标' },
  }));
  await repo.startGhostwriteJob('inheritance', await cas(repo), 'other-branch-job', 'plan', 1, 1001);
  const path = 'amberagent/novel-workspace/inheritance/.amber/jobs.json';
  const jobs = await store.readText(path);
  // Model an imported paused/non-current durable job without weakening the production switch gate.
  await store.writeText(path, JSON.stringify({ version: 2, jobs: [] }));
  await repo.switchBranch('inheritance', alternate, await cas(repo));
  await store.writeText(path, jobs!);
  const frozen = await cas(repo);
  await assert.rejects(repo.saveSharedMaterial('inheritance', { ...base, content: '不能覆盖冻结事实' }, frozen, 'blocked'), /未结束/);
  assert.deepEqual(await cas(repo), frozen);
  assert.equal((await repo.loadProject('inheritance')).baseMaterials![0].content, '共享世界一');
  await repo.commitProject('inheritance', frozen, 'private-only', 'material_edit', p => ({
    ...p, materials: [{ ...p.materials[0], content: '当前支线编辑不碰另一分支' }],
  }));
  assert.equal((await repo.loadProject('inheritance')).materials[0].content, '当前支线编辑不碰另一分支');
});

test('legacy whole-branch materials keep independent edits hidden shared entries and branch-only additions on explicit upgrade', async () => {
  const store = createMemoryFileStore();
  const repo = createFileNovelRepository(store);
  const world = makeNovelMaterial({ id: 'world', kind: 'world', title: '世界', content: '旧共享世界', now: 1000 });
  const character = makeNovelMaterial({ id: 'person', kind: 'character', title: '人物', content: '旧人物', now: 1000 });
  await store.writeText('amberagent/novel-creation/projects/inheritance.novel.json', JSON.stringify({
    ...makeNovelProject({ id: 'inheritance', name: '旧资料', now: 1000 }), materials: [world, character],
  }));
  assert.equal((await repo.loadProject('inheritance')).baseMaterials, undefined);
  const main = (await repo.workspaceStatus('inheritance')).activeBranchId;
  await repo.createBranch('inheritance', '旧支线', await cas(repo), 'fork');
  const alternate = (await repo.workspaceStatus('inheritance')).activeBranchId;
  await repo.commitProject('inheritance', await cas(repo), 'legacy-own', 'material_edit', p => ({
    ...p, materials: [{ ...p.materials[0], content: '旧支线自己的世界' },
      makeNovelMaterial({ id: 'only-alt', kind: 'other', title: '独有资料', content: '仅旧支线', now: 1001 })],
  }));
  await repo.switchBranch('inheritance', main, await cas(repo));
  assert.equal((await repo.loadProject('inheritance')).baseMaterials, undefined);
  await repo.saveSharedMaterial('inheritance', { ...world, content: '首次共享改动' }, await cas(repo), 'enable-shared');
  await repo.switchBranch('inheritance', alternate, await cas(repo));
  const retained = await repo.loadProject('inheritance');
  assert.deepEqual(retained.materials.map(item => item.content), ['旧支线自己的世界', '仅旧支线']);
  assert.deepEqual(retained.hiddenMaterialIds, ['person']);
  assert.equal(retained.baseMaterials!.find(item => item.id === world.id)!.content, '首次共享改动');
  assert.equal((await repo.restoreMaterialInheritance('inheritance', world.id, await cas(repo), 'restore')).materials[0].content, '首次共享改动');
});

test('first explicit legacy shared edit can undo to the original baseline across branches', async () => {
  const store = createMemoryFileStore();
  const repo = createFileNovelRepository(store);
  const world = makeNovelMaterial({ id: 'world', kind: 'world', title: '世界', content: '原共享世界', now: 1000 });
  await store.writeText('amberagent/novel-creation/projects/inheritance.novel.json', JSON.stringify({
    ...makeNovelProject({ id: 'inheritance', name: '旧世界', now: 1000 }), materials: [world],
  }));
  const main = (await repo.workspaceStatus('inheritance')).activeBranchId;
  await repo.createBranch('inheritance', '旧支线', await cas(repo), 'fork');
  const alternate = (await repo.workspaceStatus('inheritance')).activeBranchId;
  await repo.switchBranch('inheritance', main, await cas(repo));
  await repo.saveSharedMaterial('inheritance', { ...world, content: '共享修改后' }, await cas(repo), 'shared-change');
  await repo.undo('inheritance', await cas(repo), 'undo-shared');
  assert.equal((await repo.loadProject('inheritance')).materials[0].content, '原共享世界');
  await repo.switchBranch('inheritance', alternate, await cas(repo));
  assert.equal((await repo.loadProject('inheritance')).materials[0].content, '原共享世界');
  await repo.nativeBackupSnapshot('inheritance');
});

test('public workspace round trip retains real base override hidden state and subsequent inherited edits', async () => {
  const { repo, base, main, alternate } = await setup();
  await repo.commitProject('inheritance', await cas(repo), 'override', 'material_edit', p => ({
    ...p, materials: [{ ...p.materials[0], content: '公开包里的支线覆写', tags: ['分支标签'] }],
  }));
  await repo.saveSharedMaterial('inheritance', { ...base, content: '公开包里的共享base' }, await cas(repo), 'shared-edit');
  const { buildNovelWorkspaceImportPlan, buildNovelWorkspacePublicFiles } = await import('../main/ets/novel/workspace_interop.ts');
  const { parseNovelWorkspaceManifest } = await import('../main/ets/novel/workspace_contract.ts');
  const files = buildNovelWorkspacePublicFiles(await repo.publicExportPlan('inheritance'));
  const text = (path: string) => new TextDecoder().decode(files.find(file => file.path === path)!.bytes);
  const imported = buildNovelWorkspaceImportPlan(parseNovelWorkspaceManifest(text('manifest.yaml'), text('project.md')), files);
  const target = createFileNovelRepository(createMemoryFileStore());
  await target.installWorkspacePlan(imported);
  assert.equal((await target.loadProject('inheritance')).materials[0].content, '公开包里的支线覆写');
  assert.equal((await target.loadProject('inheritance')).baseMaterials![0].content, '公开包里的共享base');
  await target.switchBranch('inheritance', main, await cas(target));
  assert.equal((await target.loadProject('inheritance')).materials[0].content, '公开包里的共享base');
  await target.saveSharedMaterial('inheritance', { ...base, content: '导入后的最新共享' }, await cas(target), 'new-shared-after-import');
  await target.switchBranch('inheritance', alternate, await cas(target));
  assert.equal((await target.loadProject('inheritance')).materials[0].content, '公开包里的支线覆写');
  const inherited = await target.restoreMaterialInheritance('inheritance', base.id, await cas(target), 'inherit-after-import');
  assert.equal(inherited.materials[0].content, '导入后的最新共享');
  assert.deepEqual(inherited.materials[0].aliases, ['别名']);
  assert.equal(inherited.materials[0].injectionMode, 'smart');
  await target.nativeBackupSnapshot('inheritance');
});


test('explicit branch override survives equal shared text and unrelated commits until explicit restore', async () => {
  const { repo, base, main, alternate } = await setup();
  await repo.commitProject('inheritance', await cas(repo), 'override', 'material_edit', p => ({
    ...p, materials: [{ ...p.materials[0], content: '刻意独立的同稿' }],
  }));
  await repo.switchBranch('inheritance', main, await cas(repo));
  await repo.saveSharedMaterial('inheritance', { ...base, content: '刻意独立的同稿' }, await cas(repo), 'equal-base');
  await repo.switchBranch('inheritance', alternate, await cas(repo));
  await repo.commitProject('inheritance', await cas(repo), 'unrelated-plan', 'branch_settings_change', p => ({
    ...p, branchSettings: { ...p.branchSettings, futurePlan: '另一项编辑' },
  }));
  assert.equal(materialOrigin(await repo.loadProject('inheritance'), base.id), 'override');
  await repo.switchBranch('inheritance', main, await cas(repo));
  await repo.saveSharedMaterial('inheritance', { ...base, content: '共享再次变化' }, await cas(repo), 'new-base');
  await repo.switchBranch('inheritance', alternate, await cas(repo));
  assert.equal((await repo.loadProject('inheritance')).materials[0].content, '刻意独立的同稿');
  assert.equal((await repo.restoreMaterialInheritance('inheritance', base.id, await cas(repo), 'inherit')).materials[0].content, '共享再次变化');
});


test('shared propagation refuses damaged inactive branch text without changing base or claiming that text', async () => {
  const { store, repo, base, main, alternate } = await setup();
  await repo.switchBranch('inheritance', main, await cas(repo));
  const path = `amberagent/novel-workspace/inheritance/branches/${alternate}/setting/world/world.md`;
  await store.writeText(path, '未记入该分支 head 的外部修改');
  const before = await cas(repo);
  await assert.rejects(repo.saveSharedMaterial('inheritance', { ...base, content: '拒绝本次同步' }, before, 'damaged'), /分支/);
  assert.deepEqual(await cas(repo), before);
  assert.equal((await repo.loadProject('inheritance')).baseMaterials![0].content, '共享世界一');
  assert.equal(await store.readText(path), '未记入该分支 head 的外部修改');
});
