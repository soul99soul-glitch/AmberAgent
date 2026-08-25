// SubAgentStore — subagent transcript fileIo 承载端口(D-132c Task 9)
//
// Android 基准: SubAgentManager.appendEvent + SubAgentTranscriptReader
//   context.filesDir/amberagent/subagents/runs/<runId>.jsonl
//   canonical containment(File.canonicalFile/startsWith)、regular file、
//   末尾 256 KiB(RandomAccessFile seek)。
//
// 实现 SubAgentTranscriptPort(chat HAR D-132a):
//   canonicalPath/appendText/exists/isRegularFile/isPathInside/readTail
//
// 承载映射(登记):
//   - java.io.File.getCanonicalFile → fileIo 路径规范化(Harmony 无 symlink
//     解析的同步 API;应用私有沙箱内路径恒为规范,直接返回绝对路径)
//   - RandomAccessFile.seek(length - maxBytes) → stat.size + READ_ONLY 偏移读取
//     (Harmony fileIo.readSync 不支持 offset 入参,先整文件读入再取末尾 maxBytes)
//   - File.appendText → READ_WRITE | APPEND | CREATE(每次追加单行)
// ArkTS 适配:JSONL 单行 = encoder + appendSync;tail 截断为 string slice

import { fileIo } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { util } from '@kit.ArkTS';
import type { Context } from '@kit.AbilityKit';
import type { SubAgentTranscriptPort, SubAgentTranscriptTail } from '@amber/chat-domain';

const DOMAIN: number = 0x0001;
const TAG: string = 'Chat.SubAgentStore';

// 段级规范化(沙箱内无 symlink,lexical 等价):'..' 弹栈,'.' 与空段忽略
const normalizeSegments = (p: string): string => {
  const parts: string[] = p.split('/');
  const out: string[] = [];
  for (const part of parts) {
    if (part.length === 0 || part === '.') continue;
    if (part === '..') {
      out.pop();
      continue;
    }
    out.push(part);
  }
  return `/${out.join('/')}`;
};

const filesDirPath = (): string => {
  const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
  return ctx.filesDir;
};

// 私有 runs 根目录(context.filesDir/amberagent/subagents/runs)
export const subAgentRunsDir = (): string => `${filesDirPath()}/amberagent/subagents/runs`;

// transcriptPathForRun:与 manager 的 transcriptPathForRun 等价 — runs 根 + runId.jsonl
export const subAgentTranscriptPathForRun = (runId: string): string =>
  `${subAgentRunsDir()}/${runId}.jsonl`;

const ensureRunsDir = (): void => {
  try {
    fileIo.mkdirSync(subAgentRunsDir(), true);
  } catch (_e) {
    // 已存在(File.mkdirs 结果忽略语义)
  }
};

export const createEntrySubAgentTranscriptPort = (): SubAgentTranscriptPort => ({
  // 应用私有沙箱内路径恒为规范(无 symlink 解析);非法路径返回 null
  canonicalPath: (path: string): Promise<string | null> => {
    if (path.length === 0) return Promise.resolve(null);
    try {
      // fileIo.accessSync 不区分文件/目录;存在即规范(沙箱内)
      fileIo.accessSync(path);
      return Promise.resolve(path);
    } catch (_e) {
      return Promise.resolve(null);
    }
  },

  appendText: (path: string, text: string): Promise<void> => {
    ensureRunsDir();
    const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE |
      fileIo.OpenMode.APPEND;
    const file: fileIo.File = fileIo.openSync(path, mode);
    try {
      const bytes: Uint8Array = new util.TextEncoder().encode(text);
      fileIo.writeSync(file.fd, bytes.buffer);
    } finally {
      fileIo.closeSync(file);
    }
    return Promise.resolve();
  },

  exists: (path: string): Promise<boolean> => {
    try {
      fileIo.accessSync(path);
      return Promise.resolve(true);
    } catch (_e) {
      return Promise.resolve(false);
    }
  },

  isRegularFile: (path: string): Promise<boolean> => {
    try {
      const stat: fileIo.Stat = fileIo.statSync(path);
      // Harmony fileIo.stat 无 isRegularFile;私有沙箱内非目录即常规文件
      return Promise.resolve(!stat.isDirectory());
    } catch (_e) {
      return Promise.resolve(false);
    }
  },

  isPathInside: (root: string, path: string): Promise<boolean> => {
    if (root.length === 0 || path.length === 0) return Promise.resolve(false);
    // 相对路径维持旧语义直接拒绝(不参与规范化,防误放宽)
    if (!path.startsWith('/')) return Promise.resolve(false);
    // 段级规范化后再判 containment:`root/../outside` 这类未解析 .. 的路径
    // 不能靠裸 startsWith 放行
    const normRoot: string = normalizeSegments(root);
    const normPath: string = normalizeSegments(path);
    return Promise.resolve(normPath === normRoot || normPath.startsWith(`${normRoot}/`));
  },

  readTail: (path: string, maxBytes: number): Promise<SubAgentTranscriptTail> => {
    try {
      const file: fileIo.File = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY);
      try {
        const stat: fileIo.Stat = fileIo.statSync(file.fd);
        const size: number = stat.size;
        if (size === 0) {
          const empty: SubAgentTranscriptTail = { text: '', startsAfterFileStart: false };
          return Promise.resolve(empty);
        }
        // SDK readSync 支持 offset/length/position:按字节窗只读末尾 maxBytes,
        // 不再整文件入内存;窗口首字节可能是多字节字符的续字节 → 前移到字符边界
        const start: number = Math.max(size - maxBytes, 0);
        const length: number = size - start;
        const buffer: ArrayBuffer = new ArrayBuffer(length);
        const read: number = fileIo.readSync(file.fd, buffer, {
          offset: start, length,
        });
        const bytes: Uint8Array = new Uint8Array(buffer, 0, read);
        let begin: number = 0;
        while (begin < bytes.length && (bytes[begin] & 0xC0) === 0x80) {
          begin++;
        }
        const text: string = util.TextDecoder.create('utf-8')
          .decodeToString(bytes.subarray(begin));
        const tail: SubAgentTranscriptTail = { text, startsAfterFileStart: start > 0 };
        return Promise.resolve(tail);
      } finally {
        fileIo.closeSync(file);
      }
    } catch (e) {
      hilog.warn(DOMAIN, TAG, 'readTail failed: %{public}s', String(e));
      const fallback: SubAgentTranscriptTail = { text: '', startsAfterFileStart: false };
      return Promise.resolve(fallback);
    }
  },
});
