// SkillStore — skills 子系统 entry 平台实现(D-123)
// Android 锚点:
//   SkillManager.kt:22-26(getSkillsDir = filesDir/skills;FileFolders.SKILLS =
//     "skills",FilesManager.kt:651)
//   WorkspaceManager.kt:23(mirrorDir = filesDir/amberagent/workspace-mirror;
//     SAF DocumentFile 层 HarmonyOS 无对应物 → POSIX 镜像目录直读,登记)
// 端口实现:
//   SkillFilePort → @kit.CoreFileKit fileIo 同步 API(accessSync/listFileSync/
//     statSync/mkdirSync/openSync/readSync/writeSync/unlinkSync/rmdirSync/
//     renameSync)
//   SkillWorkspacePort → 同一 fileIo,根 = workspace-mirror;异步签名用同步
//     实现包裹(域层 await 语义不变)
// 偏差登记:
//   - File.renameTo 平台依赖 → POSIX rename 语义(目标空目录/文件可覆盖,
//     非空目录失败;saveSkillFilesAtomically 的 backup 目录恒为空 → 可覆盖)
//   - File.mkdirs 失败返回 false(不抛)→ mkdirSync try/catch → false
// D-126 续:installBuiltinSkillsIfMissing(SkillManager.kt:66-87)= rawfile 打包
//   entry/src/main/resources/rawfile/builtin-skills/(4 件 SKILL.md 自 Android
//   assets 逐字拷贝);Context.assets.list/open → resourceManager
//   getRawFileListSync/getRawFileContentSync 同步读

import { fileIo } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { Context } from '@kit.AbilityKit';
import type {
  SkillFilePort, SkillDirEntry, SkillWorkspacePort, SkillWorkspaceEntry,
  BuiltinSkillAssetPort,
} from '@amber/chat-domain';

const TAG: string = 'SkillStore';
const DOMAIN: number = 0xA123;

const filesDir = (): string => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  return ctx.filesDir;
};

// ===== SkillFilePort(fileIo 同步) =====

const existsSync = (path: string): boolean => {
  try {
    return fileIo.accessSync(path);
  } catch (_e) {
    return false;
  }
};

const isDirectorySync = (path: string): boolean => {
  try {
    if (!fileIo.accessSync(path)) return false;
    return fileIo.statSync(path).isDirectory();
  } catch (_e) {
    return false;
  }
};

const mkdirsSync = (path: string): boolean => {
  try {
    if (isDirectorySync(path)) return false; // File.mkdirs():已存在 → false
    fileIo.mkdirSync(path, true);
    return true;
  } catch (e) {
    hilog.warn(DOMAIN, TAG, 'mkdirs failed %{public}s: %{public}s', path, String(e));
    return false;
  }
};

const deleteRecursivelySync = (path: string): boolean => {
  try {
    if (!existsSync(path)) return false;
    if (isDirectorySync(path)) {
      const names: string[] = fileIo.listFileSync(path);
      names.forEach((name: string): void => {
        deleteRecursivelySync(`${path}/${name}`);
      });
      fileIo.rmdirSync(path);
    } else {
      fileIo.unlinkSync(path);
    }
    return true;
  } catch (e) {
    hilog.warn(DOMAIN, TAG, 'deleteRecursively failed %{public}s: %{public}s', path, String(e));
    return false;
  }
};

const walkFilesSync = (root: string): string[] => {
  const out: string[] = [];
  const visit = (dir: string, prefix: string): void => {
    let names: string[] = [];
    try {
      names = fileIo.listFileSync(dir);
    } catch (_e) {
      return; // 不可读目录 → 空(File.walkTopDown 跳过权限失败子树,同语义)
    }
    names.sort();
    names.forEach((name: string): void => {
      const full: string = `${dir}/${name}`;
      const rel: string = prefix.length === 0 ? name : `${prefix}/${name}`;
      if (isDirectorySync(full)) {
        visit(full, rel);
      } else {
        out.push(rel);
      }
    });
  };
  visit(root, '');
  return out;
};

