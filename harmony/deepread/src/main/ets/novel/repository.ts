// 小说项目仓库。生产数据从旧的单 JSON 迁入 amber.novel.workspace v1；
// 页面仍通过 NovelProjectRepository 访问，因此没有第二个数据源或平行入口。

import type { FileStore } from '../platform/files.ts';
import { joinPath } from '../platform/files.ts';
import { NOVEL_SCHEMA_VERSION, novelId } from './models.ts';
import type {
  NovelBranch, NovelChapter, NovelMaterial, NovelMaterialKind, NovelProject, NovelSettingProposal,
} from './models.ts';
import { notFound, invalidInput } from './error.ts';
import {
  NOVEL_WORKSPACE_FORMAT, NOVEL_WORKSPACE_VERSION, chapterFileName,
  parseNovelWorkspaceManifest, serializeNovelWorkspaceManifest,
} from './workspace_contract.ts';
import type { NovelWorkspaceManifest } from './workspace_contract.ts';
import { decodeNovelWorkspaceUtf8 } from './workspace_exchange.ts';
import type {
  NovelWorkspaceArchiveFile, NovelWorkspaceValidatedImport,
} from './workspace_exchange.ts';
import type { NovelBookChapterSnapshot, NovelBookExportInput } from './workspace_book_export.ts';

const LEGACY_PROJECTS_DIR: string = 'amberagent/novel-creation/projects';
const LEGACY_SUFFIX: string = '.novel.json';
const ROOT: string = 'amberagent/novel-workspace';
const STAGING_ROOT: string = joinPath(ROOT, '.staging');
const TRASH_ROOT: string = joinPath(ROOT, '.trash');
const DELETED_ROOT: string = joinPath(ROOT, '.deleted');
const STATE_FILE: string = '.amber/project-state.json';
const LEDGER_FILE: string = '.amber/ledger.jsonl';
const CHECKOUT_FILE: string = '.amber/checkout.json';
const MAX_PROJECT_BYTES: number = 16 * 1024 * 1024;
const WORKSPACE_STATE_VERSION: number = 1;

export interface NovelProjectRepository {
  listProjects(): Promise<NovelProject[]>;
  loadProject(id: string): Promise<NovelProject>;
  createProject(project: NovelProject): Promise<NovelProject>;
  updateProject(id: string, transform: (p: NovelProject) => NovelProject): Promise<NovelProject>;
  deleteProject(id: string): Promise<void>;
  restorePrevious(): Promise<NovelProject | null>;
  installWorkspace(input: NovelWorkspaceValidatedImport): Promise<NovelProject>;
  workspaceFiles(id: string): Promise<NovelWorkspaceArchiveFile[]>;
  bookExportInput(id: string): Promise<NovelBookExportInput>;
}

interface PersistedNovelProject extends Omit<NovelProject, 'settingProposals'> {
  settingProposals?: NovelSettingProposal[];
}

interface WorkspaceChapterRecord extends Omit<NovelChapter, 'content'> {
  path: string;
}

interface WorkspaceMaterialRecord extends Omit<NovelMaterial, 'content'> {
  path: string;
}

interface WorkspaceProjectMeta extends Omit<NovelProject, 'chapters' | 'materials'> {}

interface WorkspaceProjectState {
  stateVersion: number;
  project: WorkspaceProjectMeta;
  chapters: WorkspaceChapterRecord[];
  materials: WorkspaceMaterialRecord[];
}

interface WorkspaceCheckout {
  branchId: string;
  head: string;
  treeDigest: string;
}

interface SnapshotFile {
  path: string;
  content: string;
}

export const migrateNovelProject = (stored: PersistedNovelProject): NovelProject => {
  if (stored.schemaVersion === 2) {
    return {
      ...stored,
      schemaVersion: NOVEL_SCHEMA_VERSION,
      settingProposals: [],
    };
  }
  return stored as NovelProject;
};

const validateProject = (project: NovelProject): NovelProject => {
  if (project.schemaVersion !== NOVEL_SCHEMA_VERSION) throw invalidInput('项目版本不受支持');
  if (project.id.trim().length === 0 || project.name.trim().length === 0) {
    throw invalidInput('项目名称或 id 无效');
  }
  serializeNovelWorkspaceManifest({
    format: NOVEL_WORKSPACE_FORMAT,
    version: NOVEL_WORKSPACE_VERSION,
    projectId: project.id,
    title: project.name,
    activeBranch: 'main',
    createdAt: project.createdAt,
    updatedAt: project.updatedAt,
  });
  return project;
};

