import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createFileNovelRepository } from '../main/ets/novel/repository.ts';
import { createMemoryFileStore, type FileStore } from '../main/ets/platform/files.ts';
import { makeNovelProject, makeNovelChapter } from '../main/ets/novel/models.ts';
import { makeNovelNativeBackupImport } from '../main/ets/novel/native_backup.ts';
import type { NovelWorkspaceArchiveFile } from '../main/ets/novel/workspace_exchange.ts';

const base = 'amberagent/novel-workspace';
const root = `${base}/p`;
const chapterPath = 'branches/main/chapters/001-开篇.md';
const projectDocument = '# 作者说明\n完整创作目标和背景，不应被正文恢复覆盖。';

const snapshotFiles = async (files: FileStore, path: string): Promise<NovelWorkspaceArchiveFile[]> => {
  const snapshot: NovelWorkspaceArchiveFile[] = [];
  for (const name of await files.list(path)) {
    const child = `${path}/${name}`;
    if (await files.isDirectory(child)) snapshot.push(...await snapshotFiles(files, child));
    else snapshot.push({ path: child, bytes: (await files.readBytes(child))! });
  }
  return snapshot;
};

const setup = async (interruption: 'backup' | 'install') => {
  const files = createMemoryFileStore();
  let armed = false;
  let interrupted: NovelWorkspaceArchiveFile[] | null = null;
  const observed = new Proxy(files, {
    get(target, property) {
      if (property === 'rename') return async (from: string, to: string): Promise<void> => {
        await target.rename(from, to);
        const swapped = interruption === 'backup' ? from === root
          : from === `${base}/.staging/p` && to === root;
        // Capture the actual durable boundary; reopening this copy simulates process death.
        if (armed && interrupted === null && swapped) interrupted = await snapshotFiles(files, base);
      };
      const value = Reflect.get(target, property);
      return typeof value === 'function' ? value.bind(target) : value;
    },
  });
  const repository = createFileNovelRepository(observed);
  await repository.createProject({ ...makeNovelProject({ id: 'p', name: '小说', now: 1000 }),
    chapters: [makeNovelChapter({ id: 'chapter', title: '开篇', content: '已保存正文', now: 1000 })] });
  await files.writeText(`${root}/project.md`, projectDocument);
  return {
    files, repository,
    arm: (): void => { armed = true; },
    reopen: async () => {
      assert.notEqual(interrupted, null, 'the selected swap boundary must be captured');
      const reopenedFiles = createMemoryFileStore();
      for (const file of interrupted!) await reopenedFiles.writeBytes(file.path, file.bytes);
      return { files: reopenedFiles, repository: createFileNovelRepository(reopenedFiles) };
    },
  };
};

const retainedChapterContents = async (files: FileStore): Promise<Array<string | null>> =>
  Promise.all((await files.list(`${base}/.retained/p`)).map(id =>
    files.readText(`${base}/.retained/p/${id}/${chapterPath}`)));

for (const kind of ['native', 'public'] as const) {
  for (const interruption of ['backup', 'install'] as const) {
    test(`${kind} replacement survives process death after ${interruption} without losing the prior tree`, async () => {
      const fixture = await setup(interruption);
      const backup = await fixture.repository.nativeBackupSnapshot('p');
      const native = makeNovelNativeBackupImport(backup.metadata, backup.files);
      const plan = await fixture.repository.publicExportPlan('p');
      await fixture.repository.updateProject('p', project => ({ ...project,
        chapters: project.chapters.map(chapter => ({ ...chapter, content: '替换前正文' })) }));
      fixture.arm();
      if (kind === 'native') {
        await fixture.repository.installNativeBackup(native, await fixture.repository.inspectNativeRestore(native));
      } else {
        await fixture.repository.installWorkspacePlan(plan, await fixture.repository.inspectWorkspaceRestore(plan));
      }
      const reopened = await fixture.reopen();
      const inventory = await reopened.repository.listProjectInventory();
      assert.deepEqual(inventory.failures, []);
      assert.equal(inventory.projects.length, 1);
      assert.equal(inventory.projects[0].chapters[0].content,
        interruption === 'backup' ? '替换前正文' : '已保存正文');
      assert.equal(await reopened.files.readText(`${root}/project.md`), projectDocument);
      assert.equal(await reopened.files.exists(`${base}/.staging/p`), false);
      if (interruption === 'install') {
        assert.ok((await retainedChapterContents(reopened.files)).includes('替换前正文'));
      }
    });
  }
}

