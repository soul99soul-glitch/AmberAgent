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
import {
  parseWorkspaceCommit, resolveDurableWorkspaceProposal, validateDurableWorkspaceProposal,
  validateWorkspaceReceiptLedger,
} from './workspace_history.ts';
import type {
  DurableWorkspaceProposal, WorkspaceCommit, WorkspaceProposalPatch,
} from './workspace_history.ts';

const LEGACY_PROJECTS_DIR: string = 'amberagent/novel-creation/projects';
const LEGACY_SUFFIX: string = '.novel.json';
const ROOT: string = 'amberagent/novel-workspace';
const STAGING_ROOT: string = joinPath(ROOT, '.staging');
const TRASH_ROOT: string = joinPath(ROOT, '.trash');
const DELETED_ROOT: string = joinPath(ROOT, '.deleted');
const STATE_FILE: string = '.amber/project-state.json';
const LEDGER_FILE: string = '.amber/ledger.jsonl';
const CHECKOUT_FILE: string = '.amber/checkout.json';
const PROPOSALS_FILE: string = '.amber/proposals.json';
const MAX_PROJECT_BYTES: number = 16 * 1024 * 1024;
const WORKSPACE_STATE_VERSION: number = 1;

export type NovelWorkspaceMutationKind =
  | 'compat_update' | 'rename' | 'model_change' | 'manual_edit' | 'chapter_delete'
  | 'material_edit' | 'material_delete' | 'collect' | 'suggestion_refresh'
  | 'proposal_resolve' | 'generation_commit' | 'polish_adopt' | 'chapter_discard'
  | 'version_restore' | 'discussion_archive' | 'interrupt_commit' | 'create_branch'
  | 'undo' | 'plot_sync' | 'unresolved_resolve';

export interface NovelWorkspaceCas {
  branchId: string;
  head: string;
  treeDigest: string;
}

export interface NovelWorkspaceStatus {
  cas: NovelWorkspaceCas;
  activeBranchId: string;
  activeBranchName: string;
  branches: NovelBranch[];
  canUndo: boolean;
  unresolvedFromOrdinal: number | null;
  plotStale: boolean;
  pendingProposalCount: number;
}