export const createEntrySkillFilePort = (): SkillFilePort => ({
  listDir: (path: string): SkillDirEntry[] => {
    try {
      if (!isDirectorySync(path)) return [];
      return fileIo.listFileSync(path).map((name: string): SkillDirEntry => ({
        name,
        isDirectory: isDirectorySync(`${path}/${name}`),
      }));
    } catch (_e) {
      return []; // File.listFiles() null → emptyList
    }
  },
  exists: existsSync,
  isDirectory: isDirectorySync,
  mkdirs: mkdirsSync,
  readBytes: (path: string): Uint8Array => {
    const file = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY);
    try {
      const size: number = fileIo.statSync(file.fd).size;
      const buf: ArrayBuffer = new ArrayBuffer(size);
      fileIo.readSync(file.fd, buf);
      return new Uint8Array(buf);
    } finally {
      fileIo.closeSync(file);
    }
  },
  writeText: (path: string, content: string): void => {
    const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE |
      fileIo.OpenMode.TRUNC;
    const file = fileIo.openSync(path, mode);
    try {
      fileIo.writeSync(file.fd, content);
    } finally {
      fileIo.closeSync(file);
    }
  },
  // D-126:二进制写(copyAssetDirectory input.copyTo(output) 载体)
  writeBytes: (path: string, bytes: Uint8Array): void => {
    const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE |
      fileIo.OpenMode.TRUNC;
    const file = fileIo.openSync(path, mode);
    try {
      // subarray 视图必须按 byteOffset/byteLength 切片,防写出整个 backing buffer
      const exact: ArrayBuffer = (bytes.buffer as ArrayBuffer)
        .slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
      fileIo.writeSync(file.fd, exact);
    } finally {
      fileIo.closeSync(file);
    }
  },
  deleteRecursively: deleteRecursivelySync,
  renameTo: (from: string, to: string): boolean => {
    try {
      if (!existsSync(from)) return false;
      fileIo.renameSync(from, to);
      return true;
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'rename %{public}s → %{public}s failed: %{public}s',
        from, to, String(e));
      return false;
    }
  },
  walkFiles: walkFilesSync,
});

// ===== SkillWorkspacePort(POSIX 镜像目录;SAF 层不移植,登记) =====

const WORKSPACE_MIRROR: string = 'amberagent/workspace-mirror';

const workspaceRoot = (): string => `${filesDir()}/${WORKSPACE_MIRROR}`;

// WorkspacePaths.normalize(WorkspacePaths.kt):'..' → 抛(Path traversal is not
//   allowed);'/workspace' 前缀剥离在域层已完成(:353)
const normalizeWorkspacePath = (path: string): string => {
  const parts: string[] = [];
  path.replace(/\\/g, '/').split('/').forEach((seg: string): void => {
    if (seg === '' || seg === '.') return;
    if (seg === '..') throw new Error(`Path traversal is not allowed: ${path}`);
    parts.push(seg);
  });
  return parts.join('/');
};

const workspaceAbs = (relative: string): string => {
  const rel: string = normalizeWorkspacePath(relative);
  return rel.length === 0 ? workspaceRoot() : `${workspaceRoot()}/${rel}`;
};

export const createEntrySkillWorkspacePort = (): SkillWorkspacePort => ({
  readBytes: (path: string): Promise<Uint8Array> => {
    const abs: string = workspaceAbs(path);
    if (!existsSync(abs) || isDirectorySync(abs)) {
      return Promise.reject(new Error(`Unable to read file: ${path}`));
    }
    const file = fileIo.openSync(abs, fileIo.OpenMode.READ_ONLY);
    try {
      const size: number = fileIo.statSync(file.fd).size;
      const buf: ArrayBuffer = new ArrayBuffer(size);
      fileIo.readSync(file.fd, buf);
      return Promise.resolve(new Uint8Array(buf));
    } finally {
      fileIo.closeSync(file);
    }
  },
  list: (dir: string): Promise<SkillWorkspaceEntry[]> => {
    const abs: string = workspaceAbs(dir);
    if (!isDirectorySync(abs)) {
      return Promise.reject(new Error(`Not a directory: ${dir}`));
    }
    const rel: string = normalizeWorkspacePath(dir);
    const entries: SkillWorkspaceEntry[] = fileIo.listFileSync(abs)
      .map((name: string): SkillWorkspaceEntry => ({
        path: rel.length === 0 ? name : `${rel}/${name}`,
        name,
        directory: isDirectorySync(`${abs}/${name}`),
      }));
    // WorkspaceManager.list 排序:目录优先,再按 name 小写(:49)
    entries.sort((a: SkillWorkspaceEntry, b: SkillWorkspaceEntry): number => {
      if (a.directory !== b.directory) return a.directory ? -1 : 1;
      const an: string = a.name.toLowerCase();
      const bn: string = b.name.toLowerCase();
      return an < bn ? -1 : an > bn ? 1 : 0;
    });
    return Promise.resolve(entries);
  },
});

// ===== BuiltinSkillAssetPort(rawfile 同步读;D-126) =====
// Context.assets.list(assetPath):目录 → 子名;文件 → [](copyAssetDirectory
//   判据)→ resourceManager.getRawFileListSync 同语义:目录 → 子名数组;
//   文件/不存在 → [](try/catch 归一,Android orEmpty() 同)
// Context.assets.open(assetPath) → getRawFileContentSync 一次性字节读

export const createEntryBuiltinSkillAssetPort = (): BuiltinSkillAssetPort => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  return {
    list: (path: string): string[] => {
      try {
        return ctx.resourceManager.getRawFileListSync(path);
      } catch (_e) {
        return []; // assets.list 文件路径/不存在 → null → orEmpty()
      }
    },
    readBytes: (path: string): Uint8Array =>
      ctx.resourceManager.getRawFileContentSync(path),
  };
};
