import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createProject, upsertMaterial, resolveSuggestion } from '../main/ets/novel/mutations.ts';
import { makeNovelChapter, makeNovelSuggestion } from '../main/ets/novel/models.ts';
import { buildSuggestionAdoption } from '../main/ets/novel/material_adoption.ts';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { buildNovelWorkspacePublicFiles, buildNovelWorkspaceImportPlan } from '../main/ets/novel/workspace_interop.ts';
import { parseNovelWorkspaceManifest } from '../main/ets/novel/workspace_contract.ts';

const metadata = { aliases: [' 舟哥 ', 'Captain', 'captain'], tags: ['同盟', ' 港口 '], customKind: ' 势力 ', injectionMode: 'smart' as const };

test('actual material edit persists normalized fields and public exchange restores their values', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const creation = createNovelCreation({ repository, modelRunning: { async validate() {}, start() { throw new Error('unused'); }, cancel() {} } });
  const project = await creation.create('资料');
  const material = await creation.upsertMaterial(project.id, null, 'other', '港口联盟', '联盟背景', true, metadata);
  const reloaded = (await repository.loadProject(project.id)).materials[0];
  assert.deepEqual(reloaded.aliases, ['舟哥', 'Captain']);
  assert.deepEqual(reloaded.tags, ['同盟', '港口']);
  assert.equal(reloaded.customKind, '势力');
  assert.equal(reloaded.injectionMode, 'smart');
  const files = buildNovelWorkspacePublicFiles(await repository.publicExportPlan(project.id));
  const text = (path: string) => new TextDecoder().decode(files.find(file => file.path === path)!.bytes);
  const plan = buildNovelWorkspaceImportPlan(parseNovelWorkspaceManifest(text('manifest.yaml'), text('project.md')), files);
  const imported = plan.branches.find(branch => branch.id === plan.activeBranchId)!.project.materials.find(item => item.id === material.id)!;
  assert.deepEqual(imported.aliases, reloaded.aliases);
  assert.deepEqual(imported.tags, reloaded.tags);
  assert.equal(imported.customKind, reloaded.customKind);
  assert.equal(imported.injectionMode, 'smart');
  await creation.upsertMaterial(project.id, material.id, material.kind, material.title, material.content, true, { tags: ['新标签'] });
  const partial = (await repository.loadProject(project.id)).materials[0];
  assert.deepEqual(partial.aliases, ['舟哥', 'Captain']);
  assert.deepEqual(partial.tags, ['新标签']);
  assert.equal(partial.customKind, '势力');
  assert.equal(partial.injectionMode, 'smart');
});

test('adoption rejects target-only metadata changes and preserves source fields when creating a material', () => {
  const empty = createProject('提案', 1);
  const chapter = makeNovelChapter({ title: '首章', content: '渡口会面', now: 1 });
  const suggestion = makeNovelSuggestion({ sourceChapterId: chapter.id, kind: 'relationship', title: '盟约', content: '互相信任', now: 1, ...metadata });
  const project = { ...empty, chapters: [chapter], materialSuggestions: [suggestion] };
  const accepted = resolveSuggestion(project, suggestion.id, true, 2).project;
  const material = accepted.materials[0];
  assert.equal(material.kind, 'relationship');
  assert.deepEqual(material.aliases, ['舟哥', 'Captain']);
  assert.equal(material.injectionMode, 'smart');
  const pending = { ...accepted, materialSuggestions: [{ ...suggestion, status: 'pending' as const }] };
  const edit = buildSuggestionAdoption(pending, pending.materialSuggestions[0], material.id);
  const changed = upsertMaterial(pending, material.id, material.kind, material.title, material.content, true, 3,
    { ...metadata, tags: ['已修改'] }).project;
  assert.throws(() => resolveSuggestion(changed, suggestion.id, true, 4, edit), /目标资料已变更/);
  const changedSource = { ...pending, materialSuggestions: [{ ...suggestion, aliases: ['另一名称'] }] };
  assert.throws(() => resolveSuggestion(changedSource, suggestion.id, true, 4, edit), /资料建议或来源已变更/);
});

