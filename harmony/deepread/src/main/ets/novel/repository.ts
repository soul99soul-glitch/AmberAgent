// 小说项目仓库。生产数据从旧的单 JSON 迁入 amber.novel.workspace v1；
// 页面仍通过 NovelProjectRepository 访问，因此没有第二个数据源或平行入口。

import type { FileStore } from '../platform/files.ts';
import { joinPath } from '../platform/files.ts';
import {
  NOVEL_SCHEMA_VERSION, novelId, defaultNovelModelPolicy, emptyNovelBranchSettings,
  makeNovelChapterVersion, makeNovelMessage, makeNovelProject, withNovelProjectCompatibility,
} from './models.ts';
import type {
  NovelBranch, NovelBranchSettings, NovelChapter, NovelMaterial, NovelMaterialKind, NovelMessage,
  NovelModelPolicy, NovelProject, NovelSettingProposal,
} from './models.ts';
import type { UIMessage } from '../agent/message.ts';
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
import {
  applyGhostwriteReview, assertGhostwriteClaim, defaultGhostwriteDigest,
  claimGhostwriteJob as claimGhostwriteJobDomain, freezeGhostwritePlan,
  ghostwriteReceipt, makeGhostwriteJob, pauseGhostwriteJob as pauseGhostwriteJobDomain,
  projectGhostwriteProgress, resumeGhostwriteJob as resumeGhostwriteJobDomain,
  transitionGhostwriteJob, validateDurableGhostwriteJob, validateGhostwriteReview,
  withGhostwriteCandidate,
} from './ghostwrite.ts';
import type {
  DurableGhostwriteJob, GhostwriteCandidate, GhostwriteClaimRef, GhostwriteProgress,
  GhostwriteReview, GhostwriteStage,
} from './ghostwrite.ts';
import {
  MAX_POLISH_CONTEXT_ITEM_CHARS, MAX_POLISH_CONTEXT_ITEMS, MAX_POLISH_CONTEXT_TOTAL_CHARS,
  applyPolishReview, assertPolishClaim, cancelPolishJob as cancelPolishJobDomain,
  claimPolishJob as claimPolishJobDomain, commitPolishChapter as commitPolishChapterDomain,
  currentPolishTarget,
  failPolishJob as failPolishJobDomain, makePolishCandidate, makePolishChapterTarget,
  makePolishContextSnapshotItem, makePolishJob, pausePolishJob as pausePolishJobDomain,
  polishReceipt, projectPolishProgress, resumePolishJob as resumePolishJobDomain,
  retryPolishJob as retryPolishJobDomain, transitionPolishJob, validateDurablePolishJob,
  validatePolishReview, withPolishCandidate, yieldPolishJobToSystem,
} from './polish.ts';
import type {
  DurablePolishJob, PolishCandidate, PolishChapterTarget, PolishContextOptions,
  PolishContextSnapshotItem, PolishProgress, PolishReview, PolishStage, PolishWarning,
} from './polish.ts';

const LEGACY_PROJECTS_DIR: string = 'amberagent/novel-creation/projects';
const LEGACY_DELETED_DIR: string = 'novel_deleted';
const LEGACY_SUFFIX: string = '.novel.json';
const ROOT: string = 'amberagent/novel-workspace';
const STAGING_ROOT: string = joinPath(ROOT, '.staging');
const TRASH_ROOT: string = joinPath(ROOT, '.trash');
const DELETED_ROOT: string = joinPath(ROOT, '.deleted');
const LOCK_ROOT: string = joinPath(ROOT, '.locks');
const STATE_FILE: string = '.amber/project-state.json';
const LEDGER_FILE: string = '.amber/ledger.jsonl';
const CHECKOUT_FILE: string = '.amber/checkout.json';
const PROPOSALS_FILE: string = '.amber/proposals.json';
const GHOSTWRITE_JOBS_FILE: string = '.amber/jobs.json';
const MAX_PROJECT_BYTES: number = 16 * 1024 * 1024;
const WORKSPACE_STATE_VERSION: number = 1;

export type NovelWorkspaceMutationKind =
  | 'compat_update' | 'rename' | 'model_change' | 'manual_edit' | 'chapter_delete'
  | 'material_edit' | 'material_delete' | 'collect' | 'suggestion_refresh'
  | 'proposal_resolve' | 'generation_commit' | 'polish_adopt' | 'chapter_discard'
  | 'version_restore' | 'discussion_archive' | 'interrupt_commit' | 'create_branch'
  | 'undo' | 'plot_sync' | 'unresolved_resolve' | 'transcript_checkpoint'
  | 'branch_settings_change' | 'batch_polish_commit';

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
  listGhostwriteJobs(id: string): Promise<DurableGhostwriteJob[]>;
  loadGhostwriteJob(id: string, jobId: string): Promise<DurableGhostwriteJob>;
  startGhostwriteJob(
    id: string, expected: NovelWorkspaceCas, jobId: string, planId: string,
    targetChapterCount: number, now: number,
  ): Promise<DurableGhostwriteJob>;
  claimGhostwriteJob(
    id: string, jobId: string, token: string, now: number, leaseMs: number,
  ): Promise<DurableGhostwriteJob>;
  checkpointGhostwriteStage(
    id: string, jobId: string, claim: GhostwriteClaimRef, nextStage: GhostwriteStage, now: number,
  ): Promise<DurableGhostwriteJob>;
  checkpointGhostwriteCandidate(
    id: string, jobId: string, claim: GhostwriteClaimRef,
    candidate: GhostwriteCandidate, now: number,
  ): Promise<DurableGhostwriteJob>;
  checkpointGhostwriteReview(
    id: string, jobId: string, claim: GhostwriteClaimRef,
    review: GhostwriteReview, now: number,
  ): Promise<DurableGhostwriteJob>;
  failGhostwriteJob(
    id: string, jobId: string, claim: GhostwriteClaimRef, reason: string, now: number,
  ): Promise<DurableGhostwriteJob>;
  pauseGhostwriteJob(
    id: string, jobId: string, claim: GhostwriteClaimRef, now: number,
  ): Promise<DurableGhostwriteJob>;
  resumeGhostwriteJob(id: string, jobId: string, now: number): Promise<DurableGhostwriteJob>;
  retryGhostwriteJob(id: string, jobId: string, now: number): Promise<DurableGhostwriteJob>;
  cancelGhostwriteJob(
    id: string, jobId: string, claim: GhostwriteClaimRef, now: number,
  ): Promise<DurableGhostwriteJob>;
  commitGhostwriteChapter(
    id: string, jobId: string, claim: GhostwriteClaimRef, commandId: string, now: number,
  ): Promise<DurableGhostwriteJob>;
  ghostwriteProgress(id: string, jobId: string): Promise<GhostwriteProgress[]>;
  listPolishJobs(id: string): Promise<DurablePolishJob[]>;
  loadPolishJob(id: string, jobId: string): Promise<DurablePolishJob>;
  startPolishJob(
    id: string, expected: NovelWorkspaceCas, jobId: string,
    fromOrdinal: number, toOrdinal: number, contextOptions: PolishContextOptions, now: number,
  ): Promise<DurablePolishJob>;
  claimPolishJob(
    id: string, jobId: string, token: string, now: number, leaseMs: number,
  ): Promise<DurablePolishJob>;
  checkpointPolishStage(
    id: string, jobId: string, claim: GhostwriteClaimRef, nextStage: PolishStage, now: number,
  ): Promise<DurablePolishJob>;
  checkpointPolishCandidate(
    id: string, jobId: string, claim: GhostwriteClaimRef,
    candidate: PolishCandidate, now: number,
  ): Promise<DurablePolishJob>;
  checkpointPolishReview(
    id: string, jobId: string, claim: GhostwriteClaimRef,
    review: PolishReview, now: number,
  ): Promise<DurablePolishJob>;
  failPolishJob(
    id: string, jobId: string, claim: GhostwriteClaimRef, reason: string, now: number,
  ): Promise<DurablePolishJob>;
  pausePolishJob(
    id: string, jobId: string, claim: GhostwriteClaimRef, now: number,
  ): Promise<DurablePolishJob>;
  yieldPolishJob(
    id: string, jobId: string, claim: GhostwriteClaimRef, now: number,
  ): Promise<DurablePolishJob>;
  resumePolishJob(id: string, jobId: string, now: number): Promise<DurablePolishJob>;
  retryPolishJob(id: string, jobId: string, now: number): Promise<DurablePolishJob>;
  cancelPolishJob(id: string, jobId: string, now: number): Promise<DurablePolishJob>;
  commitPolishChapter(
    id: string, jobId: string, claim: GhostwriteClaimRef, commandId: string, now: number,
  ): Promise<DurablePolishJob>;
  polishProgress(id: string, jobId: string): Promise<PolishProgress[]>;
  deleteProject(id: string): Promise<void>;
  restorePrevious(): Promise<NovelProject | null>;
  installWorkspace(input: NovelWorkspaceValidatedImport): Promise<NovelProject>;
  workspaceFiles(id: string): Promise<NovelWorkspaceArchiveFile[]>;
  bookExportInput(id: string): Promise<NovelBookExportInput>;
}

