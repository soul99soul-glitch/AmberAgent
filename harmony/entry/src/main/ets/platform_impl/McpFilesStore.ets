// McpFilesStore — McpFilesPort 的 entry 实现
//
// D-120:convertImageContentToFilePart(McpManager.kt:207-219)的落盘面:
// saveUploadFromBytes(bytes, displayName, mimeType) → upload/{uuid}.{ext}
// + trackUploadFile 登记(D-086 同路径)+ file:// uri;
// extensionFromMimeType = android.webkit.MimeTypeMap 等价小表。

import { fileIo } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { Context } from '@kit.AbilityKit';
import { newId, trackUploadFile, FILE_FOLDER_UPLOAD } from '@amber/chat-domain';
import type { McpFilesPort } from '@amber/chat-domain';
import { getFilesRepository } from './ManagedFileStore.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'Mcp.Files';

// MimeTypeMap.getExtensionFromMimeType 常用图像子集(未知 → null,domain 补 'bin')
const extensionFromMimeType = (mimeType: string): string | null => {
  switch (mimeType.toLowerCase()) {
    case 'image/png': return 'png';
    case 'image/jpeg': return 'jpg';
    case 'image/gif': return 'gif';
    case 'image/webp': return 'webp';
    case 'image/bmp': return 'bmp';
    case 'image/svg+xml': return 'svg';
    default: return null;
  }
};

const filesDir = (): string => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  return ctx.filesDir;
};

class EntryMcpFilesStore implements McpFilesPort {
  async saveUploadFromBytes(bytes: Uint8Array, displayName: string,
    mimeType: string): Promise<string> {
    const dir: string = `${filesDir()}/${FILE_FOLDER_UPLOAD}`;
    try {
      await fileIo.mkdir(dir, true);
    } catch {
      // 已存在
    }
    // buildUuidFileName(displayName) 语义(FilesManager.kt:469-480):uuid + 原扩展名
    const dot: number = displayName.lastIndexOf('.');
    const ext: string = dot >= 0 ? displayName.substring(dot + 1) : 'bin';
    const fileName: string = `${newId()}.${ext}`;
    const path: string = `${dir}/${fileName}`;
    const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE |
      fileIo.OpenMode.TRUNC;
    const file: fileIo.File = await fileIo.open(path, mode);
    try {
      const buffer: ArrayBuffer = bytes.buffer.slice(
        bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
      await fileIo.write(file.fd, buffer);
    } finally {
      await fileIo.close(file);
    }
    // trackUploadFile(FilesManager.kt:200)— fire-and-forget(Android runCatching 静默,
    //   此处 hilog.warn,D-086 登记同例)
    trackUploadFile(getFilesRepository(), {
      relativePath: `${FILE_FOLDER_UPLOAD}/${fileName}`,
      displayName,
      mimeType,
      sizeBytes: bytes.byteLength,
    }).catch((error: Error): void => {
      hilog.warn(DOMAIN, TAG, '[trackUploadFile] %{public}s', error.message);
    });
    return `file://${path}`;
  }

  extensionFromMimeType(mimeType: string): string | null {
    return extensionFromMimeType(mimeType);
  }
}

let instance: EntryMcpFilesStore | null = null;

export const getMcpFilesStore = (): McpFilesPort => {
  if (instance === null) {
    instance = new EntryMcpFilesStore();
  }
  return instance;
};
