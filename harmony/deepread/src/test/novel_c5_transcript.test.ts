import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { makeNovelMessage, makeNovelProject } from '../main/ets/novel/models.ts';
import { novelMessageText, novelMessageUi } from '../main/ets/novel/transcript.ts';
import type { UIMessage } from '../main/ets/agent/message.ts';

const NOW = 1_700_000_000_000;

const canonicalAssistant = (): UIMessage => ({
  id: 'ui-full',
  role: 'assistant',
  parts: [
    { type: 'text', text: '正文', metadata: { source: 'model' } },
    { type: 'reasoning', reasoning: '思考过程', createdAt: '2026-08-31T00:00:00.000Z', finishedAt: null, metadata: null },
    {
      type: 'tool', toolCallId: 'tool-pending', toolName: 'lookup', input: '{"q":"a"}', output: [],
      approvalState: { type: 'pending' }, metadata: null,
    },
    {
      type: 'tool', toolCallId: 'tool-answered', toolName: 'ask_user', input: '{}',
      output: [{ type: 'text', text: '用户回答', metadata: null }],
      approvalState: { type: 'answered', answer: '继续' }, metadata: { durable: true },
    },
    {
      type: 'tool', toolCallId: 'tool-rejected', toolName: 'write_file', input: '{}', output: [],
      approvalState: { type: 'denied', reason: '用户拒绝' }, metadata: null,
    },
  ],
  annotations: [
    { type: 'url_citation', title: '来源', url: 'https://example.com' },
    { type: 'generation_interrupted', reason: 'network' },
  ],
  createdAt: '2026-08-31T00:00:00.000Z',
  finishedAt: '2026-08-31T00:00:01.000Z',
  modelId: 'writer-v1',
  usage: { promptTokens: 10, completionTokens: 20, cachedTokens: 3, totalTokens: 30 },
  translation: 'translation',
});

test('canonical transcript roundtrips mixed parts and does not serialize legacy content', async () => {
  const fileStore = createMemoryFileStore();
  const repository = createFileNovelRepository(fileStore);
  const message = makeNovelMessage({
    id: 'novel-message', role: 'assistant', mode: 'write', uiMessage: canonicalAssistant(), createdAt: NOW,
  });
  const project = {
    ...makeNovelProject({ id: 'transcript', name: '富消息', now: NOW }),
    messages: [message],
  };
  await repository.createProject(project);
  const loaded = await repository.loadProject(project.id);
  assert.deepEqual(novelMessageUi(loaded.messages[0]), canonicalAssistant());
  assert.equal(novelMessageText(loaded.messages[0]), '正文');
  assert.equal(loaded.messages[0].content, '正文');

  const stateRaw = await fileStore.readText(
    'amberagent/novel-workspace/transcript/.amber/project-state.json',
  );
  assert.equal((stateRaw ?? '').includes('"content":"正文"'), false);
  assert.equal((stateRaw ?? '').includes('"tool-answered"'), true);
});

test('v3 content-only transcript migrates once into a canonical v4 workspace', async () => {
  const fileStore = createMemoryFileStore();
  const repository = createFileNovelRepository(fileStore);
  const current = makeNovelProject({ id: 'v3-transcript', name: '旧会话', now: NOW });
  const legacy: Record<string, unknown> = {
    ...current,
    schemaVersion: 3,
    modelId: 'legacy-without-provider',
    messages: [{
      id: 'v3-message', role: 'assistant', mode: 'discuss', content: '旧讨论正文',
      collectedChapterId: null, createdAt: NOW, granularity: null, interrupted: false,
    }],
  };
  await fileStore.writeText(
    'amberagent/novel-creation/projects/v3-transcript.novel.json', JSON.stringify(legacy),
  );

  const migrated = await repository.loadProject('v3-transcript');
  assert.equal(migrated.schemaVersion, 4);
  assert.equal(novelMessageText(migrated.messages[0]), '旧讨论正文');
  assert.equal(migrated.modelPolicy.writing.kind, 'global');
  const stateRaw = await fileStore.readText(
    'amberagent/novel-workspace/v3-transcript/.amber/project-state.json',
  );
  assert.equal((stateRaw ?? '').includes('"content":"旧讨论正文"'), false);
  assert.equal(await fileStore.exists(
    'amberagent/novel-creation/projects/v3-transcript.novel.json',
  ), false);
});

test('model policy and branch settings are durable and isolated by branch', async () => {
  const fileStore = createMemoryFileStore();
  const repository = createFileNovelRepository(fileStore);
  const initial = {
    ...makeNovelProject({
      id: 'settings', name: '设定', now: NOW,
      modelPolicy: {
        writing: { kind: 'fixed' as const, providerId: 'provider-a', modelId: 'writer-a' },
        review: null,
        stateSync: { kind: 'fixed' as const, providerId: 'provider-b', modelId: 'sync-b' },
      },
    }),
    branchSettings: {
      thisChapterPlan: '本章计划',
      futurePlan: '后续计划',
      preferences: '主线偏好',
      foreshadows: [{
        id: 'f1', title: '玉佩', content: '尚未揭示', status: 'open' as const,
        createdAt: NOW, resolvedAt: null,
      }],
      confirmedDecisions: [{ id: 'd1', title: '视角', content: '第一人称', confirmedAt: NOW }],
    },
  };
  await repository.createProject(initial);
  const main = await repository.loadProject(initial.id);
  assert.equal(main.modelPolicy.writing.kind, 'fixed');
  assert.equal(main.modelPolicy.review, null);
  assert.equal(main.branchSettings.foreshadows[0].status, 'open');
  assert.equal(await fileStore.exists(
    'amberagent/novel-workspace/settings/branches/main/plan/this-chapter.md',
  ), true);
  assert.equal(await fileStore.exists(
    'amberagent/novel-workspace/settings/branches/main/plan/future.md',
  ), true);
  assert.equal(await fileStore.exists(
    'amberagent/novel-workspace/settings/branches/main/setting/preferences.md',
  ), true);

  const mainStatus = await repository.workspaceStatus(initial.id);
  const fork = await repository.createBranch(initial.id, '支线', mainStatus.cas, 'create-settings-branch');
  const forkBranchId = fork.branches.find(branch => !branch.isMain)?.id;
  assert.ok(forkBranchId !== undefined);
  await repository.updateProject(initial.id, project => ({
    ...project,
    branchSettings: {
      ...project.branchSettings,
      preferences: '支线偏好',
      foreshadows: project.branchSettings.foreshadows.map(foreshadow => ({
        ...foreshadow, status: 'resolved' as const, resolvedAt: NOW + 1,
      })),
    },
    updatedAt: NOW + 1,
  }));

  const forkStatus = await repository.workspaceStatus(initial.id);
  await repository.switchBranch(initial.id, 'main', forkStatus.cas);
  const restoredMain = await repository.loadProject(initial.id);
  assert.equal(restoredMain.branchSettings.preferences, '主线偏好');
  assert.equal(restoredMain.branchSettings.foreshadows[0].status, 'open');

  const switchBack = await repository.workspaceStatus(initial.id);
  await repository.switchBranch(initial.id, forkBranchId, switchBack.cas);
  const restoredFork = await repository.loadProject(initial.id);
  assert.equal(restoredFork.branchSettings.preferences, '支线偏好');
  assert.equal(restoredFork.branchSettings.foreshadows[0].status, 'resolved');
});
