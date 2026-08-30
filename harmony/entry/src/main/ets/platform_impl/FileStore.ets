// FileStore — FileStore 契约的真实实现
// @kit.CoreFileKit fileIo,根目录 context.filesDir(惰性从 AppStorage 取 abilityContext,
// 与 PreferencesStorage 一致)。议会写 JSONL 转录、小说存项目 JSON 都用它。
// ArkTS 兼容:class implements FileStore。

import { fileIo } from '@kit.CoreFileKit';
import type { FileStore } from '@amber/deepread-domain';
import { joinPath } from '@amber/deepread-domain';

class OhosFileStore implements FileStore {
  private root: string | null = null;

  private async getRoot(): Promise<string> {
    if (this.root !== null) {
      return this.root;
    }
    const ctx: Context = AppStorage.get<Context>('abilityContext') as Context;
    this.root = ctx.filesDir;
    return this.root;
  }

  // 相对路径校验:拒绝绝对路径与 '.'/'..' 段 — joinPath 只折叠斜杠不消 '..',
  // 不校验时 writeText('../x') 可逃出 filesDir(模型侧路径属外部输入,防御纵深)
  private validateRelativePath(path: string): void {
    if (path.startsWith('/') || path.startsWith('\\') || path.indexOf('\\') >= 0) {
      throw new Error(`FileStore path must be relative: ${path}`);
    }
    const segs: string[] = path.split('/');
    for (const seg of segs) {
      if (seg === '.' || seg === '..') {
        throw new Error(`FileStore path must not contain '${seg}' segment: ${path}`);
      }
    }
  }

  private async resolve(path: string): Promise<string> {
    this.validateRelativePath(path);
    const root: string = await this.getRoot();
    return joinPath(root, path);
  }

  // 确保父目录存在(递归建,已存在忽略)
  private async ensureParent(full: string): Promise<void> {
    const idx: number = full.lastIndexOf('/');
    if (idx <= 0) return;
    const dir: string = full.slice(0, idx);
    try {
      await fileIo.mkdir(dir, true);
    } catch {
      // 已存在或并发创建,忽略
    }
  }

  async readText(path: string): Promise<string | null> {
    const full: string = await this.resolve(path);
    const ok: boolean = await fileIo.access(full);
    if (!ok) return null;
    return await fileIo.readText(full);
  }

  async writeText(path: string, content: string): Promise<void> {
    const full: string = await this.resolve(path);
    await this.ensureParent(full);
    const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC;
    const file: fileIo.File = await fileIo.open(full, mode);
    try {
      await fileIo.write(file.fd, content);
    } finally {
      await fileIo.close(file);
    }
  }

  async readBytes(path: string): Promise<Uint8Array | null> {
    const full: string = await this.resolve(path);
    const ok: boolean = await fileIo.access(full);
    if (!ok) return null;
    const file: fileIo.File = await fileIo.open(full, fileIo.OpenMode.READ_ONLY);
    try {
      const stat: fileIo.Stat = await fileIo.stat(file.fd);
      const buffer: ArrayBuffer = new ArrayBuffer(stat.size);
      await fileIo.read(file.fd, buffer);
      return new Uint8Array(buffer);
    } finally {
      await fileIo.close(file);
    }
  }

  async writeBytes(path: string, content: Uint8Array): Promise<void> {
    const full: string = await this.resolve(path);
    await this.ensureParent(full);
    const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC;
    const file: fileIo.File = await fileIo.open(full, mode);
    try {
      const exact: ArrayBuffer = (content.buffer as ArrayBuffer)
        .slice(content.byteOffset, content.byteOffset + content.byteLength);
      await fileIo.write(file.fd, exact);
    } finally {
      await fileIo.close(file);
    }
  }

  async appendText(path: string, content: string): Promise<void> {
    const full: string = await this.resolve(path);
    await this.ensureParent(full);
    const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.APPEND;
    const file: fileIo.File = await fileIo.open(full, mode);
    try {
      await fileIo.write(file.fd, content);
    } finally {
      await fileIo.close(file);
    }
  }

  async exists(path: string): Promise<boolean> {
    const full: string = await this.resolve(path);
    return await fileIo.access(full);
  }

  async list(dir: string): Promise<string[]> {
    const full: string = await this.resolve(dir);
    const ok: boolean = await fileIo.access(full);
    if (!ok) return [];
    return await fileIo.listFile(full);
  }

  async delete(path: string): Promise<void> {
    const full: string = await this.resolve(path);
    const ok: boolean = await fileIo.access(full);
    if (!ok) return;
    await fileIo.unlink(full);
  }

  async rename(from: string, to: string): Promise<void> {
    const source: string = await this.resolve(from);
    const target: string = await this.resolve(to);
    await this.ensureParent(target);
    await fileIo.rename(source, target);
  }

  async deleteTree(path: string): Promise<void> {
    const full: string = await this.resolve(path);
    const remove = async (target: string): Promise<void> => {
      const ok: boolean = await fileIo.access(target);
      if (!ok) return;
      const stat: fileIo.Stat = await fileIo.stat(target);
      if (stat.isDirectory()) {
        const names: string[] = await fileIo.listFile(target);
        for (let i: number = 0; i < names.length; i++) {
          await remove(joinPath(target, names[i]));
        }
        await fileIo.rmdir(target);
      } else {
        await fileIo.unlink(target);
      }
    };
    await remove(full);
  }

  async isDirectory(path: string): Promise<boolean> {
    const full: string = await this.resolve(path);
    const ok: boolean = await fileIo.access(full);
    if (!ok) return false;
    const stat: fileIo.Stat = await fileIo.stat(full);
    return stat.isDirectory();
  }

  async mkdir(dir: string): Promise<void> {
    const full: string = await this.resolve(dir);
    try {
      await fileIo.mkdir(full, true);
    } catch {
      // 已存在,忽略
    }
  }

  async withExclusiveLock<T>(path: string, op: () => Promise<T>): Promise<T> {
    const full: string = await this.resolve(path);
    await this.ensureParent(full);
    const mode: number = fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE;
    const file: fileIo.File = await fileIo.open(full, mode);
    try {
      await file.lock(true);
      try {
        return await op();
      } finally {
        file.unlock();
      }
    } finally {
      await fileIo.close(file);
    }
  }

  // D-127:File.lastModified 等价 — fileIo.stat.mtime 单位为秒,×1000 归毫秒(登记)
  async mtime(path: string): Promise<number | null> {
    const full: string = await this.resolve(path);
    const ok: boolean = await fileIo.access(full);
    if (!ok) return null;
    try {
      const st: fileIo.Stat = await fileIo.stat(full);
      return st.mtime * 1000;
    } catch {
      return null;
    }
  }
}

export const createFileStore = (): FileStore => {
  return new OhosFileStore();
};
