import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelProject, makeNovelChapter, makeNovelMaterial, makeNovelMessage } from '../main/ets/novel/models.ts';
import { withNovelChapterContract, withNovelUpcomingArc } from '../main/ets/novel/chapter_contract.ts';
import { chapterPlotSourceDigest } from '../main/ets/novel/plot_projection.ts';
import { buildNovelWorkspacePublicFiles, buildNovelWorkspaceImportPlan } from '../main/ets/novel/workspace_interop.ts';
import { parseNovelWorkspaceManifest } from '../main/ets/novel/workspace_contract.ts';
import { copyNovelNativeBackup } from '../main/ets/novel/native_backup_copy.ts';
import { emptyNovelStructuredState } from '../main/ets/novel/structured_state.ts';
import type { NovelNativeBackupImport } from '../main/ets/novel/native_backup.ts';
import type { NovelProject, NovelMaterial } from '../main/ets/novel/models.ts';

const publicInstall = async (source: ReturnType<typeof createFileNovelRepository>, id: string, omitPreferences = false) => {
    let files = buildNovelWorkspacePublicFiles(await source.publicExportPlan(id));
    if (omitPreferences) files = files.filter(file => !file.path.endsWith('/setting/preferences.md'));
    const text = (path: string) => new TextDecoder().decode(files.find(file => file.path === path)!.bytes);
    const plan = buildNovelWorkspaceImportPlan(parseNovelWorkspaceManifest(text('manifest.yaml'), text('project.md')), files);
    const target = createFileNovelRepository(createMemoryFileStore());
    await target.installWorkspacePlan(plan);
    return target;
};
const nativeInput = async (source: ReturnType<typeof createFileNovelRepository>, id: string): Promise<NovelNativeBackupImport> => {
    const saved = await source.nativeBackupSnapshot(id);
    return { projectId: id, files: saved.files, manifest: { ...saved.metadata,
        format: 'amber.novel.native-backup', version: 1, checksumAlgorithm: 'fnv1a32', entries: [] } };
};
const fixture = async () => {
    const source = createFileNovelRepository(createMemoryFileStore());
    const chapter = makeNovelChapter({ id: 'chapter-001', title: '渡桥', content: '小舟救起了沈青。路人喊道：“阿远！”', now: 100 });
    const material = makeNovelMaterial({ id: 'lin', kind: 'character', title: '林舟', content: '共享人物', now: 100,
        aliases: ['小舟'], tags: ['主人公'], customKind: '人物类别', injectionMode: 'smart' });
    let project = makeNovelProject({ id: 'p5-exchange', name: '完整交换', now: 100 });
    project = await source.createProject({ ...project, chapters: [chapter], materials: [material],
        creationMode: 'quickStart', quickStartSeed: { genre: '悬疑', coreIdea: '渡桥', world: '旧城', characters: '林舟', direction: '调查' } });
    const branchId = (await source.workspaceStatus(project.id)).activeBranchId;
    project = withNovelChapterContract(project, { outlinePlacement: '渡桥', goalAndConflict: '救人受到阻拦',
        mustHappen: ['救起同行者'], mustNotHappen: ['透露身份'], endingHook: '路人的呼喊', visibleFacts: ['只知道别名'] }, 'confirmed', branchId, 101);
    project = withNovelUpcomingArc(project, ['入城', '调查旧事'], 102);
    project = { ...project, chapters: [chapter], materials: [material], baseMaterials: [material], materialOverrides: [], hiddenMaterialIds: [],
        creationMode: 'quickStart', quickStartSeed: { genre: '悬疑', coreIdea: '渡桥', world: '旧城', characters: '林舟', direction: '调查' },
        polishPreference: '压缩修饰但保留事实', stateSyncReasoningEnabled: true,
        branchSettings: { ...project.branchSettings, preferences: '采用第三人称', suggestedChapterCount: 2 },
        structuredState: { ...emptyNovelStructuredState(), events: [{ id: 'rescue', chapterId: chapter.id,
            sourceDigest: chapterPlotSourceDigest(chapter.content), quote: '小舟救起了沈青。', summary: '林舟救人', entityRefs: ['lin'] }],
            chapterSources: [{ chapterId: chapter.id, sourceDigest: chapterPlotSourceDigest(chapter.content) }],
            identityClarifications: [{ mention: '阿远', action: 'ignore', materialId: null }] },
        messages: [makeNovelMessage({ id: 'source', role: 'assistant', mode: 'write', content: '完整正文来源', createdAt: 100 })],
        discussionArchives: [{ id: 'archive', sourceMessageIds: ['old-discussion'], throughMessageId: 'old-discussion',
            summary: '作者确认的讨论摘要', decisions: [], createdAt: 100 }] };
    await source.commitProject(project.id, (await source.workspaceStatus(project.id)).cas, 'setup', 'compat_update', () => project);
    await source.createBranch(project.id, '支线', (await source.workspaceStatus(project.id)).cas, 'fork');
    await source.commitProject(project.id, (await source.workspaceStatus(project.id)).cas, 'override', 'material_edit', current => ({ ...current,
        materials: [{ ...current.materials[0], content: '支线人物', aliases: ['舟哥'], tags: ['支线'], injectionMode: 'off', enabled: false }] }));
    return { source, id: project.id };
};
const materialFields = (materials: NovelMaterial[] | undefined) => materials?.map(({ createdAt, updatedAt, ...fields }) => fields);
const authorFields = (project: NovelProject) => ({ branchSettings: project.branchSettings, structuredState: project.structuredState,
    baseMaterials: materialFields(project.baseMaterials), materialOverrides: materialFields(project.materialOverrides), hiddenMaterialIds: project.hiddenMaterialIds,
    polishPreference: project.polishPreference, stateSyncReasoningEnabled: project.stateSyncReasoningEnabled });

