// FileStore — 文件持久化端口
//
// 议会(ModelCouncil)写 JSONL 转录、小说(Novel)存项目 JSON,都需要文件存储。
// deepread 只定义契约;entry 用 @kit.CoreFileKit(fs) 实现,根目录 context.filesDir。
// 另附 createMemoryFileStore:Node 单测用,也可作无文件权限时的兜底。
//
// 路径约定:调用方传相对路径(如 'model-council/runs/x.jsonl'),实现负责拼根目录。
// ArkTS 安全:interface + factory,无 any。

// 拼路径:用 '/' 连接,折叠重复斜杠,去掉尾部斜杠(保留单个根 '/')。
export const joinPath = (...parts: string[]): string => {
  const joined: string = parts.join('/');
  let out: string = joined.replace(/\/{2,}/g, '/');
  if (out.length > 1 && out.endsWith('/')) {
    out = out.slice(0, -1);
  }
  return out;
};

export interface FileStore {
  // 读文本;不存在返回 null
  readText(path: string): Promise<string | null>;
  // 写文本(覆盖),自动创建父目录
  writeText(path: string, content: string): Promise<void>;
  // 二进制读写;不存在返回 null。写入必须只保存传入 Uint8Array 视图范围。
  readBytes(path: string): Promise<Uint8Array | null>;
  writeBytes(path: string, content: Uint8Array): Promise<void>;
  // 追加文本,自动创建父目录;文件不存在则新建
  appendText(path: string, content: string): Promise<void>;
  exists(path: string): Promise<boolean>;
  // 列目录下的名字(非完整路径);目录不存在返回空数组
  list(dir: string): Promise<string[]>;
  // 删除文件;不存在视为成功
  delete(path: string): Promise<void>;
  // 同一根目录内原子 rename;目标已存在由实现抛错。
  rename(from: string, to: string): Promise<void>;
  // 删除文件或完整目录树;不存在视为成功。
  deleteTree(path: string): Promise<void>;
  isDirectory(path: string): Promise<boolean>;
  // 递归建目录
  mkdir(dir: string): Promise<void>;
  // 对同一路径加独占文件锁后执行。生产实现用于跨进程串行化工作区 root swap；
  // 锁文件必须位于被交换目录之外。
  withExclusiveLock<T>(path: string, op: () => Promise<T>): Promise<T>;
  // D-127:文件最后修改时间(epoch millis,File.lastModified 等价);
  //   不存在 → null(DeepReadPlaybookRepository updatedAt/快照挑选用)
  mtime(path: string): Promise<number | null>;
}

// 内存实现:用 Map 存文件。供测试与兜底。
// ArkTS 要求 class implements(对象字面量实现 interface 不允许),故用 class。
class MemoryFileStore implements FileStore {
  private files: Map<string, string> = new Map();
  private byteFiles: Map<string, Uint8Array> = new Map();
  // D-127:mtime 台账(每次写 +1;0 起单调递增)
  private mtimes: Map<string, number> = new Map();
  private writeSeq: number = 0;
  private lockTails: Map<string, Promise<void>> = new Map<string, Promise<void>>();

  private norm(p: string): string {
    return joinPath(p);
  }

  async readText(path: string): Promise<string | null> {
    const key: string = this.norm(path);
    const text: string | undefined = this.files.get(key);
    if (text !== undefined) return text;
    const bytes: Uint8Array | undefined = this.byteFiles.get(key);
    return bytes === undefined ? null : decodeFileUtf8(bytes);
  }

  async writeText(path: string, content: string): Promise<void> {
    const key: string = this.norm(path);
    this.files.set(key, content);
    this.byteFiles.delete(key);
    this.writeSeq += 1;
    this.mtimes.set(key, this.writeSeq);
  }

  async appendText(path: string, content: string): Promise<void> {
    const key: string = this.norm(path);
    const prev: string = this.files.has(key) ? (this.files.get(key) as string) : '';
    this.files.set(key, prev + content);
    this.byteFiles.delete(key);
    this.writeSeq += 1;
    this.mtimes.set(key, this.writeSeq);
  }

