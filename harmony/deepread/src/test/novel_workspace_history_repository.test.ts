import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelChapter, makeNovelProject } from '../main/ets/novel/models.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { NovelCreation } from '../main/ets/novel/creation.ts';
import type { NovelModelRunning } from '../main/ets/novel/model_running.ts';

const NOW = 2_000_000;

test('workspace commit enforces branch head tree CAS and replays the same receipt once', async () => {
  const store = createMemoryFileStore();
  const repository = createFileNovelRepository(store);
  await repository.createProject(makeNovelProject({ id: 'cas', name: 'CAS', now: NOW }));
  const before = await repository.workspaceStatus('cas');
  const first = await repository.commitProject(
    'cas', before.cas, 'command-1', 'manual_edit', project => ({
      ...project,
      chapters: [makeNovelChapter({ id: 'c1', title: '第一章', content: '第一次', now: NOW + 1 })],
      updatedAt: NOW + 1,
    }),
  );
  const replay = await repository.commitProject(
    'cas', before.cas, 'command-1', 'manual_edit', project => ({
      ...project,
      name: '不应执行',
      updatedAt: NOW + 2,
    }),
  );
  assert.equal(replay.revision, first.revision);
  assert.equal(replay.name, 'CAS');
  await assert.rejects(repository.commitProject(
    'cas', before.cas, 'command-2', 'manual_edit', project => ({ ...project, name: '漂移覆盖' })),
    /工作区已变化/,
  );
  assert.equal((await repository.loadProject('cas')).name, 'CAS');
  const ledger = await store.readText('amberagent/novel-workspace/cas/.amber/ledger.jsonl');
  assert.equal((ledger ?? '').trim().split('\n').length, 2);
});

test('fork and switch hydrate isolated branch state instead of project arrays', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  await repository.createProject({
    ...makeNovelProject({ id: 'branches', name: '分支', now: NOW }),
    chapters: [makeNovelChapter({ id: 'c1', title: '第一章', content: '主线', now: NOW })],
  });
  const main = await repository.workspaceStatus('branches');
  const fork = await repository.createBranch('branches', '另一种可能', main.cas, 'fork-1');
  const forkStatus = await repository.workspaceStatus('branches');
  assert.notEqual(forkStatus.activeBranchId, main.activeBranchId);
  assert.equal(fork.chapters[0].content, '主线');
  await repository.commitProject(
    'branches', forkStatus.cas, 'edit-fork', 'manual_edit', project => ({
      ...project,
      chapters: [{ ...project.chapters[0], content: '支线', updatedAt: NOW + 1 }],
      updatedAt: NOW + 1,
    }),
  );
  const editedFork = await repository.loadProject('branches');
  assert.equal(editedFork.chapters[0].content, '支线');
  const afterEdit = await repository.workspaceStatus('branches');
  const mainProject = await repository.switchBranch(
    'branches', main.activeBranchId, afterEdit.cas,
  );
  assert.equal(mainProject.chapters[0].content, '主线');
  const mainAgain = await repository.workspaceStatus('branches');
  const forkAgain = await repository.switchBranch(
    'branches', forkStatus.activeBranchId, mainAgain.cas,
  );
  assert.equal(forkAgain.chapters[0].content, '支线');
});

test('middle edit marks unresolved and plot stale; sync and resolve are explicit commits', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  await repository.createProject({
    ...makeNovelProject({ id: 'consistency', name: '一致性', now: NOW }),
    chapters: [
      makeNovelChapter({ id: 'c1', title: '第一章', content: '一', now: NOW }),
      makeNovelChapter({ id: 'c2', title: '第二章', content: '二', now: NOW }),
      makeNovelChapter({ id: 'c3', title: '第三章', content: '三', now: NOW }),
    ],
  });
  const before = await repository.workspaceStatus('consistency');
  await repository.commitProject(
    'consistency', before.cas, 'middle-edit', 'manual_edit', project => ({
      ...project,
      chapters: project.chapters.map(chapter => chapter.id === 'c1'
        ? { ...chapter, content: '一改', updatedAt: NOW + 1 }
        : chapter),
      updatedAt: NOW + 1,
    }),
  );
  const changed = await repository.workspaceStatus('consistency');
  assert.equal(changed.unresolvedFromOrdinal, 2);
  assert.equal(changed.plotStale, true);
  await repository.syncPlot('consistency', changed.cas, 'sync-plot');
  const synced = await repository.workspaceStatus('consistency');
  assert.equal(synced.plotStale, false);
  assert.equal(synced.unresolvedFromOrdinal, 2);
  await repository.resolveUnresolved('consistency', synced.cas, 'resolve-unresolved');
  const resolved = await repository.workspaceStatus('consistency');
  assert.equal(resolved.unresolvedFromOrdinal, null);
});

