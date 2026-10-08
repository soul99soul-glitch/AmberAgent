import { test } from 'node:test';
import { strict as assert } from 'node:assert';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore } from '../main/ets/platform/files.ts';
import { makeNovelChapter, makeNovelMaterial, makeNovelProject } from '../main/ets/novel/models.ts';
import type { NovelWorkspaceValidatedImport } from '../main/ets/novel/workspace_exchange.ts';
import { NOVEL_WORKSPACE_FORMAT, NOVEL_WORKSPACE_VERSION } from '../main/ets/novel/workspace_contract.ts';

const NOW = 1_000_000;

test('createProject then loadProject roundtrip', async () => {
  const fs = createMemoryFileStore();
  const repo = createFileNovelRepository(fs);
  const p = {
    ...makeNovelProject({ id: 'p1', name: '小说A', now: NOW }),
    chapters: [makeNovelChapter({ id: 'c1', title: '开端', content: '山门初见。', now: NOW })],
    materials: [makeNovelMaterial({
      id: 'm1', kind: 'world' as const, title: '宗门', content: '青云宗。', now: NOW,
    })],
  };
  await repo.createProject(p);
  const loaded = await repo.loadProject('p1');
  assert.equal(loaded.name, '小说A');
  assert.equal(loaded.id, 'p1');
  assert.equal(loaded.chapters[0].content, '山门初见。');
  assert.equal(loaded.materials[0].content, '青云宗。');
  assert.equal(await fs.exists('amberagent/novel-workspace/p1/manifest.yaml'), true);
  assert.equal(await fs.readText(
    'amberagent/novel-workspace/p1/branches/main/chapters/001-开端.md'), '山门初见。');
  assert.equal(await fs.readText(
    'amberagent/novel-workspace/p1/setting/world/m1.md'), '青云宗。');
  assert.equal(await fs.exists('amberagent/novel-workspace/p1/.amber/project-state.json'), true);
  assert.equal(await fs.exists('amberagent/novel-workspace/p1/.amber/ledger.jsonl'), true);
});

test('listProjects sorted by updatedAt desc', async () => {
  const repo = createFileNovelRepository(createMemoryFileStore());
  await repo.createProject({ ...makeNovelProject({ id: 'old', name: '旧', now: NOW }), updatedAt: NOW });
  await repo.createProject({ ...makeNovelProject({ id: 'new', name: '新', now: NOW }), updatedAt: NOW + 100 });
  const list = await repo.listProjects();
  assert.deepEqual(list.map(p => p.id), ['new', 'old']);
});

test('updateProject rejects id change', async () => {
  const repo = createFileNovelRepository(createMemoryFileStore());
  await repo.createProject(makeNovelProject({ id: 'p1', name: '原', now: NOW }));
  await assert.rejects(repo.updateProject('p1', p => ({ ...p, id: 'other' })));
});

test('deleteProject is serialized after an in-flight update and cannot be revived', async () => {
  const repo = createFileNovelRepository(createMemoryFileStore());
  await repo.createProject(makeNovelProject({ id: 'race', name: '原', now: NOW }));
  const updating: Promise<unknown> = repo.updateProject('race', project => ({
    ...project,
    name: '更新中',
    updatedAt: NOW + 1,
  }));
  const deleting: Promise<void> = repo.deleteProject('race');
  await Promise.all([updating, deleting]);
  await assert.rejects(repo.loadProject('race'));
});

test('restorePrevious migrates the newest legacy deleted JSON into an active workspace', async () => {
  const fs = createMemoryFileStore();
  const repo = createFileNovelRepository(fs);
  const legacy = {
    ...makeNovelProject({ id: 'legacy-deleted', name: '旧版回收项目', now: NOW }),
    updatedAt: NOW + 10,
  };
  await fs.writeText('novel_deleted/legacy-deleted.novel.json', JSON.stringify(legacy));

  const restored = await repo.restorePrevious();

  assert.equal(restored?.id, 'legacy-deleted');
  assert.equal(restored?.name, '旧版回收项目');
  assert.equal(await fs.exists('amberagent/novel-workspace/legacy-deleted/manifest.yaml'), true);
  assert.equal(await fs.exists('novel_deleted/legacy-deleted.novel.json'), false);
  assert.equal((await repo.loadProject('legacy-deleted')).updatedAt, NOW + 10);
});

