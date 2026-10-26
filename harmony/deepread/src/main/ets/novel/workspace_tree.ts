import type { FileStore } from '../platform/files.ts';
import { createMemoryFileStore, joinPath } from '../platform/files.ts';
import { invalidInput, notFound } from './error.ts';
import type { NovelBranch, NovelChapter, NovelMaterial, NovelProject } from './models.ts';
import { NOVEL_SCHEMA_VERSION, emptyNovelBranchSettings } from './models.ts';
import type {
  DurableNovelJob, WorkspaceBranchSnapshot, WorkspaceChapterRecord, WorkspaceCheckout,
  WorkspaceCommitSnapshot, WorkspaceLedgerCommit, WorkspaceMaterialRecord, WorkspaceNovelJobStore,
  WorkspaceProjectState, WorkspaceProposalDecision, WorkspaceProposalStore, WorkspaceUndoRecord,
} from './workspace_codec.ts';
import {
  CHECKOUT_FILE, GHOSTWRITE_JOBS_FILE, LEDGER_FILE, PROPOSALS_FILE, STATE_FILE, WORKSPACE_STATE_VERSION,
  assertBackupJobsIdle, branchSnapshotPath, branchStateMatchesCommit, buildSnapshotFiles,
  chapterContentsMatch, commitSnapshotPath, emptyNovelJobStore, emptyProposalStore, findCommit,
  latestBranchCommit, materialPath, parseLedger, parseNovelJobStore, parseProposalStore,
  snapshotTreeDigest, undoPath, validateProject, withAuthorPlot,
} from './workspace_codec.ts';
import type { NovelWorkspaceManifest } from './workspace_contract.ts';
import { parseNovelWorkspaceManifest } from './workspace_contract.ts';
import type { NovelWorkspaceArchiveFile } from './workspace_exchange.ts';
import type { DurableWorkspaceProposal, WorkspaceCommit } from './workspace_history.ts';
import { writeWorkspaceStorageFiles } from './workspace_storage.ts';

export const readActiveBranchSnapshot = async (fileStore: FileStore, root: string, branchId: string): Promise<WorkspaceBranchSnapshot> => {
  const path: string = joinPath(root, branchSnapshotPath(branchId));
  const raw: string | null = await fileStore.readText(path);
  if (raw === null) throw invalidInput(`active branch state 缺失: ${path}`);
  try {
    return JSON.parse(raw) as WorkspaceBranchSnapshot;
  } catch {
    throw invalidInput('active branch state 无效');
  }
};
export const readHistoricalSnapshot = async (fileStore: FileStore, root: string, id: string, commit: WorkspaceCommit): Promise<WorkspaceCommitSnapshot> => {
  const raw: string | null = await fileStore.readText(joinPath(root, commitSnapshotPath(commit.commitId)));
  if (raw === null) throw invalidInput('检查点快照缺失');
  const snapshot: WorkspaceCommitSnapshot = JSON.parse(raw) as WorkspaceCommitSnapshot;
  snapshot.project = validateProject(snapshot.project);
  if (snapshot.project.id !== id || snapshotTreeDigest(buildSnapshotFiles(snapshot.project,
    commit.branchId, snapshot.plotContent).files) !== commit.treeDigest) throw invalidInput('检查点快照与台账不一致');
  return snapshot;
};
export const readCommits = async (fileStore: FileStore, root: string, projectId: string): Promise<WorkspaceLedgerCommit[]> => {
  const raw: string | null = await fileStore.readText(joinPath(root, LEDGER_FILE));
  if (raw === null) throw notFound('project', projectId);
  return parseLedger(raw);
};
export const readProposalStore = async (fileStore: FileStore, root: string): Promise<WorkspaceProposalStore> => {
  const raw: string | null = await fileStore.readText(joinPath(root, PROPOSALS_FILE));
  return raw === null ? emptyProposalStore() : parseProposalStore(raw);
};
export const readNovelJobStore = async (fileStore: FileStore, root: string): Promise<WorkspaceNovelJobStore> => {
  const raw: string | null = await fileStore.readText(joinPath(root, GHOSTWRITE_JOBS_FILE));
  return raw === null ? emptyNovelJobStore() : parseNovelJobStore(raw);
};
export const readCheckout = async (fileStore: FileStore, root: string): Promise<WorkspaceCheckout | null> => {
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

export const projectFromStoredState = async (
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
      suggestionWarning: record.suggestionWarning,
      ordinal: record.ordinal,
    });
  }
  const materials: NovelMaterial[] = [];
  for (let i: number = 0; i < state.materials.length; i++) {
    const record: WorkspaceMaterialRecord = state.materials[i];
    const content: string | null = await fileStore.readText(joinPath(root, record.path));
    if (content === null) throw invalidInput(`设定文件缺失: ${record.path}`);
    materials.push({
      id: record.id, kind: record.kind, title: record.title, content, enabled: record.enabled,
      aliases: record.aliases, tags: record.tags, customKind: record.customKind, injectionMode: record.injectionMode,
      createdAt: record.createdAt, updatedAt: record.updatedAt,
    });
  }
  return validateProject({
    ...state.project,
    chapters,
    materials,
    branchSettings: state.branchSettings ?? emptyNovelBranchSettings(),
  } as NovelProject);
};