test('actual model proposal parsers carry relationship metadata through adoption and repository reload', async () => {
  const { parseSuggestions } = await import('../main/ets/novel/suggestion_engine.ts');
  const { parseSettingProposals } = await import('../main/ets/novel/setting_proposal_parser.ts');
  const { parseNovelQuickStart } = await import('../main/ets/novel/quick_start_proposals.ts');
  const repository = createFileNovelRepository(createMemoryFileStore());
  const creation = createNovelCreation({ repository, modelRunning: { async validate() {}, start() { throw new Error('unused'); }, cancel() {} } });
  const project = await creation.create('模型建议');
  const chapter = await creation.saveChapter(project.id, null, '首章', '两人在港口结盟');
  const payload = { kind: 'relationship', title: '港口盟约', content: '两人互信', ...metadata };
  const suggestions = parseSuggestions(JSON.stringify({ suggestions: [payload] }), chapter.id, 1);
  const proposals = parseSettingProposals(JSON.stringify({ type: 'novel_setting_proposal', changes: [payload] }), '', 1);
  const quick = parseNovelQuickStart(JSON.stringify({ overview: '关系概览', proposals: [payload] }), 'quick-source', 1).proposals;
  await repository.updateProject(project.id, current => ({ ...current, materialSuggestions: suggestions, settingProposals: proposals.concat(quick) }));
  await creation.resolveMaterialSuggestion(project.id, suggestions[0].id, true);
  await creation.resolveSettingProposal(project.id, proposals[0].id, true);
  await creation.resolveSettingProposal(project.id, quick[0].id, true);
  const materials = (await repository.loadProject(project.id)).materials;
  assert.equal(materials.length, 3);
  for (const item of materials) {
    assert.equal(item.kind, 'relationship');
    assert.deepEqual(item.aliases, ['舟哥', 'Captain']);
    assert.deepEqual(item.tags, ['同盟', '港口']);
    assert.equal(item.injectionMode, 'smart');
  }
});

test('shared material, branch override and hidden inheritance roundtrip with their exact metadata scopes', async () => {
  const { makeNovelMaterial } = await import('../main/ets/novel/models.ts');
  const repository = createFileNovelRepository(createMemoryFileStore());
  const creation = createNovelCreation({ repository, modelRunning: { async validate() {}, start() { throw new Error('unused'); }, cancel() {} } });
  const project = await creation.create('共享公开交换');
  const base = makeNovelMaterial({ kind: 'character', title: '林舟', content: '共享作者资料', now: 1, ...metadata });
  await creation.saveSharedMaterial(project.id, base);
  const mainId = (await creation.workspaceStatus(project.id)).activeBranchId;
  await creation.createBranch(project.id, '改写线');
  const overrideId = (await creation.workspaceStatus(project.id)).activeBranchId;
  await creation.upsertMaterial(project.id, base.id, 'character', '另一林舟', '分支作者资料', false,
    { aliases: ['暗舟'], tags: ['敌对'], injectionMode: 'off' });
  await creation.switchBranch(project.id, mainId);
  await creation.createBranch(project.id, '隐藏线');
  const hiddenStatus = await creation.workspaceStatus(project.id);
  await creation.rename(project.id, '新项目名');
  await assert.rejects(creation.deleteMaterial(project.id, base.id, hiddenStatus.cas), /工作区已变化/);
  await creation.deleteMaterial(project.id, base.id, (await creation.workspaceStatus(project.id)).cas);
  const files = buildNovelWorkspacePublicFiles(await creation.publicExportPlan(project.id));
  const text = (path: string) => new TextDecoder().decode(files.find(file => file.path === path)!.bytes);
  const plan = buildNovelWorkspaceImportPlan(parseNovelWorkspaceManifest(text('manifest.yaml'), text('project.md')), files);
  const main = plan.branches.find(branch => branch.id === mainId)!.project;
  const override = plan.branches.find(branch => branch.id === overrideId)!.project;
  const hidden = plan.branches.find(branch => branch.id === hiddenStatus.activeBranchId)!.project;
  assert.deepEqual(main.baseMaterials?.[0].aliases, ['舟哥', 'Captain']);
  assert.equal(main.materialOverrides?.length, 0);
  assert.equal(override.baseMaterials?.[0].content, '共享作者资料');
  assert.equal(override.materialOverrides?.[0].content, '分支作者资料');
  assert.deepEqual(override.materials[0].aliases, ['暗舟']);
  assert.equal(override.materials[0].injectionMode, 'off');
  assert.deepEqual(hidden.hiddenMaterialIds, [base.id]);
  assert.equal(hidden.materials.length, 0);
  assert.equal(hidden.baseMaterials?.[0].content, '共享作者资料');
});