test('public import does not treat project polish preference as missing writing preference', async () => {
    const { source, id } = await fixture();
    const target = await publicInstall(source, id, true);
    const imported = await target.loadProject(id);
    assert.equal(imported.polishPreference, '压缩修饰但保留事实');
    assert.equal(imported.branchSettings.preferences, '');
});

test('actual public/native file installation preserves contracts arc state evidence clarifications preferences and branch material overrides', async () => {
    const { source, id } = await fixture();
    const original = await source.publicExportPlan(id);
    const publicTarget = await publicInstall(source, id);
    const nativeTarget = createFileNovelRepository(createMemoryFileStore());
    const backup = await nativeInput(source, id);
    await nativeTarget.installNativeBackup(backup, await nativeTarget.inspectNativeRestore(backup));
    for (const branch of original.branches) {
        for (const target of [publicTarget, nativeTarget]) {
            const imported = await target.switchBranch(id, branch.id, (await target.workspaceStatus(id)).cas);
            assert.deepEqual(authorFields(imported), authorFields(branch.project));
        }
    }
    const native = await nativeTarget.loadProject(id);
    assert.equal(native.creationMode, 'quickStart');
    assert.equal(native.quickStartSeed!.coreIdea, '渡桥');
    assert.equal(native.discussionArchives[0].summary, '作者确认的讨论摘要');
    const publicProject = await publicTarget.loadProject(id);
    assert.equal(publicProject.messages.length, 0, 'public workspace contains author workspace, not private session/runtime history');
    assert.equal(publicProject.discussionArchives.length, 0);
    assert.equal(publicProject.quickStartSeed, null, 'iOS public import creates a blank project without original private seed');
});

test('same identity native restore preserves ordinary response recovery but fresh identity copy clears it and retains messages', async () => {
    const { source, id } = await fixture();
    const status = await source.workspaceStatus(id);
    await source.commitProject(id, status.cas, 'failed-run', 'transcript_checkpoint', project => ({ ...project,
        ordinaryRun: { version: 1, id: 'run', branchId: status.activeBranchId, mode: 'write', granularity: 'whole_chapter',
            runKind: 'prose_whole_chapter', userText: '原请求', originalRequest: { systemPrompt: '写作', maxOutputTokens: 1024,
                modelTarget: { kind: 'global' }, toolProfile: 'none', history: [], operation: { kind: 'turn', userPrompt: '原请求' } },
            transcriptPrefix: [], checkpointMessages: project.messages.map(message => message.uiMessage),
            cursor: { responseId: 'response-original', sequence: 2, providerId: 'provider' }, status: 'failed', error: '断线', startedAt: 1, updatedAt: 2 } }));
    const backup = await nativeInput(source, id);
    const same = createFileNovelRepository(createMemoryFileStore());
    await same.installNativeBackup(backup, await same.inspectNativeRestore(backup));
    assert.equal((await same.loadProject(id)).ordinaryRun!.cursor!.responseId, 'response-original');
    const copied = copyNovelNativeBackup(backup, 'copied-project');
    const fresh = createFileNovelRepository(createMemoryFileStore());
    await fresh.installNativeBackup(copied, await fresh.inspectNativeRestore(copied));
    assert.equal((await fresh.loadProject(copied.projectId)).ordinaryRun, undefined);
    assert.deepEqual((await fresh.loadProject(copied.projectId)).messages, (await source.loadProject(id)).messages);
    for (const branch of (await fresh.loadProject(copied.projectId)).branches) {
        const imported = await fresh.switchBranch(copied.projectId, branch.id, (await fresh.workspaceStatus(copied.projectId)).cas);
        assert.equal(imported.ordinaryRun, undefined);
    }
    const publicTarget = await publicInstall(source, id);
    assert.equal((await publicTarget.loadProject(id)).ordinaryRun, undefined);
});