export const loadAt = async (fileStore: FileStore, root: string): Promise<NovelProject> => {
  const manifestRaw: string | null = await fileStore.readText(joinPath(root, 'manifest.yaml'));
  const stateRaw: string | null = await fileStore.readText(joinPath(root, STATE_FILE));
  if (manifestRaw === null || stateRaw === null) throw invalidInput('小说工作区不完整');
  const manifest: NovelWorkspaceManifest = parseNovelWorkspaceManifest(manifestRaw);
  const state: WorkspaceProjectState = JSON.parse(stateRaw) as WorkspaceProjectState;
  if (state.stateVersion !== WORKSPACE_STATE_VERSION || state.project.id !== manifest.projectId) {
    throw invalidInput('小说工作区状态与 manifest 不一致');
  }
  const requiresWriteMigration: boolean = state.project.schemaVersion !== NOVEL_SCHEMA_VERSION;
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
      suggestionWarning: record.suggestionWarning,
      ordinal: record.ordinal,
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
      aliases: record.aliases, tags: record.tags, customKind: record.customKind, injectionMode: record.injectionMode,
      createdAt: record.createdAt,
      updatedAt: record.updatedAt,
    });
  }
  const project: NovelProject = validateProject({
    ...state.project,
    name: manifest.title,
    chapters,
    materials,
    branchSettings: state.branchSettings ?? emptyNovelBranchSettings(),
  } as NovelProject);
  for (const base of project.baseMaterials ?? []) {
    if (await fileStore.readText(joinPath(root, materialPath(base))) !== base.content) {
      throw invalidInput('共享资料文件与状态不一致');
    }
  }
  // v2/v3 工作区在下一次 installSnapshot 前没有 v4 的 branch setting 文件与摘要；
  // 先只读恢复 canonical project，再由 loadProjectInternal 原子单写升级。
  if (requiresWriteMigration) return project;
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
      !chapterContentsMatch(project.chapters, commitSnapshot.project.chapters) ||
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
  return withAuthorPlot(project, branchSnapshot.plotContent);
};

export const workspaceNeedsSchemaMigration = async (fileStore: FileStore, root: string): Promise<boolean> => {
  const raw: string | null = await fileStore.readText(joinPath(root, STATE_FILE));
  if (raw === null) return false;
  try {
    const state: WorkspaceProjectState = JSON.parse(raw) as WorkspaceProjectState;
    return state.project.schemaVersion !== NOVEL_SCHEMA_VERSION;
  } catch {
    return false;
  }
};

