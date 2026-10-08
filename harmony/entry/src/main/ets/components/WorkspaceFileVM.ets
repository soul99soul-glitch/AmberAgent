// WorkspaceFileVM — workspace 文件视图模型(G6-S1,对齐 Android WorkspaceFileVM.kt:174 行)
// Android 基准: WorkspaceFileVM(VM + StateFlow: recent/currentPath/currentDirItems/loading)
//   refresh()/navigateTo()/navigateUp()/deleteFile()/shareableFile()
// Harmony 端口: WorkspaceStore.ets(createEntryWorkspaceManager → PosixWorkspaceManager)
//   fileIo 同步 API(exists/listNames/readText/readBytes/deleteRecursively)
//
// 本文件为纯 class(非 @Component),供 pages/组件 引入做状态管理与列表计算。
// ArkUI 规则: 无 as unknown / 无对象展开 / 无 any/unknown。

import { createEntryWorkspaceManager, workspaceRootAbs } from '../platform_impl/WorkspaceStore.ets';
import { normalizeWorkspacePath } from '@amber/chat-domain';
import type { WorkspaceEntry } from '@amber/chat-domain';
import { fileIo } from '@kit.CoreFileKit';

// ===== 文件信息(与 WorkspaceFileSheet/Preview 共享;导出供 pages 引用) =====
export interface WorkspaceFileInfo {
  name: string;
  path: string;
  size: number;          // 字节数(目录为 0)
  modifiedAt: string;    // ISO 时间字符串
  mimeType: string;      // 扩展名表 mime(域层 textMimeTypeForPath 对齐)
}

// 排序键
export type WorkspaceFileSortKey = 'name' | 'size' | 'date';

// 工厂(避免对象展开:全字段显式构造)
export function makeWorkspaceFileInfo(
  name: string, path: string, size: number,
  modifiedAt: string, mimeType: string,
): WorkspaceFileInfo {
  return {
    name: name, path: path, size: size,
    modifiedAt: modifiedAt, mimeType: mimeType,
  };
}

// 扩展名 → mime(对齐 Android shareMimeType / MimeTypeMap;无 SAF 时用扩展名表)
export function extOf(name: string): string {
  const idx: number = name.lastIndexOf('.');
  if (idx < 0 || idx === name.length - 1) return '';
  return name.substring(idx + 1).toLowerCase();
}

export function mimeTypeForPath(name: string): string {
  const ext: string = extOf(name);
  if (ext === 'md' || ext === 'markdown' || ext === 'txt' || ext === 'log') return 'text/plain';
  if (ext === 'json') return 'application/json';
  if (ext === 'csv') return 'text/csv';
  if (ext === 'html' || ext === 'htm') return 'text/html';
  if (ext === 'xml') return 'application/xml';
  if (ext === 'yml' || ext === 'yaml') return 'application/x-yaml';
  if (ext === 'png') return 'image/png';
  if (ext === 'jpg' || ext === 'jpeg') return 'image/jpeg';
  if (ext === 'gif') return 'image/gif';
  if (ext === 'webp') return 'image/webp';
  if (ext === 'pdf') return 'application/pdf';
  if (ext === 'zip') return 'application/zip';
  return 'application/octet-stream';
}

// 纯函数:过滤(不依赖 this,可在事件回调中调用)
export function searchFiles(files: WorkspaceFileInfo[], query: string): WorkspaceFileInfo[] {
  const q: string = query.trim().toLowerCase();
  if (q.length === 0) return files;
  return files.filter((f: WorkspaceFileInfo): boolean => {
    return f.name.toLowerCase().indexOf(q) >= 0
      || f.path.toLowerCase().indexOf(q) >= 0;
  });
}

// 纯函数:排序(返回新数组,不修改入参)
export function sortFiles(files: WorkspaceFileInfo[], key: WorkspaceFileSortKey): WorkspaceFileInfo[] {
  const arr: WorkspaceFileInfo[] = files.slice();
  if (key === 'name') {
    arr.sort((a: WorkspaceFileInfo, b: WorkspaceFileInfo): number =>
      a.name.toLowerCase().localeCompare(b.name.toLowerCase()));
  } else if (key === 'size') {
    arr.sort((a: WorkspaceFileInfo, b: WorkspaceFileInfo): number => b.size - a.size);
  } else {
    // 'date' — 降序(最新在前);modifiedAt 为 ISO 字符串,字典序等价时间序
    arr.sort((a: WorkspaceFileInfo, b: WorkspaceFileInfo): number =>
      b.modifiedAt.localeCompare(a.modifiedAt));
  }
  return arr;
}

// 格式化字节(对齐 Android formatSize)
export function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1024 * 1024) return `${Math.floor(bytes / 1024)}KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)}MB`;
}

// 格式化时间(对齐 Android formatTime;modifiedAt 为 ISO,这里转相对时间)
export function formatFileTime(iso: string): string {
  if (iso.length === 0) return '';
  const ts: number = Date.parse(iso);
  if (Number.isNaN(ts)) return iso;
  const diff: number = Date.now() - ts;
  if (diff < 60_000) return '刚刚';
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}分钟前`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}小时前`;
  const d: Date = new Date(ts);
  const mm: string = d.getMonth() + 1 < 10 ? `0${d.getMonth() + 1}` : `${d.getMonth() + 1}`;
  const dd: string = d.getDate() < 10 ? `0${d.getDate()}` : `${d.getDate()}`;
  const hh: string = d.getHours() < 10 ? `0${d.getHours()}` : `${d.getHours()}`;
  const mi: string = d.getMinutes() < 10 ? `0${d.getMinutes()}` : `${d.getMinutes()}`;
  return `${mm}-${dd} ${hh}:${mi}`;
}

// ===== 视图模型 =====
// 读取既有 Workspace 文件树，归档产物与 file_write 文件使用同一根目录。
export class WorkspaceFileVM {
  // 列表只提供文件；删除失败交给宿主显示，不能把失败伪装成成功。
  async deleteFile(path: string): Promise<void> {
    const normalized: string = normalizeWorkspacePath(path);
    if (normalized === '.') throw new Error('不能删除 Workspace 根目录');
    fileIo.unlinkSync(`${workspaceRootAbs()}/${normalized}`);
  }

  async loadFiles(): Promise<WorkspaceFileInfo[]> {
    const manager = createEntryWorkspaceManager();
    const root: string = workspaceRootAbs();
    const files: WorkspaceFileInfo[] = [];
    const visit = async (path: string): Promise<void> => {
      const entries: WorkspaceEntry[] = await manager.list(path);
      for (const entry of entries) {
        if (entry.directory) {
          await visit(entry.path);
        } else {
          const modifiedAt: string = new Date(fileIo.statSync(`${root}/${entry.path}`).mtime * 1000).toISOString();
          files.push(makeWorkspaceFileInfo(entry.name, entry.path, entry.sizeBytes ?? 0,
            modifiedAt, entry.mimeType ?? mimeTypeForPath(entry.name)));
        }
      }
    };
    await visit('.');
    return sortFiles(files, 'date');
  }
}
