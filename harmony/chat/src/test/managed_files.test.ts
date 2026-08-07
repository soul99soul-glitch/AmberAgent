// managed_files 测试(D-086)
// 锚点:ManagedFileDAO.kt / FilesRepository.kt / FilesManager.kt(trackUploadFile/deleteChatFiles)
import assert from 'node:assert/strict';
import test from 'node:test';
import type {
  DeleteChatFilesDeps, FilesRepository, ManagedFileDaoPort, ManagedFileEntity,
} from '../main/ets/chat/managed_files.ts';
import {
  createFilesRepository, deleteChatFiles, makeManagedFileEntity, trackUploadFile,
  conversationFileUris, removedFileUris,
  FILE_FOLDERS_UPLOAD,
} from '../main/ets/chat/managed_files.ts';
import { makeConversation, makeMessageNode } from '../main/ets/chat/conversation.ts';
import { makeUIMessage } from '../main/ets/chat/message.ts';

const entity = (over: Partial<ManagedFileEntity>): ManagedFileEntity => makeManagedFileEntity({
  folder: FILE_FOLDERS_UPLOAD,
  relativePath: 'upload/a.png',
  displayName: 'a.png',
  mimeType: 'image/png',
  sizeBytes: 100,
  createdAt: 1000,
  updatedAt: 1000,
  ...over,
});

// Room 语义的内存 DAO(REPLACE + AUTOINCREMENT 高水位 rowid + relative_path 唯一)
interface DaoHarness {
  dao: ManagedFileDaoPort;
  rows: ManagedFileEntity[];
  nextId: number;
}

const daoHarness = (): DaoHarness => {
  const h: DaoHarness = { rows: [], nextId: 1, dao: {
    insert: async (file: ManagedFileEntity): Promise<number> => {
      // relative_path 唯一冲突 → 删旧行(REPLACE 语义)
      const byPath: number = h.rows.findIndex(
        (r: ManagedFileEntity): boolean => r.relativePath === file.relativePath);
      if (byPath >= 0 && h.rows[byPath].id !== file.id) h.rows.splice(byPath, 1);
      if (file.id === 0) {
        // AUTOINCREMENT:sqlite_sequence 高水位,REPLACE 删行后不复用
        const id: number = h.nextId;
        h.nextId += 1;
        h.rows.push({ ...file, id });
        return id;
      }
      const byId: number = h.rows.findIndex(
        (r: ManagedFileEntity): boolean => r.id === file.id);
      if (byId >= 0) h.rows[byId] = file;
      else h.rows.push(file);
      return file.id;
    },
    update: async (file: ManagedFileEntity): Promise<void> => {
      const index: number = h.rows.findIndex(
        (r: ManagedFileEntity): boolean => r.id === file.id);
      if (index >= 0) h.rows[index] = file;
    },
    getById: async (id: number): Promise<ManagedFileEntity | null> =>
      h.rows.find((r: ManagedFileEntity): boolean => r.id === id) ?? null,
    getByPath: async (relativePath: string): Promise<ManagedFileEntity | null> =>
      h.rows.find((r: ManagedFileEntity): boolean => r.relativePath === relativePath) ?? null,
    listByFolder: async (folder: string): Promise<ManagedFileEntity[]> =>
      h.rows.filter((r: ManagedFileEntity): boolean => r.folder === folder)
        .sort((a: ManagedFileEntity, b: ManagedFileEntity): number => b.createdAt - a.createdAt),
    deleteById: async (id: number): Promise<number> => {
      const before: number = h.rows.length;
      h.rows = h.rows.filter((r: ManagedFileEntity): boolean => r.id !== id);
      return before - h.rows.length;
    },
    deleteByPath: async (relativePath: string): Promise<number> => {
      const before: number = h.rows.length;
      h.rows = h.rows.filter((r: ManagedFileEntity): boolean => r.relativePath !== relativePath);
      return before - h.rows.length;
    },
    deleteByFolder: async (folder: string): Promise<number> => {
      const before: number = h.rows.length;
      h.rows = h.rows.filter((r: ManagedFileEntity): boolean => r.folder !== folder);
      return before - h.rows.length;
    },
  } };
  return h;
};

test('repository.insert: id=0 → 新 rowid(max+1,空表→1)+ 返回 copy(id)', async () => {
  const h: DaoHarness = daoHarness();
  const repo: FilesRepository = createFilesRepository(h.dao);
  const first: ManagedFileEntity = await repo.insert(entity({}));
  assert.equal(first.id, 1);
  const second: ManagedFileEntity = await repo.insert(entity({ relativePath: 'upload/b.png' }));
  assert.equal(second.id, 2);
  assert.equal(h.rows.length, 2);
});