  async exists(path: string): Promise<boolean> {
    const key: string = this.norm(path);
    if (this.files.has(key) || this.byteFiles.has(key)) return true;
    const prefix: string = key + '/';
    for (const name of this.files.keys()) if (name.startsWith(prefix)) return true;
    for (const name of this.byteFiles.keys()) if (name.startsWith(prefix)) return true;
    return false;
  }

  async list(dir: string): Promise<string[]> {
    const prefix: string = this.norm(dir);
    const base: string = prefix === '/' ? '' : prefix + '/';
    const names: string[] = [];
    const add = (key: string): void => {
      if (base.length > 0 && !key.startsWith(base)) return;
      const rest: string = base.length > 0 ? key.slice(base.length) : key.replace(/^\//, '');
      if (rest.length === 0) return;
      const slash: number = rest.indexOf('/');
      const name: string = slash < 0 ? rest : rest.slice(0, slash);
      if (name.length > 0 && names.indexOf(name) < 0) names.push(name);
    };
    this.files.forEach((_v: string, key: string): void => add(key));
    this.byteFiles.forEach((_v: Uint8Array, key: string): void => add(key));
    return names;
  }

  async delete(path: string): Promise<void> {
    const key: string = this.norm(path);
    this.files.delete(key);
    this.byteFiles.delete(key);
    this.mtimes.delete(key);
  }

  async readBytes(path: string): Promise<Uint8Array | null> {
    const key: string = this.norm(path);
    const bytes: Uint8Array | undefined = this.byteFiles.get(key);
    if (bytes !== undefined) return new Uint8Array(bytes);
    const text: string | undefined = this.files.get(key);
    if (text === undefined) return null;
    return utf8Encode(text);
  }

  async writeBytes(path: string, content: Uint8Array): Promise<void> {
    const key: string = this.norm(path);
    this.byteFiles.set(key, new Uint8Array(content));
    this.files.delete(key);
    this.writeSeq += 1;
    this.mtimes.set(key, this.writeSeq);
  }

  async rename(from: string, to: string): Promise<void> {
    const source: string = this.norm(from);
    const target: string = this.norm(to);
    if (await this.exists(target)) throw new Error(`rename target exists: ${target}`);
    const prefix: string = source + '/';
    let moved: boolean = false;
    const textMoves: Array<{ from: string; to: string; value: string }> = [];
    this.files.forEach((value: string, key: string): void => {
      if (key === source || key.startsWith(prefix)) {
        textMoves.push({ from: key, to: target + key.slice(source.length), value });
      }
    });
    const byteMoves: Array<{ from: string; to: string; value: Uint8Array }> = [];
    this.byteFiles.forEach((value: Uint8Array, key: string): void => {
      if (key === source || key.startsWith(prefix)) {
        byteMoves.push({ from: key, to: target + key.slice(source.length), value });
      }
    });
    textMoves.forEach((move): void => {
      const stamp: number | undefined = this.mtimes.get(move.from);
      this.files.delete(move.from);
      this.files.set(move.to, move.value);
      this.mtimes.delete(move.from);
      if (stamp !== undefined) this.mtimes.set(move.to, stamp);
      moved = true;
    });
    byteMoves.forEach((move): void => {
      const stamp: number | undefined = this.mtimes.get(move.from);
      this.byteFiles.delete(move.from);
      this.byteFiles.set(move.to, new Uint8Array(move.value));
      this.mtimes.delete(move.from);
      if (stamp !== undefined) this.mtimes.set(move.to, stamp);
      moved = true;
    });
    if (!moved) throw new Error(`rename source missing: ${source}`);
  }

  async deleteTree(path: string): Promise<void> {
    const key: string = this.norm(path);
    const prefix: string = key + '/';
    const textKeys: string[] = Array.from(this.files.keys());
    textKeys.forEach((name: string): void => {
      if (name === key || name.startsWith(prefix)) {
        this.files.delete(name);
        this.mtimes.delete(name);
      }
    });
    const byteKeys: string[] = Array.from(this.byteFiles.keys());
    byteKeys.forEach((name: string): void => {
      if (name === key || name.startsWith(prefix)) {
        this.byteFiles.delete(name);
        this.mtimes.delete(name);
      }
    });
  }

  async isDirectory(path: string): Promise<boolean> {
    const key: string = this.norm(path);
    if (this.files.has(key) || this.byteFiles.has(key)) return false;
    const prefix: string = key + '/';
    for (const name of this.files.keys()) if (name.startsWith(prefix)) return true;
    for (const name of this.byteFiles.keys()) if (name.startsWith(prefix)) return true;
    return false;
  }

  async mkdir(_dir: string): Promise<void> {
    // 内存实现无显式目录,写文件时隐式存在
  }

  withExclusiveLock<T>(path: string, op: () => Promise<T>): Promise<T> {
    const key: string = this.norm(path);
    const previous: Promise<void> = this.lockTails.get(key) ?? Promise.resolve();
    const run: Promise<T> = previous.catch((): void => {}).then(op);
    const tail: Promise<void> = run.then((): void => {}, (): void => {});
    this.lockTails.set(key, tail);
    return run.finally((): void => {
      if (this.lockTails.get(key) === tail) this.lockTails.delete(key);
    });
  }

  // D-127:写入序号当 mtime(单调递增,测试可预期;epoch 语义由调用方自定)
  async mtime(path: string): Promise<number | null> {
    const key: string = this.norm(path);
    if (!this.files.has(key) && !this.byteFiles.has(key)) return null;
    const v: number | undefined = this.mtimes.get(key);
    return v === undefined ? null : v;
  }
}

const decodeFileUtf8 = (bytes: Uint8Array): string => {
  let result: string = '';
  for (let i: number = 0; i < bytes.length;) {
    const first: number = bytes[i];
    if (first < 0x80) {
      result += String.fromCharCode(first);
      i++;
      continue;
    }
    let needed: number = 0;
    let codePoint: number = 0;
    if (first >= 0xC2 && first <= 0xDF) {
      needed = 1;
      codePoint = first & 0x1F;
    } else if (first >= 0xE0 && first <= 0xEF) {
      needed = 2;
      codePoint = first & 0x0F;
    } else if (first >= 0xF0 && first <= 0xF4) {
      needed = 3;
      codePoint = first & 0x07;
    } else {
      throw new Error('文件不是 UTF-8 文本');
    }
    if (i + needed >= bytes.length) throw new Error('文件不是 UTF-8 文本');
    for (let j: number = 1; j <= needed; j++) {
      const next: number = bytes[i + j];
      if ((next & 0xC0) !== 0x80) throw new Error('文件不是 UTF-8 文本');
      codePoint = (codePoint << 6) | (next & 0x3F);
    }
    const minimum: number = needed === 1 ? 0x80 : needed === 2 ? 0x800 : 0x10000;
    if (codePoint < minimum || codePoint > 0x10FFFF || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
      throw new Error('文件不是 UTF-8 文本');
    }
    if (codePoint <= 0xFFFF) {
      result += String.fromCharCode(codePoint);
    } else {
      const scalar: number = codePoint - 0x10000;
      result += String.fromCharCode(0xD800 + (scalar >> 10), 0xDC00 + (scalar & 0x3FF));
    }
    i += needed + 1;
  }
  return result;
};

const utf8Encode = (text: string): Uint8Array => {
  const out: number[] = [];
  for (let i: number = 0; i < text.length; i++) {
    let cp: number = text.charCodeAt(i);
    if (cp >= 0xd800 && cp <= 0xdbff && i + 1 < text.length) {
      const low: number = text.charCodeAt(i + 1);
      if (low >= 0xdc00 && low <= 0xdfff) {
        cp = 0x10000 + ((cp - 0xd800) << 10) + (low - 0xdc00);
        i += 1;
      }
    }
    if (cp < 0x80) out.push(cp);
    else if (cp < 0x800) out.push(0xc0 | (cp >> 6), 0x80 | (cp & 0x3f));
    else if (cp < 0x10000) {
      out.push(0xe0 | (cp >> 12), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
    } else {
      out.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f),
        0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
    }
  }
  return new Uint8Array(out);
};

export const createMemoryFileStore = (): FileStore => {
  return new MemoryFileStore();
};
