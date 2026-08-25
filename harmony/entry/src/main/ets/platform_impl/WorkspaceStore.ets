// WorkspaceStore — workspace 子系统 entry 平台实现(D-124)
// Android 锚点:
//   WorkspaceManager.kt:23(mirrorDir = filesDir/amberagent/workspace-mirror)
//   WorkspaceManager.kt 全文(SAF DocumentFile 树 + POSIX 镜像双层;
//     HarmonyOS 无 SAF → 镜像目录即 workspace,登记偏差;
//     WorkspaceState.configured 恒 true,'No AmberAgent workspace selected'
//     不可达,登记)
// 端口实现:WorkspaceFsPort → @kit.CoreFileKit fileIo 同步 API
//   (accessSync/statSync/listFileSync/mkdirSync/openSync/readSync/writeSync/
//    renameSync/copyFileSync/unlinkSync/rmdirSync)
// 偏差登记:
//   - DocumentFile.type → 扩展名表 mime(域层 textMimeTypeForPath,登记)
//   - SAF ↔ mirror 双向同步/withMirrorSync/copyUriToUploads → P1 登记
//     (随文件 sheet/terminal 切片)

import { fileIo } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { util } from '@kit.ArkTS';
import type { Context } from '@kit.AbilityKit';
import {
  PosixWorkspaceManager,
} from '@amber/chat-domain';
import type {
  WorkspaceFsPort, WorkspaceFsEntry,
} from '@amber/chat-domain';

const TAG: string = 'WorkspaceStore';
const DOMAIN: number = 0xA124;

const WORKSPACE_MIRROR: string = 'amberagent/workspace-mirror';

export const workspaceRootAbs = (): string => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  return `${ctx.filesDir}/${WORKSPACE_MIRROR}`;
};

// ===== fileIo 同步原语(与 SkillStore.ets 同形) =====

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

// ===== WorkspaceFsPort(fileIo 同步) =====

export const createEntryWorkspaceFsPort = (): WorkspaceFsPort => ({
  exists: existsSync,
  isDirectory: isDirectorySync,
  isFile: (abs: string): boolean => {
    try {
      if (!fileIo.accessSync(abs)) return false;
      return fileIo.statSync(abs).isFile();
    } catch (_e) {
      return false;
    }
  },
  listNames: (abs: string): WorkspaceFsEntry[] =>
    fileIo.listFileSync(abs).map((name: string): WorkspaceFsEntry => {
      const full: string = `${abs}/${name}`;
      const dir: boolean = isDirectorySync(full);
      let sizeBytes: number | null = null;
      if (!dir) {
        try {
          sizeBytes = fileIo.statSync(full).size;
        } catch (_e) {
          sizeBytes = null;
        }
      }
      return { name, directory: dir, sizeBytes };
    }),
  readText: (abs: string): string => {
    const file = fileIo.openSync(abs, fileIo.OpenMode.READ_ONLY);
    try {
      const size: number = fileIo.statSync(file.fd).size;
      const buf: ArrayBuffer = new ArrayBuffer(size);
      fileIo.readSync(file.fd, buf);
      return util.TextDecoder.create('utf-8').decodeToString(new Uint8Array(buf));
    } finally {
      fileIo.closeSync(file);
    }
  },
  writeText: (abs: string, content: string, append: boolean): void => {
    const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE |
      (append ? fileIo.OpenMode.APPEND : fileIo.OpenMode.TRUNC);
    const file = fileIo.openSync(abs, mode);
    try {
      fileIo.writeSync(file.fd, content);
    } finally {
      fileIo.closeSync(file);
    }
  },
  // D-125:二进制读写(WorkspaceArtifactTools 载体)
  readBytes: (abs: string): Uint8Array => {
    const file = fileIo.openSync(abs, fileIo.OpenMode.READ_ONLY);
    try {
      const size: number = fileIo.statSync(file.fd).size;
      const buf: ArrayBuffer = new ArrayBuffer(size);
      fileIo.readSync(file.fd, buf);
      return new Uint8Array(buf);
    } finally {
      fileIo.closeSync(file);
    }
  },
  writeBytes: (abs: string, bytes: Uint8Array, append: boolean): void => {
    const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE |
      (append ? fileIo.OpenMode.APPEND : fileIo.OpenMode.TRUNC);
    const file = fileIo.openSync(abs, mode);
    try {
      // bytes 可能是 subarray(非零 byteOffset/短于 backing buffer)——必须按视图范围
      // 切片,否则会写出整个 backing buffer(zip stored 条目即为此路径)
      const exact: ArrayBuffer = (bytes.buffer as ArrayBuffer)
        .slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength);
      fileIo.writeSync(file.fd, exact);
    } finally {
      fileIo.closeSync(file);
    }
  },
  fileSize: (abs: string): number => fileIo.statSync(abs).size,
  mkdirs: (abs: string): void => {
    if (isDirectorySync(abs)) return;
    fileIo.mkdirSync(abs, true);
  },
  rename: (fromAbs: string, toAbs: string): void => {
    fileIo.renameSync(fromAbs, toAbs);
  },
  copyFile: (fromAbs: string, toAbs: string): void => {
    fileIo.copyFileSync(fromAbs, toAbs);
  },
  deleteRecursively: deleteRecursivelySync,
});

// ===== PosixWorkspaceManager 装配(根 = 镜像目录) =====

export const createEntryWorkspaceManager = (): PosixWorkspaceManager =>
  new PosixWorkspaceManager({
    port: createEntryWorkspaceFsPort(),
    rootAbs: workspaceRootAbs(),
  });
