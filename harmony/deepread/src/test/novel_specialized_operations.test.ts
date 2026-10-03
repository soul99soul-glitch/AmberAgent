import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelProject, makeNovelChapter, makeNovelMaterial, makeNovelMessage, makeNovelSettingProposal } from '../main/ets/novel/models.ts';
import { novelChapterParagraphs } from '../main/ets/novel/specialized_operations.ts';
import type { NovelProjectOperationKind, NovelProjectToolInput } from '../main/ets/novel/specialized_operations.ts';
const body = '  第一段\r\n同段第二行\r\n \r\n第二段😀\r\n\r\n  第三段  \r\n';
const setup = async () => {
  const store = createMemoryFileStore();
  const repo = createFileNovelRepository(store);
  const creation = createNovelCreation({ repository: repo, nowMs: () => 2000, modelRunning: {
    async validate() {}, cancel() {}, start() { throw new Error('model must not run for tool mutations'); },
  } });
  const project = await repo.createProject({ ...makeNovelProject({ id: 'tools', name: '原名', now: 1000 }),
    chapters: [makeNovelChapter({ id: 'c1', title: '第一章', content: body, now: 1000 })],
    materials: [{ ...makeNovelMaterial({ id: 'm1', kind: 'world', title: '世界', content: '原世界', now: 1000 }), aliases: ['旧名'], tags: ['时代'], customKind: '', injectionMode: 'off', enabled: false }],
    settingProposals: [makeNovelSettingProposal({ id: 's1', sourceMessageId: 'msg', kind: 'world', title: '设定候选', content: '未采用事实', now: 1000 })],
  });
  const cas = async () => (await repo.workspaceStatus(project.id)).cas;
  const approve = async (kind: NovelProjectOperationKind, args: NovelProjectToolInput) => {
    const proposal = await creation.proposeProjectOperation(project.id, kind, args);
    await creation.resolveWorkspaceProposal(project.id, proposal.proposalId, true, `approve-${proposal.proposalId}`);
    return repo.loadProject(project.id);
  };
  return { repo, store, creation, project, cas, approve };
};
test('tool → durable review → reload → author approval mutates once, read operations stay read only', async () => {
  const { repo, store, creation } = await setup();
  const before = (await repo.workspaceStatus('tools')).cas;
  const result = await creation.executeProjectTool('tools', 'novel_rename_project', { title: '新作品' });
  assert.equal(result.requires_author_approval, true);
  assert.equal((await repo.loadProject('tools')).name, '原名');
  assert.deepEqual((await repo.workspaceStatus('tools')).cas, before);
  const reloaded = createFileNovelRepository(store);
  const proposal = (await reloaded.workspaceProposals('tools'))[0];
  assert.equal(proposal.review?.previews[0].oldText, '原名');
  assert.equal(proposal.review?.previews[0].newText, '新作品');
  await reloaded.resolveProposal('tools', proposal.proposalId, true, 'author', 3000);
  const after = (await reloaded.workspaceStatus('tools')).cas;
  await reloaded.resolveProposal('tools', proposal.proposalId, true, 'author', 3001);
  assert.equal((await reloaded.loadProject('tools')).name, '新作品');
  assert.deepEqual((await reloaded.workspaceStatus('tools')).cas, after);
  assert.equal((await creation.executeProjectTool('tools', 'novel_list_chapters', {})).chapters![0].id, 'c1');
  assert.equal((await creation.executeProjectTool('tools', 'novel_read_chapter', { chapter_id: 'c1', start_paragraph: 2, end_paragraph: 2 })).paragraphs![0].text, '第二段😀');
  assert.equal((await creation.executeProjectTool('tools', 'novel_list_setting_proposals', {})).setting_proposals![0].id, 's1');
});
test('range revision freezes exact source and UTF16 spans, preserves other CRLF and archives old version', async () => {
  const { repo, creation, approve } = await setup();
  const read = await creation.executeProjectTool('tools', 'novel_read_chapter', { chapter_id: 'c1' });
  assert.equal(novelChapterParagraphs(body).length, 3);
  const proposal = await creation.proposeProjectOperation('tools', 'revise_chapter', {
    chapter_id: 'c1', start_paragraph: 2, end_paragraph: 2, new_text: '新的第二段', expected_text: '第二段😀', source_digest: read.source_digest,
  });
  assert.equal(proposal.review!.previews[0].oldText, '第二段😀');
  assert.equal(body.slice(proposal.review!.previews[0].start, proposal.review!.previews[0].end), '第二段😀');
  await creation.resolveWorkspaceProposal('tools', proposal.proposalId, true, 'range-approve');
  const saved = await repo.loadProject('tools');
  assert.equal(saved.chapters[0].content, body.replace('第二段😀', '新的第二段'));
  assert.equal(saved.chapterVersions[0].content, body);
  await assert.rejects(creation.proposeProjectOperation('tools', 'revise_chapter', { chapter_id: 'c1', start_paragraph: 2, end_paragraph: 2, new_text: '不能写', source_digest: read.source_digest }), /来源已变化/);
  await approve('set_chapter_title', { chapter_id: 'c1', title: '新标题' });
  assert.equal((await repo.loadProject('tools')).chapters[0].content, saved.chapters[0].content);
});
test('only transcript checkpoints may advance CAS before approval; source changes remain rejected', async () => {
  const { repo, creation, cas } = await setup();
  const proposal = await creation.proposeProjectOperation('tools', 'set_polish_preference', { preference: '保留节奏' });
  await repo.commitProject('tools', await cas(), 'transcript-1', 'transcript_checkpoint', p => ({ ...p, messages: [makeNovelMessage({ id: 'msg', role: 'assistant', mode: 'discuss', content: '待确认', createdAt: 2001 })] }));
  await repo.commitProject('tools', await cas(), 'transcript-2', 'transcript_checkpoint', p => ({ ...p, messages: p.messages.concat([makeNovelMessage({ id: 'msg2', role: 'user', mode: 'discuss', content: '确认', createdAt: 2002 })]) }));
  await creation.resolveWorkspaceProposal('tools', proposal.proposalId, true, 'after-discussion');
  assert.equal((await repo.loadProject('tools')).polishPreference, '保留节奏');
  const stale = await creation.proposeProjectOperation('tools', 'rename_project', { title: '不应写入' });
  await repo.commitProject('tools', await cas(), 'metadata', 'project_setup_change', p => ({ ...p, polishPreference: '新偏好' }));
  await assert.rejects(creation.resolveWorkspaceProposal('tools', stale.proposalId, true, 'stale'), /工作区已变化/);
  await creation.resolveWorkspaceProposal('tools', stale.proposalId, false, 'reject-stale');
  const changedSource = await creation.proposeProjectOperation('tools', 'rename_project', { title: '不能绕过源校验' });
  await repo.commitProject('tools', await cas(), 'mislabelled-transcript', 'transcript_checkpoint', p => ({ ...p, polishPreference: '故意变更来源' }));
  await assert.rejects(creation.resolveWorkspaceProposal('tools', changedSource.proposalId, true, 'source-reject'), /来源|变化/);
});
test('unrelated body edit and same-digest ordinary metadata commit both reject pending approval', async () => {
  const { repo, creation, cas } = await setup();
  const first = await creation.proposeProjectOperation('tools', 'rename_project', { title: '不能改' });
  await repo.commitProject('tools', await cas(), 'no-op-metadata', 'branch_settings_change', p => p);
  await assert.rejects(creation.resolveWorkspaceProposal('tools', first.proposalId, true, 'no-op-reject'), /工作区已变化/);
  const second = await creation.proposeProjectOperation('tools', 'rename_project', { title: '不能改' });
  await repo.commitProject('tools', await cas(), 'body', 'manual_edit', p => ({ ...p, chapters: [{ ...p.chapters[0], content: '后来正文' }] }));
  await assert.rejects(creation.resolveWorkspaceProposal('tools', second.proposalId, true, 'body-reject'), /工作区已变化/);
});
test('proposal list and count follow active branch, foreign branch author resolution is refused', async () => {
  const { repo, creation, cas } = await setup();
  const p = await creation.proposeProjectOperation('tools', 'rename_project', { title: '主线提案' });
  const main = (await repo.workspaceStatus('tools')).activeBranchId;
  await repo.createBranch('tools', '分支', await cas(), 'branch');
  assert.equal((await repo.workspaceStatus('tools')).pendingProposalCount, 1);
  assert.equal((await repo.workspaceProposals('tools')).length, 0);
  await assert.rejects(creation.resolveWorkspaceProposal('tools', p.proposalId, false, 'foreign'), /其他分支/);
  await repo.switchBranch('tools', main, await cas());
  assert.equal((await repo.workspaceStatus('tools')).pendingProposalCount, 2);
});
test('plan and arc dedicated writes show actual draft and author confirms strict contract', async () => {
  const { creation, approve, repo } = await setup();
  const plan = { outline_placement: '开篇', goal_and_conflict: '查清失踪真相', must_happen: ['看到线索'], must_not_happen: ['解决全案'], ending_hook: '门后响声', visible_facts: ['雨夜'] };
  const draft = await creation.proposeProjectOperation('tools', 'propose_chapter_plan', plan);
  assert.match(draft.review!.previews[0].newText, /查清失踪真相/);
  await creation.resolveWorkspaceProposal('tools', draft.proposalId, true, 'draft');
  assert.equal((await repo.loadProject('tools')).branchSettings.chapterContract!.status, 'draft');
  await approve('upsert_upcoming_arc', { beats: ['追索', '交锋'] });
  assert.deepEqual((await repo.loadProject('tools')).branchSettings.upcomingArc!.beats, ['追索', '交锋']);
  await approve('clear_upcoming_arc', {});
  assert.equal((await repo.loadProject('tools')).branchSettings.upcomingArc, undefined);
  await approve('prepare_ghostwrite', { ...plan, suggested_chapter_count: 2, upcoming_arc: ['交锋'] });
  assert.equal((await repo.loadProject('tools')).branchSettings.chapterContract!.status, 'confirmed');
  assert.equal((await repo.loadProject('tools')).branchSettings.suggestedChapterCount, 2);
  await assert.rejects(creation.proposeProjectOperation('tools', 'propose_chapter_plan', plan), /不能.*降级/);
  await approve('reject_setting_proposals', { proposal_ids: ['s1'] });
  assert.equal((await repo.loadProject('tools')).settingProposals[0].status, 'rejected');
});
test('material partial update preserves P3 metadata; branch override and approved generic path are canonical and replayable', async () => {
  const { repo, creation, approve } = await setup();
  await approve('revise_material', { material_id: 'm1', kind: 'world', title: '世界', content: '共享更新' });
  let material = (await repo.loadProject('tools')).materials[0];
  assert.deepEqual(material.tags, ['时代']); assert.equal(material.injectionMode, 'off');
  const branch = (await repo.workspaceStatus('tools')).activeBranchId;
  const proposal = await creation.proposeProjectOperation('tools', 'revise_material', { material_id: 'm1', kind: 'world', title: '分支世界', content: '分支更新', scope: 'branch' });
  assert.equal(proposal.review!.previews[0].newText, '分支更新');
  await creation.resolveWorkspaceProposal('tools', proposal.proposalId, true, 'override');
  const path = `branches/${branch}/setting/world/m1.md`;
  await creation.workspaceWriteResult('tools', 'generic-material', [{ path, operation: 'write', content: '审批后的素材正文' }]);
  await creation.workspaceWriteResult('tools', 'generic-material', [{ path, operation: 'write', content: '审批后的素材正文' }]);
  material = (await repo.loadProject('tools')).materials[0];
  assert.equal(material.content, '审批后的素材正文'); assert.equal(material.injectionMode, 'off');
  assert.equal((await repo.loadProject('tools')).baseMaterials![0].content, '共享更新');
  assert.ok((await repo.nativeBackupSnapshot('tools')).files.length > 0);
  await assert.rejects(creation.proposeProjectOperation('tools', 'revise_material', { material_id: 'm1', kind: 'character', title: '误改类型', content: '误改' }), /类型/);
  await approve('revise_material', { kind: 'masterOutline', title: '大纲', content: '大纲正文', tags: ['主线'], injection_mode: 'smart' });
  assert.equal((await repo.loadProject('tools')).materials.find(item => item.title === '大纲')!.kind, 'outline');
});
test('recent chapters use one exact historical restore, and explicit multi-delete uses one approval transaction', async () => {
  const { repo, creation, cas, approve } = await setup();
  await repo.commitProject('tools', await cas(), 'append-2', 'manual_edit', p => ({ ...p, chapters: p.chapters.concat([makeNovelChapter({ id: 'c2', title: '第二章', content: '二章旧文', now: 1002 })]) }));
  await repo.commitProject('tools', await cas(), 'edit-2', 'manual_edit', p => ({ ...p, chapters: p.chapters.map(c => c.id === 'c2' ? { ...c, content: '二章新文' } : c) }));
  const checkpoint = (await repo.workspaceStatus('tools')).cas.head;
  await repo.commitProject('tools', await cas(), 'append-3', 'manual_edit', p => ({ ...p, chapters: p.chapters.concat([makeNovelChapter({ id: 'c3', title: '第三章', content: '三章', now: 1003 })]) }));
  await repo.commitProject('tools', await cas(), 'edit-3', 'manual_edit', p => ({ ...p, chapters: p.chapters.map(c => c.id === 'c3' ? { ...c, content: '三章重写' } : c) }));
  const proposal = await creation.proposeProjectOperation('tools', 'revert_recent_chapters', { chapter_count: 1 });
  assert.equal(proposal.operation!.restoreHead, checkpoint);
  const before = (await repo.workspaceHistory('tools')).length;
  await creation.resolveWorkspaceProposal('tools', proposal.proposalId, true, 'rewind');
  assert.equal((await repo.workspaceHistory('tools')).length, before + 1);
  assert.deepEqual((await repo.loadProject('tools')).chapters.map(c => c.id), ['c1', 'c2']);
  assert.equal((await repo.loadProject('tools')).chapters[1].content, '二章新文');
  await assert.rejects(creation.proposeProjectOperation('tools', 'revert_recent_chapters', { chapter_count: 2 }), /真实历史检查点/);
  const deleted = await approve('delete_chapters', { chapter_ids: ['c1', 'c2'], chapter_ordinals: [1] });
  assert.equal(deleted.chapters.length, 0);
});
test('native backup preserves pending operation, exact ranges and source guards through restore', async () => {
  const { repo, creation } = await setup();
  const proposal = await creation.proposeProjectOperation('tools', 'revise_chapter', { chapter_id: 'c1', start_paragraph: 1, end_paragraph: 2, new_text: '备份后确认的两段' });
  const saved = await repo.nativeBackupSnapshot('tools');
  const target = createFileNovelRepository(createMemoryFileStore());
  const input = { projectId: 'tools', files: saved.files, manifest: { ...saved.metadata,
    format: 'amber.novel.native-backup' as const, version: 1 as const, checksumAlgorithm: 'fnv1a32' as const, entries: [] } };
  await target.installNativeBackup(input, await target.inspectNativeRestore(input));
  assert.deepEqual((await target.workspaceProposals('tools'))[0].review, proposal.review);
  assert.deepEqual((await target.workspaceProposals('tools'))[0].operation, proposal.operation);
  await target.resolveProposal('tools', proposal.proposalId, true, 'restored-author', 3000);
  assert.equal((await target.loadProject('tools')).chapters[0].content, body.slice(0, proposal.review!.previews[0].start) + '备份后确认的两段' + body.slice(proposal.review!.previews[0].end));
});
test('author approval honors durable ghostwrite gate, rejection remains available and malformed domain arguments do not stage', async () => {
  const { repo, creation, cas } = await setup();
  await repo.commitProject('tools', await cas(), 'plan', 'branch_settings_change', p => ({ ...p, branchSettings: { ...p.branchSettings, thisChapterPlan: '待代笔' } }));
  const proposal = await creation.proposeProjectOperation('tools', 'set_chapter_title', { title: '不能覆盖冻结任务' });
  await repo.startGhostwriteJob('tools', await cas(), 'durable-job', 'frozen-plan', 1, 2000);
  const before = await cas();
  await assert.rejects(creation.resolveWorkspaceProposal('tools', proposal.proposalId, true, 'blocked-approve'), /未结束/);
  assert.deepEqual(await cas(), before);
  await creation.resolveWorkspaceProposal('tools', proposal.proposalId, false, 'still-reject');
  assert.equal((await repo.workspaceProposals('tools'))[0].status, 'rejected');
  await assert.rejects(creation.proposeProjectOperation('tools', 'prepare_ghostwrite', { goal_and_conflict: '目标', must_happen: ['事件'], suggested_chapter_count: 11 }), /章数/);
  await assert.rejects(creation.proposeProjectOperation('tools', 'revise_chapter', { chapter_id: 'c1', start_paragraph: 1.5, end_paragraph: 2, new_text: '错误段号' }), /起始段/);
  await assert.rejects(creation.proposeProjectOperation('tools', 'delete_chapters', { chapter_ids: ['不存在'] }), /章节不存在/);
});
test('non-main project metadata exports current preference and relative generic material remains branch-local', async () => {
  const { repo, creation, cas, approve } = await setup();
  const main = (await cas()).branchId;
  await repo.createBranch('tools', '另一路', await cas(), 'metadata-branch');
  await approve('set_polish_preference', { preference: '分支页确认的项目偏好' });
  await approve('rename_project', { title: '分支页确认的项目名' });
  const { buildNovelWorkspacePublicFiles, buildNovelWorkspaceImportPlan } = await import('../main/ets/novel/workspace_interop.ts');
  const { parseNovelWorkspaceManifest } = await import('../main/ets/novel/workspace_contract.ts');
  const files = buildNovelWorkspacePublicFiles(await repo.publicExportPlan('tools'));
  const read = (path: string) => new TextDecoder().decode(files.find(file => file.path === path)!.bytes);
  const imported = buildNovelWorkspaceImportPlan(parseNovelWorkspaceManifest(read('manifest.yaml'), read('project.md')), files);
  assert.equal(imported.branches[0].project.polishPreference, '分支页确认的项目偏好');
  assert.equal(imported.branches[0].project.name, '分支页确认的项目名');
  await creation.workspaceWriteResult('tools', 'relative-branch-material', [{ path: 'setting/world/m1.md', operation: 'write', content: '当前分支独有' }]);
  assert.equal((await repo.loadProject('tools')).baseMaterials![0].content, '原世界');
  await repo.switchBranch('tools', main, await cas());
  assert.equal((await repo.loadProject('tools')).materials[0].content, '原世界');
});
for (const verdict of ['denied', 'approved'] as const) test(`actual material tool ${verdict} closes and can reopen or replay author continuation`, async () => {
  const { repo, cas } = await setup();
  const { makeUIMessage, makeAssistantMessage } = await import('../main/ets/agent/message.ts');
  const path = 'setting/world/m1.md';
  const input = JSON.stringify({ proposal_id: 'material-continuation', patches: [{ operation: 'write', path, content: '作者采用的正文' }] });
  const pending = makeUIMessage('assistant', [{ type: 'tool', toolCallId: 'material-call', toolName: 'novel_workspace_write', input,
    output: [], approvalState: { type: 'pending' }, metadata: null }]);
  await repo.commitProject('tools', await cas(), 'pending-transcript', 'transcript_checkpoint', p => ({ ...p,
    messages: [makeNovelMessage({ id: 'source', role: 'assistant', mode: 'discuss', uiMessage: pending, createdAt: 2000 })] }));
  let creation: ReturnType<typeof createNovelCreation>;
  creation = createNovelCreation({ repository: repo, nowMs: () => 3000, modelRunning: { async validate() {}, cancel() {}, start(request) {
    const listeners = new Set<(event: import('../main/ets/novel/model_running.ts').NovelModelEvent) => void>();
    setTimeout(() => { void (async () => {
      try {
        const messages: import('../main/ets/agent/message.ts').UIMessage[] = request.history.map(message => ({ ...message, parts: message.parts.map(part => part.type === 'tool' ? {
          ...part, approvalState: verdict === 'approved' ? { type: 'approved' as const } : { type: 'denied' as const, reason: '作者拒绝' },
          output: [{ type: 'text' as const, text: JSON.stringify({ status: verdict }), metadata: null }],
        } : part) }));
        await request.checkpoint(messages);
        if (verdict === 'approved') await creation.workspaceWriteResult('tools', 'material-continuation', [{ operation: 'write', path, content: '作者采用的正文' }]);
        await request.checkpoint(messages.concat([makeAssistantMessage('作者决定已处理')]));
        listeners.forEach(callback => callback({ kind: 'completed' }));
      } catch (error) { listeners.forEach(callback => callback({ kind: 'failed', message: String(error) })); }
    })(); }, 0);
    return { subscribe(callback) { listeners.add(callback); return () => { listeners.delete(callback); }; } };
  } } });
  const terminal = (run: import('../main/ets/novel/creation.ts').NovelRun) => new Promise<import('../main/ets/novel/creation.ts').NovelRunEvent>(resolve =>
    run.subscribe(event => { if (['completed', 'failed', 'waiting_user', 'interrupted'].includes(event.kind)) resolve(event); }));
  const decision = verdict === 'approved' ? { kind: 'approved' as const } : { kind: 'denied' as const, reason: '作者拒绝' };
  const event = await terminal(creation.continueTool('tools', 'material-call', decision));
  assert.equal(event.kind, 'completed', JSON.stringify(event));
  await creation.open('tools');
  if (verdict === 'approved') assert.equal((await terminal(creation.continueTool('tools', 'material-call', decision))).kind, 'completed');
  assert.equal((await repo.workspaceProposals('tools'))[0].status, verdict === 'approved' ? 'accepted' : 'rejected');
  assert.equal((await repo.loadProject('tools')).materials[0].content, verdict === 'approved' ? '作者采用的正文' : '原世界');
  assert.equal((await repo.loadProject('tools')).baseMaterials![0].content, '原世界');
});
test('shared material approval previews exact base source while preserving an active branch override', async () => {
  const { creation, approve, repo } = await setup();
  await approve('revise_material', { material_id: 'm1', kind: 'world', title: '分支世界', content: '当前分支覆盖', scope: 'branch' });
  const proposal = await creation.proposeProjectOperation('tools', 'revise_material', { material_id: 'm1', kind: 'world', title: '共享世界', content: '新的共享来源' });
  assert.equal(proposal.review!.previews[0].oldText, '原世界');
  assert.equal(proposal.review!.previews[0].newText, '新的共享来源');
  assert.match(proposal.review!.previews[0].label, /项目共享/);
  await creation.resolveWorkspaceProposal('tools', proposal.proposalId, true, 'shared-with-override');
  const project = await repo.loadProject('tools');
  assert.equal(project.baseMaterials![0].content, '新的共享来源');
  assert.equal(project.materials[0].content, '当前分支覆盖');
});