test('restorePrevious prioritizes actual deletion timestamp over unrecorded legacy updated time', async () => {
  const fs = createMemoryFileStore();
  const repo = createFileNovelRepository(fs);
  await repo.createProject({
    ...makeNovelProject({ id: 'workspace-older', name: '旧工作区', now: NOW }),
    updatedAt: NOW + 10,
  });
  await repo.deleteProject('workspace-older');
  const legacy = {
    ...makeNovelProject({ id: 'legacy-newer', name: '新 JSON', now: NOW }),
    updatedAt: NOW + 20,
  };
  await fs.writeText('novel_deleted/legacy-newer.novel.json', JSON.stringify(legacy));

  const restored = await repo.restorePrevious();

  assert.equal(restored?.id, 'workspace-older');
  assert.equal(await fs.exists('novel_deleted/legacy-newer.novel.json'), true);
  assert.equal(await fs.exists('amberagent/novel-workspace/.deleted/workspace-older'), false);
});

test('restorePrevious keeps corrupt legacy deleted JSON as evidence', async () => {
  const fs = createMemoryFileStore();
  const repo = createFileNovelRepository(fs);
  await fs.writeText('novel_deleted/corrupt.novel.json', '{corrupt');

  assert.equal(await repo.restorePrevious(), null);
  assert.equal(await fs.readText('novel_deleted/corrupt.novel.json'), '{corrupt');
});

