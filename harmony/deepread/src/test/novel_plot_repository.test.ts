import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelChapter, makeNovelProject } from '../main/ets/novel/models.ts';
import type { NovelProject } from '../main/ets/novel/models.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { rebuildChapterPlots } from '../main/ets/novel/plot_projection.ts';

const fixture = (id: string): NovelProject => ({
  ...makeNovelProject({ id, name: id, now: 1 }),
  chapters: [1, 2, 3].map(n => makeNovelChapter({ id: `c${n}`, title: `第${n}章`, content: `正文${n}`, now: 1 })),
});

test('deleting or discarding an earlier chapter marks the first survivor stale and preserves earlier stale on later edit', async () => {
  for (const kind of ['chapter_delete', 'chapter_discard'] as const) {
    const store = createMemoryFileStore();
    const repo = createFileNovelRepository(store);
    await repo.createProject(fixture(kind));
    const before = await repo.workspaceStatus(kind);
    const result = await repo.commitProject(kind, before.cas, 'remove', kind, p => ({
      ...p, chapters: kind === 'chapter_delete' ? p.chapters.slice(1)
        : p.chapters.map(c => c.id === 'c1' ? { ...c, discarded: true } : c),
    }));
    assert.deepEqual(result.chapterPlots.map(p => [p.chapterId, p.stale]), [['c2', true], ['c3', true]]);
    const removed = await repo.workspaceStatus(kind);
    assert.equal(removed.unresolvedFromOrdinal, 1);
    assert.equal(removed.plotStale, true);
    await repo.commitProject(kind, removed.cas, 'edit-later', 'manual_edit', p => ({
      ...p, chapters: p.chapters.map(c => c.id === 'c3' ? { ...c, content: '后章修订' } : c),
    }));
    const cold = createFileNovelRepository(store);
    assert.deepEqual((await cold.loadProject(kind)).chapterPlots.map(p => [p.chapterId, p.stale]),
      [['c2', true], ['c3', false]]);
    assert.equal((await cold.workspaceStatus(kind)).unresolvedFromOrdinal, 1);
  }
});

test('title and diagnostic changes refresh only the chapter text; append is ready; undo restores exact pointers', async () => {
  const repo = createFileNovelRepository(createMemoryFileStore());
  const original = await repo.createProject(fixture('title'));
  const before = await repo.workspaceStatus('title');
  const renamed = await repo.commitProject('title', before.cas, 'title-change', 'manual_edit', p => ({
    ...p, chapters: p.chapters.map(c => c.id === 'c1' ? { ...c, title: '新章名', suggestionWarning: '分析失败' } : c),
  }));
  assert.equal(renamed.chapterPlots[0].text.startsWith('新章名'), true);
  assert.equal((await repo.workspaceStatus('title')).plotStale, false);
  assert.equal((await repo.loadProject('title')).chapters[0].suggestionWarning, '分析失败');
  const afterTitle = await repo.workspaceStatus('title');
  const restored = await repo.undo('title', afterTitle.cas, 'undo-title');
  assert.deepEqual(restored.chapterPlots, original.chapterPlots);
  const afterUndo = await repo.workspaceStatus('title');
  const appended = await repo.commitProject('title', afterUndo.cas, 'append', 'collect', p => ({
    ...p, chapters: p.chapters.concat(makeNovelChapter({ id: 'c4', title: '第四章', content: '新正文', now: 2 })),
  }));
  assert.equal(appended.chapterPlots.length, 4);
  assert.equal((await repo.workspaceStatus('title')).plotStale, false);
});

test('chapter proposal projects facts atomically; author plot edits and sync preserve separate content', async () => {
  const store = createMemoryFileStore();
  const repo = createFileNovelRepository(store);
  await repo.createProject(fixture('proposal-plot'));
  let status = await repo.workspaceStatus('proposal-plot');
  await repo.createProposal('proposal-plot', status.cas, 'edit-first', [{
    operation: 'write', path: 'branches/main/chapters/001-第1章.md', content: '第一章新事实',
  }], 2);
  await repo.resolveProposal('proposal-plot', 'edit-first', true, 'accept-first', 3);
  status = await repo.workspaceStatus('proposal-plot');
  assert.equal(status.unresolvedFromOrdinal, 2);
  assert.deepEqual((await repo.loadProject('proposal-plot')).chapterPlots.map(p => p.stale), [false, true, true]);
  await repo.createProposal('proposal-plot', status.cas, 'author-plot', [{
    operation: 'write', path: 'branches/main/plan/plot.md', content: '作者自定长线剧情，保留原文。',
  }], 4);
  await repo.resolveProposal('proposal-plot', 'author-plot', true, 'accept-author-plot', 5);
  status = await repo.workspaceStatus('proposal-plot');
  assert.equal(status.plotStale, true);
  const synced = await repo.syncPlot('proposal-plot', status.cas, 'sync');
  assert.equal(synced.authorPlot, '作者自定长线剧情，保留原文。');
  assert.equal((await repo.syncPlot('proposal-plot', status.cas, 'sync')).authorPlot, synced.authorPlot);
  assert.equal(Object.keys(synced).includes('authorPlot'), false);
  assert.deepEqual(synced.chapterPlots, rebuildChapterPlots(synced.chapters));
  assert.equal(await store.readText('amberagent/novel-workspace/proposal-plot/branches/main/plan/plot.md'),
    '作者自定长线剧情，保留原文。');
  assert.equal((await createFileNovelRepository(store).workspaceStatus('proposal-plot')).plotStale, false);
  const forked = await repo.createBranch('proposal-plot', '分支',
    (await repo.workspaceStatus('proposal-plot')).cas, 'fork');
  assert.equal(forked.authorPlot, synced.authorPlot);
  const switched = await repo.switchBranch('proposal-plot', 'main',
    (await repo.workspaceStatus('proposal-plot')).cas);
  assert.equal(switched.authorPlot, synced.authorPlot);
});