for (const explicit of [false, true]) {
  test(`public inbox roundtrip ${explicit ? 'keeps explicit clears' : 'keeps absent metadata'} when adopting into an existing target`, async () => {
    const { makeNovelSettingProposal } = await import('../main/ets/novel/models.ts');
    const { buildSettingProposalAdoption } = await import('../main/ets/novel/material_adoption.ts');
    const noModel = { async validate() {}, start() { throw new Error('unused'); }, cancel() {} };
    const repository = createFileNovelRepository(createMemoryFileStore());
    const creation = createNovelCreation({ repository, modelRunning: noModel });
    const project = await creation.create('提案公开交换');
    const target = await creation.upsertMaterial(project.id, null, 'character', '林舟', '作者资料', false,
      { aliases: ['舟哥'], tags: ['作者标签'], customKind: '作者类别', injectionMode: 'off' });
    const proposal = makeNovelSettingProposal({ sourceMessageId: '', kind: 'character', title: '林舟', content: '建议正文', now: 1,
      ...(explicit ? { aliases: [], tags: [], customKind: '', injectionMode: 'always' as const } : {}) });
    await repository.updateProject(project.id, current => ({ ...current, settingProposals: [proposal] }));
    const files = buildNovelWorkspacePublicFiles(await repository.publicExportPlan(project.id));
    const text = (path: string) => new TextDecoder().decode(files.find(file => file.path === path)!.bytes);
    const plan = buildNovelWorkspaceImportPlan(parseNovelWorkspaceManifest(text('manifest.yaml'), text('project.md')), files);
    const restoredRepo = createFileNovelRepository(createMemoryFileStore());
    await restoredRepo.installWorkspacePlan(plan);
    const restored = await restoredRepo.loadProject(project.id);
    const restoredProposal = restored.settingProposals[0];
    assert.deepEqual(restoredProposal.aliases, explicit ? [] : undefined);
    assert.equal(restoredProposal.injectionMode, explicit ? 'always' : undefined);
    const status = await restoredRepo.workspaceStatus(project.id);
    const edit = buildSettingProposalAdoption(restored, restoredProposal, target.id, status.activeBranchId);
    const restoredCreation = createNovelCreation({ repository: restoredRepo, modelRunning: noModel });
    await restoredCreation.resolveSettingProposal(project.id, restoredProposal.id, true, edit);
    const adopted = (await restoredRepo.loadProject(project.id)).materials[0];
    assert.deepEqual(adopted.aliases, explicit ? [] : ['舟哥']);
    assert.deepEqual(adopted.tags, explicit ? [] : ['作者标签']);
    assert.equal(adopted.customKind, explicit ? '' : '作者类别');
    assert.equal(adopted.injectionMode, explicit ? 'always' : 'off');
    assert.equal(adopted.enabled, explicit);
  });
}

test('proposal and suggestion source digests distinguish absent fields from explicit clearing metadata', async () => {
  const { makeNovelMaterial, makeNovelSettingProposal } = await import('../main/ets/novel/models.ts');
  const { buildSettingProposalAdoption } = await import('../main/ets/novel/material_adoption.ts');
  const { resolveSettingProposal } = await import('../main/ets/novel/mutations.ts');
  const target = makeNovelMaterial({ kind: 'character', title: '林舟', content: '作者资料', now: 1,
    aliases: ['舟哥'], injectionMode: 'off' });
  const chapter = makeNovelChapter({ title: '初遇', content: '林舟登场', now: 1 });
  const proposal = makeNovelSettingProposal({ sourceMessageId: '', kind: 'character', title: '林舟', content: '候选资料', now: 1 });
  const suggestion = makeNovelSuggestion({ sourceChapterId: chapter.id, kind: 'character', title: '林舟', content: '候选资料', now: 1 });
  const project = { ...createProject('来源变更', 1), materials: [target], chapters: [chapter], settingProposals: [proposal], materialSuggestions: [suggestion] };
  const proposalEdit = buildSettingProposalAdoption(project, proposal, target.id);
  const suggestionEdit = buildSuggestionAdoption(project, suggestion, target.id);
  const changed = { ...project,
    settingProposals: [{ ...proposal, aliases: [], injectionMode: 'always' as const }],
    materialSuggestions: [{ ...suggestion, aliases: [], injectionMode: 'always' as const }],
  };
  assert.throws(() => resolveSettingProposal(changed, proposal.id, true, 2, proposalEdit), /资料建议或来源已变更/);
  assert.throws(() => resolveSuggestion(changed, suggestion.id, true, 2, suggestionEdit), /资料建议或来源已变更/);
});
