// DocumentSupport — DocumentReaderDeps entry 实现(D-074)
// Android 基准: DocumentAsPromptTransformer.kt:73-128
//   - document.url.toUri().toFile():非 file scheme → 抛 IllegalStateException
//     → runCatching null → '[ERROR, invalid file uri]'(entry:null 返回)
//   - File.exists()/isFile()/length()/absolutePath → fileIo.statSync 映射
//   - bufferedReader 字符读取 → 整读 + UTF-8 decode + 截断(语义等价;
//     64MB 上限已由上层判定)
// 偏差(登记):
//   - PDF/DOCX/PPTX/EPUB 解析(document 模块未移植)→ parse* 不提供,
//     HAR 侧抛错走 '[ERROR, failed to read file]'(与 Android 解析失败同文案)= P1
//   - OfficeNativeSwitch(native 桥)不移植
import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import type { UIMessagePartDocument } from '@amber/chat-domain';
import type { DocumentFileHandle, DocumentReaderDeps } from '@amber/chat-domain';

const S_IFMT: number = 0o170000;
const S_IFREG: number = 0o100000;

const statOne = (document: UIMessagePartDocument): Promise<DocumentFileHandle | null> => {
  // toUri().toFile():非 file scheme → 非法 uri(null)
  if (!document.url.startsWith('file://')) return Promise.resolve(null);
  const path: string = document.url.substring('file://'.length);
  try {
    const st = fileIo.statSync(path);
    const handle: DocumentFileHandle = {
      exists: true,
      isFile: (st.mode & S_IFMT) === S_IFREG,
      sizeBytes: st.size,
      absolutePath: path,
    };
    return Promise.resolve(handle);
  } catch {
    // File("") / 不存在 → exists=false(走 '[ERROR, file not found]')
    const missing: DocumentFileHandle = {
      exists: false,
      isFile: false,
      sizeBytes: 0,
      absolutePath: path,
    };
    return Promise.resolve(missing);
  }
};

const readText = (handle: DocumentFileHandle, maxCharsPlusOne: number): Promise<string> => {
  const file = fileIo.openSync(handle.absolutePath, fileIo.OpenMode.READ_ONLY);
  try {
    const st = fileIo.statSync(file.fd);
    const buf = new ArrayBuffer(st.size);
    fileIo.readSync(file.fd, buf);
    const decoder = util.TextDecoder.create('utf-8');
    const text: string = decoder.decodeToString(new Uint8Array(buf));
    return Promise.resolve(text.substring(0, maxCharsPlusOne));
  } finally {
    fileIo.closeSync(file);
  }
};

// parse*(PDF/DOCX/PPTX/EPUB)刻意不提供 — document 模块未移植(P1 登记)
export const createEntryDocumentReader = (): DocumentReaderDeps => ({
  statFile: statOne,
  readTextFile: readText,
});
