// managed_files — FilesRepository 跟踪(D-086)
//
// Android 基准:
//   - ManagedFileEntity.kt(全文 32 行:managed_files 表,relative_path 唯一索引,folder 索引)
//   - ManagedFileDAO.kt(全文 36 行:insert REPLACE 返回 rowid;update by id;
//     getById/getByPath;listByFolder ORDER created_at DESC;deleteById/Path/Folder 返回行数)
//   - FilesRepository.kt(全文 30 行:insert 返回 copy(id=rowid);其余直通)
//   - FilesManager.kt trackUploadFile(:482-505)/deleteChatFiles(:263-301)/
//     FileFolders(:649-656)
// 语义钉住:
//   - Room @Insert(REPLACE) + autogen id:entity.id=0 → 新 rowid(max+1,空表→1);
//     relative_path 唯一冲突 → 旧行删除换新 id;entity.id≠0 → 按 id 替换
//   - @Update 未命中 = 0 行(no-op);delete 返回受影响行数
//   - trackUploadFile:已存在同 relativePath → 跳过;appScope.launch+runCatching 吞错
//     → 调用侧 fire-and-forget(HAR 函数本身返回布尔供测试)
//   - deleteChatFiles:仅 file: 前缀 uri;relativePath 须以 'upload/' 开头;
//     文件删除与库删除各自 runCatching 独立吞错;File.toUri 相对路径解析在调用侧

// ===== FileFolders(FilesManager.kt:649-656)=====
export const FILE_FOLDERS_UPLOAD: string = 'upload';
export const FILE_FOLDERS_SKILLS: string = 'skills';
export const FILE_FOLDERS_CHAT_IMAGES: string = 'chat_images';
export const FILE_FOLDERS_IMAGES: string = 'images';

// ===== ManagedFileEntity(ManagedFileEntity.kt:18-32)=====
export interface ManagedFileEntity {
  id: number; // Long,autoGenerate;0 = 未分配
  folder: string;
  relativePath: string;
  displayName: string;
  mimeType: string;
  sizeBytes: number;
  createdAt: number;
  updatedAt: number;
}

export const makeManagedFileEntity = (
  opts: Partial<ManagedFileEntity>
    & Pick<ManagedFileEntity, 'folder' | 'relativePath' | 'displayName' | 'mimeType'>,
): ManagedFileEntity => ({
  id: opts.id ?? 0,
  folder: opts.folder,
  relativePath: opts.relativePath,
  displayName: opts.displayName,
  mimeType: opts.mimeType,
  sizeBytes: opts.sizeBytes ?? 0,
  createdAt: opts.createdAt ?? 0,
  updatedAt: opts.updatedAt ?? 0,
});

// ===== DAO 端口(ManagedFileDAO.kt 查询语义)=====
export interface ManagedFileDaoPort {
  // OnConflictStrategy.REPLACE;返回新 rowid
  insert: (file: ManagedFileEntity) => Promise<number>;
  // @Update by 主键;未命中 no-op
  update: (file: ManagedFileEntity) => Promise<void>;
  getById: (id: number) => Promise<ManagedFileEntity | null>;
  getByPath: (relativePath: string) => Promise<ManagedFileEntity | null>;
  // ORDER BY created_at DESC(Flow → 快照 Promise;观察 UI = P1)
  listByFolder: (folder: string) => Promise<ManagedFileEntity[]>;
  deleteById: (id: number) => Promise<number>;
  deleteByPath: (relativePath: string) => Promise<number>;
  deleteByFolder: (folder: string) => Promise<number>;
}

// ===== FilesRepository(FilesRepository.kt 全文)=====
export interface FilesRepository {
  insert: (file: ManagedFileEntity) => Promise<ManagedFileEntity>;
  update: (file: ManagedFileEntity) => Promise<void>;
  getById: (id: number) => Promise<ManagedFileEntity | null>;
  getByPath: (relativePath: string) => Promise<ManagedFileEntity | null>;
  listByFolder: (folder: string) => Promise<ManagedFileEntity[]>;
  deleteById: (id: number) => Promise<number>;
  deleteByPath: (relativePath: string) => Promise<number>;
  deleteByFolder: (folder: string) => Promise<number>;
}

export const createFilesRepository = (dao: ManagedFileDaoPort): FilesRepository => ({
  // insert(:13-16)— 返回 copy(id = 新 rowid)
  insert: async (file: ManagedFileEntity): Promise<ManagedFileEntity> => {
    const id: number = await dao.insert(file);
    return { ...file, id };
  },
  update: (file: ManagedFileEntity): Promise<void> => dao.update(file),
  getById: (id: number): Promise<ManagedFileEntity | null> => dao.getById(id),
  getByPath: (relativePath: string): Promise<ManagedFileEntity | null> =>
    dao.getByPath(relativePath),
  listByFolder: (folder: string): Promise<ManagedFileEntity[]> => dao.listByFolder(folder),
  deleteById: (id: number): Promise<number> => dao.deleteById(id),
  deleteByPath: (relativePath: string): Promise<number> => dao.deleteByPath(relativePath),
  deleteByFolder: (folder: string): Promise<number> => dao.deleteByFolder(folder),
});

// ===== trackUploadFile(FilesManager.kt:482-505)=====
// 已存在同 relativePath → 直接返回 false;否则 insert(folder=upload,createdAt=updatedAt=now)
export const trackUploadFile = async (
  repository: FilesRepository,
  input: { relativePath: string; displayName: string; mimeType: string; sizeBytes: number },
  now: number = Date.now(),
): Promise<boolean> => {
  const existing: ManagedFileEntity | null = await repository.getByPath(input.relativePath);
  if (existing !== null) return false;
  await repository.insert(makeManagedFileEntity({
    folder: FILE_FOLDERS_UPLOAD,
    relativePath: input.relativePath,
    displayName: input.displayName,
    mimeType: input.mimeType,
    sizeBytes: input.sizeBytes,
    createdAt: now,
    updatedAt: now,
  }));
  return true;
};

// ===== deleteChatFiles(FilesManager.kt:263-301)=====
export interface DeleteChatFilesDeps {
  // uri → filesDir 内相对路径;非 filesDir 内/解析失败 → null(getRelativePathInFilesDir)
  toRelativePath: (uri: string) => string | null;
  fileExists: (relativePath: string) => Promise<boolean>;
  deleteFile: (relativePath: string) => Promise<void>;
  deleteByPath: (relativePath: string) => Promise<number>;
}

// 编排忠实:file: 前缀过滤 → 相对路径须 upload/ 开头 → 各自 runCatching 删除文件
//   → 收集到的路径逐个 runCatching deleteByPath;返回实际出库的路径集(测试锚)
export const deleteChatFiles = async (
  deps: DeleteChatFilesDeps, uris: string[],
): Promise<string[]> => {
  const relativePaths: string[] = [];
  for (const uri of uris) {
    if (!uri.startsWith('file:')) continue;
    try {
      const relativePath: string | null = deps.toRelativePath(uri);
      if (relativePath === null || !relativePath.startsWith(`${FILE_FOLDERS_UPLOAD}/`)) {
        continue;
      }
      if (relativePaths.indexOf(relativePath) < 0) {
        relativePaths.push(relativePath);
      }
      if (await deps.fileExists(relativePath)) {
        await deps.deleteFile(relativePath);
      }
    } catch {
      // Log.e 吞错(:283-286)
    }
  }
  for (const path of relativePaths) {
    try {
      await deps.deleteByPath(path);
    } catch {
      // Log.e 吞错(:291-295)
    }
  }
  return relativePaths;
};
