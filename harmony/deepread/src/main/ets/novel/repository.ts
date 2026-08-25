// novel/repository — 项目文件存储(移植自 Android NovelFileProjectRepository)
// 每个项目一个 JSON 文件:amberagent/novel-creation/projects/<id>.novel.json。
// MVP 直接整文件写(原子 temp+rename 列为后续加固);读时校验 schemaVersion。

import type { FileStore } from '../platform/files.ts';
import { joinPath } from '../platform/files.ts';
import { NOVEL_SCHEMA_VERSION } from './models.ts';
import type { NovelProject } from './models.ts';
import { notFound, invalidInput } from './error.ts';

const PROJECTS_DIR: string = 'amberagent/novel-creation/projects';
const SUFFIX: string = '.novel.json';
const MAX_PROJECT_BYTES: number = 16 * 1024 * 1024;

export interface NovelProjectRepository {
  listProjects(): Promise<NovelProject[]>;
  loadProject(id: string): Promise<NovelProject>;
  createProject(project: NovelProject): Promise<NovelProject>;
  updateProject(id: string, transform: (p: NovelProject) => NovelProject): Promise<NovelProject>;
  deleteProject(id: string): Promise<void>;
}

const validate = (p: NovelProject): NovelProject => {
  if (p.schemaVersion !== NOVEL_SCHEMA_VERSION) throw invalidInput('项目版本不受支持');
  if (p.name === undefined || p.name.trim().length === 0) throw invalidInput('项目名称无效');
  return p;
};

export const createFileNovelRepository = (fileStore: FileStore): NovelProjectRepository => {
  const projectPath = (id: string): string => joinPath(PROJECTS_DIR, id + SUFFIX);

  // 按项目串行化 update(读-改-写):并发更新(后台建议刷新 vs 用户编辑)
  // 会基于同一旧快照互相覆盖,丢一条更新
  const updateTails: Map<string, Promise<void>> = new Map<string, Promise<void>>();
  const withProjectLock = <T>(id: string, op: () => Promise<T>): Promise<T> => {
    const previous: Promise<void> = updateTails.get(id) ?? Promise.resolve();
    const run: Promise<T> = previous.catch((): void => {}).then(op);
    const tail: Promise<void> = run.then((): void => {}, (): void => {});
    updateTails.set(id, tail);
    return run.finally((): void => {
      // 身份比较:只清理自己写入的尾指针(否则会误删后继更新的锁)
      if (updateTails.get(id) === tail) updateTails.delete(id);
    });
  };

  const decode = (raw: string): NovelProject => validate(JSON.parse(raw) as NovelProject);

  const write = async (id: string, project: NovelProject): Promise<void> => {
    const raw: string = JSON.stringify(project);
    if (raw.length > MAX_PROJECT_BYTES) throw invalidInput('项目过大');
    await fileStore.writeText(projectPath(id), raw);
  };

  return {
    async listProjects(): Promise<NovelProject[]> {
      const names: string[] = await fileStore.list(PROJECTS_DIR);
      const projects: NovelProject[] = [];
      for (let i = 0; i < names.length; i++) {
        const name: string = names[i];
        if (!name.endsWith(SUFFIX)) continue;
        const raw: string | null = await fileStore.readText(joinPath(PROJECTS_DIR, name));
        if (raw === null) continue;
        try {
          projects.push(decode(raw));
        } catch {
          // 跳过不可读文件
        }
      }
      projects.sort((a, b) => {
        if (b.updatedAt !== a.updatedAt) return b.updatedAt - a.updatedAt;
        return a.name.toLowerCase().localeCompare(b.name.toLowerCase());
      });
      return projects;
    },

    async loadProject(id: string): Promise<NovelProject> {
      const raw: string | null = await fileStore.readText(projectPath(id));
      if (raw === null) throw notFound('project', id);
      return decode(raw);
    },

    async createProject(project: NovelProject): Promise<NovelProject> {
      validate(project);
      await write(project.id, project);
      return project;
    },

    updateProject: (id: string, transform: (p: NovelProject) => NovelProject): Promise<NovelProject> =>
      withProjectLock(id, async (): Promise<NovelProject> => {
        const raw: string | null = await fileStore.readText(projectPath(id));
        if (raw === null) throw notFound('project', id);
        const current: NovelProject = decode(raw);
        const next: NovelProject = transform(current);
        if (next.id !== id) throw invalidInput('项目 id 不可变更');
        await write(id, next);
        return next;
      }),

    async deleteProject(id: string): Promise<void> {
      await fileStore.delete(projectPath(id));
    },
  };
};
