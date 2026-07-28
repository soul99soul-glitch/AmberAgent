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

  private async resolve(path: string): Promise<string> {
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

  async mkdir(dir: string): Promise<void> {
    const full: string = await this.resolve(dir);
    try {
      await fileIo.mkdir(full, true);
    } catch {
      // 已存在,忽略
    }
  }
}

export const createFileStore = (): FileStore => {
  return new OhosFileStore();
};