const mainBranch = (project: NovelProject): NovelBranch => {
  const existing: NovelBranch | undefined = project.branches.find(
    (branch: NovelBranch): boolean => branch.isMain && branch.lifecycle === 'active',
  );
  if (existing !== undefined) return existing;
  return {
    id: 'main',
    name: '主线',
    lifecycle: 'active',
    isMain: true,
    forkFromChapterId: null,
    createdAt: project.createdAt,
  };
};

const normalizeProject = (project: NovelProject): NovelProject => {
  if (project.branches.length > 0) return project;
  return { ...project, branches: [mainBranch(project)] };
};

const projectRoot = (id: string): string => joinPath(ROOT, id);
const stagingRoot = (id: string): string => joinPath(STAGING_ROOT, id);
const trashRoot = (id: string): string => joinPath(TRASH_ROOT, id);
const deletedRoot = (id: string): string => joinPath(DELETED_ROOT, id);
const legacyPath = (id: string): string => joinPath(LEGACY_PROJECTS_DIR, id + LEGACY_SUFFIX);

const encodeUtf8 = (text: string): Uint8Array => {
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

const stableDigest = (files: NovelWorkspaceArchiveFile[]): string => {
  const ordered: NovelWorkspaceArchiveFile[] = files.slice().sort(
    (left: NovelWorkspaceArchiveFile, right: NovelWorkspaceArchiveFile): number =>
      left.path.localeCompare(right.path),
  );
  let a: number = 0x811c9dc5;
  let b: number = 0x9e3779b9;
  const mix = (value: number): void => {
    a = Math.imul(a ^ value, 0x01000193) >>> 0;
    b = Math.imul(b ^ value, 0x85ebca6b) >>> 0;
  };
  for (let i: number = 0; i < ordered.length; i++) {
    const path: string = `${ordered[i].path}\n`;
    for (let j: number = 0; j < path.length; j++) mix(path.charCodeAt(j));
    const bytes: Uint8Array = ordered[i].bytes;
    for (let j: number = 0; j < bytes.length; j++) mix(bytes[j]);
  }
  return a.toString(16).padStart(8, '0') + b.toString(16).padStart(8, '0');
};

const materialPath = (material: NovelMaterial): string =>
  `setting/${material.kind}/${material.id}.md`;

const chapterOrdinal = (chapter: NovelChapter, fallback: number): number => {
  const match: RegExpMatchArray | null = chapter.id.match(/^chapter-(\d{3})(?:-|$)/);
  if (match === null) return fallback;
  const parsed: number = Number.parseInt(match[1], 10);
  return parsed >= 1 && parsed <= 999 ? parsed : fallback;
};

const buildSnapshotFiles = (input: NovelProject): { project: NovelProject; files: SnapshotFile[] } => {
  const project: NovelProject = normalizeProject(validateProject(input));
  const branch: NovelBranch = mainBranch(project);
  const manifest: NovelWorkspaceManifest = {
    format: NOVEL_WORKSPACE_FORMAT,
    version: NOVEL_WORKSPACE_VERSION,
    projectId: project.id,
    title: project.name,
    activeBranch: branch.id,
    createdAt: project.createdAt,
    updatedAt: project.updatedAt,
  };
  const files: SnapshotFile[] = [
    { path: 'manifest.yaml', content: serializeNovelWorkspaceManifest(manifest) },
    { path: 'project.md', content: `# ${project.name}\n` },
  ];
  const chapterRecords: WorkspaceChapterRecord[] = [];
  for (let i: number = 0; i < project.chapters.length; i++) {
    const chapter: NovelChapter = project.chapters[i];
    const relative: string = chapter.discarded
      ? `.amber/discarded/${chapter.id}.md`
      : `branches/${branch.id}/chapters/${chapterFileName(chapterOrdinal(chapter, i + 1), chapter.title)}`;
    chapterRecords.push({
      id: chapter.id,
      title: chapter.title,
      createdAt: chapter.createdAt,
      updatedAt: chapter.updatedAt,
      discarded: chapter.discarded,
      path: relative,
    });
    files.push({ path: relative, content: chapter.content });
  }
  const materialRecords: WorkspaceMaterialRecord[] = [];
  for (let i: number = 0; i < project.materials.length; i++) {
    const material: NovelMaterial = project.materials[i];
    const relative: string = materialPath(material);
    materialRecords.push({
      id: material.id,
      kind: material.kind,
      title: material.title,
      enabled: material.enabled,
      createdAt: material.createdAt,
      updatedAt: material.updatedAt,
      path: relative,
    });
    files.push({ path: relative, content: material.content });
  }
  const state: WorkspaceProjectState = {
    stateVersion: WORKSPACE_STATE_VERSION,
    project: {
      schemaVersion: project.schemaVersion,
      id: project.id,
      name: project.name,
      messages: project.messages,
      createdAt: project.createdAt,
      updatedAt: project.updatedAt,
      modelId: project.modelId,
      lastGenerationGranularity: project.lastGenerationGranularity,
      materialSuggestions: project.materialSuggestions,
      settingProposals: project.settingProposals,
      branches: project.branches,
      chapterVersions: project.chapterVersions,
      revision: project.revision,
    },
    chapters: chapterRecords,
    materials: materialRecords,
  };
  const stateRaw: string = JSON.stringify(state);
  if (stateRaw.length > MAX_PROJECT_BYTES) throw invalidInput('项目过大');
  files.push({ path: STATE_FILE, content: stateRaw });
  return { project, files };
};

const decodeLegacy = (raw: string): NovelProject => {
  const stored: PersistedNovelProject = JSON.parse(raw) as PersistedNovelProject;
  return validateProject(migrateNovelProject(stored));
};

const readCheckout = async (fileStore: FileStore, root: string): Promise<WorkspaceCheckout | null> => {
  const raw: string | null = await fileStore.readText(joinPath(root, CHECKOUT_FILE));
  if (raw === null) return null;
  try {
    const value: WorkspaceCheckout = JSON.parse(raw) as WorkspaceCheckout;
    if (value.head.length === 0 || value.treeDigest.length === 0) return null;
    return value;
  } catch {
    return null;
  }
};

const loadAt = async (fileStore: FileStore, root: string): Promise<NovelProject> => {
  const manifestRaw: string | null = await fileStore.readText(joinPath(root, 'manifest.yaml'));
  const stateRaw: string | null = await fileStore.readText(joinPath(root, STATE_FILE));
  if (manifestRaw === null || stateRaw === null) throw invalidInput('小说工作区不完整');
  const manifest: NovelWorkspaceManifest = parseNovelWorkspaceManifest(manifestRaw);
  const state: WorkspaceProjectState = JSON.parse(stateRaw) as WorkspaceProjectState;
  if (state.stateVersion !== WORKSPACE_STATE_VERSION || state.project.id !== manifest.projectId) {
    throw invalidInput('小说工作区状态与 manifest 不一致');
  }
  const chapters: NovelChapter[] = [];
  for (let i: number = 0; i < state.chapters.length; i++) {
    const record: WorkspaceChapterRecord = state.chapters[i];
    const content: string | null = await fileStore.readText(joinPath(root, record.path));
    if (content === null) throw invalidInput(`章节文件缺失: ${record.path}`);
    chapters.push({
      id: record.id,
      title: record.title,
      content,
      createdAt: record.createdAt,
      updatedAt: record.updatedAt,
      discarded: record.discarded,
    });
  }
  const materials: NovelMaterial[] = [];
  for (let i: number = 0; i < state.materials.length; i++) {
    const record: WorkspaceMaterialRecord = state.materials[i];
    const content: string | null = await fileStore.readText(joinPath(root, record.path));
    if (content === null) throw invalidInput(`设定文件缺失: ${record.path}`);
    materials.push({
      id: record.id,
      kind: record.kind,
      title: record.title,
      content,
      enabled: record.enabled,
      createdAt: record.createdAt,
      updatedAt: record.updatedAt,
    });
  }
  return validateProject({ ...state.project, name: manifest.title, chapters, materials });
};

const walkWorkspaceFiles = async (
  fileStore: FileStore, root: string, relative: string = '',
): Promise<NovelWorkspaceArchiveFile[]> => {
  const dir: string = relative.length === 0 ? root : joinPath(root, relative);
  const names: string[] = await fileStore.list(dir);
  const files: NovelWorkspaceArchiveFile[] = [];
  for (let i: number = 0; i < names.length; i++) {
    const child: string = relative.length === 0 ? names[i] : joinPath(relative, names[i]);
    const full: string = joinPath(root, child);
    if (await fileStore.isDirectory(full)) {
      const nested: NovelWorkspaceArchiveFile[] = await walkWorkspaceFiles(fileStore, root, child);
      files.push(...nested);
    } else {
      const bytes: Uint8Array | null = await fileStore.readBytes(full);
      if (bytes !== null) files.push({ path: child, bytes });
    }
  }
  files.sort((left: NovelWorkspaceArchiveFile, right: NovelWorkspaceArchiveFile): number =>
    left.path.localeCompare(right.path));
  return files;
};

const priorManagedPaths = async (fileStore: FileStore, root: string): Promise<Set<string>> => {
  const managed: Set<string> = new Set<string>(['manifest.yaml', 'project.md']);
  const raw: string | null = await fileStore.readText(joinPath(root, STATE_FILE));
  if (raw === null) return managed;
  try {
    const state: WorkspaceProjectState = JSON.parse(raw) as WorkspaceProjectState;
    state.chapters.forEach((record: WorkspaceChapterRecord): void => { managed.add(record.path); });
    state.materials.forEach((record: WorkspaceMaterialRecord): void => { managed.add(record.path); });
  } catch {
    // 损坏 state 由 loadAt 显式失败；这里不猜测额外 managed 文件。
  }
  return managed;
};

const passthroughFilesAt = async (
  fileStore: FileStore, root: string,
): Promise<NovelWorkspaceArchiveFile[]> => {
  const managed: Set<string> = await priorManagedPaths(fileStore, root);
  const files: NovelWorkspaceArchiveFile[] = await walkWorkspaceFiles(fileStore, root);
  return files.filter((file: NovelWorkspaceArchiveFile): boolean =>
    !file.path.startsWith('.amber/') &&
      (file.path === 'project.md' || !managed.has(file.path)));
};

export const createFileNovelRepository = (fileStore: FileStore): NovelProjectRepository => {
  const updateTails: Map<string, Promise<void>> = new Map<string, Promise<void>>();
  const withProjectLock = <T>(id: string, op: () => Promise<T>): Promise<T> => {
    const previous: Promise<void> = updateTails.get(id) ?? Promise.resolve();
    const run: Promise<T> = previous.catch((): void => {}).then(op);
    const tail: Promise<void> = run.then((): void => {}, (): void => {});
    updateTails.set(id, tail);
    return run.finally((): void => {
      if (updateTails.get(id) === tail) updateTails.delete(id);
    });
  };

  const recoverProject = async (id: string): Promise<void> => {
    const live: string = projectRoot(id);
    const stage: string = stagingRoot(id);
    const backup: string = trashRoot(id);
    const liveExists: boolean = await fileStore.exists(live);
    const backupExists: boolean = await fileStore.exists(backup);
    if (!liveExists && backupExists) await fileStore.rename(backup, live);
    else if (liveExists && backupExists) await fileStore.deleteTree(backup);
    await fileStore.deleteTree(stage);
  };

  const recoverAll = async (): Promise<void> => {
    const backups: string[] = await fileStore.list(TRASH_ROOT);
    for (let i: number = 0; i < backups.length; i++) await recoverProject(backups[i]);
    const stages: string[] = await fileStore.list(STAGING_ROOT);
    for (let i: number = 0; i < stages.length; i++) {
      if (backups.indexOf(stages[i]) < 0) await fileStore.deleteTree(stagingRoot(stages[i]));
    }
  };

  const installSnapshot = async (
    input: NovelProject, action: string, importedFiles: NovelWorkspaceArchiveFile[] = [],
  ): Promise<NovelProject> => {
    const built = buildSnapshotFiles(input);
    const project: NovelProject = built.project;
    const live: string = projectRoot(project.id);
    const stage: string = stagingRoot(project.id);
    const backup: string = trashRoot(project.id);
    await recoverProject(project.id);
    const liveExists: boolean = await fileStore.exists(live);
    const priorLedger: string = liveExists
      ? (await fileStore.readText(joinPath(live, LEDGER_FILE)) ?? '')
      : '';
    const priorCheckout: WorkspaceCheckout | null = liveExists
      ? await readCheckout(fileStore, live)
      : null;
    const preserved: NovelWorkspaceArchiveFile[] = liveExists
      ? await passthroughFilesAt(fileStore, live)
      : importedFiles;
    await fileStore.deleteTree(stage);
    let preservedProjectMd: NovelWorkspaceArchiveFile | null = null;
    for (let i: number = 0; i < preserved.length; i++) {
      if (preserved[i].path === 'project.md') preservedProjectMd = preserved[i];
    }
    // project.md 属于用户可维护的公开工作区文件。仅新建项目时生成标题占位；
    // 导入及后续业务状态更新都必须逐字节保留现有内容。
    const generatedFiles: SnapshotFile[] = preservedProjectMd === null
      ? built.files
      : built.files.filter((file: SnapshotFile): boolean => file.path !== 'project.md');
    const generatedPaths: Set<string> = new Set<string>();
    generatedFiles.forEach((file: SnapshotFile): void => { generatedPaths.add(file.path); });
    const passthrough: NovelWorkspaceArchiveFile[] = preserved.filter(
      (file: NovelWorkspaceArchiveFile): boolean =>
        !file.path.startsWith('.amber/') && !generatedPaths.has(file.path),
    );
    const visible: NovelWorkspaceArchiveFile[] = generatedFiles
      .filter((file: SnapshotFile): boolean => !file.path.startsWith('.amber/'))
      .map((file: SnapshotFile): NovelWorkspaceArchiveFile => ({
        path: file.path,
        bytes: encodeUtf8(file.content),
      }));
    visible.push(...passthrough);
    const treeDigest: string = stableDigest(visible);
    const head: string = `c${project.revision}-${treeDigest}`;
    const commit = {
      version: 1,
      commitId: head,
      parent: priorCheckout === null ? null : priorCheckout.head,
      branchId: mainBranch(project).id,
      treeDigest,
      action,
      createdAt: project.updatedAt,
    };
    const checkout: WorkspaceCheckout = {
      branchId: mainBranch(project).id,
      head,
      treeDigest,
    };
    const files: SnapshotFile[] = generatedFiles.concat([
      { path: LEDGER_FILE, content: priorLedger + JSON.stringify(commit) + '\n' },
      { path: CHECKOUT_FILE, content: JSON.stringify(checkout) },
    ]);
    for (let i: number = 0; i < files.length; i++) {
      await fileStore.writeText(joinPath(stage, files[i].path), files[i].content);
    }
    for (let i: number = 0; i < passthrough.length; i++) {
      await fileStore.writeBytes(joinPath(stage, passthrough[i].path), passthrough[i].bytes);
    }
    if (!liveExists) {
      await fileStore.rename(stage, live);
      return project;
    }
    await fileStore.deleteTree(backup);
    await fileStore.rename(live, backup);
    try {
      await fileStore.rename(stage, live);
    } catch (error) {
      if (!await fileStore.exists(live) && await fileStore.exists(backup)) {
        await fileStore.rename(backup, live);
      }
      throw error;
    }
    await fileStore.deleteTree(backup);
    return project;
  };

  const migrateLegacyIfPresent = async (id: string): Promise<NovelProject | null> => {
    const raw: string | null = await fileStore.readText(legacyPath(id));
    if (raw === null) return null;
    const migrated: NovelProject = await installSnapshot(decodeLegacy(raw), 'legacy_migration');
    await fileStore.delete(legacyPath(id));
    return migrated;
  };

  const loadProjectInternal = async (id: string): Promise<NovelProject> => {
    await recoverProject(id);
    if (await fileStore.exists(projectRoot(id))) return await loadAt(fileStore, projectRoot(id));
    const migrated: NovelProject | null = await migrateLegacyIfPresent(id);
    if (migrated !== null) return migrated;
    throw notFound('project', id);
  };

  const repository: NovelProjectRepository = {
    async listProjects(): Promise<NovelProject[]> {
      await recoverAll();
      const projects: NovelProject[] = [];
      const seen: Set<string> = new Set<string>();
      const names: string[] = await fileStore.list(ROOT);
      for (let i: number = 0; i < names.length; i++) {
        const id: string = names[i];
        if (id.startsWith('.') || !await fileStore.isDirectory(projectRoot(id))) continue;
        try {
          projects.push(await loadAt(fileStore, projectRoot(id)));
          seen.add(id);
        } catch {
          // 损坏项目不伪装为可用项目；loadProject 会给出显式错误。
        }
      }
      const legacyNames: string[] = await fileStore.list(LEGACY_PROJECTS_DIR);
      for (let i: number = 0; i < legacyNames.length; i++) {
        const name: string = legacyNames[i];
        if (!name.endsWith(LEGACY_SUFFIX)) continue;
        const id: string = name.slice(0, -LEGACY_SUFFIX.length);
        if (seen.has(id)) continue;
        try {
          const migrated: NovelProject | null = await migrateLegacyIfPresent(id);
          if (migrated !== null) projects.push(migrated);
        } catch {
          // 保留旧文件，避免失败迁移造成数据丢失。
        }
      }
      projects.sort((left: NovelProject, right: NovelProject): number => {
        if (right.updatedAt !== left.updatedAt) return right.updatedAt - left.updatedAt;
        return left.name.toLowerCase().localeCompare(right.name.toLowerCase());
      });
      return projects;
    },

    async loadProject(id: string): Promise<NovelProject> {
      return await loadProjectInternal(id);
    },

    async createProject(project: NovelProject): Promise<NovelProject> {
      await recoverProject(project.id);
      if (await fileStore.exists(projectRoot(project.id))) throw invalidInput('项目已存在');
      return await installSnapshot(project, 'create');
    },

    updateProject: (id: string, transform: (p: NovelProject) => NovelProject): Promise<NovelProject> =>
      withProjectLock(id, async (): Promise<NovelProject> => {
        const current: NovelProject = await loadProjectInternal(id);
        const next: NovelProject = transform(current);
        if (next.id !== id) throw invalidInput('项目 id 不可变更');
        const committed: NovelProject = { ...next, revision: current.revision + 1 };
        return await installSnapshot(committed, 'compat_update');
      }),

    deleteProject: (id: string): Promise<void> =>
      withProjectLock(id, async (): Promise<void> => {
        await recoverProject(id);
        const live: string = projectRoot(id);
        if (!await fileStore.exists(live)) return;
        await fileStore.deleteTree(deletedRoot(id));
        await fileStore.rename(live, deletedRoot(id));
      }),

    async restorePrevious(): Promise<NovelProject | null> {
      const names: string[] = await fileStore.list(DELETED_ROOT);
      let best: NovelProject | null = null;
      let bestId: string = '';
      for (let i: number = 0; i < names.length; i++) {
        try {
          const project: NovelProject = await loadAt(fileStore, deletedRoot(names[i]));
          if (best === null || project.updatedAt > best.updatedAt) {
            best = project;
            bestId = names[i];
          }
        } catch {
          // 跳过损坏的回收项，不删除证据。
        }
      }
      if (best === null) return null;
      if (await fileStore.exists(projectRoot(best.id))) throw invalidInput('同 id 项目已存在');
      await fileStore.rename(deletedRoot(bestId), projectRoot(best.id));
      return await repository.updateProject(best.id, (project: NovelProject): NovelProject => project);
    },

    async installWorkspace(input: NovelWorkspaceValidatedImport): Promise<NovelProject> {
      const manifest: NovelWorkspaceManifest = input.manifest;
      let id: string = manifest.projectId;
      await recoverProject(id);
      if (await fileStore.exists(projectRoot(id)) || await fileStore.exists(deletedRoot(id))) {
        id = novelId();
      }
      const chapterPrefix: string = `branches/${manifest.activeBranch}/chapters/`;
      const chaptersWithOrdinal: Array<{ ordinal: number; chapter: NovelChapter }> = [];
      const materials: NovelMaterial[] = [];
      const materialIds: Set<string> = new Set<string>();
      for (let i: number = 0; i < input.files.length; i++) {
        const file: NovelWorkspaceArchiveFile = input.files[i];
        if (file.path.startsWith(chapterPrefix) && file.path.endsWith('.md')) {
          const name: string = file.path.slice(chapterPrefix.length);
          if (name.indexOf('/') >= 0) continue;
          const match: RegExpMatchArray | null = name.match(/^(\d{3})-(.+)\.md$/);
          if (match === null) throw invalidInput(`章节文件名无效: ${file.path}`);
          const ordinal: number = Number.parseInt(match[1], 10);
          if (ordinal < 1) throw invalidInput(`章节序号无效: ${file.path}`);
          chaptersWithOrdinal.push({
            ordinal,
            chapter: {
              id: `chapter-${match[1]}`,
              title: match[2],
              content: decodeNovelWorkspaceUtf8(file.bytes),
              createdAt: manifest.createdAt,
              updatedAt: manifest.updatedAt,
              discarded: false,
            },
          });
        } else if (file.path.startsWith('setting/') && file.path.endsWith('.md')) {
          const segments: string[] = file.path.split('/');
          if (segments.length < 3) continue;
          let kind: NovelMaterialKind = 'other';
          if (segments[1] === 'world' || segments[1] === 'character' ||
            segments[1] === 'outline' || segments[1] === 'requirement') {
            kind = segments[1] as NovelMaterialKind;
          }
          const base: string = segments[segments.length - 1].slice(0, -3);
          let materialId: string = base;
          if (materialIds.has(materialId)) materialId = `${kind}-${base}`;
          materialIds.add(materialId);
          materials.push({
            id: materialId,
            kind,
            title: base,
            content: decodeNovelWorkspaceUtf8(file.bytes),
            enabled: true,
            createdAt: manifest.createdAt,
            updatedAt: manifest.updatedAt,
          });
        }
      }
      chaptersWithOrdinal.sort((left, right): number => left.ordinal - right.ordinal);
      for (let i: number = 1; i < chaptersWithOrdinal.length; i++) {
        if (chaptersWithOrdinal[i - 1].ordinal === chaptersWithOrdinal[i].ordinal) {
          throw invalidInput(`章节序号重复: ${chaptersWithOrdinal[i].ordinal}`);
        }
      }
      const chapters: NovelChapter[] = chaptersWithOrdinal.map(
        (item): NovelChapter => item.chapter,
      );
      const project: NovelProject = {
        schemaVersion: NOVEL_SCHEMA_VERSION,
        id,
        name: manifest.title,
        messages: [],
        chapters,
        materials,
        createdAt: manifest.createdAt,
        updatedAt: manifest.updatedAt,
        modelId: null,
        lastGenerationGranularity: 'whole_chapter',
        materialSuggestions: [],
        settingProposals: [],
        branches: [{
          id: manifest.activeBranch,
          name: manifest.activeBranch === 'main' ? '主线' : manifest.activeBranch,
          lifecycle: 'active',
          isMain: true,
          forkFromChapterId: null,
          createdAt: manifest.createdAt,
        }],
        chapterVersions: [],
        revision: 0,
      };
      return await installSnapshot(project, 'workspace_import', input.files);
    },

    async workspaceFiles(id: string): Promise<NovelWorkspaceArchiveFile[]> {
      await loadProjectInternal(id);
      const files: NovelWorkspaceArchiveFile[] = await walkWorkspaceFiles(fileStore, projectRoot(id));
      return files.filter((file: NovelWorkspaceArchiveFile): boolean =>
        !file.path.startsWith('.amber/'));
    },

    async bookExportInput(id: string): Promise<NovelBookExportInput> {
      const project: NovelProject = await loadProjectInternal(id);
      const visible: NovelChapter[] = project.chapters.filter(
        (chapter: NovelChapter): boolean => !chapter.discarded,
      );
      const chapters: NovelBookChapterSnapshot[] = visible.map(
        (chapter: NovelChapter, index: number): NovelBookChapterSnapshot => ({
          ordinal: chapterOrdinal(chapter, index + 1),
          title: chapter.title,
          content: chapter.content,
        }),
      );
      return { bookTitle: project.name, chapters };
    },
  };
  return repository;
};