export interface NovelProjectRepository {
  listProjects(): Promise<NovelProject[]>;
  loadProject(id: string): Promise<NovelProject>;
  createProject(project: NovelProject): Promise<NovelProject>;
  updateProject(id: string, transform: (p: NovelProject) => NovelProject): Promise<NovelProject>;
  workspaceStatus(id: string): Promise<NovelWorkspaceStatus>;
  commitProject(
    id: string, expected: NovelWorkspaceCas, commandId: string, kind: NovelWorkspaceMutationKind,
    transform: (p: NovelProject) => NovelProject,
  ): Promise<NovelProject>;
  createBranch(
    id: string, name: string, expected: NovelWorkspaceCas, commandId: string,
  ): Promise<NovelProject>;
  switchBranch(id: string, branchId: string, expected: NovelWorkspaceCas): Promise<NovelProject>;
  undo(id: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject>;
  syncPlot(id: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject>;
  resolveUnresolved(id: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject>;
  workspaceProposals(id: string): Promise<DurableWorkspaceProposal[]>;
  createProposal(
    id: string, expected: NovelWorkspaceCas, proposalId: string,
    patches: WorkspaceProposalPatch[], createdAt: number,
  ): Promise<DurableWorkspaceProposal>;
  resolveProposal(
    id: string, proposalId: string, accept: boolean, commandId: string, resolvedAt: number,
  ): Promise<DurableWorkspaceProposal>;
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

type WorkspaceLedgerCommit = WorkspaceCommit;

interface WorkspaceBranchSnapshot {
  branchId: string;
  state: WorkspaceProjectState;
  unresolvedFromOrdinal: number | null;
  plotStale: boolean;
  plotSourceTreeDigest: string;
  plotSyncedTreeDigest: string | null;
  plotContent: string | null;
}

interface WorkspaceCommitSnapshot {
  project: NovelProject;
  unresolvedFromOrdinal: number | null;
  plotStale: boolean;
  plotSourceTreeDigest: string;
  plotSyncedTreeDigest: string | null;
  plotContent: string | null;
}

interface WorkspaceUndoRecord {
  branchId: string;
  snapshot: WorkspaceCommitSnapshot;
}

interface WorkspaceProposalDecision {
  commandId: string;
  proposalId: string;
  accept: boolean;
}

interface WorkspaceProposalStore {
  version: number;
  proposals: DurableWorkspaceProposal[];
  decisions: WorkspaceProposalDecision[];
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
const branchSnapshotPath = (branchId: string): string => `.amber/branches/${branchId}.json`;
const commitSnapshotPath = (head: string): string => `.amber/commits/${head}.json`;
const undoPath = (branchId: string): string => `.amber/undo/${branchId}.json`;

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

const parseLedger = (raw: string): WorkspaceLedgerCommit[] => {
  const lines: string[] = raw.split('\n').filter((line: string): boolean => line.trim().length > 0);
  const commits: WorkspaceLedgerCommit[] = [];
  for (let i: number = 0; i < lines.length; i++) {
    try {
      commits.push(parseWorkspaceCommit(lines[i]));
    } catch {
      throw invalidInput('工作区台账损坏');
    }
  }
  if (commits.length === 0) throw invalidInput('工作区台账为空');
  let checked: WorkspaceLedgerCommit[];
  try {
    checked = validateWorkspaceReceiptLedger(commits);
  } catch {
    throw invalidInput('工作区台账损坏');
  }
  const commitIds: Set<string> = new Set<string>();
  for (let i: number = 0; i < checked.length; i++) {
    const commit: WorkspaceLedgerCommit = checked[i];
    if (commitIds.has(commit.commitId)) throw invalidInput('工作区台账 commit 重复');
    if (i === 0) {
      if (commit.parent !== null) throw invalidInput('工作区台账初始 parent 无效');
    } else if (commit.parent === null || !commitIds.has(commit.parent)) {
      throw invalidInput('工作区台账 parent 链损坏');
    }
    commitIds.add(commit.commitId);
  }
  return checked;
};

const findCommit = (commits: WorkspaceLedgerCommit[], head: string): WorkspaceLedgerCommit | null => {
  for (let i: number = commits.length - 1; i >= 0; i--) {
    if (commits[i].commitId === head) return commits[i];
  }
  return null;
};

const findReceipt = (commits: WorkspaceLedgerCommit[], receipt: string): WorkspaceLedgerCommit | null => {
  for (let i: number = commits.length - 1; i >= 0; i--) {
    if (commits[i].receipt === receipt) return commits[i];
  }
  return null;
};

const assertCas = (expected: NovelWorkspaceCas, actual: WorkspaceCheckout): void => {
  if (expected.branchId !== actual.branchId || expected.head !== actual.head ||
    expected.treeDigest !== actual.treeDigest) {
    throw invalidInput('工作区已变化，拒绝覆盖');
  }
};

const assertCommandId = (commandId: string): void => {
  if (commandId.trim().length === 0 || commandId.length > 256 || commandId.indexOf('\n') >= 0) {
    throw invalidInput('commandId 无效');
  }
};

const chapterChangedAt = (before: NovelChapter[], after: NovelChapter[]): number | null => {
  const count: number = Math.max(before.length, after.length);
  for (let i: number = 0; i < count; i++) {
    const left: NovelChapter | undefined = before[i];
    const right: NovelChapter | undefined = after[i];
    if (left === undefined || right === undefined || left.id !== right.id || left.title !== right.title ||
      left.content !== right.content || left.discarded !== right.discarded) return i;
  }
  return null;
};

const followingUnresolvedOrdinal = (changedIndex: number | null, chapterCount: number): number | null => {
  if (changedIndex === null) return null;
  const following: number = changedIndex + 2;
  return following <= chapterCount ? following : null;
};

const emptyProposalStore = (): WorkspaceProposalStore => ({
  version: 1,
  proposals: [],
  decisions: [],
});

const parseProposalStore = (raw: string): WorkspaceProposalStore => {
  let value: WorkspaceProposalStore;
  try {
    value = JSON.parse(raw) as WorkspaceProposalStore;
  } catch {
    throw invalidInput('durable proposal store 无效');
  }
  if (value.version !== 1 || !Array.isArray(value.proposals) || !Array.isArray(value.decisions)) {
    throw invalidInput('durable proposal store 无效');
  }
  const proposals: DurableWorkspaceProposal[] = [];
  const proposalIds: Set<string> = new Set<string>();
  for (let i: number = 0; i < value.proposals.length; i++) {
    const proposal: DurableWorkspaceProposal = validateDurableWorkspaceProposal(value.proposals[i]);
    if (proposalIds.has(proposal.proposalId)) throw invalidInput(`proposal id 重复: ${proposal.proposalId}`);
    proposalIds.add(proposal.proposalId);
    proposals.push(proposal);
  }
  const decisions: WorkspaceProposalDecision[] = [];
  const commandIds: Set<string> = new Set<string>();
  for (let i: number = 0; i < value.decisions.length; i++) {
    const decision: WorkspaceProposalDecision = value.decisions[i];
    assertCommandId(decision.commandId);
    if (decision.proposalId.trim().length === 0 || commandIds.has(decision.commandId)) {
      throw invalidInput('proposal decision store 无效');
    }
    commandIds.add(decision.commandId);
    decisions.push({
      commandId: decision.commandId,
      proposalId: decision.proposalId,
      accept: decision.accept,
    });
  }
  return { version: 1, proposals, decisions };
};

const materialPath = (material: NovelMaterial): string =>
  `setting/${material.kind}/${material.id}.md`;

const branchMaterialPath = (branchId: string, material: NovelMaterial): string =>
  `branches/${branchId}/setting/${material.kind}/${material.id}.md`;

const chapterOrdinal = (chapter: NovelChapter, fallback: number): number => {
  const match: RegExpMatchArray | null = chapter.id.match(/^chapter-(\d{3})(?:-|$)/);
  if (match === null) return fallback;
  const parsed: number = Number.parseInt(match[1], 10);
  return parsed >= 1 && parsed <= 999 ? parsed : fallback;
};

const buildSnapshotFiles = (
  input: NovelProject, activeBranchId: string | null = null, plotContent: string | null = null,
): { project: NovelProject; state: WorkspaceProjectState; files: SnapshotFile[] } => {
  const project: NovelProject = normalizeProject(validateProject(input));
  const branch: NovelBranch | undefined = activeBranchId === null
    ? mainBranch(project)
    : project.branches.find((item: NovelBranch): boolean =>
      item.id === activeBranchId && item.lifecycle === 'active');
  if (branch === undefined) throw invalidInput(`分支不存在: ${activeBranchId ?? ''}`);
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
  if (plotContent !== null) {
    files.push({ path: `branches/${branch.id}/plan/plot.md`, content: plotContent });
  }
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
    const relative: string = branchMaterialPath(branch.id, material);
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
    files.push({ path: materialPath(material), content: material.content });
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
  return { project, state, files };
};

const snapshotTreeDigest = (files: SnapshotFile[]): string => stableDigest(files
  .filter((file: SnapshotFile): boolean =>
    !file.path.startsWith('.amber/') && file.path !== 'manifest.yaml' && file.path !== 'project.md')
  .map((file: SnapshotFile): NovelWorkspaceArchiveFile => ({
    path: file.path,
    bytes: encodeUtf8(file.content),
  })));

const branchStateMatchesCommit = (
  current: WorkspaceProjectState, committed: WorkspaceProjectState,
): boolean => JSON.stringify(current.chapters) === JSON.stringify(committed.chapters) &&
  JSON.stringify(current.materials) === JSON.stringify(committed.materials) &&
  JSON.stringify(current.project.messages) === JSON.stringify(committed.project.messages) &&
  JSON.stringify(current.project.materialSuggestions) === JSON.stringify(committed.project.materialSuggestions) &&
  JSON.stringify(current.project.settingProposals) === JSON.stringify(committed.project.settingProposals) &&
  JSON.stringify(current.project.chapterVersions) === JSON.stringify(committed.project.chapterVersions) &&
  current.project.revision === committed.project.revision;

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

const projectFromStoredState = async (
  fileStore: FileStore, root: string, state: WorkspaceProjectState,
): Promise<NovelProject> => {
  const chapters: NovelChapter[] = [];
  for (let i: number = 0; i < state.chapters.length; i++) {
    const record: WorkspaceChapterRecord = state.chapters[i];
    const content: string | null = await fileStore.readText(joinPath(root, record.path));
    if (content === null) throw invalidInput(`章节文件缺失: ${record.path}`);
    chapters.push({
      id: record.id, title: record.title, content,
      createdAt: record.createdAt, updatedAt: record.updatedAt, discarded: record.discarded,
    });
  }
  const materials: NovelMaterial[] = [];
  for (let i: number = 0; i < state.materials.length; i++) {
    const record: WorkspaceMaterialRecord = state.materials[i];
    const content: string | null = await fileStore.readText(joinPath(root, record.path));
    if (content === null) throw invalidInput(`设定文件缺失: ${record.path}`);
    materials.push({
      id: record.id, kind: record.kind, title: record.title, content, enabled: record.enabled,
      createdAt: record.createdAt, updatedAt: record.updatedAt,
    });
  }
  return validateProject({ ...state.project, chapters, materials });
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
  const project: NovelProject = validateProject({ ...state.project, name: manifest.title, chapters, materials });
  const checkoutRaw: string | null = await fileStore.readText(joinPath(root, CHECKOUT_FILE));
  const ledgerRaw: string | null = await fileStore.readText(joinPath(root, LEDGER_FILE));
  if (checkoutRaw === null || ledgerRaw === null) throw invalidInput('小说工作区历史不完整');
  const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, root);
  if (checkout === null || checkout.branchId !== manifest.activeBranch) {
    throw invalidInput('checkout 与 manifest 不一致');
  }
  const commits: WorkspaceLedgerCommit[] = parseLedger(ledgerRaw);
  const headCommit: WorkspaceLedgerCommit | null = findCommit(commits, checkout.head);
  if (headCommit === null || headCommit.branchId !== checkout.branchId ||
    headCommit.treeDigest !== checkout.treeDigest) {
    throw invalidInput('checkout/head/tree 不一致');
  }
  const branchRaw: string | null = await fileStore.readText(joinPath(root, branchSnapshotPath(checkout.branchId)));
  if (branchRaw === null) throw invalidInput('active branch state 缺失');
  let branchSnapshot: WorkspaceBranchSnapshot;
  try {
    branchSnapshot = JSON.parse(branchRaw) as WorkspaceBranchSnapshot;
  } catch {
    throw invalidInput('active branch state 无效');
  }
  if (branchSnapshot.branchId !== checkout.branchId ||
    JSON.stringify(branchSnapshot.state) !== JSON.stringify(state)) {
    throw invalidInput('active branch state 与 checkout 不一致');
  }
  if (branchSnapshot.plotSourceTreeDigest !== checkout.treeDigest ||
    branchSnapshot.plotStale !==
      (branchSnapshot.plotSyncedTreeDigest !== branchSnapshot.plotSourceTreeDigest)) {
    throw invalidInput('plot digest 与 branch tree 不一致');
  }
  const built = buildSnapshotFiles(project, checkout.branchId, branchSnapshot.plotContent);
  if (snapshotTreeDigest(built.files) !== checkout.treeDigest) {
    throw invalidInput('正文树与台账不一致');
  }
  const commitRaw: string | null = await fileStore.readText(joinPath(root, commitSnapshotPath(checkout.head)));
  if (commitRaw === null) throw invalidInput('head snapshot 缺失');
  try {
    const commitSnapshot: WorkspaceCommitSnapshot = JSON.parse(commitRaw) as WorkspaceCommitSnapshot;
    const normalizedCommitState: WorkspaceProjectState = buildSnapshotFiles(
      commitSnapshot.project, checkout.branchId).state;
    if (!branchStateMatchesCommit(state, normalizedCommitState) ||
      commitSnapshot.unresolvedFromOrdinal !== branchSnapshot.unresolvedFromOrdinal ||
      commitSnapshot.plotStale !== branchSnapshot.plotStale ||
      commitSnapshot.plotSourceTreeDigest !== branchSnapshot.plotSourceTreeDigest ||
      commitSnapshot.plotSyncedTreeDigest !== branchSnapshot.plotSyncedTreeDigest ||
      commitSnapshot.plotContent !== branchSnapshot.plotContent) {
      throw invalidInput('branch state 与 head 不一致');
    }
  } catch (error) {
    if (error instanceof Error && error.message.indexOf('branch state') >= 0) throw error;
    throw invalidInput('head snapshot 无效');
  }
  return project;
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
    state.materials.forEach((record: WorkspaceMaterialRecord): void => {
      managed.add(record.path);
      managed.add(`setting/${record.kind}/${record.id}.md`);
    });
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

  const readProposalStore = async (id: string): Promise<WorkspaceProposalStore> => {
    const raw: string | null = await fileStore.readText(joinPath(projectRoot(id), PROPOSALS_FILE));
    return raw === null ? emptyProposalStore() : parseProposalStore(raw);
  };

  const installProposalStoreOnly = async (id: string, store: WorkspaceProposalStore): Promise<void> => {
    const live: string = projectRoot(id);
    const stage: string = stagingRoot(id);
    const backup: string = trashRoot(id);
    const files: NovelWorkspaceArchiveFile[] = await walkWorkspaceFiles(fileStore, live);
    await fileStore.deleteTree(stage);
    for (let i: number = 0; i < files.length; i++) {
      const path: string = files[i].path;
      if (path === PROPOSALS_FILE) continue;
      if (path.startsWith('.amber/') || path.endsWith('.md') || path.endsWith('.yaml')) {
        await fileStore.writeText(joinPath(stage, path), decodeNovelWorkspaceUtf8(files[i].bytes));
      } else {
        await fileStore.writeBytes(joinPath(stage, path), files[i].bytes);
      }
    }
    await fileStore.writeText(joinPath(stage, PROPOSALS_FILE), JSON.stringify(store));
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
  };

  const validateProposalTargets = (
    proposal: DurableWorkspaceProposal, branch: WorkspaceBranchSnapshot,
  ): void => {
    const plotPath: string = `branches/${branch.branchId}/plan/plot.md`;
    const chapterPaths: Set<string> = new Set<string>();
    for (let i: number = 0; i < branch.state.chapters.length; i++) {
      chapterPaths.add(branch.state.chapters[i].path);
    }
    for (let i: number = 0; i < proposal.patches.length; i++) {
      const path: string = proposal.patches[i].path;
      if (path !== plotPath && !chapterPaths.has(path)) {
        throw invalidInput(`proposal 仅可修改已有章节或 plot: ${path}`);
      }
    }
  };

  const materializeCheckout = async (
    id: string, currentState: WorkspaceProjectState, target: NovelProject,
    targetSnapshot: WorkspaceBranchSnapshot, checkout: WorkspaceCheckout,
  ): Promise<void> => {
    const live: string = projectRoot(id);
    const stage: string = stagingRoot(id);
    const backup: string = trashRoot(id);
    const allFiles: NovelWorkspaceArchiveFile[] = await walkWorkspaceFiles(fileStore, live);
    await fileStore.deleteTree(stage);
    for (let i: number = 0; i < allFiles.length; i++) {
      const path: string = allFiles[i].path;
      if (path.startsWith('.amber/') || path.endsWith('.md') || path.endsWith('.yaml')) {
        await fileStore.writeText(joinPath(stage, path), decodeNovelWorkspaceUtf8(allFiles[i].bytes));
      } else {
        await fileStore.writeBytes(joinPath(stage, path), allFiles[i].bytes);
      }
    }
    for (let i: number = 0; i < currentState.materials.length; i++) {
      const material: WorkspaceMaterialRecord = currentState.materials[i];
      await fileStore.delete(joinPath(stage, `setting/${material.kind}/${material.id}.md`));
    }
    const built = buildSnapshotFiles(target, checkout.branchId, targetSnapshot.plotContent);
    if (snapshotTreeDigest(built.files) !== checkout.treeDigest) {
      throw invalidInput('目标分支 head/tree 不一致');
    }
    const targetState: WorkspaceProjectState = built.state;
    const durableTarget: WorkspaceBranchSnapshot = {
      ...targetSnapshot,
      state: targetState,
      plotSourceTreeDigest: checkout.treeDigest,
    };
    const writes: SnapshotFile[] = built.files.filter(
      (file: SnapshotFile): boolean => file.path !== 'project.md').concat([
      { path: 'manifest.yaml', content: serializeNovelWorkspaceManifest({
        format: NOVEL_WORKSPACE_FORMAT,
        version: NOVEL_WORKSPACE_VERSION,
        projectId: target.id,
        title: target.name,
        activeBranch: checkout.branchId,
        createdAt: target.createdAt,
        updatedAt: target.updatedAt,
      }) },
      { path: STATE_FILE, content: JSON.stringify(targetState) },
      { path: CHECKOUT_FILE, content: JSON.stringify(checkout) },
      { path: branchSnapshotPath(checkout.branchId), content: JSON.stringify(durableTarget) },
    ]);
    for (let i: number = 0; i < writes.length; i++) {
      await fileStore.writeText(joinPath(stage, writes[i].path), writes[i].content);
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
  };

  const installSnapshot = async (
    input: NovelProject, mutation: string, importedFiles: NovelWorkspaceArchiveFile[] = [],
    branchId: string | null = null, receipt: string | null = null,
    unresolvedFromOrdinal: number | null = null, plotStale: boolean = false,
    makeUndo: boolean = false, changedChapterOrdinal: number | null = null,
    preservePriorBranch: boolean = false,
    clearUndo: boolean = false,
    plotSyncedOverride?: string | null,
    plotContentOverride?: string | null,
    proposalStoreOverride: WorkspaceProposalStore | null = null,
  ): Promise<NovelProject> => {
    const normalized: NovelProject = normalizeProject(validateProject(input));
    const activeBranchId: string = branchId ?? mainBranch(normalized).id;
    let built = buildSnapshotFiles(normalized, activeBranchId);
    const project: NovelProject = built.project;
    const live: string = projectRoot(project.id);
    const stage: string = stagingRoot(project.id);
    const backup: string = trashRoot(project.id);
    await recoverProject(project.id);
    const liveExists: boolean = await fileStore.exists(live);
    const priorLedgerRaw: string = liveExists
      ? (await fileStore.readText(joinPath(live, LEDGER_FILE)) ?? '') : '';
    const priorCommits: WorkspaceLedgerCommit[] = liveExists ? parseLedger(priorLedgerRaw) : [];
    const priorCheckout: WorkspaceCheckout | null = liveExists
      ? await readCheckout(fileStore, live)
      : null;
    let priorActiveSnapshot: WorkspaceBranchSnapshot | null = null;
    if (liveExists && priorCheckout !== null) {
      const priorBranchRaw: string | null = await fileStore.readText(
        joinPath(live, branchSnapshotPath(priorCheckout.branchId)));
      if (priorBranchRaw !== null) {
        priorActiveSnapshot = JSON.parse(priorBranchRaw) as WorkspaceBranchSnapshot;
      }
    }
    const plotContent: string | null = plotContentOverride !== undefined
      ? plotContentOverride
      : priorActiveSnapshot === null ? null : priorActiveSnapshot.plotContent;
    built = buildSnapshotFiles(normalized, activeBranchId, plotContent);
    let preservedPublic: NovelWorkspaceArchiveFile[] = liveExists
      ? await passthroughFilesAt(fileStore, live)
      : importedFiles;
    if (plotContentOverride !== undefined) {
      const plotPath: string = `branches/${activeBranchId}/plan/plot.md`;
      preservedPublic = preservedPublic.filter(
        (file: NovelWorkspaceArchiveFile): boolean => file.path !== plotPath);
    }
    const allPrior: NovelWorkspaceArchiveFile[] = liveExists
      ? await walkWorkspaceFiles(fileStore, live) : [];
    if (preservePriorBranch && priorCheckout !== null) {
      const prefix: string = `branches/${priorCheckout.branchId}/`;
      const priorBranchFiles: NovelWorkspaceArchiveFile[] = allPrior.filter(
        (file: NovelWorkspaceArchiveFile): boolean => file.path.startsWith(prefix),
      );
      preservedPublic = preservedPublic.concat(priorBranchFiles);
    }
    const preservedPrivate: NovelWorkspaceArchiveFile[] = allPrior.filter(
      (file: NovelWorkspaceArchiveFile): boolean => file.path.startsWith('.amber/') &&
        file.path !== STATE_FILE && file.path !== LEDGER_FILE && file.path !== CHECKOUT_FILE &&
        file.path !== branchSnapshotPath(activeBranchId) &&
        !(proposalStoreOverride !== null && file.path === PROPOSALS_FILE) &&
        !(clearUndo && file.path === undoPath(activeBranchId)),
    );
    const preserved: NovelWorkspaceArchiveFile[] = preservedPublic.concat(preservedPrivate);
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
      (file: NovelWorkspaceArchiveFile): boolean => !generatedPaths.has(file.path),
    );
    const treeDigest: string = snapshotTreeDigest(generatedFiles);
    const head: string = `c${priorCommits.length}-${treeDigest}`;
    const commandReceipt: string = receipt ?? `${mutation}:${project.id}:${priorCommits.length}`;
    const commit: WorkspaceLedgerCommit = {
      version: 1,
      commitId: head,
      parent: priorCheckout === null ? null : priorCheckout.head,
      branchId: activeBranchId,
      treeDigest,
      mutation,
      receipt: commandReceipt,
      changedPaths: generatedFiles.filter((file: SnapshotFile): boolean =>
        !file.path.startsWith('.amber/') && file.path !== 'manifest.yaml' && file.path !== 'project.md')
        .map((file: SnapshotFile): string => file.path),
      changedChapterOrdinal,
      createdAt: project.updatedAt,
    };
    if (commit.changedPaths.length === 0) {
      commit.changedPaths = [`branches/${activeBranchId}/plan/state.md`];
    }
    const commits: WorkspaceLedgerCommit[] = priorCommits.concat([commit]);
    const checkout: WorkspaceCheckout = {
      branchId: activeBranchId,
      head,
      treeDigest,
    };
    const plotSyncedTreeDigest: string | null = plotSyncedOverride !== undefined
      ? plotSyncedOverride
      : plotStale && priorActiveSnapshot !== null
        ? priorActiveSnapshot.plotSyncedTreeDigest
        : treeDigest;
    const branchSnapshot: WorkspaceBranchSnapshot = {
      branchId: activeBranchId,
      state: built.state,
      unresolvedFromOrdinal,
      plotStale: plotSyncedTreeDigest !== treeDigest,
      plotSourceTreeDigest: treeDigest,
      plotSyncedTreeDigest,
      plotContent,
    };
    const commitSnapshot: WorkspaceCommitSnapshot = {
      project,
      unresolvedFromOrdinal,
      plotStale: branchSnapshot.plotStale,
      plotSourceTreeDigest: treeDigest,
      plotSyncedTreeDigest: branchSnapshot.plotSyncedTreeDigest,
      plotContent,
    };
    const files: SnapshotFile[] = generatedFiles.concat([
      { path: LEDGER_FILE, content: commits.map(
        (item: WorkspaceLedgerCommit): string => JSON.stringify(item)).join('\n') + '\n' },
      { path: CHECKOUT_FILE, content: JSON.stringify(checkout) },
      { path: branchSnapshotPath(activeBranchId), content: JSON.stringify(branchSnapshot) },
      { path: commitSnapshotPath(head), content: JSON.stringify(commitSnapshot) },
    ]);
    if (makeUndo && liveExists && priorCheckout !== null) {
      const priorProject: NovelProject = await loadAt(fileStore, live);
      if (priorActiveSnapshot === null) throw invalidInput('active branch state 缺失');
      const priorBranch: WorkspaceBranchSnapshot = priorActiveSnapshot;
      const undo: WorkspaceUndoRecord = {
        branchId: priorCheckout.branchId,
        snapshot: {
          project: priorProject,
          unresolvedFromOrdinal: priorBranch.unresolvedFromOrdinal,
          plotStale: priorBranch.plotStale,
          plotSourceTreeDigest: priorBranch.plotSourceTreeDigest,
          plotSyncedTreeDigest: priorBranch.plotSyncedTreeDigest,
          plotContent: priorBranch.plotContent,
        },
      };
      files.push({ path: undoPath(activeBranchId), content: JSON.stringify(undo) });
    }
    if (proposalStoreOverride !== null) {
      files.push({ path: PROPOSALS_FILE, content: JSON.stringify(proposalStoreOverride) });
    }
    for (let i: number = 0; i < files.length; i++) {
      await fileStore.writeText(joinPath(stage, files[i].path), files[i].content);
    }
    for (let i: number = 0; i < passthrough.length; i++) {
      if (passthrough[i].path.startsWith('.amber/') || passthrough[i].path.endsWith('.md') ||
        passthrough[i].path.endsWith('.yaml')) {
        await fileStore.writeText(
          joinPath(stage, passthrough[i].path), decodeNovelWorkspaceUtf8(passthrough[i].bytes));
      } else {
        await fileStore.writeBytes(joinPath(stage, passthrough[i].path), passthrough[i].bytes);
      }
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

  const readActiveBranchSnapshot = async (id: string, branchId: string): Promise<WorkspaceBranchSnapshot> => {
    const path: string = joinPath(projectRoot(id), branchSnapshotPath(branchId));
    const raw: string | null = await fileStore.readText(path);
    if (raw === null) throw invalidInput(`active branch state 缺失: ${path}`);
    try {
      return JSON.parse(raw) as WorkspaceBranchSnapshot;
    } catch {
      throw invalidInput('active branch state 无效');
    }
  };

  const readCommits = async (id: string): Promise<WorkspaceLedgerCommit[]> => {
    const raw: string | null = await fileStore.readText(joinPath(projectRoot(id), LEDGER_FILE));
    if (raw === null) throw notFound('project', id);
    return parseLedger(raw);
  };

  const replayProject = async (
    id: string, commits: WorkspaceLedgerCommit[], commandId: string,
  ): Promise<NovelProject | null> => {
    const replay: WorkspaceLedgerCommit | null = findReceipt(commits, commandId);
    if (replay === null) return null;
    const raw: string | null = await fileStore.readText(
      joinPath(projectRoot(id), commitSnapshotPath(replay.commitId)));
    if (raw === null) throw invalidInput('receipt snapshot 缺失');
    try {
      const snapshot: WorkspaceCommitSnapshot = JSON.parse(raw) as WorkspaceCommitSnapshot;
      return validateProject(snapshot.project);
    } catch {
      throw invalidInput('receipt snapshot 无效');
    }
  };

  const commitLocked = async (
    id: string, expected: NovelWorkspaceCas, commandId: string, kind: NovelWorkspaceMutationKind,
    transform: (p: NovelProject) => NovelProject,
  ): Promise<NovelProject> => {
    assertCommandId(commandId);
    await recoverProject(id);
    const commits: WorkspaceLedgerCommit[] = await readCommits(id);
    const replay: NovelProject | null = await replayProject(id, commits, commandId);
    if (replay !== null) return replay;
    const current: NovelProject = await loadProjectInternal(id);
    const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
    if (checkout === null) throw invalidInput('checkout 无效');
    assertCas(expected, checkout);
    const branch: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, checkout.branchId);
    const transformed: NovelProject = transform(current);
    if (transformed.id !== id) throw invalidInput('项目 id 不可变更');
    const next: NovelProject = { ...transformed, revision: current.revision + 1 };
    const changedIndex: number | null = chapterChangedAt(current.chapters, next.chapters);
    const unresolved: number | null = changedIndex === null
      ? branch.unresolvedFromOrdinal
      : followingUnresolvedOrdinal(changedIndex, next.chapters.length);
    const stale: boolean = changedIndex === null ? branch.plotStale : true;
    return await installSnapshot(
      next, kind, [], checkout.branchId, commandId, unresolved, stale, true,
      changedIndex === null ? null : changedIndex + 1,
    );
  };

  const latestBranchCommit = (
    commits: WorkspaceLedgerCommit[], branchId: string,
  ): WorkspaceLedgerCommit | null => {
    for (let i: number = commits.length - 1; i >= 0; i--) {
      if (commits[i].branchId === branchId) return commits[i];
    }
    return null;
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
        await loadProjectInternal(id);
        const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
        if (checkout === null) throw invalidInput('checkout 无效');
        return await commitLocked(id, checkout, novelId(), 'compat_update', transform);
      }),

    workspaceStatus: (id: string): Promise<NovelWorkspaceStatus> =>
      withProjectLock(id, async (): Promise<NovelWorkspaceStatus> => {
        const project: NovelProject = await loadProjectInternal(id);
        const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
        if (checkout === null) throw invalidInput('checkout 无效');
        const branchState: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, checkout.branchId);
        const active: NovelBranch | undefined = project.branches.find(
          (branch: NovelBranch): boolean => branch.id === checkout.branchId && branch.lifecycle === 'active');
        if (active === undefined) throw invalidInput('active branch 不存在');
        const proposalStore: WorkspaceProposalStore = await readProposalStore(id);
        return {
          cas: checkout,
          activeBranchId: active.id,
          activeBranchName: active.name,
          branches: project.branches.filter(
            (branch: NovelBranch): boolean => branch.lifecycle === 'active'),
          canUndo: await fileStore.exists(joinPath(projectRoot(id), undoPath(active.id))),
          unresolvedFromOrdinal: branchState.unresolvedFromOrdinal,
          plotStale: branchState.plotSourceTreeDigest !== branchState.plotSyncedTreeDigest,
          pendingProposalCount: project.settingProposals.filter(
            (proposal: NovelSettingProposal): boolean => proposal.status === 'pending').length +
            proposalStore.proposals.filter(
              (proposal: DurableWorkspaceProposal): boolean => proposal.status === 'pending').length,
        };
      }),

    commitProject: (
      id: string, expected: NovelWorkspaceCas, commandId: string, kind: NovelWorkspaceMutationKind,
      transform: (p: NovelProject) => NovelProject,
    ): Promise<NovelProject> => withProjectLock(
      id, async (): Promise<NovelProject> => await commitLocked(id, expected, commandId, kind, transform)),

    createBranch: (
      id: string, name: string, expected: NovelWorkspaceCas, commandId: string,
    ): Promise<NovelProject> => withProjectLock(id, async (): Promise<NovelProject> => {
      assertCommandId(commandId);
      const commits: WorkspaceLedgerCommit[] = await readCommits(id);
      const replay: NovelProject | null = await replayProject(id, commits, commandId);
      if (replay !== null) return replay;
      const current: NovelProject = await loadProjectInternal(id);
      const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
      if (checkout === null) throw invalidInput('checkout 无效');
      assertCas(expected, checkout);
      const clean: string = name.trim();
      if (clean.length === 0) throw invalidInput('分支名不能为空');
      const currentState: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, checkout.branchId);
      const branch: NovelBranch = {
        id: novelId(), name: clean, lifecycle: 'active', isMain: false,
        forkFromChapterId: current.chapters.length === 0
          ? null : current.chapters[current.chapters.length - 1].id,
        createdAt: Date.now(),
      };
      const fork: NovelProject = {
        ...current,
        branches: current.branches.concat([branch]),
        revision: current.revision + 1,
        updatedAt: Date.now(),
      };
      return await installSnapshot(
        fork, 'create_branch', [], branch.id, commandId,
        currentState.unresolvedFromOrdinal, currentState.plotStale, false, null, true,
      );
    }),

    switchBranch: (
      id: string, targetBranchId: string, expected: NovelWorkspaceCas,
    ): Promise<NovelProject> => withProjectLock(id, async (): Promise<NovelProject> => {
      const current: NovelProject = await loadProjectInternal(id);
      const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
      if (checkout === null) throw invalidInput('checkout 无效');
      assertCas(expected, checkout);
      if (targetBranchId === checkout.branchId) return current;
      const targetBranch: NovelBranch | undefined = current.branches.find(
        (branch: NovelBranch): boolean => branch.id === targetBranchId && branch.lifecycle === 'active');
      if (targetBranch === undefined) throw invalidInput(`分支不存在: ${targetBranchId}`);
      const targetSnapshot: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, targetBranchId);
      const targetStored: NovelProject = await projectFromStoredState(
        fileStore, projectRoot(id), targetSnapshot.state);
      const target: NovelProject = {
        ...targetStored,
        name: current.name,
        modelId: current.modelId,
        lastGenerationGranularity: current.lastGenerationGranularity,
        branches: current.branches,
        updatedAt: current.updatedAt,
      };
      const commits: WorkspaceLedgerCommit[] = await readCommits(id);
      const targetHead: WorkspaceLedgerCommit | null = latestBranchCommit(commits, targetBranchId);
      if (targetHead === null || snapshotTreeDigest(
        buildSnapshotFiles(target, targetBranchId, targetSnapshot.plotContent).files) !==
        targetHead.treeDigest) {
        throw invalidInput('目标分支 head/tree 不一致');
      }
      const targetCheckout: WorkspaceCheckout = {
        branchId: targetBranchId,
        head: targetHead.commitId,
        treeDigest: targetHead.treeDigest,
      };
      const currentState: WorkspaceProjectState = buildSnapshotFiles(current, checkout.branchId).state;
      await materializeCheckout(id, currentState, target, targetSnapshot, targetCheckout);
      return target;
    }),

    undo: (
      id: string, expected: NovelWorkspaceCas, commandId: string,
    ): Promise<NovelProject> => withProjectLock(id, async (): Promise<NovelProject> => {
      assertCommandId(commandId);
      const commits: WorkspaceLedgerCommit[] = await readCommits(id);
      const replay: NovelProject | null = await replayProject(id, commits, commandId);
      if (replay !== null) return replay;
      const current: NovelProject = await loadProjectInternal(id);
      const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
      if (checkout === null) throw invalidInput('checkout 无效');
      assertCas(expected, checkout);
      const raw: string | null = await fileStore.readText(joinPath(projectRoot(id), undoPath(checkout.branchId)));
      if (raw === null) throw invalidInput('没有可撤销的提交');
      let undo: WorkspaceUndoRecord;
      try {
        undo = JSON.parse(raw) as WorkspaceUndoRecord;
      } catch {
        throw invalidInput('undo snapshot 无效');
      }
      if (undo.branchId !== checkout.branchId) throw invalidInput('undo snapshot 分支不一致');
      const restored: NovelProject = {
        ...undo.snapshot.project,
        branches: current.branches,
        revision: current.revision + 1,
        updatedAt: Date.now(),
      };
      return await installSnapshot(
        restored, 'undo', [], checkout.branchId, commandId,
        undo.snapshot.unresolvedFromOrdinal, undo.snapshot.plotStale, false, null, false, true,
        undo.snapshot.plotSyncedTreeDigest, undo.snapshot.plotContent,
      );
    }),

    syncPlot: (
      id: string, expected: NovelWorkspaceCas, commandId: string,
    ): Promise<NovelProject> => withProjectLock(id, async (): Promise<NovelProject> => {
      assertCommandId(commandId);
      const commits: WorkspaceLedgerCommit[] = await readCommits(id);
      const replay: NovelProject | null = await replayProject(id, commits, commandId);
      if (replay !== null) return replay;
      const current: NovelProject = await loadProjectInternal(id);
      const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
      if (checkout === null) throw invalidInput('checkout 无效');
      assertCas(expected, checkout);
      const branch: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, checkout.branchId);
      const next: NovelProject = { ...current, revision: current.revision + 1, updatedAt: Date.now() };
      return await installSnapshot(
        next, 'plot_sync', [], checkout.branchId, commandId,
        branch.unresolvedFromOrdinal, false, true,
      );
    }),