test('one-level undo restores branch tree by appending a new commit', async () => {
  const store = createMemoryFileStore();
  const repository = createFileNovelRepository(store);
  await repository.createProject({
    ...makeNovelProject({ id: 'undo', name: '撤销', now: NOW }),
    chapters: [makeNovelChapter({ id: 'c1', title: '第一章', content: '原文', now: NOW })],
  });
  const before = await repository.workspaceStatus('undo');
  await repository.commitProject(
    'undo', before.cas, 'edit', 'manual_edit', project => ({
      ...project,
      chapters: [{ ...project.chapters[0], content: '改文', updatedAt: NOW + 1 }],
      updatedAt: NOW + 1,
    }),
  );
  const changed = await repository.workspaceStatus('undo');
  assert.equal(changed.canUndo, true);
  const restored = await repository.undo('undo', changed.cas, 'undo-1');
  assert.equal(restored.chapters[0].content, '原文');
  const finalStatus = await repository.workspaceStatus('undo');
  assert.equal(finalStatus.canUndo, false);
  const ledger = await store.readText('amberagent/novel-workspace/undo/.amber/ledger.jsonl');
  assert.equal((ledger ?? '').trim().split('\n').length, 3);
});

test('load rejects checkout tree drift instead of overwriting visible text', async () => {
  const store = createMemoryFileStore();
  const repository = createFileNovelRepository(store);
  await repository.createProject({
    ...makeNovelProject({ id: 'drift', name: '漂移', now: NOW }),
    chapters: [makeNovelChapter({ id: 'c1', title: '第一章', content: '原文', now: NOW })],
  });
  await store.writeText(
    'amberagent/novel-workspace/drift/branches/main/chapters/001-第一章.md',
    '外部改动',
  );
  await assert.rejects(repository.loadProject('drift'), /正文树与台账不一致/);
  assert.equal(await store.readText(
    'amberagent/novel-workspace/drift/branches/main/chapters/001-第一章.md'), '外部改动');
});

test('load rejects forged ledger parent and duplicate receipts', async () => {
  const store = createMemoryFileStore();
  const repository = createFileNovelRepository(store);
  await repository.createProject(makeNovelProject({ id: 'ledger-forged', name: '台账', now: NOW }));
  const before = await repository.workspaceStatus('ledger-forged');
  await repository.commitProject(
    'ledger-forged', before.cas, 'rename-once', 'rename', project => ({
      ...project, name: 'Renamed', updatedAt: NOW + 1,
    }),
  );
  const ledgerPath = 'amberagent/novel-workspace/ledger-forged/.amber/ledger.jsonl';
  const raw = (await store.readText(ledgerPath)) ?? '';
  const lines = raw.trim().split('\n');
  const second = JSON.parse(lines[1]) as Record<string, unknown>;
  second.parent = 'forged-parent';
  await store.writeText(ledgerPath, `${lines[0]}\n${JSON.stringify(second)}\n`);
  await assert.rejects(repository.loadProject('ledger-forged'), /台账 parent 链损坏/);

  second.parent = JSON.parse(lines[0]).commitId as string;
  second.receipt = JSON.parse(lines[0]).receipt as string;
  await store.writeText(ledgerPath, `${lines[0]}\n${JSON.stringify(second)}\n`);
  await assert.rejects(repository.loadProject('ledger-forged'), /工作区台账损坏/);
});

test('durable proposal approve commits protected chapter and plot patches exactly once', async () => {
  const store = createMemoryFileStore();
  const repository = createFileNovelRepository(store);
  await repository.createProject({
    ...makeNovelProject({ id: 'proposal-approve', name: '审批', now: NOW }),
    chapters: [makeNovelChapter({ id: 'c1', title: '第一章', content: '旧正文', now: NOW })],
  });
  const before = await repository.workspaceStatus('proposal-approve');
  const chapterPath = 'branches/main/chapters/001-第一章.md';
  const proposal = await repository.createProposal(
    'proposal-approve', before.cas, 'proposal-1', [
      { operation: 'write', path: chapterPath, content: '新正文' },
      { operation: 'write', path: 'branches/main/plan/plot.md', content: '新剧情' },
    ], NOW + 1,
  );
  assert.equal(proposal.status, 'pending');
  assert.equal((await repository.workspaceProposals('proposal-approve')).length, 1);
  const accepted = await repository.resolveProposal(
    'proposal-approve', 'proposal-1', true, 'decision-1', NOW + 2,
  );
  assert.equal(accepted.status, 'accepted');
  assert.equal((await repository.loadProject('proposal-approve')).chapters[0].content, '新正文');
  assert.equal(await store.readText(
    'amberagent/novel-workspace/proposal-approve/branches/main/plan/plot.md'), '新剧情');
  const replay = await repository.resolveProposal(
    'proposal-approve', 'proposal-1', true, 'decision-1', NOW + 3,
  );
  assert.equal(replay.status, 'accepted');
  const cannotReverse = await repository.resolveProposal(
    'proposal-approve', 'proposal-1', false, 'decision-2', NOW + 4,
  );
  assert.equal(cannotReverse.status, 'accepted');
  const ledger = await store.readText(
    'amberagent/novel-workspace/proposal-approve/.amber/ledger.jsonl');
  assert.equal((ledger ?? '').trim().split('\n').length, 2);
  const reloaded = createFileNovelRepository(store);
  assert.equal((await reloaded.workspaceProposals('proposal-approve'))[0].status, 'accepted');
});

