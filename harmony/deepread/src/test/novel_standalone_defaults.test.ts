import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  defaultNovelModelDefaults, resolveNovelDefaultTarget, novelStorySeedText,
} from '../main/ets/novel/standalone_defaults.ts';
import { createNovelCreation } from '../main/ets/novel/creation.ts';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import type { NovelModelTarget } from '../main/ets/novel/models.ts';

const fixed = (modelId: string): NovelModelTarget => ({ kind: 'fixed', providerId: 'provider', modelId });

test('project fixed takes priority; unconfigured roles inherit default writing; explicit default role wins', () => {
  const defaults = { writing: fixed('default-writing'), review: fixed('default-review'), stateSync: { kind: 'global' as const } };
  const policy = { writing: fixed('project-writing'), review: null, stateSync: null };
  assert.deepEqual(resolveNovelDefaultTarget(policy, defaults, 'writing'), fixed('project-writing'));
  assert.deepEqual(resolveNovelDefaultTarget(policy, defaults, 'review'), fixed('default-review'));
  assert.deepEqual(resolveNovelDefaultTarget(policy, defaults, 'stateSync'), fixed('project-writing'));
  assert.deepEqual(resolveNovelDefaultTarget({ ...policy, review: fixed('project-review') }, defaults, 'review'), fixed('project-review'));
  assert.deepEqual(resolveNovelDefaultTarget({ writing: { kind: 'global' }, review: null, stateSync: null }, defaultNovelModelDefaults(), 'writing'), { kind: 'global' });
});

test('structured quick-start seed survives project reload before AI turn; blank fields create no material', async () => {
  const repository = createFileNovelRepository(createMemoryFileStore());
  const validated: NovelModelTarget[] = [];
  const creation = createNovelCreation({ repository, modelRunning: {
    async validate(target) { validated.push(target); },
    start() { throw new Error('No AI turn should start while creating a seed'); },
    cancel() {},
  }, loadModelDefaults: async () => ({ writing: fixed('writing'), review: fixed('review'), stateSync: fixed('sync') }) });
  const seed = { genre: ' 悬疑 ', coreIdea: '旧城的信', world: '近未来', characters: '失忆调查员', direction: '揭开身世' };
  const project = await creation.create(' 种子项目 ', seed);
  const reloaded = await repository.loadProject(project.id);
  assert.equal(reloaded.materials.length, 4);
  assert.deepEqual(reloaded.materials.map(item => item.kind), ['requirement', 'world', 'character', 'outline']);
  assert.ok(reloaded.materials.every(item => item.enabled));
  assert.equal(reloaded.messages.length, 0);
  assert.match(novelStorySeedText(seed), /世界背景：近未来/);
  await creation.validateGhostwriteModels(project.id);
  assert.deepEqual(validated, [fixed('writing'), fixed('review'), fixed('sync')]);
  const empty = await creation.create('空白', { genre: '', coreIdea: '', world: ' ', characters: '', direction: '' });
  assert.equal((await repository.loadProject(empty.id)).materials.length, 0);
});

test('durable polish applies novel role defaults and frozen project preference to the actual writer request', async () => {
  const { createPolishRunner } = await import('../main/ets/novel/polish_runner.ts');
  const repository = createFileNovelRepository(createMemoryFileStore());
  const requests: import('../main/ets/novel/model_running.ts').NovelModelRequest[] = [];
  const model: import('../main/ets/novel/model_running.ts').NovelModelRunning = {
    async validate() {},
    start(request) {
      requests.push(request);
      return { subscribe(callback) {
        const timer = setTimeout(() => callback({ kind: 'failed', message: 'test captures provider request' }), 0);
        return () => clearTimeout(timer);
      } };
    },
    cancel() {},
  };
  const creation = createNovelCreation({ repository, modelRunning: model });
  const project = await creation.create('润色默认值');
  await creation.setProjectPolishPreference(project.id, '克制，保留人物语气');
  await creation.saveChapter(project.id, null, '第一章', '风很冷。');
  const status = await repository.workspaceStatus(project.id);
  const job = await repository.startPolishJob(project.id, status.cas, 'polish-default-test', 1, 1,
    { includePlot: false, includeForeshadows: false, includeCharacters: false, includeDecisions: false }, Date.now());
  const runner = createPolishRunner({ repository, modelRunning: model,
    resolveModelTarget: async (policy, role) => resolveNovelDefaultTarget(policy,
      { writing: fixed('standalone-writer'), review: fixed('standalone-review'), stateSync: { kind: 'global' } }, role),
    loadPolishPreference: async () => '克制，保留人物语气',
  });
  await runner.run(project.id, job.jobId, 'test-owner');
  assert.equal(requests.length, 1);
  assert.deepEqual(requests[0].modelTarget, fixed('standalone-writer'));
  assert.ok(requests[0].operation.kind === 'turn');
  assert.match(requests[0].operation.userPrompt, /作者润色偏好：\n克制，保留人物语气/);
});