    resolveUnresolved: (
      id: string, expected: NovelWorkspaceCas, commandId: string,
    ): Promise<NovelProject> => withProjectLock(id, async (): Promise<NovelProject> => {
      assertCommandId(commandId);
      const commits: WorkspaceLedgerCommit[] = await readCommits(id);
      const replay: NovelProject | null = await replayProject(id, commits, commandId);
      if (replay !== null) return replay;
      const current: NovelProject = await loadProjectInternal(id);
      const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
      if (checkout === null) throw invalidInput('checkout 无效');
      assertCas(expected, checkout);
      const branch: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, checkout.branchId);
      const next: NovelProject = { ...current, revision: current.revision + 1, updatedAt: Date.now() };
      return await installSnapshot(
        next, 'unresolved_resolve', [], checkout.branchId, commandId, null, branch.plotStale, true,
      );
    }),

    workspaceProposals: (id: string): Promise<DurableWorkspaceProposal[]> =>
      withProjectLock(id, async (): Promise<DurableWorkspaceProposal[]> => {
        await loadProjectInternal(id);
        return (await readProposalStore(id)).proposals.slice();
      }),

    createProposal: (
      id: string, expected: NovelWorkspaceCas, proposalId: string,
      patches: WorkspaceProposalPatch[], createdAt: number,
    ): Promise<DurableWorkspaceProposal> => withProjectLock(
      id, async (): Promise<DurableWorkspaceProposal> => {
        const current: NovelProject = await loadProjectInternal(id);
        const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
        if (checkout === null) throw invalidInput('checkout 无效');
        assertCas(expected, checkout);
        const proposal: DurableWorkspaceProposal = validateDurableWorkspaceProposal({
          proposalId,
          status: 'pending',
          expectedCas: {
            branchId: expected.branchId,
            head: expected.head,
            treeDigest: expected.treeDigest,
          },
          patches,
          createdAt,
          resolvedAt: null,
        });
        const branch: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, checkout.branchId);
        validateProposalTargets(proposal, branch);
        const store: WorkspaceProposalStore = await readProposalStore(id);
        const existing: DurableWorkspaceProposal | undefined = store.proposals.find(
          (item: DurableWorkspaceProposal): boolean => item.proposalId === proposal.proposalId);
        if (existing !== undefined) {
          if (JSON.stringify(existing) !== JSON.stringify(proposal)) {
            throw invalidInput(`proposal id 已存在: ${proposal.proposalId}`);
          }
          return existing;
        }
        const nextStore: WorkspaceProposalStore = {
          version: 1,
          proposals: store.proposals.concat([proposal]),
          decisions: store.decisions,
        };
        await installProposalStoreOnly(current.id, nextStore);
        return proposal;
      }),

    resolveProposal: (
      id: string, proposalId: string, accept: boolean, commandId: string, resolvedAt: number,
    ): Promise<DurableWorkspaceProposal> => withProjectLock(
      id, async (): Promise<DurableWorkspaceProposal> => {
        assertCommandId(commandId);
        const store: WorkspaceProposalStore = await readProposalStore(id);
        const commandDecision: WorkspaceProposalDecision | undefined = store.decisions.find(
          (decision: WorkspaceProposalDecision): boolean => decision.commandId === commandId);
        if (commandDecision !== undefined) {
          if (commandDecision.proposalId !== proposalId || commandDecision.accept !== accept) {
            throw invalidInput('proposal decision command 已用于其他决定');
          }
          const replay: DurableWorkspaceProposal | undefined = store.proposals.find(
            (proposal: DurableWorkspaceProposal): boolean => proposal.proposalId === proposalId);
          if (replay === undefined) throw invalidInput('proposal decision 缺少 proposal');
          return replay;
        }
        const proposalIndex: number = store.proposals.findIndex(
          (proposal: DurableWorkspaceProposal): boolean => proposal.proposalId === proposalId);
        if (proposalIndex < 0) throw notFound('proposal', proposalId);
        const proposal: DurableWorkspaceProposal = store.proposals[proposalIndex];
        if (proposal.status !== 'pending') return proposal;
        const current: NovelProject = await loadProjectInternal(id);
        const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
        if (checkout === null) throw invalidInput('checkout 无效');
        assertCas(proposal.expectedCas, checkout);
        const branch: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, checkout.branchId);
        validateProposalTargets(proposal, branch);
        const resolved: DurableWorkspaceProposal = resolveDurableWorkspaceProposal(
          proposal, accept, resolvedAt).proposal;
        const proposals: DurableWorkspaceProposal[] = store.proposals.slice();
        proposals[proposalIndex] = resolved;
        const decision: WorkspaceProposalDecision = { commandId, proposalId, accept };
        const nextStore: WorkspaceProposalStore = {
          version: 1,
          proposals,
          decisions: store.decisions.concat([decision]),
        };
        if (!accept) {
          await installProposalStoreOnly(id, nextStore);
          return resolved;
        }
        const commits: WorkspaceLedgerCommit[] = await readCommits(id);
        if (findReceipt(commits, commandId) !== null) {
          throw invalidInput('proposal decision command 与既有 receipt 冲突');
        }
        let chapters: NovelChapter[] = current.chapters.slice();
        let plotContent: string | null = branch.plotContent;
        let changedIndex: number | null = null;
        for (let i: number = 0; i < proposal.patches.length; i++) {
          const patch: WorkspaceProposalPatch = proposal.patches[i];
          const plotPath: string = `branches/${checkout.branchId}/plan/plot.md`;
          if (patch.path === plotPath) {
            plotContent = patch.operation === 'write' ? patch.content : null;
            continue;
          }
          const recordIndex: number = branch.state.chapters.findIndex(
            (record: WorkspaceChapterRecord): boolean => record.path === patch.path);
          if (recordIndex < 0) throw invalidInput(`proposal 章节路径不存在: ${patch.path}`);
          const chapterId: string = branch.state.chapters[recordIndex].id;
          const projectIndex: number = chapters.findIndex(
            (chapter: NovelChapter): boolean => chapter.id === chapterId);
          if (projectIndex < 0) throw invalidInput(`proposal 章节不存在: ${chapterId}`);
          changedIndex = changedIndex === null ? projectIndex : Math.min(changedIndex, projectIndex);
          if (patch.operation === 'delete') {
            chapters.splice(projectIndex, 1);
          } else {
            chapters[projectIndex] = {
              ...chapters[projectIndex],
              content: patch.content ?? '',
              updatedAt: resolvedAt,
            };
          }
        }
        const next: NovelProject = {
          ...current,
          chapters,
          revision: current.revision + 1,
          updatedAt: Math.max(current.updatedAt, resolvedAt),
        };
        const unresolved: number | null = changedIndex === null
          ? branch.unresolvedFromOrdinal
          : followingUnresolvedOrdinal(changedIndex, chapters.length);
        const stale: boolean = changedIndex === null ? false : true;
        await installSnapshot(
          next, 'proposal_resolve', [], checkout.branchId, commandId,
          unresolved, stale, true, changedIndex === null ? null : changedIndex + 1,
          false, false, undefined, plotContent, nextStore,
        );
        return resolved;
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
