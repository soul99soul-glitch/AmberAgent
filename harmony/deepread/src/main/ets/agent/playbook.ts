// playbook — Deep Read Playbook 本地仓库(D-127)
//
// Android 基准(逐字锚点):
//   feature/board/impl/.../deepread/DeepReadPlaybookRepository.kt(全文 115 行):
//     read(:19-22)/update(:24-44)/restoreDefault(:46-52)/restorePrevious
//     (:54-66)/ensureInitialized(:68-74)/defaultMarkdown(:76-77)/saveSnapshot
//     (:79-96)/snapshotOf(:98-103)/sha256(:105-108)+ 常量(:110-114)
//   消费点:DeepReadAgentRunManager.kt:350-369 — 规划前 read().markdown 注入
//     generateArticlePlan(harmony run_manager.ts:126 deps.playbookMarkdown 同位)
//
// 偏差适配登记:
//   - Context.assets.open(ASSET_PATH) → loadDefaultMarkdown 注入(entry =
//     resourceManager rawfile 同步读;资产自 Android assets 逐字拷贝)
//   - java.io.File → FileStore 端口(D-1xx 既有);File.lastModified →
//     FileStore.mtime(D-127 端口扩展;fileIo stat 秒 → ×1000 毫秒,entry 侧)
//   - MessageDigest SHA-256 → sha256Hex 同步注入(entry =
//     CryptoArchitectureKit sha256HexUtf8,D-061 既有)
//   - Mutex → 异步 promise 链锁(单飞语义;TS 单线程但 await 交错需串行化)
//   - Result<Snapshot> → DeepReadPlaybookResult(失败信息 =
//     exception.message 逐字;工具层 rejected 映射同 Android fold);
//     ArkTS 判别联合收窄支持不全(compiler 10505001)→ 可空字段单结构
//     (登记:失败 snapshot=null/成功 error=null,不变量由构造点保证)

import type { FileStore } from '../platform/files.ts';

// ===== 模型(DeepReadPlaybookTools.kt:81-88 toJson 键序 status/revision/
//   updated_at/markdown 在 chat HAR 工具侧复刻) =====

export interface DeepReadPlaybookSnapshot {
  revision: string;
  markdown: string;
  updatedAt: number;
}

// Result<Snapshot>:ok=true → snapshot;ok=false → error
export interface DeepReadPlaybookResult {
  ok: boolean;
  snapshot: DeepReadPlaybookSnapshot | null;
  error: string | null;
}

const resultOk = (snapshot: DeepReadPlaybookSnapshot): DeepReadPlaybookResult => ({
  ok: true, snapshot, error: null,
});

const resultErr = (error: string): DeepReadPlaybookResult => ({
  ok: false, snapshot: null, error,
});

export interface DeepReadPlaybookDeps {
  store: FileStore;
  // SHA-256 hex(小写无分隔);revision = hex.take(16)
  sha256Hex: (text: string) => string;
  // System.currentTimeMillis()
  nowMs: () => number;
  // context.assets.open('deepread/deep_read_playbook.md').readText()
  loadDefaultMarkdown: () => Promise<string>;
}

// :110-114 常量
const PLAYBOOK_DIR: string = 'deep_read_playbook';
const PLAYBOOK_FILE: string = 'deep_read_playbook/playbook.md';
const SNAPSHOTS_DIR: string = 'deep_read_playbook/snapshots';
const MAX_MARKDOWN_BYTES: number = 40000;
const MIN_MARKDOWN_CHARS: number = 400;

// UTF-8 字节长(encodeToByteArray().size;:38 体量闸)
const utf8ByteLength = (text: string): number => {
  let bytes: number = 0;
  for (let i = 0; i < text.length; i++) {
    const code: number = text.charCodeAt(i);
    if (code < 0x80) {
      bytes += 1;
    } else if (code < 0x800) {
      bytes += 2;
    } else if (code >= 0xD800 && code <= 0xDBFF) {
      // 高代理:配低代理 = 4 字节,跳过低代理
      bytes += 4;
      i += 1;
    } else {
      bytes += 3;
    }
  }
  return bytes;
};

// ===== 异步互斥(Kotlin Mutex 对等:await 交错下串行化仓库体) =====

class AsyncMutex {
  private tail: Promise<void> = Promise.resolve();

  withLock<T>(block: () => Promise<T>): Promise<T> {
    const run: Promise<T> = this.tail.then(block);
    this.tail = run.then((): void => undefined, (): void => undefined);
    return run;
  }
}

export class DeepReadPlaybookRepository {
  private readonly deps: DeepReadPlaybookDeps;
  private readonly mutex: AsyncMutex = new AsyncMutex();

  constructor(deps: DeepReadPlaybookDeps) {
    this.deps = deps;
  }

  // :19-22
  async read(): Promise<DeepReadPlaybookSnapshot> {
    return this.mutex.withLock(async (): Promise<DeepReadPlaybookSnapshot> => {
      await this.ensureInitialized();
      const text: string = await this.readPlaybookText();
      return await this.snapshotOf(text);
    });
  }