test('durable proposal deny closes without changing branch tree or ledger and cannot later accept', async () => {
  const store = createMemoryFileStore();
  const repository = createFileNovelRepository(store);
  await repository.createProject({
    ...makeNovelProject({ id: 'proposal-deny', name: '拒绝', now: NOW }),
    chapters: [makeNovelChapter({ id: 'c1', title: '第一章', content: '保留', now: NOW })],
  });
  const before = await repository.workspaceStatus('proposal-deny');
  await repository.createProposal(
    'proposal-deny', before.cas, 'proposal-deny-1', [{
      operation: 'delete', path: 'branches/main/chapters/001-第一章.md', content: null,
    }], NOW + 1,
  );
  const denied = await repository.resolveProposal(
    'proposal-deny', 'proposal-deny-1', false, 'deny-command', NOW + 2,
  );
  assert.equal(denied.status, 'rejected');
  assert.equal((await repository.loadProject('proposal-deny')).chapters[0].content, '保留');
  const laterAccept = await repository.resolveProposal(
    'proposal-deny', 'proposal-deny-1', true, 'late-accept', NOW + 3,
  );
  assert.equal(laterAccept.status, 'rejected');
  const ledger = await store.readText(
    'amberagent/novel-workspace/proposal-deny/.amber/ledger.jsonl');
  assert.equal((ledger ?? '').trim().split('\n').length, 1);
});

test('durable proposal approval fails closed when frozen expected CAS is stale', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  await repository.createProject({
    ...makeNovelProject({ id: 'proposal-stale', name: '漂移', now: NOW }),
    chapters: [makeNovelChapter({ id: 'c1', title: '第一章', content: '原文', now: NOW })],
  });
  const before = await repository.workspaceStatus('proposal-stale');
  await repository.createProposal(
    'proposal-stale', before.cas, 'proposal-stale-1', [{
      operation: 'write', path: 'branches/main/chapters/001-第一章.md', content: '不应覆盖',
    }], NOW + 1,
  );
  await repository.commitProject(
    'proposal-stale', before.cas, 'concurrent-edit', 'rename', project => ({
      ...project,
      name: '已变化',
      updatedAt: NOW + 2,
    }),
  );
  await assert.rejects(repository.resolveProposal(
    'proposal-stale', 'proposal-stale-1', true, 'stale-decision', NOW + 3,
  ), /工作区已变化/);
  assert.equal((await repository.loadProject('proposal-stale')).chapters[0].content, '原文');
  assert.equal((await repository.workspaceProposals('proposal-stale'))[0].status, 'pending');
});

test('NovelCreation exposes the durable proposal production service chain', async () => {
  const store = createMemoryFileStore();
  const repository = createFileNovelRepository(store);
  await repository.createProject({
    ...makeNovelProject({ id: 'proposal-service', name: '服务链', now: NOW }),
    chapters: [makeNovelChapter({ id: 'c1', title: '第一章', content: '旧正文', now: NOW })],
  });
  const creation = new NovelCreation({
    repository,
    modelRunning: {} as NovelModelRunning,
    nowMs: (): number => NOW + 1,
  });
  const proposal = await creation.createWorkspaceProposal(
    'proposal-service', [{
      operation: 'write', path: 'branches/main/chapters/001-第一章.md', content: '服务链正文',
    }], 'service-proposal');
  assert.equal(proposal.status, 'pending');
  assert.equal((await creation.workspaceProposals('proposal-service')).length, 1);
  const accepted = await creation.resolveWorkspaceProposal('proposal-service', proposal.proposalId, true);
  assert.equal(accepted.status, 'accepted');
  assert.equal((await creation.open('proposal-service')).chapters[0].content, '服务链正文');
});