test('dao.insert REPLACE: relative_path 唯一冲突 → 删旧换新 id;id≠0 → 按 id 替换', async () => {
  const h: DaoHarness = daoHarness();
  const repo: FilesRepository = createFilesRepository(h.dao);
  await repo.insert(entity({ displayName: 'old.png' }));
  // 同 relativePath 新插入 → 旧行删除,新行 id=2
  const replaced: ManagedFileEntity = await repo.insert(entity({ displayName: 'new.png' }));
  assert.equal(replaced.id, 2);
  assert.equal(h.rows.length, 1);
  assert.equal(h.rows[0].displayName, 'new.png');
  // 显式 id 替换
  await repo.insert(entity({ id: 2, displayName: 'explicit.png' }));
  assert.equal(h.rows.length, 1);
  assert.equal(h.rows[0].displayName, 'explicit.png');
  assert.equal(h.rows[0].id, 2);
});

test('repository: update by id(未命中 no-op)/ getById / getByPath / listByFolder DESC', async () => {
  const h: DaoHarness = daoHarness();
  const repo: FilesRepository = createFilesRepository(h.dao);
  await repo.insert(entity({ relativePath: 'upload/1.png', createdAt: 1000 }));
  await repo.insert(entity({ relativePath: 'upload/2.png', createdAt: 3000 }));
  await repo.insert(entity({ relativePath: 'chat_images/3.png', folder: 'chat_images', createdAt: 2000 }));
  // update 命中
  await repo.update(entity({ id: 1, displayName: 'renamed.png', updatedAt: 5000 }));
  assert.equal((await repo.getById(1))!.displayName, 'renamed.png');
  // update 未命中 → no-op
  await repo.update(entity({ id: 99 }));
  assert.equal(h.rows.length, 3);
  assert.equal(await repo.getById(99), null);
  assert.equal((await repo.getByPath('upload/2.png'))!.id, 2);
  assert.equal(await repo.getByPath('upload/none.png'), null);
  // listByFolder ORDER created_at DESC
  const uploads: ManagedFileEntity[] = await repo.listByFolder('upload');
  assert.deepEqual(uploads.map((r: ManagedFileEntity): number => r.id), [2, 1]);
});

test('repository: deleteById/Path/Folder 返回受影响行数', async () => {
  const h: DaoHarness = daoHarness();
  const repo: FilesRepository = createFilesRepository(h.dao);
  await repo.insert(entity({ relativePath: 'upload/1.png' }));
  await repo.insert(entity({ relativePath: 'upload/2.png' }));
  await repo.insert(entity({ relativePath: 'skills/3.png', folder: 'skills' }));
  assert.equal(await repo.deleteById(1), 1);
  assert.equal(await repo.deleteById(1), 0);
  assert.equal(await repo.deleteByPath('upload/2.png'), 1);
  assert.equal(await repo.deleteByPath('upload/none.png'), 0);
  assert.equal(await repo.deleteByFolder('skills'), 1);
  assert.equal(await repo.deleteByFolder('upload'), 0);
});

test('trackUploadFile: 已存在 → false 不插;新路径 → insert(upload 文件夹,now 双写)', async () => {
  const h: DaoHarness = daoHarness();
  const repo: FilesRepository = createFilesRepository(h.dao);
  const inserted: boolean = await trackUploadFile(repo, {
    relativePath: 'upload/x.png', displayName: 'image.png',
    mimeType: 'image/png', sizeBytes: 42,
  }, 7777);
  assert.equal(inserted, true);
  const row: ManagedFileEntity = (await repo.getByPath('upload/x.png'))!;
  assert.equal(row.folder, 'upload');
  assert.equal(row.createdAt, 7777);
  assert.equal(row.updatedAt, 7777);
  assert.equal(row.sizeBytes, 42);
  // 第二次同路径 → 跳过(FilesManager.kt:486-489)
  assert.equal(await trackUploadFile(repo, {
    relativePath: 'upload/x.png', displayName: 'image.png',
    mimeType: 'image/png', sizeBytes: 42,
  }, 8888), false);
  assert.equal((await repo.getByPath('upload/x.png'))!.createdAt, 7777);
});

