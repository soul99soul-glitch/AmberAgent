import type { FileStore } from '../platform/files.ts';
import { joinPath } from '../platform/files.ts';
import type { NovelProject } from './models.ts';
import type { NovelWorkspaceArchiveFile } from './workspace_exchange.ts';
import type { NovelNativeBackupMetadata } from './native_backup.ts';

export interface NovelProjectFailure { id: string; name: string; error: string; }
export interface NovelProjectInventory { projects: NovelProject[]; failures: NovelProjectFailure[]; }
export interface NovelProjectRecoveryPreview {
  projectId: string; title: string; source: 'trash' | 'head_snapshot'; sourceToken: string;
}
export interface NovelNativeRestorePreview {
  projectId: string; title: string; replaceExisting: boolean; sourceToken: string;
}
export interface NovelNativeBackupSnapshot {
  metadata: NovelNativeBackupMetadata; files: NovelWorkspaceArchiveFile[];
}

export const workspaceStorageToken = (files: NovelWorkspaceArchiveFile[]): string => {
  const ordered: NovelWorkspaceArchiveFile[] = files.slice().sort(
    (left: NovelWorkspaceArchiveFile, right: NovelWorkspaceArchiveFile): number =>
      left.path.localeCompare(right.path),
  );
  let a: number = 0x811c9dc5;
  let b: number = 0x9e3779b9;
  const mix = (value: number): void => {
    a = Math.imul(a ^ value, 0x01000193) >>> 0;
    b = Math.imul(b ^ value, 0x85ebca6b) >>> 0;
  };
  for (let i: number = 0; i < ordered.length; i++) {
    const path: string = `${ordered[i].path}\n`;
    for (let j: number = 0; j < path.length; j++) mix(path.charCodeAt(j));
    const bytes: Uint8Array = ordered[i].bytes;
    for (let j: number = 0; j < bytes.length; j++) mix(bytes[j]);
  }
  return a.toString(16).padStart(8, '0') + b.toString(16).padStart(8, '0');
};


export const writeWorkspaceStorageFiles = async (
  fileStore: FileStore, root: string, files: NovelWorkspaceArchiveFile[],
): Promise<void> => {
  await fileStore.deleteTree(root);
  for (let i: number = 0; i < files.length; i++) {
    await fileStore.writeBytes(joinPath(root, files[i].path), files[i].bytes);
  }
};

// Old bytes remain recoverable after an explicitly confirmed replacement.
export const swapWorkspaceStorageStage = async (
  fileStore: FileStore, stage: string, live: string, retained: string,
): Promise<void> => {
  const existed: boolean = await fileStore.exists(live);
  if (existed) await fileStore.rename(live, retained);
  try {
    await fileStore.rename(stage, live);
  } catch (error) {
    if (existed && !await fileStore.exists(live)) await fileStore.rename(retained, live);
    throw error;
  }
};