for (const interruption of ['backup', 'install'] as const) {
  test(`head recovery survives process death after ${interruption} and retains damaged original bytes`, async () => {
    const fixture = await setup(interruption);
    await fixture.files.writeText(`${root}/${chapterPath}`, '损坏现场');
    const preview = await fixture.repository.inspectProjectRecovery('p');
    assert.equal(preview?.source, 'head_snapshot');
    fixture.arm();
    await fixture.repository.restoreProjectRecovery(preview!);
    const reopened = await fixture.reopen();
    const inventory = await reopened.repository.listProjectInventory();
    assert.deepEqual(inventory.failures, []);
    assert.equal(inventory.projects.length, 1);
    assert.equal(inventory.projects[0].chapters[0].content, '已保存正文');
    assert.equal(await reopened.files.readText(`${root}/project.md`), projectDocument);
    assert.ok((await retainedChapterContents(reopened.files)).includes('损坏现场'));
  });
}

test('head recovery preserves existing project.md and only creates a placeholder when it is missing', async () => {
  for (const documentExists of [true, false]) {
    const fixture = await setup('backup');
    if (!documentExists) await fixture.files.delete(`${root}/project.md`);
    await fixture.files.writeText(`${root}/${chapterPath}`, '损坏现场');
    const preview = await fixture.repository.inspectProjectRecovery('p');
    await fixture.repository.restoreProjectRecovery(preview!);
    assert.equal(await fixture.files.readText(`${root}/project.md`),
      documentExists ? projectDocument : '# 小说\n');
    assert.equal((await fixture.repository.loadProject('p')).chapters[0].content, '已保存正文');
  }
});

test('trash recovery preserves both the damaged live tree and the earlier valid backup', async () => {
  const fixture = await setup('backup');
  for (const file of await snapshotFiles(fixture.files, root)) {
    await fixture.files.writeBytes(file.path.replace(root, `${base}/.trash/p`), file.bytes);
  }
  await fixture.files.writeText(`${root}/${chapterPath}`, '损坏现场');
  const preview = await fixture.repository.inspectProjectRecovery('p');
  assert.equal(preview?.source, 'trash');
  fixture.arm();
  await fixture.repository.restoreProjectRecovery(preview!);
  for (const state of [fixture, await fixture.reopen()]) {
    assert.equal((await state.repository.loadProject('p')).chapters[0].content, '已保存正文');
    const retained = await retainedChapterContents(state.files);
    assert.ok(retained.includes('损坏现场'));
    assert.ok(retained.includes('已保存正文'));
  }
});

test('startup preserves both trees when a damaged backup also has an invalid staged replacement', async () => {
  const fixture = await setup('backup');
  await fixture.files.writeText(`${root}/${chapterPath}`, '损坏现场');
  const preview = await fixture.repository.inspectProjectRecovery('p');
  fixture.arm();
  await fixture.repository.restoreProjectRecovery(preview!);
  const reopened = await fixture.reopen();
  await reopened.files.writeText(`${base}/.staging/p/.amber/project-state.json`, '{broken');
  const inventory = await reopened.repository.listProjectInventory();
  assert.equal(inventory.projects.length, 0);
  assert.equal(inventory.failures.length, 1);
  assert.equal(await reopened.files.exists(root), false);
  assert.equal(await reopened.files.readText(`${base}/.trash/p/${chapterPath}`), '损坏现场');
  assert.equal(await reopened.files.readText(`${base}/.staging/p/.amber/project-state.json`), '{broken');
});