test('restorePrevious refuses to overwrite an active project and keeps the legacy source', async () => {
  const fs = createMemoryFileStore();
  const repo = createFileNovelRepository(fs);
  const legacy = {
    ...makeNovelProject({ id: 'same-id', name: '待恢复', now: NOW }),
    updatedAt: NOW + 10,
  };
  await fs.writeText('novel_deleted/same-id.novel.json', JSON.stringify(legacy));
  await repo.createProject(makeNovelProject({ id: 'same-id', name: '当前项目', now: NOW + 20 }));

  await assert.rejects(repo.restorePrevious(), /同 id 项目已存在/);
  assert.equal(await fs.exists('novel_deleted/same-id.novel.json'), true);
  assert.equal((await repo.loadProject('same-id')).name, '当前项目');
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

test('loadProject migrates existing v2 project without losing real project data', async () => {
  const fs = createMemoryFileStore();
  const repo = createFileNovelRepository(fs);
  const current = makeNovelProject({ id: 'cultivation', name: '修仙日常', now: NOW });
  const legacy: Record<string, unknown> = {
    ...current,
    schemaVersion: 2,
    modelId: 'orphaned-model-id',
    messages: [{
      id: 'legacy-message', role: 'assistant', mode: 'write', content: '旧正文',
      collectedChapterId: null, createdAt: NOW, granularity: null, interrupted: false,
    }],
  };
  delete legacy.settingProposals;
  await fs.writeText(
    'amberagent/novel-creation/projects/cultivation.novel.json',
    JSON.stringify(legacy),
  );

  const loaded = await repo.loadProject('cultivation');
  assert.equal(loaded.schemaVersion, 4);
  assert.equal(loaded.name, '修仙日常');
  assert.equal(loaded.messages[0].content, '旧正文');
  assert.equal(loaded.messages[0].uiMessage.parts[0].type, 'text');
  assert.equal(loaded.modelPolicy.writing.kind, 'global');
  assert.deepEqual(loaded.chapters, current.chapters);
  assert.deepEqual(loaded.materials, current.materials);
  assert.deepEqual(loaded.settingProposals, []);
  assert.equal(await fs.exists('amberagent/novel-workspace/cultivation/manifest.yaml'), true);
  assert.equal(await fs.exists(
    'amberagent/novel-creation/projects/cultivation.novel.json'), false);

  const updated = await repo.updateProject('cultivation', project => ({ ...project, updatedAt: NOW + 1 }));
  assert.equal(updated.schemaVersion, 4);
  assert.equal(updated.revision, 1);
  const stateRaw = await fs.readText('amberagent/novel-workspace/cultivation/.amber/project-state.json');
  assert.equal((stateRaw ?? '').includes('"content":"旧正文"'), false);
  const storedState = JSON.parse(stateRaw ?? '{}') as { project?: { modelId?: string | null } };
  assert.equal(storedState.project?.modelId, undefined);
});

test('workspace recovery restores backup after process dies between directory renames', async () => {
  const fs = createMemoryFileStore();
  const repo = createFileNovelRepository(fs);
  await repo.createProject(makeNovelProject({ id: 'recover', name: '可恢复', now: NOW }));
  await fs.rename(
    'amberagent/novel-workspace/recover',
    'amberagent/novel-workspace/.trash/recover',
  );
  await fs.writeText(
    'amberagent/novel-workspace/.staging/recover/manifest.yaml',
    'partial',
  );

  const loaded = await repo.loadProject('recover');
  assert.equal(loaded.name, '可恢复');
  assert.equal(await fs.exists('amberagent/novel-workspace/.staging/recover'), false);
  assert.equal(await fs.exists('amberagent/novel-workspace/.trash/recover'), false);
});

test('workspace update leaves append-only ledger and changes checkout tree digest', async () => {
  const fs = createMemoryFileStore();
  const repo = createFileNovelRepository(fs);
  await repo.createProject(makeNovelProject({ id: 'ledger', name: '台账', now: NOW }));
  const beforeCheckout = await fs.readText(
    'amberagent/novel-workspace/ledger/.amber/checkout.json');
  await repo.updateProject('ledger', project => ({
    ...project,
    chapters: [makeNovelChapter({ id: 'c1', title: '一', content: '正文', now: NOW + 1 })],
    updatedAt: NOW + 1,
  }));
  const afterCheckout = await fs.readText(
    'amberagent/novel-workspace/ledger/.amber/checkout.json');
  const ledger = await fs.readText('amberagent/novel-workspace/ledger/.amber/ledger.jsonl');
  assert.notEqual(afterCheckout, beforeCheckout);
  assert.equal((ledger ?? '').trim().split('\n').length, 2);
});

test('workspace import installs a new project, preserves non-modeled files and exports current branch', async () => {
  const fs = createMemoryFileStore();
  const repo = createFileNovelRepository(fs);
  const encoder = new TextEncoder();
  const imported: NovelWorkspaceValidatedImport = {
    manifest: {
      format: NOVEL_WORKSPACE_FORMAT,
      version: NOVEL_WORKSPACE_VERSION,
      projectId: 'imported',
      title: '外来小说',
      activeBranch: 'draft-a',
      createdAt: NOW,
      updatedAt: NOW + 1,
    },
    files: [
      { path: 'manifest.yaml', bytes: encoder.encode('placeholder') },
      { path: 'project.md', bytes: encoder.encode('# 外来小说\n\n这是用户维护的项目说明。\n') },
      { path: 'branches/draft-a/chapters/031-归途.md', bytes: encoder.encode('第三十一章正文') },
      { path: 'setting/world/realm.md', bytes: encoder.encode('境界设定') },
      { path: 'drafts/reference.bin', bytes: new Uint8Array([0, 1, 255]) },
    ],
  };

  const project = await repo.installWorkspace(imported);
  assert.equal(project.id, 'imported');
  assert.equal(project.chapters[0].title, '归途');
  assert.equal(project.chapters[0].content, '第三十一章正文');
  assert.equal(project.materials[0].content, '境界设定');
  const exported = await repo.workspaceFiles(project.id);
  assert.equal(exported.some(file => file.path.startsWith('.amber/')), false);
  assert.equal(new TextDecoder().decode(
    exported.find(file => file.path === 'project.md')?.bytes),
  '# 外来小说\n\n这是用户维护的项目说明。\n');
  assert.deepEqual(Array.from(exported.find(file => file.path === 'drafts/reference.bin')?.bytes ?? []),
    [0, 1, 255]);
  const book = await repo.bookExportInput(project.id);
  assert.equal(book.chapters[0].ordinal, 31);

  await repo.updateProject(project.id, current => ({ ...current, name: '改名后', updatedAt: NOW + 2 }));
  const afterUpdate = await repo.workspaceFiles(project.id);
  assert.equal(new TextDecoder().decode(
    afterUpdate.find(file => file.path === 'project.md')?.bytes),
  '# 外来小说\n\n这是用户维护的项目说明。\n');
  assert.deepEqual(Array.from(
    afterUpdate.find(file => file.path === 'drafts/reference.bin')?.bytes ?? []), [0, 1, 255]);
});

test('workspace import with an existing project id allocates an independent project', async () => {
  const fs = createMemoryFileStore();
  const repo = createFileNovelRepository(fs);
  await repo.createProject(makeNovelProject({ id: 'same', name: '本地', now: NOW }));
  const encoder = new TextEncoder();
  const imported = await repo.installWorkspace({
    manifest: {
      format: NOVEL_WORKSPACE_FORMAT,
      version: NOVEL_WORKSPACE_VERSION,
      projectId: 'same',
      title: '导入',
      activeBranch: 'main',
      createdAt: NOW,
      updatedAt: NOW,
    },
    files: [
      { path: 'manifest.yaml', bytes: encoder.encode('placeholder') },
      { path: 'project.md', bytes: encoder.encode('# 导入') },
    ],
  });
  assert.notEqual(imported.id, 'same');
  assert.equal((await repo.loadProject('same')).name, '本地');
});
