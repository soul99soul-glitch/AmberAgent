// AtomicJsonFile — JSON 文件原子写 + 损坏隔离
// 写:同目录 .tmp 完整写入并关闭后 rename 原子替换,消除 TRUNC 直写的撕裂读窗口;
// 读侧损坏:调用方解析/结构校验失败时调用 quarantineFile() 把原文件改名为 .bad
// 保留现场后再按空库回退 —— 否则下一次写会以空快照覆盖,造成整库静默丢失。
import { fileIo } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0001;
const TAG: string = 'AtomicJsonFile';

export const writeTextFileAtomic = async (path: string, text: string): Promise<void> => {
  const dir: string = path.substring(0, path.lastIndexOf('/'));
  try {
    await fileIo.mkdir(dir, true);
  } catch {
    // 已存在
  }
  const tmp: string = `${path}.tmp`;
  const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC;
  let file: fileIo.File | null = null;
  try {
    file = await fileIo.open(tmp, mode);
    await fileIo.write(file.fd, text);
    await fileIo.close(file);
    file = null;
    fileIo.renameSync(tmp, path);
  } catch (e) {
    if (file !== null) {
      try { await fileIo.close(file); } catch { /* 已关闭或关闭失败均尽力清理 */ }
    }
    try { fileIo.unlinkSync(tmp); } catch { /* 尽力清理临时文件 */ }
    throw e instanceof Error ? e : new Error(String(e));
  }
};

// 二次确认后隔离:调用方解析失败 ≠ 文件此刻仍损坏(写队列可能刚完成原子替换)。
// 重读并用调用方的校验器复核,仍无效才改名,避免把刚写好的有效文件误隔离。
export const quarantineIfStillInvalid = async (
  path: string, isInvalid: (text: string) => boolean,
): Promise<void> => {
  try {
    const text: string = await fileIo.readText(path);
    if (!isInvalid(text)) return; // 已被并发写修复 → 不隔离
  } catch {
    // 重读失败(文件消失等)→ 无从隔离
    return;
  }
  try {
    fileIo.renameSync(path, `${path}.${Date.now()}.bad`);
    hilog.warn(DOMAIN, TAG, 'quarantined corrupt json: %{public}s', path);
  } catch {
    // 隔离失败不阻塞空库回退(原文件保留,下次读取会再次尝试)
  }
};


// ===== 按键异步互斥(KV read-modify-write 串行化)=====
// 共享单例:AgentRuntimePrefs 与 PromptConfigStore 共写 agent_runtime 键,
// 必须共用同一队列,否则跨文件更新互相覆盖
export class KeyedAsyncMutex {
  private readonly tails: Map<string, Promise<void>> = new Map<string, Promise<void>>();

  withLock<T>(key: string, op: () => Promise<T>): Promise<T> {
    const previous: Promise<void> = this.tails.get(key) ?? Promise.resolve();
    const run: Promise<T> = previous.catch((): void => {}).then(op);
    const tail: Promise<void> = run.then((): void => {}, (): void => {});
    this.tails.set(key, tail);
    return run.finally((): void => {
      if (this.tails.get(key) === tail) this.tails.delete(key);
    });
  }
}

export const sharedKvMutex: KeyedAsyncMutex = new KeyedAsyncMutex();