interface PersistedNovelMessage extends Omit<NovelMessage, 'content' | 'uiMessage'> {
  content?: string;
  uiMessage?: UIMessage;
}

interface PersistedNovelProject extends Omit<NovelProject,
  'settingProposals' | 'messages' | 'modelId' | 'modelPolicy' | 'branchSettings'> {
  settingProposals?: NovelSettingProposal[];
  messages: PersistedNovelMessage[];
  modelId?: string | null;
  modelPolicy?: NovelModelPolicy;
  branchSettings?: NovelBranchSettings;
}

interface WorkspaceChapterRecord extends Omit<NovelChapter, 'content'> {
  path: string;
}

interface WorkspaceMaterialRecord extends Omit<NovelMaterial, 'content'> {
  path: string;
}

interface WorkspaceProjectMeta extends Omit<NovelProject,
  'chapters' | 'materials' | 'modelId' | 'branchSettings'> {}

interface WorkspaceProjectState {
  stateVersion: number;
  project: WorkspaceProjectMeta;
  chapters: WorkspaceChapterRecord[];
  materials: WorkspaceMaterialRecord[];
  branchSettings: NovelBranchSettings;
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

type DurableNovelJob = DurableGhostwriteJob | DurablePolishJob;

interface WorkspaceNovelJobStore {
  version: 2;
  jobs: DurableNovelJob[];
}

interface LoadedGhostwriteJob {
  store: WorkspaceNovelJobStore;
  index: number;
  job: DurableGhostwriteJob;
  checkout: WorkspaceCheckout;
}

interface LoadedPolishJob {
  store: WorkspaceNovelJobStore;
  index: number;
  job: DurablePolishJob;
  checkout: WorkspaceCheckout;
}

interface SnapshotFile {
  path: string;
  content: string;
}

export const migrateNovelProject = (stored: PersistedNovelProject): NovelProject => {
  if (stored.schemaVersion !== 2 && stored.schemaVersion !== 3 && stored.schemaVersion !== NOVEL_SCHEMA_VERSION) {
    throw invalidInput('项目版本不受支持');
  }
  const messages: NovelMessage[] = stored.messages.map((message: PersistedNovelMessage): NovelMessage =>
    makeNovelMessage({
      id: message.id,
      role: message.role,
      mode: message.mode,
      content: message.content,
      uiMessage: message.uiMessage,
      collectedChapterId: message.collectedChapterId,
      createdAt: message.createdAt,
      granularity: message.granularity,
      interrupted: message.interrupted,
    }));
  const project = makeNovelProject({
    id: stored.id,
    name: stored.name,
    now: stored.createdAt,
    // v2/v3 的 modelId 没有 provider，不能推断为 fixed 路由。
    modelPolicy: stored.schemaVersion === NOVEL_SCHEMA_VERSION && stored.modelPolicy !== undefined
      ? stored.modelPolicy : defaultNovelModelPolicy(),
  });
  return withNovelProjectCompatibility({
    ...project,
    schemaVersion: NOVEL_SCHEMA_VERSION,
    messages,
    chapters: stored.chapters,
    materials: stored.materials,
    createdAt: stored.createdAt,
    updatedAt: stored.updatedAt,
    lastGenerationGranularity: stored.lastGenerationGranularity,
    materialSuggestions: stored.materialSuggestions,
    settingProposals: stored.settingProposals ?? [],
    branches: stored.branches,
    chapterVersions: stored.chapterVersions,
    branchSettings: stored.branchSettings ?? emptyNovelBranchSettings(),
    revision: stored.revision,
  });
};

const validateProject = (project: NovelProject): NovelProject => {
  const normalized: NovelProject = migrateNovelProject(project as PersistedNovelProject);
  if (normalized.id.trim().length === 0 || normalized.name.trim().length === 0) {
    throw invalidInput('项目名称或 id 无效');
  }
  serializeNovelWorkspaceManifest({
    format: NOVEL_WORKSPACE_FORMAT,
    version: NOVEL_WORKSPACE_VERSION,
    projectId: normalized.id,
    title: normalized.name,
    activeBranch: 'main',
    createdAt: normalized.createdAt,
    updatedAt: normalized.updatedAt,
  });
  return normalized;
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

const ancestryCommitIds = (commits: WorkspaceLedgerCommit[], head: string): Set<string> => {
  const ancestry: Set<string> = new Set<string>();
  let cursor: string | null = head;
  while (cursor !== null) {
    if (ancestry.has(cursor)) throw invalidInput('工作区 commit ancestry 存在环');
    const commit: WorkspaceLedgerCommit | null = findCommit(commits, cursor);
    if (commit === null) throw invalidInput(`工作区 commit ancestry 缺失: ${cursor}`);
    ancestry.add(cursor);
    cursor = commit.parent;
  }
  return ancestry;
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

const emptyNovelJobStore = (): WorkspaceNovelJobStore => ({ version: 2, jobs: [] });

const isGhostwriteJob = (job: DurableNovelJob): job is DurableGhostwriteJob =>
  job.kind === 'ghostwrite';

const isPolishJob = (job: DurableNovelJob): job is DurablePolishJob =>
  job.kind === 'batch_polish';

const novelJobIsTerminal = (job: DurableNovelJob): boolean =>
  job.stage === 'completed' || job.stage === 'cancelled';

const parseNovelJobStore = (raw: string): WorkspaceNovelJobStore => {
  let value: { version: number; jobs: DurableNovelJob[] };
  try {
    value = JSON.parse(raw) as { version: number; jobs: DurableNovelJob[] };
  } catch {
    throw invalidInput('novel job store 无效');
  }
  if ((value.version !== 1 && value.version !== 2) || !Array.isArray(value.jobs)) {
    throw invalidInput('novel job store 无效');
  }
  const jobs: DurableNovelJob[] = [];
  const jobIds: Set<string> = new Set<string>();
  for (let i: number = 0; i < value.jobs.length; i++) {
    const rawJob: DurableNovelJob = value.jobs[i];
    let job: DurableNovelJob;
    if (value.version === 1 || rawJob.kind === 'ghostwrite') {
      const legacy: DurableGhostwriteJob = rawJob as DurableGhostwriteJob;
      job = validateDurableGhostwriteJob({ ...legacy, kind: 'ghostwrite' });
      if (job.review !== null) {
        if (job.candidate === null || job.frozenPlan === null) {
          throw invalidInput('ghostwrite review 缺少 candidate 或 plan');
        }
        validateGhostwriteReview(job.review, job.candidate, job.frozenPlan);
      }
      const receipts: Set<string> = new Set<string>();
      for (let j: number = 0; j < job.progress.length; j++) {
        const progress: GhostwriteProgress = job.progress[j];
        if (progress.branchId !== job.branchId ||
          progress.receipt !== ghostwriteReceipt(
            job.jobId, progress.chapterOrdinal, progress.planId, progress.planDigest, progress.candidateId,
          ) || receipts.has(progress.receipt)) {
          throw invalidInput('ghostwrite progress 无效');
        }
        receipts.add(progress.receipt);
      }
    } else if (rawJob.kind === 'batch_polish') {
      job = validateDurablePolishJob(rawJob);
    } else {
      throw invalidInput('novel job kind 无效');
    }
    if (jobIds.has(job.jobId)) throw invalidInput(`novel job id 重复: ${job.jobId}`);
    jobIds.add(job.jobId);
    jobs.push(job);
  }
  return { version: 2, jobs };
};

const materialPath = (material: NovelMaterial): string =>
  `setting/${material.kind}/${material.id}.md`;

const branchMaterialPath = (branchId: string, material: NovelMaterial): string =>
  `branches/${branchId}/setting/${material.kind}/${material.id}.md`;

const branchSettingPaths = (branchId: string): Record<
  'thisChapterPlan' | 'futurePlan' | 'preferences' | 'catalog', string
> => ({
  thisChapterPlan: `branches/${branchId}/plan/this-chapter.md`,
  futurePlan: `branches/${branchId}/plan/future.md`,
  preferences: `branches/${branchId}/setting/preferences.md`,
  // Host-generated structured catalog. The three Markdown paths above remain the only editable setting files.
  catalog: `branches/${branchId}/setting/catalog.json`,
});

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
  const settingsPaths = branchSettingPaths(branch.id);
  files.push(
    { path: settingsPaths.thisChapterPlan, content: project.branchSettings.thisChapterPlan },
    { path: settingsPaths.futurePlan, content: project.branchSettings.futurePlan },
    { path: settingsPaths.preferences, content: project.branchSettings.preferences },
    {
      path: settingsPaths.catalog,
      content: JSON.stringify({
        foreshadows: project.branchSettings.foreshadows,
        confirmedDecisions: project.branchSettings.confirmedDecisions,
      }),
    },
  );
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
      modelPolicy: project.modelPolicy,
      lastGenerationGranularity: project.lastGenerationGranularity,
      materialSuggestions: project.materialSuggestions,
      settingProposals: project.settingProposals,
      branches: project.branches,
      chapterVersions: project.chapterVersions,
      revision: project.revision,
    },
    chapters: chapterRecords,
    materials: materialRecords,
    branchSettings: project.branchSettings,
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
  JSON.stringify(current.project.modelPolicy) === JSON.stringify(committed.project.modelPolicy) &&
  JSON.stringify(current.project.materialSuggestions) === JSON.stringify(committed.project.materialSuggestions) &&
  JSON.stringify(current.project.settingProposals) === JSON.stringify(committed.project.settingProposals) &&
  JSON.stringify(current.project.chapterVersions) === JSON.stringify(committed.project.chapterVersions) &&
  JSON.stringify(current.branchSettings) === JSON.stringify(committed.branchSettings) &&
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
  return validateProject({
    ...state.project,
    chapters,
    materials,
    branchSettings: state.branchSettings ?? emptyNovelBranchSettings(),
  } as NovelProject);
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
  const project: NovelProject = validateProject({
    ...state.project,
    name: manifest.title,
    chapters,
    materials,
    branchSettings: state.branchSettings ?? emptyNovelBranchSettings(),
  } as NovelProject);
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

const workspaceNeedsSchemaMigration = async (fileStore: FileStore, root: string): Promise<boolean> => {
  const raw: string | null = await fileStore.readText(joinPath(root, STATE_FILE));
  if (raw === null) return false;
  try {
    const state: WorkspaceProjectState = JSON.parse(raw) as WorkspaceProjectState;
    return state.project.schemaVersion !== NOVEL_SCHEMA_VERSION;
  } catch {
    return false;
  }
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
    const run: Promise<T> = previous.catch((): void => {}).then(
      async (): Promise<T> => await fileStore.withExclusiveLock(joinPath(LOCK_ROOT, `${id}.lock`), op));
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

  const readProposalStore = async (id: string): Promise<WorkspaceProposalStore> => {
    const raw: string | null = await fileStore.readText(joinPath(projectRoot(id), PROPOSALS_FILE));
    return raw === null ? emptyProposalStore() : parseProposalStore(raw);
  };

  const readNovelJobStore = async (id: string): Promise<WorkspaceNovelJobStore> => {
    const raw: string | null = await fileStore.readText(joinPath(projectRoot(id), GHOSTWRITE_JOBS_FILE));
    return raw === null ? emptyNovelJobStore() : parseNovelJobStore(raw);
  };

  const ghostwriteJobIndex = (store: WorkspaceNovelJobStore, jobId: string): number => {
    const index: number = store.jobs.findIndex(
      (job: DurableNovelJob): boolean => isGhostwriteJob(job) && job.jobId === jobId);
    if (index < 0) throw notFound('ghostwrite_job', jobId);
    return index;
  };

  const polishJobIndex = (store: WorkspaceNovelJobStore, jobId: string): number => {
    const index: number = store.jobs.findIndex(
      (job: DurableNovelJob): boolean => isPolishJob(job) && job.jobId === jobId);
    if (index < 0) throw notFound('polish_job', jobId);
    return index;
  };

  const replaceGhostwriteJob = (
    store: WorkspaceNovelJobStore, index: number, job: DurableGhostwriteJob,
  ): WorkspaceNovelJobStore => {
    const jobs: DurableNovelJob[] = store.jobs.slice();
    jobs[index] = validateDurableGhostwriteJob(job);
    return { version: 2, jobs };
  };

  const replacePolishJob = (
    store: WorkspaceNovelJobStore, index: number, job: DurablePolishJob,
  ): WorkspaceNovelJobStore => {
    const jobs: DurableNovelJob[] = store.jobs.slice();
    jobs[index] = validateDurablePolishJob(job);
    return { version: 2, jobs };
  };

  const assertGhostwriteWorkspaceBinding = (
    id: string, job: DurableGhostwriteJob, checkout: WorkspaceCheckout,
  ): void => {
    if (job.projectId !== id || job.branchId !== checkout.branchId) {
      throw invalidInput('ghostwrite job 与当前 project/branch 不匹配');
    }
    assertCas(job.expectedCas, checkout);
  };

  const assertPolishWorkspaceBinding = (
    id: string, job: DurablePolishJob, checkout: WorkspaceCheckout,
  ): void => {
    if (job.projectId !== id || job.branchId !== checkout.branchId) {
      throw invalidInput('polish job 与当前 project/branch 不匹配');
    }
    assertCas(job.expectedCas, checkout);
  };

  const assertNoActiveNovelJob = (store: WorkspaceNovelJobStore, branchId: string): void => {
    const active: DurableNovelJob | undefined = store.jobs.find(
      (job: DurableNovelJob): boolean => job.branchId === branchId && !novelJobIsTerminal(job));
    if (active !== undefined) throw invalidInput('当前分支已有未结束的小说作业');
  };

  const saveNovelJobStoreOnly = async (
    id: string, store: WorkspaceNovelJobStore,
  ): Promise<void> => {
    await installPrivateStoreOnly(id, GHOSTWRITE_JOBS_FILE, JSON.stringify(store));
  };

  const installPrivateStoreOnly = async (
    id: string, targetPath: string, content: string,
  ): Promise<void> => {
    const live: string = projectRoot(id);
    const stage: string = stagingRoot(id);
    const backup: string = trashRoot(id);
    const files: NovelWorkspaceArchiveFile[] = await walkWorkspaceFiles(fileStore, live);
    await fileStore.deleteTree(stage);
    for (let i: number = 0; i < files.length; i++) {
      const path: string = files[i].path;
      if (path === targetPath) continue;
      if (path.startsWith('.amber/') || path.endsWith('.md') || path.endsWith('.yaml')) {
        await fileStore.writeText(joinPath(stage, path), decodeNovelWorkspaceUtf8(files[i].bytes));
      } else {
        await fileStore.writeBytes(joinPath(stage, path), files[i].bytes);
      }
    }
    await fileStore.writeText(joinPath(stage, targetPath), content);
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
    novelJobStoreOverride: WorkspaceNovelJobStore | null = null,
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
        !(novelJobStoreOverride !== null && file.path === GHOSTWRITE_JOBS_FILE) &&
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
    if (novelJobStoreOverride !== null) {
      files.push({ path: GHOSTWRITE_JOBS_FILE, content: JSON.stringify(novelJobStoreOverride) });
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
    if (await fileStore.exists(projectRoot(id))) {
      const root: string = projectRoot(id);
      const project: NovelProject = await loadAt(fileStore, root);
      return await workspaceNeedsSchemaMigration(fileStore, root)
        ? await installSnapshot(project, 'schema_migration')
        : project;
    }
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

  const loadBoundGhostwriteJob = async (id: string, jobId: string): Promise<LoadedGhostwriteJob> => {
    await loadProjectInternal(id);
    const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
    if (checkout === null) throw invalidInput('checkout 无效');
    const store: WorkspaceNovelJobStore = await readNovelJobStore(id);
    const index: number = ghostwriteJobIndex(store, jobId);
    const stored: DurableNovelJob = store.jobs[index];
    if (!isGhostwriteJob(stored)) throw invalidInput('ghostwrite job 类型无效');
    const job: DurableGhostwriteJob = stored;
    assertGhostwriteWorkspaceBinding(id, job, checkout);
    return { store, index, job, checkout };
  };

  const loadClaimedGhostwriteJob = async (
    id: string, jobId: string, claim: GhostwriteClaimRef, now: number,
  ): Promise<LoadedGhostwriteJob> => {
    const loaded: LoadedGhostwriteJob = await loadBoundGhostwriteJob(id, jobId);
    assertGhostwriteClaim(loaded.job, claim, now);
    return loaded;
  };

  const loadBoundPolishJob = async (id: string, jobId: string): Promise<LoadedPolishJob> => {
    await loadProjectInternal(id);
    const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
    if (checkout === null) throw invalidInput('checkout 无效');
    const store: WorkspaceNovelJobStore = await readNovelJobStore(id);
    const index: number = polishJobIndex(store, jobId);
    const stored: DurableNovelJob = store.jobs[index];
    if (!isPolishJob(stored)) throw invalidInput('polish job 类型无效');
    assertPolishWorkspaceBinding(id, stored, checkout);
    return { store, index, job: stored, checkout };
  };

  const loadClaimedPolishJob = async (
    id: string, jobId: string, claim: GhostwriteClaimRef, now: number,
  ): Promise<LoadedPolishJob> => {
    const loaded: LoadedPolishJob = await loadBoundPolishJob(id, jobId);
    assertPolishClaim(loaded.job, claim, now);
    return loaded;
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
    if (transformed.branchSettings.thisChapterPlan !== current.branchSettings.thisChapterPlan) {
      const jobStore: WorkspaceNovelJobStore = await readNovelJobStore(id);
      const bound: DurableGhostwriteJob | undefined = jobStore.jobs.find(
        (job: DurableNovelJob): job is DurableGhostwriteJob => isGhostwriteJob(job) &&
          job.branchId === checkout.branchId && !novelJobIsTerminal(job));
      if (bound !== undefined) throw invalidInput('运行中的 ghostwrite job 已冻结本章计划');
    }
    const next: NovelProject = { ...transformed, revision: current.revision + 1 };
    const changedIndex: number | null = chapterChangedAt(current.chapters, next.chapters);
    const unresolved: number | null = changedIndex === null
      ? branch.unresolvedFromOrdinal
      : followingUnresolvedOrdinal(changedIndex, next.chapters.length);
    const stale: boolean = changedIndex === null ? branch.plotStale : true;
    return await installSnapshot(
      next, kind, [], checkout.branchId, commandId, unresolved, stale,
      kind !== 'transcript_checkpoint',
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
      const projects: NovelProject[] = [];
      const seen: Set<string> = new Set<string>();
      const names: string[] = await fileStore.list(ROOT);
      const recoveryIds: string[] = names.filter((id: string): boolean => !id.startsWith('.'));
      const backups: string[] = await fileStore.list(TRASH_ROOT);
      const stages: string[] = await fileStore.list(STAGING_ROOT);
      for (let i: number = 0; i < backups.length; i++) {
        if (recoveryIds.indexOf(backups[i]) < 0) recoveryIds.push(backups[i]);
      }
      for (let i: number = 0; i < stages.length; i++) {
        if (recoveryIds.indexOf(stages[i]) < 0) recoveryIds.push(stages[i]);
      }
      for (let i: number = 0; i < recoveryIds.length; i++) {
        const id: string = recoveryIds[i];
        try {
          const project: NovelProject | null = await withProjectLock(
            id, async (): Promise<NovelProject | null> => {
              await recoverProject(id);
              if (!await fileStore.isDirectory(projectRoot(id))) return null;
              return await loadProjectInternal(id);
            });
          if (project !== null) {
            projects.push(project);
            seen.add(id);
          }
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
          const migrated: NovelProject | null = await withProjectLock(
            id, async (): Promise<NovelProject | null> => await migrateLegacyIfPresent(id));
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
      return await withProjectLock(id, async (): Promise<NovelProject> => await loadProjectInternal(id));
    },

    async createProject(project: NovelProject): Promise<NovelProject> {
      return await withProjectLock(project.id, async (): Promise<NovelProject> => {
        await recoverProject(project.id);
        if (await fileStore.exists(projectRoot(project.id))) throw invalidInput('项目已存在');
        return await installSnapshot(project, 'create');
      });
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
      assertNoActiveNovelJob(await readNovelJobStore(id), checkout.branchId);
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
      assertNoActiveNovelJob(await readNovelJobStore(id), checkout.branchId);
      const targetBranch: NovelBranch | undefined = current.branches.find(
        (branch: NovelBranch): boolean => branch.id === targetBranchId && branch.lifecycle === 'active');
      if (targetBranch === undefined) throw invalidInput(`分支不存在: ${targetBranchId}`);
      const targetSnapshot: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, targetBranchId);
      const targetStored: NovelProject = await projectFromStoredState(
        fileStore, projectRoot(id), targetSnapshot.state);
      const target: NovelProject = {
        ...targetStored,
        name: current.name,
        modelPolicy: current.modelPolicy,
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
        await installPrivateStoreOnly(current.id, PROPOSALS_FILE, JSON.stringify(nextStore));
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
          await installPrivateStoreOnly(id, PROPOSALS_FILE, JSON.stringify(nextStore));
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

    listGhostwriteJobs: (id: string): Promise<DurableGhostwriteJob[]> =>
      withProjectLock(id, async (): Promise<DurableGhostwriteJob[]> => {
        await loadProjectInternal(id);
        const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
        if (checkout === null) throw invalidInput('checkout 无效');
        return (await readNovelJobStore(id)).jobs.filter(
          (job: DurableNovelJob): job is DurableGhostwriteJob =>
            isGhostwriteJob(job) && job.branchId === checkout.branchId).slice();
      }),

    loadGhostwriteJob: (id: string, jobId: string): Promise<DurableGhostwriteJob> =>
      withProjectLock(id, async (): Promise<DurableGhostwriteJob> => {
        await loadProjectInternal(id);
        const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
        if (checkout === null) throw invalidInput('checkout 无效');
        const store: WorkspaceNovelJobStore = await readNovelJobStore(id);
        const stored: DurableNovelJob = store.jobs[ghostwriteJobIndex(store, jobId)];
        if (!isGhostwriteJob(stored)) throw invalidInput('ghostwrite job 类型无效');
        const job: DurableGhostwriteJob = stored;
        if (job.projectId !== id || job.branchId !== checkout.branchId) {
          throw invalidInput('ghostwrite job 不属于当前 project/branch');
        }
        return job;
      }),

    startGhostwriteJob: (
      id: string, expected: NovelWorkspaceCas, jobId: string, planId: string,
      targetChapterCount: number, now: number,
    ): Promise<DurableGhostwriteJob> => withProjectLock(id, async (): Promise<DurableGhostwriteJob> => {
      const project: NovelProject = await loadProjectInternal(id);
      const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
      if (checkout === null) throw invalidInput('checkout 无效');
      assertCas(expected, checkout);
      const branchState: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, checkout.branchId);
      if (branchState.unresolvedFromOrdinal !== null || branchState.plotStale) {
        throw invalidInput('当前分支存在 unresolved 或 plot stale，不能启动代笔');
      }
      const store: WorkspaceNovelJobStore = await readNovelJobStore(id);
      if (store.jobs.some((job: DurableNovelJob): boolean => job.jobId === jobId)) {
        throw invalidInput(`ghostwrite job 已存在: ${jobId}`);
      }
      assertNoActiveNovelJob(store, checkout.branchId);
      let maxOrdinal: number = 0;
      for (let i: number = 0; i < project.chapters.length; i++) {
        if (project.chapters[i].discarded) continue;
        maxOrdinal = Math.max(maxOrdinal, chapterOrdinal(project.chapters[i], i + 1));
      }
      const frozenPlan = freezeGhostwritePlan({
        planId,
        content: project.branchSettings.thisChapterPlan,
        expectedCas: {
          branchId: expected.branchId,
          head: expected.head,
          treeDigest: expected.treeDigest,
        },
      });
      const job: DurableGhostwriteJob = makeGhostwriteJob({
        jobId,
        projectId: id,
        branchId: checkout.branchId,
        now,
        frozenPlan,
        targetChapterCount,
        startChapterOrdinal: maxOrdinal + 1,
        modelPolicyAtStart: project.modelPolicy,
      });
      const nextStore: WorkspaceNovelJobStore = {
        version: 2,
        jobs: store.jobs.concat([job]),
      };
      await saveNovelJobStoreOnly(id, nextStore);
      return job;
    }),

    claimGhostwriteJob: (
      id: string, jobId: string, token: string, now: number, leaseMs: number,
    ): Promise<DurableGhostwriteJob> => withProjectLock(id, async (): Promise<DurableGhostwriteJob> => {
      const loaded: LoadedGhostwriteJob = await loadBoundGhostwriteJob(id, jobId);
      const claimed: DurableGhostwriteJob = claimGhostwriteJobDomain(
        loaded.job, token, now, leaseMs);
      await saveNovelJobStoreOnly(id, replaceGhostwriteJob(loaded.store, loaded.index, claimed));
      return claimed;
    }),

    checkpointGhostwriteStage: (
      id: string, jobId: string, claim: GhostwriteClaimRef, nextStage: GhostwriteStage, now: number,
    ): Promise<DurableGhostwriteJob> => withProjectLock(id, async (): Promise<DurableGhostwriteJob> => {
      const loaded: LoadedGhostwriteJob = await loadClaimedGhostwriteJob(id, jobId, claim, now);
      const next: DurableGhostwriteJob = transitionGhostwriteJob(loaded.job, nextStage, now);
      await saveNovelJobStoreOnly(id, replaceGhostwriteJob(loaded.store, loaded.index, next));
      return next;
    }),

    checkpointGhostwriteCandidate: (
      id: string, jobId: string, claim: GhostwriteClaimRef,
      candidate: GhostwriteCandidate, now: number,
    ): Promise<DurableGhostwriteJob> => withProjectLock(id, async (): Promise<DurableGhostwriteJob> => {
      const loaded: LoadedGhostwriteJob = await loadClaimedGhostwriteJob(id, jobId, claim, now);
      const next: DurableGhostwriteJob = withGhostwriteCandidate(loaded.job, candidate, now);
      await saveNovelJobStoreOnly(id, replaceGhostwriteJob(loaded.store, loaded.index, next));
      return next;
    }),

    checkpointGhostwriteReview: (
      id: string, jobId: string, claim: GhostwriteClaimRef,
      review: GhostwriteReview, now: number,
    ): Promise<DurableGhostwriteJob> => withProjectLock(id, async (): Promise<DurableGhostwriteJob> => {
      const loaded: LoadedGhostwriteJob = await loadClaimedGhostwriteJob(id, jobId, claim, now);
      let next: DurableGhostwriteJob;
      if (loaded.job.stage === 'planning' && loaded.job.candidate !== null &&
        loaded.job.frozenPlan !== null && loaded.job.review !== null) {
        const checked: GhostwriteReview = validateGhostwriteReview(
          review, loaded.job.candidate, loaded.job.frozenPlan);
        if (checked.nextPlan === null) throw invalidInput('planner checkpoint 缺少下一章计划');
        next = { ...loaded.job, stage: 'committing', review: checked, updatedAt: now };
      } else {
        next = applyGhostwriteReview(loaded.job, review, now);
      }
      await saveNovelJobStoreOnly(id, replaceGhostwriteJob(loaded.store, loaded.index, next));
      return next;
    }),

    failGhostwriteJob: (
      id: string, jobId: string, claim: GhostwriteClaimRef, reason: string, now: number,
    ): Promise<DurableGhostwriteJob> => withProjectLock(id, async (): Promise<DurableGhostwriteJob> => {
      const loaded: LoadedGhostwriteJob = await loadClaimedGhostwriteJob(id, jobId, claim, now);
      const clean: string = reason.trim();
      if (clean.length === 0) throw invalidInput('ghostwrite 失败原因为空');
      const next: DurableGhostwriteJob = {
        ...loaded.job,
        stage: 'failed',
        resumeStage: loaded.job.stage,
        claim: null,
        failure: clean,
        updatedAt: now,
      };
      await saveNovelJobStoreOnly(id, replaceGhostwriteJob(loaded.store, loaded.index, next));
      return next;
    }),

    pauseGhostwriteJob: (
      id: string, jobId: string, claim: GhostwriteClaimRef, now: number,
    ): Promise<DurableGhostwriteJob> => withProjectLock(id, async (): Promise<DurableGhostwriteJob> => {
      const loaded: LoadedGhostwriteJob = await loadClaimedGhostwriteJob(id, jobId, claim, now);
      const next: DurableGhostwriteJob = pauseGhostwriteJobDomain(loaded.job, claim, now);
      await saveNovelJobStoreOnly(id, replaceGhostwriteJob(loaded.store, loaded.index, next));
      return next;
    }),

    resumeGhostwriteJob: (
      id: string, jobId: string, now: number,
    ): Promise<DurableGhostwriteJob> => withProjectLock(id, async (): Promise<DurableGhostwriteJob> => {
      const loaded: LoadedGhostwriteJob = await loadBoundGhostwriteJob(id, jobId);
      const next: DurableGhostwriteJob = resumeGhostwriteJobDomain(loaded.job, now);
      await saveNovelJobStoreOnly(id, replaceGhostwriteJob(loaded.store, loaded.index, next));
      return next;
    }),

    retryGhostwriteJob: (
      id: string, jobId: string, now: number,
    ): Promise<DurableGhostwriteJob> => withProjectLock(id, async (): Promise<DurableGhostwriteJob> => {
      const loaded: LoadedGhostwriteJob = await loadBoundGhostwriteJob(id, jobId);
      if (loaded.job.stage !== 'failed') throw invalidInput('仅失败的 ghostwrite job 可重试');
      const retryStage: GhostwriteStage = loaded.job.resumeStage === null
        ? loaded.job.candidate === null ? 'writing' : 'reviewing'
        : loaded.job.resumeStage;
      if (retryStage === 'failed' || retryStage === 'cancelled' || retryStage === 'completed' ||
        retryStage === 'paused' || retryStage === 'waiting_user') {
        throw invalidInput('ghostwrite retry stage 无效');
      }
      const next: DurableGhostwriteJob = {
        ...loaded.job,
        stage: retryStage,
        resumeStage: null,
        claim: null,
        failure: null,
        updatedAt: now,
      };
      await saveNovelJobStoreOnly(id, replaceGhostwriteJob(loaded.store, loaded.index, next));
      return next;
    }),

    cancelGhostwriteJob: (
      id: string, jobId: string, claim: GhostwriteClaimRef, now: number,
    ): Promise<DurableGhostwriteJob> => withProjectLock(id, async (): Promise<DurableGhostwriteJob> => {
      const loaded: LoadedGhostwriteJob = await loadClaimedGhostwriteJob(id, jobId, claim, now);
      const next: DurableGhostwriteJob = {
        ...loaded.job,
        stage: 'cancelled',
        resumeStage: null,
        claim: null,
        updatedAt: now,
      };
      await saveNovelJobStoreOnly(id, replaceGhostwriteJob(loaded.store, loaded.index, next));
      return next;
    }),

    commitGhostwriteChapter: (
      id: string, jobId: string, claim: GhostwriteClaimRef, commandId: string, now: number,
    ): Promise<DurableGhostwriteJob> => withProjectLock(id, async (): Promise<DurableGhostwriteJob> => {
      assertCommandId(commandId);
      const store: WorkspaceNovelJobStore = await readNovelJobStore(id);
      const index: number = ghostwriteJobIndex(store, jobId);
      const stored: DurableNovelJob = store.jobs[index];
      if (!isGhostwriteJob(stored)) throw invalidInput('ghostwrite job 类型无效');
      const storedJob: DurableGhostwriteJob = stored;
      if (storedJob.progress.some(
        (entry: GhostwriteProgress): boolean => entry.receipt === commandId)) return storedJob;
      const project: NovelProject = await loadProjectInternal(id);
      const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
      if (checkout === null) throw invalidInput('checkout 无效');
      assertGhostwriteWorkspaceBinding(id, storedJob, checkout);
      assertGhostwriteClaim(storedJob, claim, now);
      if (storedJob.stage !== 'committing' || storedJob.candidate === null ||
        storedJob.review === null || storedJob.frozenPlan === null) {
        throw invalidInput('ghostwrite job 尚未到可提交阶段');
      }
      const candidate: GhostwriteCandidate = storedJob.candidate;
      const review: GhostwriteReview = validateGhostwriteReview(
        storedJob.review, candidate, storedJob.frozenPlan);
      if (review.blocking || review.rewriteRequired) throw invalidInput('ghostwrite review 尚未通过');
      const receipt: string = ghostwriteReceipt(
        storedJob.jobId, candidate.chapterOrdinal, candidate.planId,
        candidate.planDigest, candidate.candidateId,
      );
      if (commandId !== receipt) throw invalidInput('ghostwrite commit receipt 与候选不匹配');
      if (candidate.chapterOrdinal !== storedJob.currentChapterOrdinal) {
        throw invalidInput('ghostwrite candidate ordinal 与 job cursor 不匹配');
      }
      if (project.branchSettings.thisChapterPlan.trim() !== storedJob.frozenPlan.content) {
        throw invalidInput('ghostwrite 冻结计划已变化');
      }
      for (let i: number = 0; i < project.chapters.length; i++) {
        if (!project.chapters[i].discarded &&
          chapterOrdinal(project.chapters[i], i + 1) === candidate.chapterOrdinal) {
          throw invalidInput(`ghostwrite 目标章节已存在: ${candidate.chapterOrdinal}`);
        }
      }
      const finalChapter: boolean = storedJob.currentChapterOrdinal === storedJob.endChapterOrdinal;
      if (!finalChapter && review.nextPlan === null) {
        throw invalidInput('非最终 ghostwrite 章节缺少下一章计划');
      }
      const chapter: NovelChapter = {
        id: `chapter-${candidate.chapterOrdinal.toString().padStart(3, '0')}-ghostwrite-${jobId}`,
        title: candidate.title,
        content: candidate.content,
        createdAt: now,
        updatedAt: now,
        discarded: false,
      };
      const nextPlanContent: string = finalChapter ? '' : (review.nextPlan ?? '').trim();
      const nextProject: NovelProject = withNovelProjectCompatibility({
        ...project,
        chapters: project.chapters.concat([chapter]),
        branchSettings: {
          ...project.branchSettings,
          thisChapterPlan: nextPlanContent,
        },
        revision: project.revision + 1,
        updatedAt: Math.max(project.updatedAt, now),
      });
      const plotContent: string = `${review.stateDelta.plotState.trim()}\n\n` +
        `## 本章要点\n${review.stateDelta.chapterHighlight.trim()}\n`;
      const commits: WorkspaceLedgerCommit[] = await readCommits(id);
      const prepared = buildSnapshotFiles(nextProject, checkout.branchId, plotContent);
      const treeDigest: string = snapshotTreeDigest(prepared.files);
      const commitId: string = `c${commits.length}-${treeDigest}`;
      const nextCas: NovelWorkspaceCas = {
        branchId: checkout.branchId,
        head: commitId,
        treeDigest,
      };
      const progress: GhostwriteProgress = {
        chapterOrdinal: candidate.chapterOrdinal,
        planId: candidate.planId,
        planDigest: candidate.planDigest,
        candidateId: candidate.candidateId,
        receipt,
        commitId,
        branchId: checkout.branchId,
      };
      const nextFrozenPlan = finalChapter ? null : freezeGhostwritePlan({
        planId: `${storedJob.jobId}:plan:${storedJob.currentChapterOrdinal + 1}`,
        content: nextPlanContent,
        expectedCas: nextCas,
      });
      const nextJob: DurableGhostwriteJob = validateDurableGhostwriteJob({
        ...storedJob,
        stage: finalChapter ? 'completed' : 'planning',
        resumeStage: null,
        expectedCas: nextCas,
        frozenPlan: nextFrozenPlan,
        candidate: null,
        review: null,
        rewriteCount: 0,
        currentChapterOrdinal: finalChapter
          ? storedJob.currentChapterOrdinal : storedJob.currentChapterOrdinal + 1,
        claim: finalChapter ? null : storedJob.claim,
        progress: storedJob.progress.concat([progress]),
        failure: null,
        updatedAt: now,
      });
      const nextStore: WorkspaceNovelJobStore = replaceGhostwriteJob(store, index, nextJob);
      const branch: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, checkout.branchId);
      await installSnapshot(
        nextProject, 'generation_commit', [], checkout.branchId, receipt,
        branch.unresolvedFromOrdinal, false, true, candidate.chapterOrdinal,
        false, false, undefined, plotContent, null, nextStore,
      );
      const installedCheckout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
      if (installedCheckout === null || installedCheckout.head !== commitId ||
        installedCheckout.treeDigest !== treeDigest) {
        throw invalidInput('ghostwrite 原子提交 checkout 校验失败');
      }
      return nextJob;
    }),

    ghostwriteProgress: (
      id: string, jobId: string,
    ): Promise<GhostwriteProgress[]> => withProjectLock(id, async (): Promise<GhostwriteProgress[]> => {
      await loadProjectInternal(id);
      const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
      if (checkout === null) throw invalidInput('checkout 无效');
      const store: WorkspaceNovelJobStore = await readNovelJobStore(id);
      const stored: DurableNovelJob = store.jobs[ghostwriteJobIndex(store, jobId)];
      if (!isGhostwriteJob(stored)) throw invalidInput('ghostwrite job 类型无效');
      const job: DurableGhostwriteJob = stored;
      if (job.projectId !== id || job.branchId !== checkout.branchId) return [];
      const commits: WorkspaceLedgerCommit[] = await readCommits(id);
      return projectGhostwriteProgress(job, ancestryCommitIds(commits, checkout.head));
    }),

    listPolishJobs: (id: string): Promise<DurablePolishJob[]> =>
      withProjectLock(id, async (): Promise<DurablePolishJob[]> => {
        await loadProjectInternal(id);
        const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
        if (checkout === null) throw invalidInput('checkout 无效');
        return (await readNovelJobStore(id)).jobs.filter(
          (job: DurableNovelJob): job is DurablePolishJob =>
            isPolishJob(job) && job.branchId === checkout.branchId).slice();
      }),

    loadPolishJob: (id: string, jobId: string): Promise<DurablePolishJob> =>
      withProjectLock(id, async (): Promise<DurablePolishJob> => {
        await loadProjectInternal(id);
        const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
        if (checkout === null) throw invalidInput('checkout 无效');
        const store: WorkspaceNovelJobStore = await readNovelJobStore(id);
        const stored: DurableNovelJob = store.jobs[polishJobIndex(store, jobId)];
        if (!isPolishJob(stored) || stored.projectId !== id || stored.branchId !== checkout.branchId) {
          throw invalidInput('polish job 不属于当前 project/branch');
        }
        return stored;
      }),

    startPolishJob: (
      id: string, expected: NovelWorkspaceCas, jobId: string,
      fromOrdinal: number, toOrdinal: number, contextOptions: PolishContextOptions, now: number,
    ): Promise<DurablePolishJob> => withProjectLock(id, async (): Promise<DurablePolishJob> => {
      if (!Number.isInteger(fromOrdinal) || !Number.isInteger(toOrdinal) ||
        fromOrdinal < 1 || toOrdinal < fromOrdinal) throw invalidInput('润色范围无效');
      const project: NovelProject = await loadProjectInternal(id);
      const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
      if (checkout === null) throw invalidInput('checkout 无效');
      assertCas(expected, checkout);
      const store: WorkspaceNovelJobStore = await readNovelJobStore(id);
      if (store.jobs.some((job: DurableNovelJob): boolean => job.jobId === jobId)) {
        throw invalidInput(`novel job 已存在: ${jobId}`);
      }
      assertNoActiveNovelJob(store, checkout.branchId);

      const targets: PolishChapterTarget[] = [];
      for (let ordinal: number = fromOrdinal; ordinal <= toOrdinal; ordinal++) {
        let found: NovelChapter | null = null;
        for (let index: number = 0; index < project.chapters.length; index++) {
          const chapter: NovelChapter = project.chapters[index];
          if (!chapter.discarded && chapterOrdinal(chapter, index + 1) === ordinal) {
            if (found !== null) throw invalidInput(`第 ${ordinal} 章序号重复`);
            found = chapter;
          }
        }
        if (found === null) throw invalidInput(`第 ${ordinal} 章不存在或已废弃`);
        targets.push(makePolishChapterTarget({
          id: found.id, ordinal, title: found.title, sourceContent: found.content,
        }));
      }

      const branch: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, checkout.branchId);
      const contextSnapshot: PolishContextSnapshotItem[] = [];
      let contextChars: number = 0;
      const appendContext = (
        kind: 'plot' | 'foreshadow' | 'character' | 'decision', path: string, content: string,
      ): void => {
        if (contextSnapshot.length >= MAX_POLISH_CONTEXT_ITEMS ||
          contextChars >= MAX_POLISH_CONTEXT_TOTAL_CHARS) return;
        const remaining: number = MAX_POLISH_CONTEXT_TOTAL_CHARS - contextChars;
        const limit: number = Math.min(MAX_POLISH_CONTEXT_ITEM_CHARS, remaining);
        const bounded: string = content.slice(0, limit).trim();
        if (bounded.length === 0) return;
        contextSnapshot.push(makePolishContextSnapshotItem({ kind, sourcePath: path, content: bounded }));
        contextChars += bounded.length;
      };
      if (contextOptions.includePlot && branch.plotContent !== null) {
        appendContext('plot', `branches/${checkout.branchId}/plan/plot.md`, branch.plotContent);
      }
      if (contextOptions.includeForeshadows) {
        for (let i: number = 0; i < project.branchSettings.foreshadows.length; i++) {
          const item = project.branchSettings.foreshadows[i];
          appendContext('foreshadow',
            `branches/${checkout.branchId}/setting/catalog.json/foreshadow/${item.id}`,
            `# ${item.title}\n状态: ${item.status}\n${item.content}`);
        }
      }
      if (contextOptions.includeCharacters) {
        for (let i: number = 0; i < project.materials.length; i++) {
          const material: NovelMaterial = project.materials[i];
          if (!material.enabled || material.kind !== 'character') continue;
          appendContext('character', branchMaterialPath(checkout.branchId, material),
            `# ${material.title}\n${material.content}`);
        }
      }
      if (contextOptions.includeDecisions) {
        for (let i: number = 0; i < project.branchSettings.confirmedDecisions.length; i++) {
          const item = project.branchSettings.confirmedDecisions[i];
          appendContext('decision',
            `branches/${checkout.branchId}/setting/catalog.json/decision/${item.id}`,
            `# ${item.title}\n${item.content}`);
        }
      }
      const warnings: PolishWarning[] = [];
      if (branch.plotStale) {
        warnings.push({ kind: 'plot_stale', message: '剧情摘要落后于当前正文，已向写作与审稿模型显式标记。' });
      }
      if (branch.unresolvedFromOrdinal !== null) {
        warnings.push({
          kind: 'unresolved',
          message: `第 ${branch.unresolvedFromOrdinal} 章起的后续影响尚未确认，已向模型显式标记。`,
        });
      }
      const job: DurablePolishJob = makePolishJob({
        jobId, projectId: id, branchId: checkout.branchId, targets, contextOptions,
        contextSnapshot, warnings, expectedCas: checkout,
        modelPolicyAtStart: project.modelPolicy, now,
      });
      const nextStore: WorkspaceNovelJobStore = { version: 2, jobs: store.jobs.concat([job]) };
      await saveNovelJobStoreOnly(id, nextStore);
      return job;
    }),

    claimPolishJob: (
      id: string, jobId: string, token: string, now: number, leaseMs: number,
    ): Promise<DurablePolishJob> => withProjectLock(id, async (): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadBoundPolishJob(id, jobId);
      const claimed: DurablePolishJob = claimPolishJobDomain(loaded.job, token, now, leaseMs);
      await saveNovelJobStoreOnly(id, replacePolishJob(loaded.store, loaded.index, claimed));
      return claimed;
    }),

    checkpointPolishStage: (
      id: string, jobId: string, claim: GhostwriteClaimRef, nextStage: PolishStage, now: number,
    ): Promise<DurablePolishJob> => withProjectLock(id, async (): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadClaimedPolishJob(id, jobId, claim, now);
      const next: DurablePolishJob = transitionPolishJob(loaded.job, nextStage, now);
      await saveNovelJobStoreOnly(id, replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    checkpointPolishCandidate: (
      id: string, jobId: string, claim: GhostwriteClaimRef,
      candidate: PolishCandidate, now: number,
    ): Promise<DurablePolishJob> => withProjectLock(id, async (): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadClaimedPolishJob(id, jobId, claim, now);
      const next: DurablePolishJob = withPolishCandidate(loaded.job, candidate, now);
      await saveNovelJobStoreOnly(id, replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    checkpointPolishReview: (
      id: string, jobId: string, claim: GhostwriteClaimRef,
      review: PolishReview, now: number,
    ): Promise<DurablePolishJob> => withProjectLock(id, async (): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadClaimedPolishJob(id, jobId, claim, now);
      const next: DurablePolishJob = applyPolishReview(loaded.job, review, now);
      await saveNovelJobStoreOnly(id, replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    failPolishJob: (
      id: string, jobId: string, claim: GhostwriteClaimRef, reason: string, now: number,
    ): Promise<DurablePolishJob> => withProjectLock(id, async (): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadClaimedPolishJob(id, jobId, claim, now);
      const next: DurablePolishJob = failPolishJobDomain(loaded.job, claim, reason, now);
      await saveNovelJobStoreOnly(id, replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    pausePolishJob: (
      id: string, jobId: string, claim: GhostwriteClaimRef, now: number,
    ): Promise<DurablePolishJob> => withProjectLock(id, async (): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadClaimedPolishJob(id, jobId, claim, now);
      const next: DurablePolishJob = pausePolishJobDomain(loaded.job, claim, now);
      await saveNovelJobStoreOnly(id, replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    yieldPolishJob: (
      id: string, jobId: string, claim: GhostwriteClaimRef, now: number,
    ): Promise<DurablePolishJob> => withProjectLock(id, async (): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadClaimedPolishJob(id, jobId, claim, now);
      const next: DurablePolishJob = yieldPolishJobToSystem(loaded.job, claim, now);
      await saveNovelJobStoreOnly(id, replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    resumePolishJob: (
      id: string, jobId: string, now: number,
    ): Promise<DurablePolishJob> => withProjectLock(id, async (): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadBoundPolishJob(id, jobId);
      const next: DurablePolishJob = resumePolishJobDomain(loaded.job, now);
      await saveNovelJobStoreOnly(id, replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    retryPolishJob: (
      id: string, jobId: string, now: number,
    ): Promise<DurablePolishJob> => withProjectLock(id, async (): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadBoundPolishJob(id, jobId);
      const next: DurablePolishJob = retryPolishJobDomain(loaded.job, now);
      await saveNovelJobStoreOnly(id, replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    cancelPolishJob: (
      id: string, jobId: string, now: number,
    ): Promise<DurablePolishJob> => withProjectLock(id, async (): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadBoundPolishJob(id, jobId);
      const next: DurablePolishJob = cancelPolishJobDomain(loaded.job, now);
      await saveNovelJobStoreOnly(id, replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    commitPolishChapter: (
      id: string, jobId: string, claim: GhostwriteClaimRef, commandId: string, now: number,
    ): Promise<DurablePolishJob> => withProjectLock(id, async (): Promise<DurablePolishJob> => {
      assertCommandId(commandId);
      const store: WorkspaceNovelJobStore = await readNovelJobStore(id);
      const index: number = polishJobIndex(store, jobId);
      const stored: DurableNovelJob = store.jobs[index];
      if (!isPolishJob(stored)) throw invalidInput('polish job 类型无效');
      if (stored.progress.some((entry: PolishProgress): boolean => entry.receipt === commandId)) return stored;
      const project: NovelProject = await loadProjectInternal(id);
      const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
      if (checkout === null) throw invalidInput('checkout 无效');
      assertPolishWorkspaceBinding(id, stored, checkout);
      assertPolishClaim(stored, claim, now);
      if (stored.stage !== 'committing' || stored.candidate === null || stored.review === null) {
        throw invalidInput('polish job 尚未到可提交阶段');
      }
      const candidate: PolishCandidate = makePolishCandidate({
        jobId: stored.jobId,
        candidateId: stored.candidate.candidateId,
        chapterId: stored.candidate.chapterId,
        chapterOrdinal: stored.candidate.chapterOrdinal,
        sourceDigest: stored.candidate.sourceDigest,
        content: stored.candidate.content,
        attempt: stored.candidate.attempt,
      });
      validatePolishReview(stored.review, candidate);
      const target: PolishChapterTarget | null = currentPolishTarget(stored);
      if (target === null || target.id !== candidate.chapterId) throw invalidInput('polish target 与 candidate 不匹配');
      const receipt: string = polishReceipt(
        stored.jobId, candidate.chapterId, candidate.chapterOrdinal, candidate.sourceDigest,
        candidate.candidateId, candidate.digest,
      );
      if (commandId !== receipt) throw invalidInput('polish commit receipt 与候选不匹配');
      const chapterIndex: number = project.chapters.findIndex(
        (chapter: NovelChapter): boolean => chapter.id === target.id);
      if (chapterIndex < 0 || project.chapters[chapterIndex].discarded) {
        throw invalidInput('polish 目标章节不存在或已废弃');
      }
      const currentChapter: NovelChapter = project.chapters[chapterIndex];
      if (defaultGhostwriteDigest(currentChapter.content) !== target.sourceDigest) {
        throw invalidInput('polish 目标章节已在任务启动后变更');
      }
      const chapters: NovelChapter[] = project.chapters.slice();
      chapters[chapterIndex] = { ...currentChapter, content: candidate.content, updatedAt: now };
      const backup = makeNovelChapterVersion(
        currentChapter.id, 'polish', currentChapter.title, currentChapter.content, now);
      const nextProject: NovelProject = withNovelProjectCompatibility({
        ...project,
        chapters,
        chapterVersions: project.chapterVersions.concat([backup]),
        revision: project.revision + 1,
        updatedAt: Math.max(project.updatedAt, now),
      });
      const branch: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, checkout.branchId);
      const commits: WorkspaceLedgerCommit[] = await readCommits(id);
      const prepared = buildSnapshotFiles(nextProject, checkout.branchId, branch.plotContent);
      const treeDigest: string = snapshotTreeDigest(prepared.files);
      const commitId: string = `c${commits.length}-${treeDigest}`;
      const nextCas: NovelWorkspaceCas = { branchId: checkout.branchId, head: commitId, treeDigest };
      const nextJob: DurablePolishJob = commitPolishChapterDomain(
        stored, claim, { branchId: checkout.branchId, commitId, receipt, nextCas }, now);
      const nextStore: WorkspaceNovelJobStore = replacePolishJob(store, index, nextJob);
      await installSnapshot(
        nextProject, 'batch_polish_commit', [], checkout.branchId, receipt,
        branch.unresolvedFromOrdinal, branch.plotStale, true, target.ordinal,
        false, false, undefined, branch.plotContent, null, nextStore,
      );
      const installedCheckout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
      if (installedCheckout === null || installedCheckout.head !== commitId ||
        installedCheckout.treeDigest !== treeDigest) {
        throw invalidInput('polish 原子提交 checkout 校验失败');
      }
      return nextJob;
    }),

    polishProgress: (
      id: string, jobId: string,
    ): Promise<PolishProgress[]> => withProjectLock(id, async (): Promise<PolishProgress[]> => {
      await loadProjectInternal(id);
      const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
      if (checkout === null) throw invalidInput('checkout 无效');
      const store: WorkspaceNovelJobStore = await readNovelJobStore(id);
      const stored: DurableNovelJob = store.jobs[polishJobIndex(store, jobId)];
      if (!isPolishJob(stored) || stored.projectId !== id || stored.branchId !== checkout.branchId) return [];
      const commits: WorkspaceLedgerCommit[] = await readCommits(id);
      return projectPolishProgress(stored, ancestryCommitIds(commits, checkout.head));
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
      let bestLegacyFile: string = '';
      for (let i: number = 0; i < names.length; i++) {
        try {
          const project: NovelProject = await loadAt(fileStore, deletedRoot(names[i]));
          if (best === null || project.updatedAt > best.updatedAt) {
            best = project;
            bestId = names[i];
            bestLegacyFile = '';
          }
        } catch {
          // 跳过损坏的回收项，不删除证据。
        }
      }
      const legacyNames: string[] = await fileStore.list(LEGACY_DELETED_DIR);
      for (let i: number = 0; i < legacyNames.length; i++) {
        if (!legacyNames[i].endsWith(LEGACY_SUFFIX)) continue;
        const path: string = joinPath(LEGACY_DELETED_DIR, legacyNames[i]);
        try {
          const raw: string | null = await fileStore.readText(path);
          if (raw === null) continue;
          const project: NovelProject = decodeLegacy(raw);
          if (best === null || project.updatedAt > best.updatedAt) {
            best = project;
            bestId = '';
            bestLegacyFile = path;
          }
        } catch {
          // 旧版损坏 JSON 必须保留，便于人工取证或修复。
        }
      }
      if (best === null) return null;
      if (bestLegacyFile.length > 0) {
        const selectedId: string = best.id;
        const selectedFile: string = bestLegacyFile;
        return await withProjectLock(selectedId, async (): Promise<NovelProject> => {
          const raw: string | null = await fileStore.readText(selectedFile);
          if (raw === null) throw invalidInput('待恢复项目已不存在');
          const selected: NovelProject = decodeLegacy(raw);
          if (selected.id !== selectedId) throw invalidInput('待恢复项目已变更');
          await recoverProject(selectedId);
          if (await fileStore.exists(projectRoot(selectedId))) throw invalidInput('同 id 项目已存在');
          const restored: NovelProject = await installSnapshot(selected, 'legacy_restore');
          await fileStore.delete(selectedFile);
          return restored;
        });
      }
      return await withProjectLock(best.id, async (): Promise<NovelProject> => {
        if (await fileStore.exists(projectRoot(best.id))) throw invalidInput('同 id 项目已存在');
        if (!await fileStore.exists(deletedRoot(bestId))) throw invalidInput('待恢复项目已不存在');
        await fileStore.rename(deletedRoot(bestId), projectRoot(best.id));
        await loadProjectInternal(best.id);
        const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(best.id));
        if (checkout === null) throw invalidInput('checkout 无效');
        return await commitLocked(
          best.id, checkout, novelId(), 'compat_update',
          (project: NovelProject): NovelProject => project,
        );
      });
    },

    async installWorkspace(input: NovelWorkspaceValidatedImport): Promise<NovelProject> {
      const manifest: NovelWorkspaceManifest = input.manifest;
      let id: string = manifest.projectId;
      const requestedAvailable: boolean = await withProjectLock(id, async (): Promise<boolean> => {
        await recoverProject(id);
        return !await fileStore.exists(projectRoot(id)) && !await fileStore.exists(deletedRoot(id));
      });
      if (!requestedAvailable) {
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
      const base: NovelProject = makeNovelProject({ id, name: manifest.title, now: manifest.createdAt });
      const project: NovelProject = withNovelProjectCompatibility({
        ...base,
        chapters,
        materials,
        updatedAt: manifest.updatedAt,
        branches: [{
          id: manifest.activeBranch,
          name: manifest.activeBranch === 'main' ? '主线' : manifest.activeBranch,
          lifecycle: 'active',
          isMain: true,
          forkFromChapterId: null,
          createdAt: manifest.createdAt,
        }],
      });
      return await withProjectLock(id, async (): Promise<NovelProject> => {
        await recoverProject(id);
        if (await fileStore.exists(projectRoot(id)) || await fileStore.exists(deletedRoot(id))) {
          throw invalidInput('导入项目 id 已存在');
        }
        return await installSnapshot(project, 'workspace_import', input.files);
      });
    },

    async workspaceFiles(id: string): Promise<NovelWorkspaceArchiveFile[]> {
      return await withProjectLock(id, async (): Promise<NovelWorkspaceArchiveFile[]> => {
        await loadProjectInternal(id);
        const files: NovelWorkspaceArchiveFile[] = await walkWorkspaceFiles(fileStore, projectRoot(id));
        return files.filter((file: NovelWorkspaceArchiveFile): boolean =>
          !file.path.startsWith('.amber/'));
      });
    },

    async bookExportInput(id: string): Promise<NovelBookExportInput> {
      return await withProjectLock(id, async (): Promise<NovelBookExportInput> => {
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
      });
    },
  };
  return repository;
};