export const walkWorkspaceFiles = async (
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

export const priorManagedPaths = async (fileStore: FileStore, root: string): Promise<Set<string>> => {
  const managed: Set<string> = new Set<string>(['manifest.yaml', 'project.md']);
  const raw: string | null = await fileStore.readText(joinPath(root, STATE_FILE));
  if (raw === null) return managed;
  try {
    const state: WorkspaceProjectState = JSON.parse(raw) as WorkspaceProjectState;
    state.chapters.forEach((record: WorkspaceChapterRecord): void => { managed.add(record.path); });
    (state.project.baseMaterials ?? []).forEach((material: NovelMaterial): void => { managed.add(materialPath(material)); });
    state.materials.forEach((record: WorkspaceMaterialRecord): void => {
      managed.add(record.path);
      managed.add(`setting/${record.kind}/${record.id}.md`);
    });
  } catch {
    // 损坏 state 由 loadAt 显式失败；这里不猜测额外 managed 文件。
  }
  return managed;
};

export const passthroughFilesAt = async (
  fileStore: FileStore, root: string,
): Promise<NovelWorkspaceArchiveFile[]> => {
  const managed: Set<string> = await priorManagedPaths(fileStore, root);
  const files: NovelWorkspaceArchiveFile[] = await walkWorkspaceFiles(fileStore, root);
  return files.filter((file: NovelWorkspaceArchiveFile): boolean =>
    !file.path.startsWith('.amber/') &&
    (file.path === 'project.md' || !managed.has(file.path)));
};

export const validateFullTree = async (files: NovelWorkspaceArchiveFile[], requireIdle: boolean = false): Promise<NovelProject> => {
  const memory: FileStore = createMemoryFileStore();
  await writeWorkspaceStorageFiles(memory, 'candidate', files);
  const project: NovelProject = await loadAt(memory, 'candidate');
  if (project.schemaVersion !== NOVEL_SCHEMA_VERSION || await workspaceNeedsSchemaMigration(memory, 'candidate')) {
    throw invalidInput('原生备份 schema 版本不受支持');
  }
  const ledgerRaw: string | null = await memory.readText(joinPath('candidate', LEDGER_FILE));
  const commits: WorkspaceLedgerCommit[] = parseLedger(ledgerRaw ?? '');
  for (let i: number = 0; i < commits.length; i++) {
    const commit: WorkspaceLedgerCommit = commits[i];
    const raw: string | null = await memory.readText(joinPath('candidate', commitSnapshotPath(commit.commitId)));
    if (raw === null) throw invalidInput('原生备份历史快照缺失');
    const snapshot: WorkspaceCommitSnapshot = JSON.parse(raw) as WorkspaceCommitSnapshot;
    if (snapshot.project.id !== project.id || snapshotTreeDigest(buildSnapshotFiles(
      snapshot.project, commit.branchId, snapshot.plotContent).files) !== commit.treeDigest) {
      throw invalidInput('原生备份历史快照与台账不一致');
    }
  }
  for (let i: number = 0; i < project.branches.length; i++) {
    const branch: NovelBranch = project.branches[i];
    if (branch.lifecycle !== 'active') continue;
    const raw: string | null = await memory.readText(joinPath('candidate', branchSnapshotPath(branch.id)));
    if (raw === null) throw invalidInput('原生备份分支快照缺失');
    const snapshot: WorkspaceBranchSnapshot = JSON.parse(raw) as WorkspaceBranchSnapshot;
    const branchProject: NovelProject = await projectFromStoredState(memory, 'candidate', snapshot.state);
    const head: WorkspaceLedgerCommit | null = latestBranchCommit(commits, branch.id);
    if (snapshot.branchId !== branch.id || branchProject.id !== project.id || head === null ||
      snapshotTreeDigest(buildSnapshotFiles(branchProject, branch.id, snapshot.plotContent).files) !== head.treeDigest ||
      snapshot.plotSourceTreeDigest !== head.treeDigest ||
      snapshot.plotStale !== (snapshot.plotSyncedTreeDigest !== snapshot.plotSourceTreeDigest)) {
      throw invalidInput('原生备份分支正文与 head 不一致');
    }
    const headRaw: string | null = await memory.readText(joinPath('candidate', commitSnapshotPath(head.commitId)));
    const headSnapshot: WorkspaceCommitSnapshot = JSON.parse(headRaw ?? '') as WorkspaceCommitSnapshot;
    if (!branchStateMatchesCommit(snapshot.state, buildSnapshotFiles(headSnapshot.project, branch.id).state) ||
      !chapterContentsMatch(branchProject.chapters, headSnapshot.project.chapters) ||
      headSnapshot.plotContent !== snapshot.plotContent || headSnapshot.plotStale !== snapshot.plotStale ||
      headSnapshot.unresolvedFromOrdinal !== snapshot.unresolvedFromOrdinal) {
      throw invalidInput('原生备份分支状态与 head 不一致');
    }
    const undoRaw: string | null = await memory.readText(joinPath('candidate', undoPath(branch.id)));
    if (undoRaw !== null) {
      const undo: WorkspaceUndoRecord = JSON.parse(undoRaw) as WorkspaceUndoRecord;
      if (undo.branchId !== branch.id || validateProject(undo.snapshot.project).id !== project.id ||
        snapshotTreeDigest(buildSnapshotFiles(undo.snapshot.project, branch.id, undo.snapshot.plotContent).files) !== undo.snapshot.plotSourceTreeDigest) {
        throw invalidInput('原生备份撤销记录无效');
      }
    }
  }
  const proposalRaw: string | null = await memory.readText(joinPath('candidate', PROPOSALS_FILE));
  if (proposalRaw !== null) {
    const store: WorkspaceProposalStore = parseProposalStore(proposalRaw);
    for (let i: number = 0; i < store.proposals.length; i++) {
      const proposal: DurableWorkspaceProposal = store.proposals[i];
      const base: WorkspaceLedgerCommit | null = findCommit(commits, proposal.expectedCas.head);
      if (base === null || base.branchId !== proposal.expectedCas.branchId || base.treeDigest !== proposal.expectedCas.treeDigest) {
        throw invalidInput('原生备份提案引用的提交无效');
      }
    }
    if (store.decisions.some((decision: WorkspaceProposalDecision): boolean =>
      !store.proposals.some((proposal: DurableWorkspaceProposal): boolean => proposal.proposalId === decision.proposalId))) {
      throw invalidInput('原生备份提案决定缺少对应提案');
    }
  }
  const jobsRaw: string | null = await memory.readText(joinPath('candidate', GHOSTWRITE_JOBS_FILE));
  if (jobsRaw !== null) {
    const jobs: WorkspaceNovelJobStore = parseNovelJobStore(jobsRaw);
    if (requireIdle) assertBackupJobsIdle(jobs);
    for (let i: number = 0; i < jobs.jobs.length; i++) {
      const job: DurableNovelJob = jobs.jobs[i];
      const base: WorkspaceLedgerCommit | null = findCommit(commits, job.expectedCas.head);
      if (job.projectId !== project.id || base === null || base.branchId !== job.expectedCas.branchId ||
        base.treeDigest !== job.expectedCas.treeDigest || !project.branches.some((branch: NovelBranch): boolean => branch.id === job.branchId)) {
        throw invalidInput('原生备份任务引用不存在的分支');
      }
    }
  }
  return project;
};