test('deleteChatFiles: file: 前缀 + upload/ 门 + 去重 + 各自吞错 + 返回出库路径', async () => {
  const deletedFiles: string[] = [];
  const deletedRows: string[] = [];
  const deps: DeleteChatFilesDeps = {
    toRelativePath: (uri: string): string | null => {
      if (uri.indexOf('/files/') < 0) return null;
      return uri.substring(uri.indexOf('/files/') + 7);
    },
    fileExists: async (p: string): Promise<boolean> => p !== 'upload/missing.png',
    deleteFile: async (p: string): Promise<void> => {
      if (p === 'upload/boom.png') throw new Error('io');
      deletedFiles.push(p);
    },
    deleteByPath: async (p: string): Promise<number> => {
      deletedRows.push(p);
      return 1;
    },
  };
  const out: string[] = await deleteChatFiles(deps, [
    'content://external/ignore',          // 非 file: → 跳过
    'file:///data/files/upload/a.png',    // 正常
    'file:///data/files/upload/a.png',    // 重复 → 去重
    'file:///data/files/chat_images/c.png', // 非 upload/ → 跳过
    'file:///data/files/upload/../outside.png', // 路径穿越 → 跳过
    'file:///data/files/upload//empty.png', // 空路径段 → 跳过
    'file:///other/x.png',                // 无法解析 → 跳过
    'file:///data/files/upload/missing.png', // 文件不存在 → 仅出库
    'file:///data/files/upload/boom.png', // 删文件抛错 → 吞错仍收集并出库
  ]);
  assert.deepEqual(out, ['upload/a.png', 'upload/missing.png', 'upload/boom.png']);
  // 文件删除按 URI 出现次数(relativePaths 是 Set 但删文件在 forEach 内逐次,
  //   FilesManager.kt:270-282;重复 URI 第二次删除 = File.delete 幂等 false)
  assert.deepEqual(deletedFiles, ['upload/a.png', 'upload/a.png']);
  assert.deepEqual(deletedRows, ['upload/a.png', 'upload/missing.png', 'upload/boom.png']);
});

// ===== conversationFileUris(D-096;Conversation.kt:33-37 files getter +
//   :172-184 collectAllParts/fileUri)=====

test('conversationFileUris: file:// 的 image/video/audio/document 全收,其余跳过', () => {
  const conv = makeConversation('c1', [
    makeMessageNode([
      makeUIMessage('user', [
        { type: 'image', url: 'file:///data/files/upload/a.png', metadata: null },
        { type: 'image', url: 'https://cdn/x.png', metadata: null },     // 非 file:// → 跳
        { type: 'text', text: 'file:///data/files/upload/not-a-part.txt', metadata: null }, // text → 跳
        { type: 'document', url: 'file:///data/files/upload/d.txt', fileName: 'd.txt', mime: 'text/*', metadata: null },
        { type: 'video', url: 'file:///data/files/upload/v.mp4', mime: 'video/mp4', metadata: null },
        { type: 'audio', url: 'file:///data/files/upload/a.mp3', fileName: 'a.mp3', mime: 'audio/mpeg', metadata: null },
      ]),
    ]),
  ]);
  assert.deepEqual(conversationFileUris(conv), [
    'file:///data/files/upload/a.png',
    'file:///data/files/upload/d.txt',
    'file:///data/files/upload/v.mp4',
    'file:///data/files/upload/a.mp3',
  ]);
});

test('conversationFileUris: tool output 嵌套递归展开 + 不去重(列表语义)', () => {
  const conv = makeConversation('c1', [
    makeMessageNode([
      makeUIMessage('assistant', [
        {
          type: 'tool', toolCallId: 'c1', toolName: 'read_file', input: '{}',
          output: [
            { type: 'image', url: 'file:///data/files/upload/inner.png', metadata: null },
            {
              type: 'tool', toolCallId: 'c2', toolName: 'nested', input: '{}',
              output: [
                { type: 'image', url: 'file:///data/files/upload/deep.png', metadata: null },
              ],
              approvalState: { type: 'auto' }, metadata: null,
            },
          ],
          approvalState: { type: 'auto' }, metadata: null,
        },
      ]),
      // 多 message 节点(分支)全部纳入
      makeUIMessage('user', [
        { type: 'image', url: 'file:///data/files/upload/inner.png', metadata: null }, // 重复不去重
      ]),
    ]),
  ]);
  assert.deepEqual(conversationFileUris(conv), [
    'file:///data/files/upload/inner.png',
    'file:///data/files/upload/deep.png',
    'file:///data/files/upload/inner.png',
  ]);
});

test('conversationFileUris: 空会话 → []', () => {
  assert.deepEqual(conversationFileUris(makeConversation('c1', [])), []);
});

// ===== removedFileUris(D-097;ChatService.kt:1903-1912 checkFilesDelete)=====

test('removedFileUris: old 有 new 无 → 移除项;顺序保持 old 序', () => {
  assert.deepEqual(
    removedFileUris(
      ['file:///f/upload/a.png', 'file:///f/upload/b.png', 'file:///f/upload/c.png'],
      ['file:///f/upload/b.png'],
    ),
    ['file:///f/upload/a.png', 'file:///f/upload/c.png'],
  );
});

test('removedFileUris: 重复项不去重(Android filter 列表语义)', () => {
  assert.deepEqual(
    removedFileUris(
      ['file:///f/upload/a.png', 'file:///f/upload/a.png'],
      [],
    ),
    ['file:///f/upload/a.png', 'file:///f/upload/a.png'],
  );
});

test('removedFileUris: 无移除 → [](new 含全部/old 空)', () => {
  assert.deepEqual(removedFileUris(['file:///f/a.png'], ['file:///f/a.png']), []);
  assert.deepEqual(removedFileUris([], ['file:///f/a.png']), []);
});
