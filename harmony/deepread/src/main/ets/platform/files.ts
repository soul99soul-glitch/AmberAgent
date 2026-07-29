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
  // 追加文本,自动创建父目录;文件不存在则新建
  appendText(path: string, content: string): Promise<void>;
  exists(path: string): Promise<boolean>;
  // 列目录下的名字(非完整路径);目录不存在返回空数组
  list(dir: string): Promise<string[]>;
  // 删除文件;不存在视为成功
  delete(path: string): Promise<void>;
  // 递归建目录
  mkdir(dir: string): Promise<void>;
  // D-127:文件最后修改时间(epoch millis,File.lastModified 等价);
  //   不存在 → null(DeepReadPlaybookRepository updatedAt/快照挑选用)
  mtime(path: string): Promise<number | null>;
}

// 内存实现:用 Map 存文件。供测试与兜底。
// ArkTS 要求 class implements(对象字面量实现 interface 不允许),故用 class。
class MemoryFileStore implements FileStore {
  private files: Map<string, string> = new Map();
  // D-127:mtime 台账(每次写 +1;0 起单调递增)
  private mtimes: Map<string, number> = new Map();
  private writeSeq: number = 0;

  private norm(p: string): string {
    return joinPath(p);
  }

  async readText(path: string): Promise<string | null> {
    const key: string = this.norm(path);
    return this.files.has(key) ? (this.files.get(key) as string) : null;
  }

  async writeText(path: string, content: string): Promise<void> {
    const key: string = this.norm(path);
    this.files.set(key, content);
    this.writeSeq += 1;
    this.mtimes.set(key, this.writeSeq);
  }

  async appendText(path: string, content: string): Promise<void> {
    const key: string = this.norm(path);
    const prev: string = this.files.has(key) ? (this.files.get(key) as string) : '';
    this.files.set(key, prev + content);
    this.writeSeq += 1;
    this.mtimes.set(key, this.writeSeq);
  }

  async exists(path: string): Promise<boolean> {
    return this.files.has(this.norm(path));
  }

  async list(dir: string): Promise<string[]> {
    const prefix: string = this.norm(dir);
    const base: string = prefix === '/' ? '' : prefix + '/';
    const names: string[] = [];
    this.files.forEach((_v: string, key: string): void => {
      if (base.length > 0 && !key.startsWith(base)) return;
      const rest: string = base.length > 0 ? key.slice(base.length) : key.replace(/^\//, '');
      if (rest.length === 0) return;
      const slash: number = rest.indexOf('/');
      const name: string = slash < 0 ? rest : rest.slice(0, slash);
      if (name.length > 0 && names.indexOf(name) < 0) names.push(name);
    });
    return names;
  }

  async delete(path: string): Promise<void> {
    this.files.delete(this.norm(path));
    this.mtimes.delete(this.norm(path));
  }

  async mkdir(_dir: string): Promise<void> {
    // 内存实现无显式目录,写文件时隐式存在
  }

  // D-127:写入序号当 mtime(单调递增,测试可预期;epoch 语义由调用方自定)
  async mtime(path: string): Promise<number | null> {
    const key: string = this.norm(path);
    if (!this.files.has(key)) return null;
    const v: number | undefined = this.mtimes.get(key);
    return v === undefined ? null : v;
  }
}

export const createMemoryFileStore = (): FileStore => {
  return new MemoryFileStore();
};