  // :24-44
  async update(
    baseRevision: string, changeSummary: string, updatedMarkdown: string,
  ): Promise<DeepReadPlaybookResult> {
    return this.mutex.withLock(async (): Promise<DeepReadPlaybookResult> => {
      await this.ensureInitialized();
      const current: DeepReadPlaybookSnapshot = await this.snapshotOf(await this.readPlaybookText());
      if (baseRevision !== current.revision) {
        return resultErr(
          `Playbook revision conflict: current=${current.revision}, base=${baseRevision}`);
      }
      const normalized: string = updatedMarkdown.trim();
      if (normalized.length < MIN_MARKDOWN_CHARS) {
        return resultErr('Playbook markdown is too short');
      }
      if (utf8ByteLength(normalized) > MAX_MARKDOWN_BYTES) {
        return resultErr('Playbook markdown is too large');
      }
      await this.saveSnapshot(current, changeSummary);
      await this.deps.store.writeText(PLAYBOOK_FILE, normalized);
      return resultOk(await this.snapshotOf(normalized));
    });
  }

  // :46-52
  async restoreDefault(): Promise<DeepReadPlaybookSnapshot> {
    return this.mutex.withLock(async (): Promise<DeepReadPlaybookSnapshot> => {
      await this.ensureInitialized();
      const current: DeepReadPlaybookSnapshot = await this.snapshotOf(await this.readPlaybookText());
      await this.saveSnapshot(current, 'restore_default');
      const def: string = await this.defaultMarkdown();
      await this.deps.store.writeText(PLAYBOOK_FILE, def);
      return await this.snapshotOf(def);
    });
  }

  // :54-66
  async restorePrevious(): Promise<DeepReadPlaybookResult> {
    return this.mutex.withLock(async (): Promise<DeepReadPlaybookResult> => {
      await this.ensureInitialized();
      // listFiles { extension == 'md' }.maxByOrNull { lastModified() }
      const names: string[] = (await this.deps.store.list(SNAPSHOTS_DIR))
        .filter((n: string): boolean => n.endsWith('.md'));
      let previousPath: string | null = null;
      let previousMtime: number = -1;
      for (const name of names) {
        const full: string = `${SNAPSHOTS_DIR}/${name}`;
        const mt: number | null = await this.deps.store.mtime(full);
        if (mt !== null && mt > previousMtime) {
          previousMtime = mt;
          previousPath = full;
        }
      }
      if (previousPath === null) {
        return resultErr('No previous playbook snapshot');
      }
      const current: DeepReadPlaybookSnapshot = await this.snapshotOf(await this.readPlaybookText());
      await this.saveSnapshot(current, 'restore_previous');
      const previousText: string = await this.deps.store.readText(previousPath) as string;
      // substringAfter("\n\n", previousText):无分隔 → 原文
      const sepIdx: number = previousText.indexOf('\n\n');
      const markdown: string = sepIdx >= 0 ? previousText.substring(sepIdx + 2) : previousText;
      await this.deps.store.writeText(PLAYBOOK_FILE, markdown);
      return resultOk(await this.snapshotOf(markdown));
    });
  }

  // :68-74
  private async ensureInitialized(): Promise<void> {
    await this.deps.store.mkdir(PLAYBOOK_DIR);
    await this.deps.store.mkdir(SNAPSHOTS_DIR);
    if (!(await this.deps.store.exists(PLAYBOOK_FILE))) {
      const def: string = await this.defaultMarkdown();
      await this.deps.store.writeText(PLAYBOOK_FILE, def);
    }
  }

  // :76-77 — assets 读出后 trim()
  private async defaultMarkdown(): Promise<string> {
    return (await this.deps.loadDefaultMarkdown()).trim();
  }

  // :79-96
  private async saveSnapshot(
    snapshot: DeepReadPlaybookSnapshot, changeSummary: string,
  ): Promise<void> {
    await this.deps.store.mkdir(SNAPSHOTS_DIR);
    const normalizedSummary: string = changeSummary
      .replace(/[\r\n]+/g, ' ')
      .replace(/\s+/g, ' ')
      .trim()
      .substring(0, 160);
    const safeRaw: string = normalizedSummary.replace(/[^A-Za-z0-9._-]/g, '_').substring(0, 48);
    const safeSummary: string = safeRaw.length === 0 ? 'snapshot' : safeRaw;
    const file: string =
      `${SNAPSHOTS_DIR}/${this.deps.nowMs()}_${snapshot.revision}_${safeSummary}.md`;
    // buildString: appendLine ×3 + append(markdown)
    const content: string =
      `revision: ${snapshot.revision}\n` +
      `change_summary: ${normalizedSummary}\n` +
      '\n' +
      snapshot.markdown;
    await this.deps.store.writeText(file, content);
  }

  // :98-103 — revision = sha256.take(16);updatedAt = playbookFile.lastModified()
  //   (存在时),缺 → nowMs(:102 takeIf/Elvis 逐字)
  private async snapshotOf(markdown: string): Promise<DeepReadPlaybookSnapshot> {
    const mt: number | null = await this.deps.store.mtime(PLAYBOOK_FILE);
    return {
      revision: this.deps.sha256Hex(markdown).substring(0, 16),
      markdown,
      updatedAt: mt !== null ? mt : this.deps.nowMs(),
    };
  }

  private async readPlaybookText(): Promise<string> {
    return (await this.deps.store.readText(PLAYBOOK_FILE)) as string;
  }
}
