// ConversationBlobStore — 会话图片的内容寻址文件存储(ChatBlobPort 实现)。
//
// 路径:filesDir/amberagent/chat-blobs/<sha256>。文件名只接受 64 位小写 hex,
// 防止任何引用拼出目录穿越。写入幂等:同一 hash 已存在则跳过。
// 读取失败/缺失返回 null,由 chat-domain 还原为 missing 占位。

import { fileIo } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import type { ChatBlobPort } from '@amber/chat-domain';
import { sha256HexUtf8 } from './EntryLocalToolPorts.ets';

const DOMAIN: number = 0x0001;
const TAG: string = 'Chat.BlobStore';
const BLOB_DIR: string = 'amberagent/chat-blobs';
const HASH_PATTERN: RegExp = /^[a-f0-9]{64}$/;

class OhosChatBlobPort implements ChatBlobPort {
  private root: string | null = null;

  private rootPath(): string {
    if (this.root !== null) return this.root;
    const ctx: Context | undefined = AppStorage.get<Context>('abilityContext');
    if (ctx === undefined) throw new Error('abilityContext unavailable for chat blobs');
    this.root = `${ctx.filesDir}/${BLOB_DIR}`;
    return this.root;
  }

  private pathOf(hash: string): string {
    if (!HASH_PATTERN.test(hash)) throw new Error(`invalid chat blob hash: ${hash}`);
    return `${this.rootPath()}/${hash}`;
  }

  private async ensureRoot(): Promise<void> {
    const root: string = this.rootPath();
    try {
      await fileIo.mkdir(root, true);
    } catch {
      // 已存在或并发创建
    }
  }

  hash(dataUrl: string): string {
    return sha256HexUtf8(dataUrl);
  }

  async write(hash: string, dataUrl: string): Promise<void> {
    const path: string = this.pathOf(hash);
    await this.ensureRoot();
    if (await fileIo.access(path)) {
      const stat: fileIo.Stat = await fileIo.stat(path);
      if (stat.size > 0) return;
    }
    // 先写临时文件再 rename:崩溃不会留下半截内容被 read 当作有效 blob。
    const temp: string = `${path}.tmp-${Date.now()}-${Math.floor(Math.random() * 1000000)}`;
    const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC;
    const file: fileIo.File = await fileIo.open(temp, mode);
    try {
      await fileIo.write(file.fd, dataUrl);
    } finally {
      await fileIo.close(file);
    }
    try {
      await fileIo.rename(temp, path);
    } catch (error) {
      // 另一个写入者已先完成同名 hash;保留其完整文件,清掉自己的临时文件。
      if (await fileIo.access(temp)) await fileIo.unlink(temp);
      if (!(await fileIo.access(path))) {
        throw error instanceof Error ? error : new Error(String(error));
      }
    }
  }

  async read(hash: string): Promise<string | null> {
    const path: string = this.pathOf(hash);
    if (!(await fileIo.access(path))) return null;
    try {
      const text: string = await fileIo.readText(path);
      // 空文件是崩溃残留,按缺失处理,避免把空 URL 交给图片加载器。
      return text.length > 0 ? text : null;
    } catch (error) {
      hilog.warn(DOMAIN, TAG, 'read failed: %{public}s', error instanceof Error ? error.message : String(error));
      return null;
    }
  }

  async list(): Promise<string[]> {
    const root: string = this.rootPath();
    if (!(await fileIo.access(root))) return [];
    const names: string[] = await fileIo.listFile(root);
    return names.filter((name: string): boolean => HASH_PATTERN.test(name));
  }

  async remove(hash: string): Promise<void> {
    const path: string = this.pathOf(hash);
    if (!(await fileIo.access(path))) return;
    try {
      await fileIo.unlink(path);
    } catch (error) {
      hilog.warn(DOMAIN, TAG, 'remove failed: %{public}s', error instanceof Error ? error.message : String(error));
    }
  }
}

let port: ChatBlobPort | null = null;

export const getChatBlobPort = (): ChatBlobPort => {
  if (port === null) port = new OhosChatBlobPort();
  return port;
};

export const chatBlobRootPath = (): string => {
  const ctx: Context | undefined = AppStorage.get<Context>('abilityContext');
  if (ctx === undefined) return '';
  return `${ctx.filesDir}/${BLOB_DIR}`;
};
