import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelProject } from '../main/ets/novel/models.ts';
import { isNovelError } from '../main/ets/novel/error.ts';

const NOW = 1_000_000;

test('createProject then loadProject roundtrip', async () => {
  const repo = createFileNovelRepository(createMemoryFileStore());
  const p = makeNovelProject({ id: 'p1', name: '小说A', now: NOW });
  await repo.createProject(p);
  const loaded = await repo.loadProject('p1');
  assert.equal(loaded.name, '小说A');
  assert.equal(loaded.id, 'p1');
});

test('loadProject missing throws notFound', async () => {
  const repo = createFileNovelRepository(createMemoryFileStore());
  try {
    await repo.loadProject('ghost');
    assert.fail('should throw');
  } catch (e) {
    assert.ok(isNovelError(e));
  }
});

test('listProjects sorted by updatedAt desc', async () => {
  const repo = createFileNovelRepository(createMemoryFileStore());
  await repo.createProject({ ...makeNovelProject({ id: 'old', name: '旧', now: NOW }), updatedAt: NOW });
  await repo.createProject({ ...makeNovelProject({ id: 'new', name: '新', now: NOW }), updatedAt: NOW + 100 });
  const list = await repo.listProjects();
  assert.deepEqual(list.map(p => p.id), ['new', 'old']);
});

test('updateProject transforms and persists', async () => {
  const repo = createFileNovelRepository(createMemoryFileStore());
  await repo.createProject(makeNovelProject({ id: 'p1', name: '原', now: NOW }));
  const updated = await repo.updateProject('p1', p => ({ ...p, name: '改', updatedAt: NOW + 1 }));
  assert.equal(updated.name, '改');
  const reloaded = await repo.loadProject('p1');
  assert.equal(reloaded.name, '改');
});

test('updateProject rejects id change', async () => {
  const repo = createFileNovelRepository(createMemoryFileStore());
  await repo.createProject(makeNovelProject({ id: 'p1', name: '原', now: NOW }));
  await assert.rejects(repo.updateProject('p1', p => ({ ...p, id: 'other' })));
});

test('updateProject missing throws', async () => {
  const repo = createFileNovelRepository(createMemoryFileStore());
  await assert.rejects(repo.updateProject('ghost', p => p));
});

test('deleteProject removes; subsequent load throws', async () => {
  const repo = createFileNovelRepository(createMemoryFileStore());
  await repo.createProject(makeNovelProject({ id: 'p1', name: 'x', now: NOW }));
  await repo.deleteProject('p1');
  await assert.rejects(repo.loadProject('p1'));
});

test('listProjects skips unreadable/corrupt files', async () => {
  const fs = createMemoryFileStore();
  const repo = createFileNovelRepository(fs);
  await repo.createProject(makeNovelProject({ id: 'good', name: '好', now: NOW }));
  // 写一个损坏文件
  await fs.writeText('amberagent/novel-creation/projects/bad.novel.json', '{corrupt');
  const list = await repo.listProjects();
  assert.equal(list.length, 1);
  assert.equal(list[0].id, 'good');
});

test('listProjects ignores non-novel files', async () => {
  const fs = createMemoryFileStore();
  const repo = createFileNovelRepository(fs);
  await repo.createProject(makeNovelProject({ id: 'good', name: '好', now: NOW }));
  await fs.writeText('amberagent/novel-creation/projects/readme.txt', 'hi');
  const list = await repo.listProjects();
  assert.equal(list.length, 1);
});

test('loadProject migrates existing v2 project without losing real project data', async () => {
  const fs = createMemoryFileStore();
  const repo = createFileNovelRepository(fs);
  const current = makeNovelProject({ id: 'cultivation', name: '修仙日常', now: NOW });
  const legacy: Record<string, unknown> = { ...current, schemaVersion: 2 };
  delete legacy.settingProposals;
  await fs.writeText(
    'amberagent/novel-creation/projects/cultivation.novel.json',
    JSON.stringify(legacy),
  );

  const loaded = await repo.loadProject('cultivation');
  assert.equal(loaded.schemaVersion, 3);
  assert.equal(loaded.name, '修仙日常');
  assert.deepEqual(loaded.messages, current.messages);
  assert.deepEqual(loaded.chapters, current.chapters);
  assert.deepEqual(loaded.materials, current.materials);
  assert.deepEqual(loaded.settingProposals, []);

  const updated = await repo.updateProject('cultivation', project => ({ ...project, updatedAt: NOW + 1 }));
  assert.equal(updated.schemaVersion, 3);
  assert.equal(updated.revision, 1);
});