interface OptionalMeta { chapterPlots?: unknown[]; discussionArchives?: unknown[]; schemaVersion?: number }
interface OptionalState { project: OptionalMeta }
interface OptionalBranch { state: OptionalState }
interface OptionalCommit { project: OptionalMeta }

test('old missing arrays load without write migration or clearing stale and remain stale across metadata commits', async () => {
  const store = createMemoryFileStore();
  const repo = createFileNovelRepository(store);
  await repo.createProject(fixture('old'));
  const before = await repo.workspaceStatus('old');
  await repo.commitProject('old', before.cas, 'edit', 'manual_edit', p => ({
    ...p, chapters: p.chapters.map(c => c.id === 'c1' ? { ...c, content: '修订' } : c),
  }));
  const stale = await repo.workspaceStatus('old');
  const prefix = 'amberagent/novel-workspace/old/';
  const statePath = `${prefix}.amber/project-state.json`;
  const branchPath = `${prefix}.amber/branches/main.json`;
  const commitPath = `${prefix}.amber/commits/${stale.cas.head}.json`;
  const state = JSON.parse((await store.readText(statePath)) ?? '') as OptionalState;
  const branch = JSON.parse((await store.readText(branchPath)) ?? '') as OptionalBranch;
  const commit = JSON.parse((await store.readText(commitPath)) ?? '') as OptionalCommit;
  for (const meta of [state.project, branch.state.project, commit.project]) {
    delete meta.chapterPlots;
    delete meta.discussionArchives;
  }
  await store.writeText(statePath, JSON.stringify(state));
  await store.writeText(branchPath, JSON.stringify(branch));
  await store.writeText(commitPath, JSON.stringify(commit));
  const oldState = await store.readText(statePath);
  const cold = createFileNovelRepository(store);
  const loaded = await cold.loadProject('old');
  assert.deepEqual(loaded.chapterPlots, []);
  assert.deepEqual(loaded.discussionArchives, []);
  assert.equal(await store.readText(statePath), oldState);
  assert.deepEqual((await cold.workspaceStatus('old')).cas, stale.cas);
  assert.equal((await cold.workspaceStatus('old')).plotStale, true);
  for (let i = 0; i < 2; i++) {
    const status = await cold.workspaceStatus('old');
    await cold.commitProject('old', status.cas, `rename-${i}`, 'rename', p => ({ ...p, name: `新名${i}` }));
    assert.equal((await cold.workspaceStatus('old')).plotStale, true);
    assert.equal((await cold.workspaceStatus('old')).unresolvedFromOrdinal, 2);
  }
});

test('v3 schema migration preserves unresolved chapter impact and seeds stale pointers', async () => {
  const store = createMemoryFileStore();
  const repo = createFileNovelRepository(store);
  await repo.createProject(fixture('v3-stale'));
  await repo.commitProject('v3-stale', (await repo.workspaceStatus('v3-stale')).cas,
    'edit-v3', 'manual_edit', project => ({ ...project,
      chapters: project.chapters.map(chapter => chapter.id === 'c1' ? { ...chapter, content: '修订正文' } : chapter),
    }));
  const before = await repo.workspaceStatus('v3-stale');
  const prefix = 'amberagent/novel-workspace/v3-stale/';
  const statePath = prefix + '.amber/project-state.json';
  const branchPath = prefix + '.amber/branches/main.json';
  const commitPath = prefix + `.amber/commits/${before.cas.head}.json`;
  const state = JSON.parse((await store.readText(statePath)) ?? '') as OptionalState;
  const branch = JSON.parse((await store.readText(branchPath)) ?? '') as OptionalBranch;
  const commit = JSON.parse((await store.readText(commitPath)) ?? '') as OptionalCommit;
  for (const meta of [state.project, branch.state.project, commit.project]) {
    meta.schemaVersion = 3;
    delete meta.chapterPlots;
    delete meta.discussionArchives;
  }
  await store.writeText(statePath, JSON.stringify(state));
  await store.writeText(branchPath, JSON.stringify(branch));
  await store.writeText(commitPath, JSON.stringify(commit));
  const cold = createFileNovelRepository(store);
  const migrated = await cold.loadProject('v3-stale');
  assert.deepEqual(migrated.chapterPlots.map(pointer => pointer.stale), [false, true, true]);
  const after = await cold.workspaceStatus('v3-stale');
  assert.equal(after.plotStale, true);
  assert.equal(after.unresolvedFromOrdinal, 2);
  assert.equal((await createFileNovelRepository(store).workspaceStatus('v3-stale')).plotStale, true);
});

test('polish includePlot snapshots fresh manuscript pointers independently of author plot', async () => {
  for (const includePlot of [true, false]) {
    const repo = createFileNovelRepository(createMemoryFileStore());
    const id = includePlot ? 'with-pointers' : 'without-pointers';
    await repo.createProject(fixture(id));
    const job = await repo.startPolishJob(id, (await repo.workspaceStatus(id)).cas,
      'polish', 1, 1, { includePlot, includeCharacters: false, includeForeshadows: false, includeDecisions: false }, 2);
    assert.equal(job.contextSnapshot.length, includePlot ? 3 : 0);
    assert.equal(job.contextSnapshot.every(item => item.kind === 'plot' && item.content.includes('正文摘录')), true);
  }
});
