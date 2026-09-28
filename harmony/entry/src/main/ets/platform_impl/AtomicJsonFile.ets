// AtomicJsonFile — JSON 文件原子写 + 损坏隔离
// 写:同目录 .tmp 完整写入并关闭后 rename 原子替换,消除 TRUNC 直写的撕裂读窗口;
// 读侧损坏:调用方解析/结构校验失败时调用 quarantineFile() 把原文件改名为 .bad
// 保留现场后再按空库回退 —— 否则下一次写会以空快照覆盖,造成整库静默丢失。
import { fileIo } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { util } from '@kit.ArkTS';

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
    const bytes: Uint8Array = util.TextEncoder.create('utf-8').encodeInto(text);
    let consumed: number = 0;
    while (consumed < bytes.byteLength) {
      const remaining: ArrayBuffer = (bytes.buffer as ArrayBuffer)
        .slice(bytes.byteOffset + consumed, bytes.byteOffset + bytes.byteLength);
      // WriteOptions.offset addresses the file, not the buffer. Omitting it
      // continues from the current file position; slice supplies the unwritten bytes.
      const written: number = await fileIo.write(file.fd, remaining);
      if (written <= 0 || written > remaining.byteLength) {
        throw new Error('Atomic file write returned invalid byte count');
      }
      consumed += written;
    }
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

const quarantineMarkerPath = (path: string): string => `${path}.quarantined`;

// 隔离标记:损坏文件被改名 .bad 后,原路径会「文件消失」。
// 若没有持久标记,下一次读会把它当「首次初始化(空库)」,随后的写就以空快照覆盖。
export const quarantineMarkerExists = async (path: string): Promise<boolean> => {
  try {
    return await fileIo.access(quarantineMarkerPath(path));
  } catch {
    return false;
  }
};

// 仅显式恢复(用户确认丢弃/重新导入)时清除;读路径绝不自动清除
export const clearQuarantineMarker = async (path: string): Promise<void> => {
  try {
    fileIo.unlinkSync(quarantineMarkerPath(path));
  } catch {
    // 不存在视为已清除
  }
};

// 二次确认后隔离:调用方解析失败 ≠ 文件此刻仍损坏(写队列可能刚完成原子替换)。
// 重读并用调用方的校验器复核,仍无效才改名,避免把刚写好的有效文件误隔离。
// 顺序:先落隔离标记,再改名。标记写入失败则保持原文件不动——否则文件消失后
// 下次读会当「无文件=空库」,以空快照覆盖旧数据。
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
    await writeTextFileAtomic(
      quarantineMarkerPath(path), JSON.stringify({ at: Date.now(), path }));
  } catch {
    // 标记落盘失败:保留原文件(仍是损坏态),下次读会再次判定为损坏而非空库
    return;
  }
  try {
    fileIo.renameSync(path, `${path}.${Date.now()}.bad`);
    hilog.warn(DOMAIN, TAG, 'quarantined corrupt json: %{public}s', path);
  } catch {
    // 改名失败:原文件保留 + 标记已在 → 读侧仍按「损坏/隔离」处理,不会误当首次
  }
};

// ===== 读三态(缺失 / 读失败 / 损坏隔离)+ 校验 =====
// 调用方据此区分「真首次初始化」与「不可作为写基准的错误态」:
//   missing  — 文件从未存在,可作首次初始化基准
//   io_error — access/readText 抛错,基准不可信
//   blocked  — 解析/结构校验失败(已隔离)或隔离标记仍在,禁止以空快照覆盖
export type JsonReadStatus = 'ok' | 'missing' | 'io_error' | 'blocked';

export interface GuardedJsonRead<T> {
  status: JsonReadStatus;
  value: T | null;
}

export const readGuardedJson = async <T>(
  path: string, validate: (text: string) => T | null,
): Promise<GuardedJsonRead<T>> => {
  let exists: boolean = false;
  try {
    exists = await fileIo.access(path);
  } catch {
    const ioFailure: GuardedJsonRead<T> = { status: 'io_error', value: null };
    return ioFailure;
  }
  if (!exists) {
    const quarantined: boolean = await quarantineMarkerExists(path);
    const absent: GuardedJsonRead<T> = {
      status: quarantined ? 'blocked' : 'missing', value: null,
    };
    return absent;
  }
  let text: string = '';
  try {
    text = await fileIo.readText(path);
  } catch {
    const ioFailure: GuardedJsonRead<T> = { status: 'io_error', value: null };
    return ioFailure;
  }
  const parsed: T | null = validate(text);
  if (parsed !== null) {
    const ok: GuardedJsonRead<T> = { status: 'ok', value: parsed };
    return ok;
  }
  // 损坏:二次确认 + 落隔离标记(内部会重读复核并发写是否已修复)
  await quarantineIfStillInvalid(path, (t: string): boolean => validate(t) === null);
  try {
    const again: string = await fileIo.readText(path);
    const reparsed: T | null = validate(again);
    if (reparsed !== null) {
      const fixed: GuardedJsonRead<T> = { status: 'ok', value: reparsed };
      return fixed;
    }
  } catch {
    // 重读失败 → 保守按 blocked 处理(绝不回退成 missing)
  }
  const blocked: GuardedJsonRead<T> = { status: 'blocked', value: null };
  return blocked;
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
