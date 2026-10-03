import { frozenPolishContext } from './polish_context.ts';
import { recordPolishChapterResult } from './polish.ts';
import { saveChapterVersion } from './mutations.ts';
import { confirmedChapterPlanText, reconcileNovelBranchPlans, forkNovelBranchPlans } from './chapter_contract.ts';
// 小说项目仓库。生产数据从旧的单 JSON 迁入 amber.novel.workspace v1；
// 页面仍通过 NovelProjectRepository 访问，因此没有第二个数据源或平行入口。

import { applyNovelSpecializedOperation, makeNovelProjectOperation, makeNovelSpecializedReview, novelOperationSourceDigest } from './specialized_operations.ts';
import type { NovelProjectOperationKind, NovelProjectToolInput } from './specialized_operations.ts';
import { materialSuggestionChapterDigest } from './material_adoption.ts';
import { normalizeNovelMaterialFields } from './material_fields.ts';
import { initializeSharedMaterials, withBranchMaterialEdits, withUpdatedSharedMaterials, restoreNovelMaterialInheritance } from './material_inheritance.ts';
import { normalizeNovelCreationMetadata, assertNovelCreationMetadataImmutable } from './project_metadata.ts';
import { reachableWorkspaceHistory, previousWorkspaceCheckpoint, selectWorkspaceCheckpoint, mutateNovelBranchMetadata } from './history_selection.ts';
import type { FileStore } from '../platform/files.ts';
import { joinPath, createMemoryFileStore } from '../platform/files.ts';
import { workspaceStorageToken as stableDigest, writeWorkspaceStorageFiles, swapWorkspaceStorageStage } from './workspace_storage.ts';
import type { NovelProjectInventory, NovelProjectFailure, NovelProjectRecoveryPreview, NovelNativeRestorePreview, NovelNativeBackupSnapshot } from './workspace_storage.ts';
import type { NovelNativeBackupImport } from './native_backup.ts';
import { copyNovelNativeBackup } from './native_backup_copy.ts';
import { buildNovelWorkspaceImportPlan } from './workspace_interop.ts';
import type { NovelWorkspaceImportPlan, NovelWorkspaceBranchImport } from './workspace_interop.ts';
import {
  NOVEL_SCHEMA_VERSION, novelId, novelChapterOrdinal, nextNovelChapterOrdinal, defaultNovelModelPolicy, emptyNovelBranchSettings,
  makeNovelChapterVersion, makeNovelMessage, makeNovelProject, withNovelProjectCompatibility,
} from './models.ts';
import type {
  NovelBranch, NovelBranchSettings, NovelChapter, NovelMaterial, NovelMaterialKind, NovelMessage,
  NovelModelPolicy, NovelProject, NovelSettingProposal,
} from './models.ts';
import { firstStaleChapterOrdinal, rebuildChapterPlots, updateChapterPlots } from './plot_projection.ts';
import type { NovelChapterPlotPointer } from './plot_projection.ts';
import type { UIMessage } from '../agent/message.ts';
import { notFound, invalidInput } from './error.ts';
import {
  NOVEL_WORKSPACE_FORMAT, NOVEL_WORKSPACE_VERSION, chapterFileName,
  parseNovelWorkspaceManifest, serializeNovelWorkspaceManifest,
} from './workspace_contract.ts';
import type { NovelWorkspaceManifest } from './workspace_contract.ts';
import { decodeNovelWorkspaceUtf8 } from './workspace_exchange.ts';
import { invalidateNovelStructuredState } from './structured_state.ts';
import { assertNovelStateSources } from './state_rebuild.ts';
import type { NovelStateOperation } from './state_rebuild.ts';
import type {
  NovelWorkspaceArchiveFile, NovelWorkspaceValidatedImport,
} from './workspace_exchange.ts';
import type { NovelBookChapterSnapshot, NovelBookExportInput } from './workspace_book_export.ts';
import {
  parseWorkspaceCommit, resolveDurableWorkspaceProposal, validateDurableWorkspaceProposal,
  validateWorkspaceReceiptLedger,
} from './workspace_history.ts';
import type {
  DurableWorkspaceProposal, WorkspaceCommit, WorkspaceProposalPatch, WorkspaceProposalReview,
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
const PUBLIC_ORIGINALS_PREFIX: string = '.amber/public-originals/';

export type NovelWorkspaceMutationKind =
  | 'compat_update' | 'rename' | 'model_change' | 'manual_edit' | 'chapter_delete'
  | 'material_edit' | 'material_delete' | 'collect' | 'suggestion_refresh'
  | 'proposal_resolve' | 'proposal_create' | 'generation_commit' | 'polish_adopt' | 'chapter_discard'
  | 'version_restore' | 'discussion_archive' | 'interrupt_commit' | 'create_branch'
  | 'undo' | 'plot_sync' | 'unresolved_resolve' | 'transcript_checkpoint'
  | 'branch_settings_change' | 'batch_polish_commit' | 'project_setup_change'
  | 'branch_rename' | 'branch_main' | 'branch_delete' | 'history_fork'
  | 'material_base_change' | 'material_inheritance_restore' | 'state_operation'
  | 'identity_clarification';

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

export interface NovelWorkspaceSnapshot {
  project: NovelProject;
  status: NovelWorkspaceStatus;
}

export interface NovelProjectRepository {
  listProjects(): Promise<NovelProject[]>;
  listProjectInventory(): Promise<NovelProjectInventory>;
  inspectProjectRecovery(id: string): Promise<NovelProjectRecoveryPreview | null>;
  restoreProjectRecovery(preview: NovelProjectRecoveryPreview): Promise<NovelProject>;
  readProjectRecovery(preview: NovelProjectRecoveryPreview): Promise<NovelProject>;
  nativeBackupSnapshot(id: string): Promise<NovelNativeBackupSnapshot>;
  inspectNativeRestore(input: NovelNativeBackupImport): Promise<NovelNativeRestorePreview>;
  installNativeBackup(input: NovelNativeBackupImport, preview: NovelNativeRestorePreview): Promise<NovelProject>;
  inspectWorkspaceRestore(plan: NovelWorkspaceImportPlan): Promise<NovelNativeRestorePreview>;
  installWorkspacePlan(plan: NovelWorkspaceImportPlan, preview?: NovelNativeRestorePreview): Promise<NovelProject>;
  copyNativeBackup(input: NovelNativeBackupImport, projectId: string): Promise<NovelNativeBackupImport>;
  publicExportPlan(id: string): Promise<NovelWorkspaceImportPlan>;
  loadProject(id: string): Promise<NovelProject>;
  createProject(project: NovelProject): Promise<NovelProject>;
  updateProject(id: string, transform: (p: NovelProject) => NovelProject): Promise<NovelProject>;
  workspaceStatus(id: string): Promise<NovelWorkspaceStatus>;
  readWorkspaceSnapshot(id: string): Promise<NovelWorkspaceSnapshot>;
  commitProject(
    id: string, expected: NovelWorkspaceCas, commandId: string, kind: NovelWorkspaceMutationKind,
    transform: (p: NovelProject) => NovelProject,
  ): Promise<NovelProject>;
  createBranch(
    id: string, name: string, expected: NovelWorkspaceCas, commandId: string,
  ): Promise<NovelProject>;
  switchBranch(id: string, branchId: string, expected: NovelWorkspaceCas): Promise<NovelProject>;
  undo(id: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject>;
  workspaceHistory(id: string): Promise<WorkspaceCommit[]>;
  chapterVersionCheckpoint(id: string, versionId: string, expected?: NovelWorkspaceCas): Promise<WorkspaceCommit | null>;
  saveSharedMaterial(id: string, material: NovelMaterial, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject>;
  deleteSharedMaterial(id: string, materialId: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject>;
  restoreMaterialInheritance(id: string, materialId: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject>;
  forkFromHistory(id: string, head: string, name: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject>;
  undoToCheckpoint(id: string, head: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject>;
  renameBranch(id: string, branchId: string, name: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject>;
  setMainBranch(id: string, branchId: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject>;
  deleteBranch(id: string, branchId: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject>;
  syncPlot(id: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject>;
  completeStateOperation(id: string, expected: NovelWorkspaceCas, operation: NovelStateOperation): Promise<NovelProject>;
  resolveUnresolved(id: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject>;
  workspaceProposals(id: string): Promise<DurableWorkspaceProposal[]>;
  createProposal(
    id: string, expected: NovelWorkspaceCas, proposalId: string,
    patches: WorkspaceProposalPatch[], createdAt: number, review?: WorkspaceProposalReview,
  ): Promise<DurableWorkspaceProposal>;
  proposeProjectOperation(id: string, kind: NovelProjectOperationKind, args: NovelProjectToolInput, expected: NovelWorkspaceCas, proposalId: string, createdAt: number): Promise<DurableWorkspaceProposal>;
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
  reviseGhostwriteWithBrief(
    id: string, jobId: string, brief: string, expected: NovelWorkspaceCas, now: number,
    expectedCandidateDigest?: string,
  ): Promise<DurableGhostwriteJob>;
  cancelGhostwriteJob(
    id: string, jobId: string, claim: GhostwriteClaimRef | null, now: number,
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
    selectedOrdinals?: number[],
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
  recordPolishChapterResult(id: string, jobId: string, claim: GhostwriteClaimRef, status: 'failed' | 'driftSkipped', reason: string, now: number): Promise<DurablePolishJob>;
  retryPolishJob(id: string, jobId: string, now: number, chapterIds?: string[]): Promise<DurablePolishJob>;
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
  'settingProposals' | 'messages' | 'modelId' | 'modelPolicy' | 'branchSettings' | 'chapterPlots' | 'discussionArchives'> {
  settingProposals?: NovelSettingProposal[];
  messages: PersistedNovelMessage[];
  modelId?: string | null;
  modelPolicy?: NovelModelPolicy;
  branchSettings?: NovelBranchSettings;
  chapterPlots?: NovelProject['chapterPlots'];
  discussionArchives?: NovelProject['discussionArchives'];
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
      candidate: message.candidate,
      runKind: message.runKind,
      clonedFromMessageId: message.clonedFromMessageId,
      rootMessageId: message.rootMessageId,
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
    baseMaterials: stored.baseMaterials,
    materialOverrides: stored.materialOverrides,
    hiddenMaterialIds: stored.hiddenMaterialIds,
    structuredState: stored.structuredState,
    stateOperation: stored.stateOperation,
    ordinaryRun: stored.ordinaryRun,
    stateSyncReasoningEnabled: stored.stateSyncReasoningEnabled === true,
    createdAt: stored.createdAt,
    updatedAt: stored.updatedAt,
    lastGenerationGranularity: stored.lastGenerationGranularity,
    materialSuggestions: stored.materialSuggestions,
    settingProposals: stored.settingProposals ?? [],
    chapterPlots: stored.chapterPlots ?? [],
    discussionArchives: stored.discussionArchives ?? [],
    branches: stored.branches,
    chapterVersions: stored.chapterVersions,
    branchSettings: stored.branchSettings ?? emptyNovelBranchSettings(),
    revision: stored.revision,
    ...normalizeNovelCreationMetadata(stored as NovelProject),
  });
};

const validateProject = (project: NovelProject): NovelProject => {
  const normalized: NovelProject = migrateNovelProject(project as PersistedNovelProject);
  normalized.chapters.forEach((chapter: NovelChapter): void => {
    if (chapter.ordinal !== undefined && (!Number.isInteger(chapter.ordinal) || chapter.ordinal < 1 || chapter.ordinal > 999)) {
      throw invalidInput('章节序号无效');
    }
  });
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

const withAuthorPlot = (project: NovelProject, content: string | null): NovelProject => {
  Object.defineProperty(project, 'authorPlot', { value: content, enumerable: false, configurable: true });
  return project;
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
    if (commit.restoredFromHead !== undefined) {
      const target: WorkspaceLedgerCommit | undefined = checked.slice(0, i).find(item => item.commitId === commit.restoredFromHead);
      if (commit.mutation !== 'undo' || target === undefined || target.branchId !== commit.branchId) {
        throw invalidInput('工作区台账恢复检查点无效');
      }
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

const projectChapterPlots = (
  before: NovelProject, after: NovelProject, branch: WorkspaceBranchSnapshot,
): NovelProject => {
  const prior: NovelChapterPlotPointer[] = before.chapterPlots.length === 0 && branch.plotStale
    ? rebuildChapterPlots(before.chapters).map((pointer: NovelChapterPlotPointer, index: number): NovelChapterPlotPointer => ({
      ...pointer, stale: index + 1 >= (branch.unresolvedFromOrdinal ?? 1),
    })) : before.chapterPlots;
  return { ...after, chapterPlots: updateChapterPlots(before.chapters, after.chapters, prior),
    structuredState: after.structuredState === undefined ? undefined
      : invalidateNovelStructuredState(before.chapters, after.chapters, after.structuredState) };
};

const projectedPlotStatus = (
  after: NovelProject,
): { unresolved: number | null; stale: boolean } => {
  const unresolved: number | null = firstStaleChapterOrdinal(after.chapters, after.chapterPlots);
  return { unresolved, stale: unresolved !== null };
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

const chapterOrdinal = novelChapterOrdinal;

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
      ? `.amber/discarded/${branch.id}/${chapter.id}.md`
      : `branches/${branch.id}/chapters/${chapterFileName(chapterOrdinal(chapter, i + 1), chapter.title)}`;
    chapterRecords.push({
      id: chapter.id,
      title: chapter.title,
      createdAt: chapter.createdAt,
      updatedAt: chapter.updatedAt,
      discarded: chapter.discarded,
      ordinal: chapter.ordinal,
      suggestionWarning: chapter.suggestionWarning,
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
      aliases: material.aliases, tags: material.tags, customKind: material.customKind, injectionMode: material.injectionMode,
      createdAt: material.createdAt,
      updatedAt: material.updatedAt,
      path: relative,
    });
    files.push({ path: relative, content: material.content });
    if (project.baseMaterials === undefined) files.push({ path: materialPath(material), content: material.content });
  }
  for (const base of project.baseMaterials ?? []) files.push({ path: materialPath(base), content: base.content });
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
      chapterPlots: project.chapterPlots,
      discussionArchives: project.discussionArchives,
      revision: project.revision,
      creationMode: project.creationMode,
      quickStartSeed: project.quickStartSeed,
      polishPreference: project.polishPreference,
      baseMaterials: project.baseMaterials,
      materialOverrides: project.materialOverrides,
      hiddenMaterialIds: project.hiddenMaterialIds,
      structuredState: project.structuredState,
      stateOperation: project.stateOperation,
      ordinaryRun: project.ordinaryRun,
      stateSyncReasoningEnabled: project.stateSyncReasoningEnabled === true,
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

const chapterContentsMatch = (actual: NovelChapter[], expected: NovelChapter[]): boolean =>
  actual.length === expected.length && actual.every((chapter: NovelChapter, index: number): boolean =>
    chapter.id === expected[index].id && chapter.content === expected[index].content);

const branchChapterRecordsMatch = (actual: WorkspaceChapterRecord[], expected: WorkspaceChapterRecord[]): boolean =>
  actual.length === expected.length && actual.every((chapter: WorkspaceChapterRecord, index: number): boolean => {
    const other: WorkspaceChapterRecord = expected[index];
    return chapter.id === other.id && chapter.title === other.title && chapter.createdAt === other.createdAt &&
      chapter.updatedAt === other.updatedAt && chapter.discarded === other.discarded &&
      chapter.suggestionWarning === other.suggestionWarning && chapter.ordinal === other.ordinal &&
      (chapter.discarded || chapter.path === other.path);
  });

const branchStateMatchesCommit = (
  current: WorkspaceProjectState, committed: WorkspaceProjectState,
): boolean => branchChapterRecordsMatch(current.chapters, committed.chapters) &&
  JSON.stringify(current.materials) === JSON.stringify(committed.materials) &&
  // Project-level model routing follows the current checkout across branches, like the name.
  JSON.stringify(current.project.messages) === JSON.stringify(committed.project.messages) &&
  JSON.stringify(current.project.materialSuggestions) === JSON.stringify(committed.project.materialSuggestions) &&
  JSON.stringify(current.project.settingProposals) === JSON.stringify(committed.project.settingProposals) &&
  JSON.stringify(current.project.chapterVersions) === JSON.stringify(committed.project.chapterVersions) &&
  JSON.stringify(current.project.chapterPlots ?? []) === JSON.stringify(committed.project.chapterPlots ?? []) &&
  JSON.stringify(current.project.discussionArchives ?? []) === JSON.stringify(committed.project.discussionArchives ?? []) &&
  JSON.stringify(current.project.baseMaterials) === JSON.stringify(committed.project.baseMaterials) &&
  JSON.stringify(current.project.materialOverrides) === JSON.stringify(committed.project.materialOverrides) &&
  JSON.stringify(current.project.hiddenMaterialIds) === JSON.stringify(committed.project.hiddenMaterialIds) &&
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
    if (!liveExists && backupExists) {
      if (await workspaceNeedsSchemaMigration(fileStore, backup)) await loadAt(fileStore, backup);
      else await validateFullTree(await walkWorkspaceFiles(fileStore, backup));
      await fileStore.rename(backup, live);
    }
    else if (liveExists && backupExists) {
      // A failed live tree must never erase the only valid pre-swap copy.
      if (!await workspaceNeedsSchemaMigration(fileStore, live)) {
        await validateFullTree(await walkWorkspaceFiles(fileStore, live));
        await fileStore.deleteTree(backup);
      }
    }
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
      await fileStore.writeBytes(joinPath(stage, path), files[i].bytes);
    }
    await fileStore.writeText(joinPath(stage, targetPath), content);
    if (await fileStore.exists(backup)) await fileStore.rename(backup, joinPath(ROOT, '.retained', id, novelId()));
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
    const planPath: string = branchSettingPaths(branch.branchId).thisChapterPlan;
    const chapterPaths: Set<string> = new Set<string>();
    for (let i: number = 0; i < branch.state.chapters.length; i++) {
      chapterPaths.add(branch.state.chapters[i].path);
    }
    for (let i: number = 0; i < proposal.patches.length; i++) {
      const path: string = proposal.patches[i].path;
      if (path !== plotPath && path !== planPath && !chapterPaths.has(path)) {
        throw invalidInput(`proposal 仅可修改已有章节、plot 或本章计划: ${path}`);
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
      await fileStore.writeBytes(joinPath(stage, path), allFiles[i].bytes);
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
    if (await fileStore.exists(backup)) await fileStore.rename(backup, joinPath(ROOT, '.retained', id, novelId()));
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
    restoredFromHead?: string,
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
    const sharedWrites: SnapshotFile[] = [];
    const replacedSharedPaths: Set<string> = new Set();
    const baseChanged: boolean = priorActiveSnapshot !== null && normalized.baseMaterials !== undefined &&
      JSON.stringify(priorActiveSnapshot.state.project.baseMaterials) !== JSON.stringify(normalized.baseMaterials);
    if (baseChanged && priorCheckout !== null) {
      // Every affected branch will receive a new head. Validate the existing complete
      // canonical tree first, so an unrelated damaged branch cannot become a new commit.
      await validateFullTree(allPrior);
      const jobs: WorkspaceNovelJobStore = await readNovelJobStore(project.id);
      const original: NovelProject = await projectFromStoredState(fileStore, live, priorActiveSnapshot!.state);
      const originalBase: NovelMaterial[] = original.baseMaterials ?? original.materials;
      for (const branch of normalized.branches.filter(item => item.lifecycle === 'active')) {
        assertNoActiveNovelJob(jobs, branch.id);
        if (branch.id === activeBranchId) continue;
        const previous: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(project.id, branch.id);
        const branchProject: NovelProject = await projectFromStoredState(fileStore, live, previous.state);
        const previousHead: WorkspaceLedgerCommit | null = latestBranchCommit(priorCommits, branch.id);
        if (previousHead === null) throw invalidInput('分支 head 缺失');
        const next: NovelProject = withUpdatedSharedMaterials(branchProject, originalBase, normalized.baseMaterials!);
        const branchBuild = buildSnapshotFiles({ ...next, branches: normalized.branches,
          name: normalized.name, revision: branchProject.revision + 1, updatedAt: normalized.updatedAt }, branch.id, previous.plotContent);
        const digest: string = snapshotTreeDigest(branchBuild.files);
        const branchHead: string = `c${priorCommits.length}-${digest}`;
        const synced: string | null = previous.plotStale ? previous.plotSyncedTreeDigest : digest;
        const branchState: WorkspaceBranchSnapshot = { ...previous, state: branchBuild.state,
          plotSourceTreeDigest: digest, plotSyncedTreeDigest: synced, plotStale: synced !== digest };
        const commitSnapshot: WorkspaceCommitSnapshot = { project: branchBuild.project,
          unresolvedFromOrdinal: previous.unresolvedFromOrdinal, plotStale: branchState.plotStale,
          plotSourceTreeDigest: digest, plotSyncedTreeDigest: synced, plotContent: previous.plotContent };
        priorCommits.push({ version: 1, commitId: branchHead, parent: previousHead.commitId,
          branchId: branch.id, treeDigest: digest, mutation: 'material_base_change',
          receipt: `shared:${receipt ?? mutation}:${branch.id}:${priorCommits.length}`,
          changedPaths: [`branches/${branch.id}/plan/state.md`], changedChapterOrdinal: null, createdAt: normalized.updatedAt });
        buildSnapshotFiles(branchProject, branch.id, previous.plotContent).files.forEach(file => {
          if (file.path.startsWith(`branches/${branch.id}/`) || file.path.startsWith('setting/')) replacedSharedPaths.add(file.path);
        });
        const writes: SnapshotFile[] = branchBuild.files.filter(file => file.path.startsWith(`branches/${branch.id}/`))
          .concat([{ path: branchSnapshotPath(branch.id), content: JSON.stringify(branchState) },
            { path: commitSnapshotPath(branchHead), content: JSON.stringify(commitSnapshot) }]);
        writes.forEach(file => { replacedSharedPaths.add(file.path); sharedWrites.push(file); });
      }
    }
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
        !((clearUndo || makeUndo) && file.path === undoPath(activeBranchId)),
    );
    const preserved: NovelWorkspaceArchiveFile[] = preservedPublic.concat(preservedPrivate)
      .filter(file => !replacedSharedPaths.has(file.path));
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
    if (restoredFromHead !== undefined) commit.restoredFromHead = restoredFromHead;
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
      : plotStale
        ? priorActiveSnapshot?.plotSyncedTreeDigest === treeDigest
          ? null : priorActiveSnapshot?.plotSyncedTreeDigest ?? null
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
    const files: SnapshotFile[] = generatedFiles.concat(sharedWrites).concat([
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
      await fileStore.writeBytes(joinPath(stage, passthrough[i].path), passthrough[i].bytes);
    }
    if (!liveExists) {
      await fileStore.rename(stage, live);
      return withAuthorPlot(project, plotContent);
    }
    if (await fileStore.exists(backup)) await fileStore.rename(backup, joinPath(ROOT, '.retained', project.id, novelId()));
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
    return withAuthorPlot(project, plotContent);
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
      if (!await workspaceNeedsSchemaMigration(fileStore, root)) return project;
      const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, root);
      if (checkout === null) throw invalidInput('迁移前 checkout 无效');
      const branch: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, checkout.branchId);
      const migrated: NovelProject = projectChapterPlots(project, project, branch);
      return await installSnapshot(migrated, 'schema_migration', [], checkout.branchId, null,
        branch.unresolvedFromOrdinal, branch.plotStale, false, null, false, false,
        branch.plotSyncedTreeDigest, branch.plotContent);
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

  const stageWorkspaceProposalLocked = async (id: string, proposal: DurableWorkspaceProposal): Promise<DurableWorkspaceProposal> => {
    const store: WorkspaceProposalStore = await readProposalStore(id);
    const existing: DurableWorkspaceProposal | undefined = store.proposals.find(item => item.proposalId === proposal.proposalId);
    if (existing !== undefined) {
      if (JSON.stringify(existing) !== JSON.stringify(proposal)) throw invalidInput(`proposal id 已存在: ${proposal.proposalId}`);
      return existing;
    }
    await installPrivateStoreOnly(id, PROPOSALS_FILE, JSON.stringify({ version: 1,
      proposals: store.proposals.concat([proposal]), decisions: store.decisions }));
    return proposal;
  };

  const readHistoricalSnapshot = async (id: string, commit: WorkspaceCommit): Promise<WorkspaceCommitSnapshot> => {
    const raw: string | null = await fileStore.readText(joinPath(projectRoot(id), commitSnapshotPath(commit.commitId)));
    if (raw === null) throw invalidInput('检查点快照缺失');
    const snapshot: WorkspaceCommitSnapshot = JSON.parse(raw) as WorkspaceCommitSnapshot;
    snapshot.project = validateProject(snapshot.project);
    if (snapshot.project.id !== id || snapshotTreeDigest(buildSnapshotFiles(snapshot.project,
      commit.branchId, snapshot.plotContent).files) !== commit.treeDigest) throw invalidInput('检查点快照与台账不一致');
    return snapshot;
  };

  const assertProposalCheckout = (proposal: DurableWorkspaceProposal, checkout: WorkspaceCheckout, commits: WorkspaceCommit[]): void => {
    if (proposal.expectedCas.head === checkout.head) { assertCas(proposal.expectedCas, checkout); return; }
    if (proposal.expectedCas.branchId !== checkout.branchId || proposal.expectedCas.treeDigest !== checkout.treeDigest) {
      throw invalidInput('工作区已变化，拒绝覆盖');
    }
    let cursor: string | null = checkout.head;
    while (cursor !== proposal.expectedCas.head) {
      const commit = commits.find(item => item.commitId === cursor);
      if (commit === undefined || commit.branchId !== checkout.branchId || commit.mutation !== 'transcript_checkpoint' ||
        commit.treeDigest !== checkout.treeDigest || commit.parent === null) throw invalidInput('工作区已变化，拒绝覆盖');
      cursor = commit.parent;
    }
  };

  const assertProposalSources = (proposal: DurableWorkspaceProposal, current: NovelProject): void => {
    if (proposal.operation !== undefined && proposal.review?.sourceDigest !== novelOperationSourceDigest(current)) {
      throw invalidInput('操作来源已变化，请重新预览');
    }
    for (const preview of proposal.review?.previews ?? []) {
      if (preview.chapterId === undefined || preview.sourceDigest === undefined) continue;
      const chapter: NovelChapter | undefined = current.chapters.find(item => item.id === preview.chapterId);
      if (chapter === undefined || preview.sourceDigest !== materialSuggestionChapterDigest(chapter)) throw invalidInput('正文来源已变化');
      if (preview.start !== undefined && chapter.content.slice(preview.start, preview.end) !== preview.oldText) throw invalidInput('预览原文范围已变化');
    }
  };

  const readCommits = async (id: string): Promise<WorkspaceLedgerCommit[]> => {
    const raw: string | null = await fileStore.readText(joinPath(projectRoot(id), LEDGER_FILE));
    if (raw === null) throw notFound('project', id);
    return parseLedger(raw);
  };

  const loadBoundGhostwriteJob = async (id: string, jobId: string, requireCas: boolean = true): Promise<LoadedGhostwriteJob> => {
    await loadProjectInternal(id);
    const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
    if (checkout === null) throw invalidInput('checkout 无效');
    const store: WorkspaceNovelJobStore = await readNovelJobStore(id);
    const index: number = ghostwriteJobIndex(store, jobId);
    const stored: DurableNovelJob = store.jobs[index];
    if (!isGhostwriteJob(stored)) throw invalidInput('ghostwrite job 类型无效');
    const job: DurableGhostwriteJob = stored;
    if (requireCas) assertGhostwriteWorkspaceBinding(id, job, checkout);
    else if (job.projectId !== id || job.branchId !== checkout.branchId) {
      throw invalidInput('ghostwrite job 与当前 project/branch 不匹配');
    }
    return { store, index, job, checkout };
  };

  const loadClaimedGhostwriteJob = async (
    id: string, jobId: string, claim: GhostwriteClaimRef, now: number,
  ): Promise<LoadedGhostwriteJob> => {
    const loaded: LoadedGhostwriteJob = await loadBoundGhostwriteJob(id, jobId);
    assertGhostwriteClaim(loaded.job, claim, now);
    return loaded;
  };

  const loadBoundPolishJob = async (id: string, jobId: string, requireCas: boolean = true): Promise<LoadedPolishJob> => {
    await loadProjectInternal(id);
    const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
    if (checkout === null) throw invalidInput('checkout 无效');
    const store: WorkspaceNovelJobStore = await readNovelJobStore(id);
    const index: number = polishJobIndex(store, jobId);
    const stored: DurableNovelJob = store.jobs[index];
    if (!isPolishJob(stored)) throw invalidInput('polish job 类型无效');
    if (requireCas) assertPolishWorkspaceBinding(id, stored, checkout);
    else if (stored.projectId !== id || stored.branchId !== checkout.branchId) {
      throw invalidInput('polish job 与当前 project/branch 不匹配');
    }
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
      return withAuthorPlot(validateProject(snapshot.project), snapshot.plotContent);
    } catch {
      throw invalidInput('receipt snapshot 无效');
    }
  };

  const assertChapterPlanEditable = async (id: string, branchId: string): Promise<void> => {
    const jobStore: WorkspaceNovelJobStore = await readNovelJobStore(id);
    const bound: DurableGhostwriteJob | undefined = jobStore.jobs.find(
      (job: DurableNovelJob): job is DurableGhostwriteJob => isGhostwriteJob(job) &&
        job.branchId === branchId && !novelJobIsTerminal(job));
    if (bound !== undefined) throw invalidInput('运行中的 ghostwrite job 已冻结本章计划');
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
    const candidate: NovelProject = transform(current);
    const transformed: NovelProject = withBranchMaterialEdits({ ...candidate,
      branchSettings: reconcileNovelBranchPlans(current.branchSettings, candidate.branchSettings),
    }, candidate.materials);
    if (transformed.id !== id) throw invalidInput('项目 id 不可变更');
    assertNovelCreationMetadataImmutable(current, transformed);
    if (transformed.branchSettings.thisChapterPlan !== current.branchSettings.thisChapterPlan) {
      await assertChapterPlanEditable(id, checkout.branchId);
    }
    const next: NovelProject = projectChapterPlots(current, { ...transformed, revision: current.revision + 1 }, branch);
    const changedIndex: number | null = chapterChangedAt(current.chapters, next.chapters);
    const { unresolved, stale } = projectedPlotStatus(next);
    return await installSnapshot(
      next, kind, [], checkout.branchId, commandId, unresolved, stale,
      kind !== 'transcript_checkpoint' && kind !== 'state_operation',
      changedIndex === null ? null : changedIndex + 1,
    );
  };

  const historyMutationContext = async (id: string, expected: NovelWorkspaceCas, commandId: string) => {
    assertCommandId(commandId);
    const commits: WorkspaceLedgerCommit[] = await readCommits(id);
    const replay: NovelProject | null = await replayProject(id, commits, commandId);
    if (replay !== null) return { commits, replay, current: replay, checkout: expected };
    const current: NovelProject = await loadProjectInternal(id);
    const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
    if (checkout === null) throw invalidInput('checkout 无效');
    assertCas(expected, checkout);
    assertNoActiveNovelJob(await readNovelJobStore(id), checkout.branchId);
    return { commits, replay, current, checkout };
  };

  const restoreHistoryLocked = async (
    id: string, head: string | null, forkName: string | null, expected: NovelWorkspaceCas, commandId: string,
  ): Promise<NovelProject> => {
    const context = await historyMutationContext(id, expected, commandId);
    if (context.replay !== null) return context.replay;
    const target: WorkspaceCommit | null = head === null
      ? previousWorkspaceCheckpoint(context.commits, context.checkout.head, context.checkout.branchId)
      : selectWorkspaceCheckpoint(context.commits, context.checkout.head, context.checkout.branchId, head);
    if (target === null) throw invalidInput('没有可撤销的提交');
    if (forkName === null && target.commitId === context.checkout.head) throw invalidInput('已经位于该检查点');
    const raw: string | null = await fileStore.readText(joinPath(projectRoot(id), commitSnapshotPath(target.commitId)));
    if (raw === null) throw invalidInput('检查点快照缺失');
    const snapshot: WorkspaceCommitSnapshot = JSON.parse(raw) as WorkspaceCommitSnapshot;
    const prior: NovelProject = validateProject(snapshot.project);
    if (prior.id !== id || snapshotTreeDigest(buildSnapshotFiles(prior, target.branchId, snapshot.plotContent).files) !== target.treeDigest) {
      throw invalidInput('检查点快照与台账不一致');
    }
    let branches: NovelBranch[] = context.current.branches;
    let branchId: string = context.checkout.branchId;
    if (forkName !== null) {
      const clean: string = forkName.trim();
      if (clean.length === 0) throw invalidInput('分支名不能为空');
      branchId = novelId();
      branches = branches.concat([{ id: branchId, name: clean, lifecycle: 'active', isMain: false,
        forkFromChapterId: prior.chapters.length === 0 ? null : prior.chapters[prior.chapters.length - 1].id,
        createdAt: Date.now() }]);
    }
    let selected: NovelProject = prior;
    if (context.current.baseMaterials !== undefined) {
      selected = forkName !== null
        ? withBranchMaterialEdits({ ...prior, baseMaterials: context.current.baseMaterials }, prior.materials)
        : prior.baseMaterials === undefined ? initializeSharedMaterials(prior) : prior;
    }
    const restored: NovelProject = { ...selected, name: context.current.name, modelPolicy: context.current.modelPolicy,
      creationMode: context.current.creationMode, quickStartSeed: context.current.quickStartSeed,
      polishPreference: context.current.polishPreference, branches,
      branchSettings: forkName === null ? selected.branchSettings : forkNovelBranchPlans(selected.branchSettings, branchId),
      revision: context.current.revision + 1, updatedAt: Date.now() };
    return await installSnapshot(restored, forkName === null ? 'undo' : 'history_fork', [], branchId, commandId,
      snapshot.unresolvedFromOrdinal, snapshot.plotStale, false, null, forkName !== null, true,
      // Fork paths produce a new digest. Synced plot status is relative to that new tree.
      forkName === null ? snapshot.plotSyncedTreeDigest : undefined, snapshot.plotContent, null, null,
      forkName === null ? target.commitId : undefined);
  };

  const branchMetadataLocked = async (
    id: string, branchId: string, operation: 'rename' | 'main' | 'delete', name: string,
    expected: NovelWorkspaceCas, commandId: string,
  ): Promise<NovelProject> => {
    const context = await historyMutationContext(id, expected, commandId);
    if (context.replay !== null) return context.replay;
    if (operation === 'delete') assertNoActiveNovelJob(await readNovelJobStore(id), branchId);
    const branches: NovelBranch[] = mutateNovelBranchMetadata(context.current.branches,
      context.checkout.branchId, branchId, operation, name);
    const state: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, context.checkout.branchId);
    return await installSnapshot({ ...context.current, branches, revision: context.current.revision + 1,
      updatedAt: Date.now() }, `branch_${operation}`, [], context.checkout.branchId, commandId,
      state.unresolvedFromOrdinal, state.plotStale, false, null, false, false,
      state.plotSyncedTreeDigest, state.plotContent);
  };

  const latestBranchCommit = (
    commits: WorkspaceLedgerCommit[], branchId: string,
  ): WorkspaceLedgerCommit | null => {
    for (let i: number = commits.length - 1; i >= 0; i--) {
      if (commits[i].branchId === branchId) return commits[i];
    }
    return null;
  };

  const assertBackupJobsIdle = (jobs: WorkspaceNovelJobStore): void => {
    const active: DurableNovelJob | undefined = jobs.jobs.find((job: DurableNovelJob): boolean =>
      !['paused', 'failed', 'waiting_system', 'completed', 'cancelled'].includes(job.stage));
    if (active !== undefined) throw invalidInput('创作任务运行中，请暂停后备份或恢复');
  };

  const validateFullTree = async (files: NovelWorkspaceArchiveFile[], requireIdle: boolean = false): Promise<NovelProject> => {
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

  const failedProjectName = async (id: string): Promise<string> => {
    try {
      const raw: string | null = await fileStore.readText(joinPath(projectRoot(id), 'manifest.yaml'));
      return raw === null ? id : parseNovelWorkspaceManifest(raw).title;
    } catch { return id; }
  };

  const recoveryCandidate = async (id: string): Promise<{
    preview: NovelProjectRecoveryPreview; files: NovelWorkspaceArchiveFile[];
  } | null> => {
    const liveFiles: NovelWorkspaceArchiveFile[] = await walkWorkspaceFiles(fileStore, projectRoot(id));
    const backupFiles: NovelWorkspaceArchiveFile[] = await walkWorkspaceFiles(fileStore, trashRoot(id));
    if (backupFiles.length > 0) {
      try {
        const project: NovelProject = await validateFullTree(backupFiles);
        if (project.id !== id) throw invalidInput('恢复项目 id 不一致');
        return { preview: { projectId: id, title: project.name, source: 'trash',
          sourceToken: stableDigest(liveFiles) + ':' + stableDigest(backupFiles) }, files: backupFiles };
      } catch { /* Invalid trash is preserved, then inspect the canonical head snapshot. */ }
    }
    try {
      const root: string = projectRoot(id);
      const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, root);
      if (checkout === null) return null;
      const raw: string | null = await fileStore.readText(joinPath(root, commitSnapshotPath(checkout.head)));
      if (raw === null) return null;
      const snapshot: WorkspaceCommitSnapshot = JSON.parse(raw) as WorkspaceCommitSnapshot;
      const built = buildSnapshotFiles(snapshot.project, checkout.branchId, snapshot.plotContent);
      const branch: WorkspaceBranchSnapshot = { branchId: checkout.branchId, state: built.state,
        unresolvedFromOrdinal: snapshot.unresolvedFromOrdinal, plotStale: snapshot.plotStale,
        plotSourceTreeDigest: snapshot.plotSourceTreeDigest,
        plotSyncedTreeDigest: snapshot.plotSyncedTreeDigest, plotContent: snapshot.plotContent };
      const replacements: SnapshotFile[] = built.files.concat([
        { path: branchSnapshotPath(checkout.branchId), content: JSON.stringify(branch) },
      ]);
      const replacementPaths: Set<string> = new Set(replacements.map((file: SnapshotFile): string => file.path));
      const files: NovelWorkspaceArchiveFile[] = liveFiles.filter(
        (file: NovelWorkspaceArchiveFile): boolean => !replacementPaths.has(file.path)).concat(
        replacements.map((file: SnapshotFile): NovelWorkspaceArchiveFile => ({ path: file.path, bytes: encodeUtf8(file.content) })));
      const project: NovelProject = await validateFullTree(files);
      if (project.id !== id) return null;
      return { preview: { projectId: id, title: project.name, source: 'head_snapshot',
        sourceToken: stableDigest(liveFiles) + ':' + stableDigest(backupFiles) }, files };
    } catch { return null; }
  };

  const nativeRestorePreview = async (input: NovelNativeBackupImport): Promise<NovelNativeRestorePreview> => {
    const project: NovelProject = await validateFullTree(input.files, true);
    const memory: FileStore = createMemoryFileStore();
    await writeWorkspaceStorageFiles(memory, 'candidate', input.files);
    const checkout: WorkspaceCheckout | null = await readCheckout(memory, 'candidate');
    if (project.id !== input.projectId || project.id !== input.manifest.projectId || project.name !== input.manifest.title ||
      project.schemaVersion !== input.manifest.schemaVersion || checkout === null ||
      checkout.branchId !== input.manifest.activeBranch || checkout.head !== input.manifest.state.head ||
      checkout.treeDigest !== input.manifest.state.treeDigest || checkout.branchId !== input.manifest.state.branchId) {
      throw invalidInput('原生备份 manifest 与工作区状态不一致');
    }
    const current: NovelWorkspaceArchiveFile[] = await walkWorkspaceFiles(fileStore, projectRoot(project.id));
    const trash: NovelWorkspaceArchiveFile[] = await walkWorkspaceFiles(fileStore, trashRoot(project.id));
    const deleted: NovelWorkspaceArchiveFile[] = await walkWorkspaceFiles(fileStore, deletedRoot(project.id));
    return { projectId: project.id, title: project.name, replaceExisting: current.length > 0 || trash.length > 0 || deleted.length > 0,
      sourceToken: stableDigest(input.files) + ':' + stableDigest(current) + ':' + stableDigest(trash) + ':' + stableDigest(deleted) };
  };

  const workspaceRestorePreview = async (plan: NovelWorkspaceImportPlan): Promise<NovelNativeRestorePreview> => {
    const id: string = plan.manifest.projectId;
    const current: NovelWorkspaceArchiveFile[] = await walkWorkspaceFiles(fileStore, projectRoot(id));
    const trash: NovelWorkspaceArchiveFile[] = await walkWorkspaceFiles(fileStore, trashRoot(id));
    const deleted: NovelWorkspaceArchiveFile[] = await walkWorkspaceFiles(fileStore, deletedRoot(id));
    return { projectId: id, title: plan.manifest.title,
      replaceExisting: current.length > 0 || trash.length > 0 || deleted.length > 0,
      sourceToken: stableDigest([{ path: 'plan.json', bytes: encodeUtf8(JSON.stringify(plan)) }]) + ':' +
        stableDigest(current) + ':' + stableDigest(trash) + ':' + stableDigest(deleted) };
  };

  const readWorkspaceSnapshotLocked = async (id: string): Promise<NovelWorkspaceSnapshot> => {
    const project: NovelProject = await loadProjectInternal(id);
    const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
    if (checkout === null) throw invalidInput('checkout 无效');
    const branchState: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, checkout.branchId);
    const active: NovelBranch | undefined = project.branches.find(
      (branch: NovelBranch): boolean => branch.id === checkout.branchId && branch.lifecycle === 'active');
    if (active === undefined) throw invalidInput('active branch 不存在');
    const proposalStore: WorkspaceProposalStore = await readProposalStore(id);
    const status: NovelWorkspaceStatus = {
      cas: checkout,
      activeBranchId: active.id,
      activeBranchName: active.name,
      branches: project.branches.filter(
        (branch: NovelBranch): boolean => branch.lifecycle === 'active'),
      canUndo: previousWorkspaceCheckpoint(await readCommits(id), checkout.head, active.id) !== null,
      unresolvedFromOrdinal: branchState.unresolvedFromOrdinal,
      plotStale: branchState.plotSourceTreeDigest !== branchState.plotSyncedTreeDigest,
      pendingProposalCount: project.settingProposals.filter(
        (proposal: NovelSettingProposal): boolean => proposal.status === 'pending').length +
        proposalStore.proposals.filter(
          (proposal: DurableWorkspaceProposal): boolean => proposal.status === 'pending' && proposal.expectedCas.branchId === active.id).length,
    };
    return { project, status };
  };


  const repository: NovelProjectRepository = {
    async listProjects(): Promise<NovelProject[]> {
      return (await repository.listProjectInventory()).projects;
    },
    async listProjectInventory(): Promise<NovelProjectInventory> {
      const failures: NovelProjectFailure[] = [];
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
        } catch (error) {
          failures.push({ id, name: await failedProjectName(id), error: error instanceof Error ? error.message : String(error) });
          seen.add(id);
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
        } catch (error) {
          failures.push({ id, name: await failedProjectName(id), error: error instanceof Error ? error.message : String(error) });
        }
      }
      projects.sort((left: NovelProject, right: NovelProject): number => {
        if (right.updatedAt !== left.updatedAt) return right.updatedAt - left.updatedAt;
        return left.name.toLowerCase().localeCompare(right.name.toLowerCase());
      });
      return { projects, failures };
    },

    inspectProjectRecovery: (id: string): Promise<NovelProjectRecoveryPreview | null> =>
      withProjectLock(id, async (): Promise<NovelProjectRecoveryPreview | null> => {
        const candidate = await recoveryCandidate(id);
        return candidate === null ? null : candidate.preview;
      }),

    readProjectRecovery: (preview: NovelProjectRecoveryPreview): Promise<NovelProject> =>
      withProjectLock(preview.projectId, async (): Promise<NovelProject> => {
        const candidate = await recoveryCandidate(preview.projectId);
        if (candidate === null || candidate.preview.sourceToken !== preview.sourceToken ||
          candidate.preview.source !== preview.source) throw invalidInput('恢复来源已变化，请重新预览');
        return await validateFullTree(candidate.files);
      }),

    restoreProjectRecovery: (preview: NovelProjectRecoveryPreview): Promise<NovelProject> =>
      withProjectLock(preview.projectId, async (): Promise<NovelProject> => {
        const candidate = await recoveryCandidate(preview.projectId);
        if (candidate === null || candidate.preview.sourceToken !== preview.sourceToken ||
          candidate.preview.source !== preview.source) throw invalidInput('恢复来源已变化，请重新预览');
        const stage: string = stagingRoot(preview.projectId);
        await writeWorkspaceStorageFiles(fileStore, stage, candidate.files);
        await loadAt(fileStore, stage);
        const retained: string = joinPath(ROOT, '.retained', preview.projectId, novelId());
        await swapWorkspaceStorageStage(fileStore, stage, projectRoot(preview.projectId), retained);
        if (preview.source === 'trash') await fileStore.rename(trashRoot(preview.projectId),
          joinPath(ROOT, '.retained', preview.projectId, novelId()));
        return await loadAt(fileStore, projectRoot(preview.projectId));
      }),

    nativeBackupSnapshot: (id: string): Promise<NovelNativeBackupSnapshot> =>
      withProjectLock(id, async (): Promise<NovelNativeBackupSnapshot> => {
        const project: NovelProject = await loadProjectInternal(id);
        assertBackupJobsIdle(await readNovelJobStore(id));
        const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
        if (checkout === null) throw invalidInput('checkout 无效');
        const files: NovelWorkspaceArchiveFile[] = await walkWorkspaceFiles(fileStore, projectRoot(id));
        await validateFullTree(files);
        return { metadata: { projectId: id, title: project.name, schemaVersion: project.schemaVersion,
          activeBranch: checkout.branchId, state: checkout, createdAt: Date.now() }, files };
      }),

    inspectNativeRestore: (input: NovelNativeBackupImport): Promise<NovelNativeRestorePreview> =>
      withProjectLock(input.projectId, async (): Promise<NovelNativeRestorePreview> => await nativeRestorePreview(input)),

    copyNativeBackup: async (input: NovelNativeBackupImport, projectId: string): Promise<NovelNativeBackupImport> => {
      await validateFullTree(input.files, true);
      const copied: NovelNativeBackupImport = copyNovelNativeBackup(input, projectId);
      await validateFullTree(copied.files, true);
      return copied;
    },

    inspectWorkspaceRestore: (plan: NovelWorkspaceImportPlan): Promise<NovelNativeRestorePreview> =>
      withProjectLock(plan.manifest.projectId, async (): Promise<NovelNativeRestorePreview> => await workspaceRestorePreview(plan)),

    installNativeBackup: (input: NovelNativeBackupImport, preview: NovelNativeRestorePreview): Promise<NovelProject> =>
      withProjectLock(input.projectId, async (): Promise<NovelProject> => {
        const current: NovelNativeRestorePreview = await nativeRestorePreview(input);
        if (current.projectId !== preview.projectId || current.sourceToken !== preview.sourceToken ||
          current.replaceExisting !== preview.replaceExisting) throw invalidInput('恢复目标或备份已变化，请重新预览');
        if (await fileStore.exists(projectRoot(input.projectId))) {
          const jobsBytes: Uint8Array | null = await fileStore.readBytes(joinPath(projectRoot(input.projectId), GHOSTWRITE_JOBS_FILE));
          if (jobsBytes !== null) {
            let jobs: WorkspaceNovelJobStore | null = null;
            try { jobs = parseNovelJobStore(decodeNovelWorkspaceUtf8(jobsBytes)); }
            catch { /* Explicit replacement retains this unreadable old record with the old tree. */ }
            if (jobs !== null) assertBackupJobsIdle(jobs);
          }
        }
        const stage: string = stagingRoot(input.projectId);
        await writeWorkspaceStorageFiles(fileStore, stage, input.files);
        await loadAt(fileStore, stage);
        await swapWorkspaceStorageStage(fileStore, stage, projectRoot(input.projectId),
          joinPath(ROOT, '.retained', input.projectId, novelId()));
        const roots: string[] = [trashRoot(input.projectId), deletedRoot(input.projectId)];
        for (let i: number = 0; i < roots.length; i++) {
          if (await fileStore.exists(roots[i])) await fileStore.rename(roots[i], joinPath(ROOT, '.retained', input.projectId, novelId()));
        }
        return await loadAt(fileStore, projectRoot(input.projectId));
      }),

    publicExportPlan: (id: string): Promise<NovelWorkspaceImportPlan> =>
      withProjectLock(id, async (): Promise<NovelWorkspaceImportPlan> => {
        const project: NovelProject = await loadProjectInternal(id);
        const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
        if (checkout === null) throw invalidInput('checkout 无效');
        const allFiles: NovelWorkspaceArchiveFile[] = await walkWorkspaceFiles(fileStore, projectRoot(id));
        const originals: NovelWorkspaceArchiveFile[] = allFiles.filter(
          (file: NovelWorkspaceArchiveFile): boolean => file.path.startsWith(PUBLIC_ORIGINALS_PREFIX)).map(
          (file: NovelWorkspaceArchiveFile): NovelWorkspaceArchiveFile => ({
            path: file.path.slice(PUBLIC_ORIGINALS_PREFIX.length), bytes: file.bytes,
          }));
        let originalPlan: NovelWorkspaceImportPlan | null = null;
        const originalManifest: NovelWorkspaceArchiveFile | undefined = originals.find(
          (file: NovelWorkspaceArchiveFile): boolean => file.path === 'manifest.yaml');
        if (originalManifest !== undefined) {
          const projectDocument: NovelWorkspaceArchiveFile | undefined = originals.find(
            (file: NovelWorkspaceArchiveFile): boolean => file.path === 'project.md');
          originalPlan = buildNovelWorkspaceImportPlan(parseNovelWorkspaceManifest(
            decodeNovelWorkspaceUtf8(originalManifest.bytes), projectDocument === undefined ? undefined :
              decodeNovelWorkspaceUtf8(projectDocument.bytes)), originals);
        }
        const branches: NovelWorkspaceBranchImport[] = [];
        const managedPaths: Set<string> = new Set(['manifest.yaml', 'project.md']);
        for (let i: number = 0; i < project.branches.length; i++) {
          const branch: NovelBranch = project.branches[i];
          if (branch.lifecycle !== 'active') continue;
          const snapshot: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, branch.id);
          const branchProject: NovelProject = await projectFromStoredState(fileStore, projectRoot(id), snapshot.state);
          buildSnapshotFiles(branchProject, branch.id, snapshot.plotContent).files.forEach(
            (file: SnapshotFile): void => { managedPaths.add(file.path); });
          const originalBranch: NovelWorkspaceBranchImport | undefined = originalPlan?.branches.find(
            (item: NovelWorkspaceBranchImport): boolean => item.id === branch.id);
          branches.push({ id: branch.id, pathName: originalBranch?.pathName ?? branch.id, name: branch.name,
            project: { ...branchProject, name: project.name, polishPreference: project.polishPreference, branches: project.branches }, plotContent: snapshot.plotContent,
            plotStale: snapshot.plotStale, unresolvedFromChapterOrdinal: snapshot.unresolvedFromOrdinal });
        }
        const originalPaths: Set<string> = new Set(originals.map((file: NovelWorkspaceArchiveFile): string => file.path));
        const unsupported: NovelWorkspaceArchiveFile[] = allFiles.filter((file: NovelWorkspaceArchiveFile): boolean =>
          !file.path.startsWith('.amber/') && !managedPaths.has(file.path) && !originalPaths.has(file.path));
        const files: NovelWorkspaceArchiveFile[] = originals.length > 0 ? originals.concat(unsupported) :
          allFiles.filter((file: NovelWorkspaceArchiveFile): boolean => !file.path.startsWith('.amber/'));
        const manifest: NovelWorkspaceManifest = parseNovelWorkspaceManifest(
          await fileStore.readText(joinPath(projectRoot(id), 'manifest.yaml')) ?? '');

        return { manifest, activeBranchId: checkout.branchId, branches, files,
          unsupportedPaths: unsupported.map((file: NovelWorkspaceArchiveFile): string => file.path) };
      }),

    installWorkspacePlan: (plan: NovelWorkspaceImportPlan, preview?: NovelNativeRestorePreview): Promise<NovelProject> =>
      withProjectLock(plan.manifest.projectId, async (): Promise<NovelProject> => {
        const id: string = plan.manifest.projectId;
        const current: NovelNativeRestorePreview = await workspaceRestorePreview(plan);
        if (preview === undefined && current.replaceExisting) throw invalidInput('导入项目 id 已存在');
        if (preview !== undefined && (preview.projectId !== id || preview.sourceToken !== current.sourceToken ||
          preview.replaceExisting !== current.replaceExisting)) throw invalidInput('导入目标或工作区已变化，请重新预览');
        const jobsRaw: string | null = await fileStore.readText(joinPath(projectRoot(id), GHOSTWRITE_JOBS_FILE));
        if (jobsRaw !== null) {
          let jobs: WorkspaceNovelJobStore | null = null;
          try { jobs = parseNovelJobStore(jobsRaw); } catch { /* Retain unreadable original evidence during explicit replacement. */ }
          if (jobs !== null) assertBackupJobsIdle(jobs);
        }
        const active: NovelWorkspaceBranchImport | undefined = plan.branches.find(
          (branch: NovelWorkspaceBranchImport): boolean => branch.id === plan.activeBranchId);
        if (active === undefined) throw invalidInput('导入活动分支不存在');
        const contents: Map<string, NovelWorkspaceArchiveFile> = new Map();
        const unsupportedPaths: Set<string> = new Set(plan.unsupportedPaths);
        plan.files.forEach((file: NovelWorkspaceArchiveFile): void => {
          if (unsupportedPaths.has(file.path)) contents.set(file.path, file);
          else {
            const path: string = PUBLIC_ORIGINALS_PREFIX + file.path;
            contents.set(path, { path, bytes: file.bytes });
          }
          if (file.path === 'project.md') contents.set(file.path, file);
        });
        const commits: WorkspaceLedgerCommit[] = [];
        const order: NovelWorkspaceBranchImport[] = plan.branches.filter(
          (branch: NovelWorkspaceBranchImport): boolean => branch.id !== active.id).concat([active]);
        let activeCheckout: WorkspaceCheckout | null = null;
        for (let i: number = 0; i < order.length; i++) {
          const branch: NovelWorkspaceBranchImport = order[i];
          if (branch.project.id !== id) throw invalidInput('导入分支项目 id 不一致');
          const built = buildSnapshotFiles(branch.project, branch.id, branch.plotContent);
          const digest: string = snapshotTreeDigest(built.files);
          const head: string = `import-${i}-${digest}`;
          const plotSynced: string | null = branch.plotStale ? null : digest;
          const snapshot: WorkspaceBranchSnapshot = { branchId: branch.id, state: built.state,
            unresolvedFromOrdinal: branch.unresolvedFromChapterOrdinal, plotStale: branch.plotStale,
            plotSourceTreeDigest: digest, plotSyncedTreeDigest: plotSynced, plotContent: branch.plotContent };
          const commitSnapshot: WorkspaceCommitSnapshot = { project: built.project,
            unresolvedFromOrdinal: branch.unresolvedFromChapterOrdinal, plotStale: branch.plotStale,
            plotSourceTreeDigest: digest, plotSyncedTreeDigest: plotSynced, plotContent: branch.plotContent };
          const files: SnapshotFile[] = built.files.filter((file: SnapshotFile): boolean => file.path !== 'project.md').concat([
            { path: branchSnapshotPath(branch.id), content: JSON.stringify(snapshot) },
            { path: commitSnapshotPath(head), content: JSON.stringify(commitSnapshot) },
          ]);
          files.forEach((file: SnapshotFile): void => { contents.set(file.path, { path: file.path, bytes: encodeUtf8(file.content) }); });
          commits.push({ version: 1, commitId: head, parent: commits.length === 0 ? null : commits[commits.length - 1].commitId, branchId: branch.id, treeDigest: digest,
            mutation: 'workspace_import', receipt: `workspace_import:${id}:${branch.id}`,
            changedPaths: [`branches/${branch.id}/plan/state.md`], changedChapterOrdinal: null, createdAt: branch.project.updatedAt });
          if (branch.id === active.id) activeCheckout = { branchId: branch.id, head, treeDigest: digest };
        }
        contents.set(LEDGER_FILE, { path: LEDGER_FILE, bytes: encodeUtf8(commits.map(
          (commit: WorkspaceLedgerCommit): string => JSON.stringify(commit)).join('\n') + '\n') });
        contents.set(CHECKOUT_FILE, { path: CHECKOUT_FILE, bytes: encodeUtf8(JSON.stringify(activeCheckout)) });
        const files: NovelWorkspaceArchiveFile[] = Array.from(contents.values());
        await validateFullTree(files);
        await writeWorkspaceStorageFiles(fileStore, stagingRoot(id), files);
        await swapWorkspaceStorageStage(fileStore, stagingRoot(id), projectRoot(id),
          joinPath(ROOT, '.retained', id, novelId()));
        for (const root of [trashRoot(id), deletedRoot(id)]) {
          if (await fileStore.exists(root)) await fileStore.rename(root, joinPath(ROOT, '.retained', id, novelId()));
        }
        return await loadAt(fileStore, projectRoot(id));
      }),

    async loadProject(id: string): Promise<NovelProject> {
      return await withProjectLock(id, async (): Promise<NovelProject> => await loadProjectInternal(id));
    },

    async createProject(project: NovelProject): Promise<NovelProject> {
      return await withProjectLock(project.id, async (): Promise<NovelProject> => {
        await recoverProject(project.id);
        if (await fileStore.exists(projectRoot(project.id))) throw invalidInput('项目已存在');
        return await installSnapshot(initializeSharedMaterials({ ...project, chapterPlots: rebuildChapterPlots(project.chapters) }), 'create');
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
      withProjectLock(id, async (): Promise<NovelWorkspaceStatus> => (await readWorkspaceSnapshotLocked(id)).status),

    readWorkspaceSnapshot: (id: string): Promise<NovelWorkspaceSnapshot> =>
      withProjectLock(id, async (): Promise<NovelWorkspaceSnapshot> => await readWorkspaceSnapshotLocked(id)),

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
        branchSettings: forkNovelBranchPlans(current.branchSettings, branch.id),
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
        creationMode: current.creationMode,
        quickStartSeed: current.quickStartSeed,
        polishPreference: current.polishPreference,
        baseMaterials: current.baseMaterials ?? targetStored.baseMaterials,
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
      return withAuthorPlot(target, targetSnapshot.plotContent);
    }),

    workspaceHistory: (id: string): Promise<WorkspaceCommit[]> => withProjectLock(id, async () => {
      const snapshot: NovelWorkspaceSnapshot = await readWorkspaceSnapshotLocked(id);
      return reachableWorkspaceHistory(await readCommits(id), snapshot.status.cas.head, snapshot.status.activeBranchId);
    }),

    saveSharedMaterial: (
      id: string, material: NovelMaterial, expected: NovelWorkspaceCas, commandId: string,
    ): Promise<NovelProject> => withProjectLock(id, async () => {
      const context = await historyMutationContext(id, expected, commandId);
      if (context.replay !== null) return context.replay;
      if (material.id.trim().length === 0 || material.title.trim().length === 0) throw invalidInput('资料名称或 id 无效');
      const fields = normalizeNovelMaterialFields(material, material.enabled);
      const saved: NovelMaterial = { ...material, ...fields, enabled: fields.injectionMode !== 'off' };
      const current: NovelProject = initializeSharedMaterials(context.current);
      const base: NovelMaterial[] = (current.baseMaterials ?? []).filter(item => item.id !== saved.id).concat([saved]);
      const next: NovelProject = withUpdatedSharedMaterials(current, current.baseMaterials ?? [], base);
      const state: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, context.checkout.branchId);
      return await installSnapshot({ ...next, revision: context.current.revision + 1, updatedAt: Date.now() },
        'material_base_change', [], context.checkout.branchId, commandId, state.unresolvedFromOrdinal,
        state.plotStale, true, null, false, false, state.plotStale ? state.plotSyncedTreeDigest : undefined, state.plotContent);
    }),

    deleteSharedMaterial: (
      id: string, materialId: string, expected: NovelWorkspaceCas, commandId: string,
    ): Promise<NovelProject> => withProjectLock(id, async () => {
      const context = await historyMutationContext(id, expected, commandId);
      if (context.replay !== null) return context.replay;
      const current: NovelProject = initializeSharedMaterials(context.current);
      if (!(current.baseMaterials ?? []).some(item => item.id === materialId)) throw notFound('material', materialId);
      const next: NovelProject = withUpdatedSharedMaterials(current, current.baseMaterials ?? [],
        (current.baseMaterials ?? []).filter(item => item.id !== materialId));
      const state: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, context.checkout.branchId);
      return await installSnapshot({ ...next, revision: context.current.revision + 1, updatedAt: Date.now() },
        'material_base_change', [], context.checkout.branchId, commandId, state.unresolvedFromOrdinal,
        state.plotStale, true, null, false, false, state.plotStale ? state.plotSyncedTreeDigest : undefined, state.plotContent);
    }),

    restoreMaterialInheritance: (
      id: string, materialId: string, expected: NovelWorkspaceCas, commandId: string,
    ): Promise<NovelProject> => withProjectLock(id, async () => {
      const context = await historyMutationContext(id, expected, commandId);
      if (context.replay !== null) return context.replay;
      const next: NovelProject = restoreNovelMaterialInheritance(context.current, materialId);
      const state: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, context.checkout.branchId);
      return await installSnapshot({ ...next, revision: context.current.revision + 1, updatedAt: Date.now() },
        'material_inheritance_restore', [], context.checkout.branchId, commandId, state.unresolvedFromOrdinal,
        state.plotStale, true, null, false, false, state.plotStale ? state.plotSyncedTreeDigest : undefined, state.plotContent);
    }),

    chapterVersionCheckpoint: (
      id: string, versionId: string, expected?: NovelWorkspaceCas,
    ): Promise<WorkspaceCommit | null> => withProjectLock(id, async (): Promise<WorkspaceCommit | null> => {
      const current: NovelWorkspaceSnapshot = await readWorkspaceSnapshotLocked(id);
      if (expected !== undefined) assertCas(expected, current.status.cas);
      const version = current.project.chapterVersions.find(item => item.id === versionId);
      if (version === undefined) return null;
      const history: WorkspaceCommit[] = reachableWorkspaceHistory(await readCommits(id),
        current.status.cas.head, current.status.activeBranchId);
      for (const commit of history) {
        const raw: string | null = await fileStore.readText(joinPath(projectRoot(id), commitSnapshotPath(commit.commitId)));
        if (raw === null) throw invalidInput('检查点快照缺失');
        const snapshot: WorkspaceCommitSnapshot = JSON.parse(raw) as WorkspaceCommitSnapshot;
        const project: NovelProject = validateProject(snapshot.project);
        if (project.id !== id || snapshotTreeDigest(buildSnapshotFiles(project, commit.branchId, snapshot.plotContent).files) !== commit.treeDigest) {
          throw invalidInput('检查点快照与台账不一致');
        }
        if (project.chapters.some(chapter => chapter.id === version.chapterId &&
          chapter.title === version.title && chapter.content === version.content)) return commit;
      }
      return null;
    }),

    undo: (id: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject> =>
      withProjectLock(id, async () => await restoreHistoryLocked(id, null, null, expected, commandId)),

    undoToCheckpoint: (id: string, head: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject> =>
      withProjectLock(id, async () => await restoreHistoryLocked(id, head, null, expected, commandId)),

    forkFromHistory: (id: string, head: string, name: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject> =>
      withProjectLock(id, async () => await restoreHistoryLocked(id, head, name, expected, commandId)),

    renameBranch: (id: string, branchId: string, name: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject> =>
      withProjectLock(id, async () => await branchMetadataLocked(id, branchId, 'rename', name, expected, commandId)),

    setMainBranch: (id: string, branchId: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject> =>
      withProjectLock(id, async () => await branchMetadataLocked(id, branchId, 'main', '', expected, commandId)),

    deleteBranch: (id: string, branchId: string, expected: NovelWorkspaceCas, commandId: string): Promise<NovelProject> =>
      withProjectLock(id, async () => await branchMetadataLocked(id, branchId, 'delete', '', expected, commandId)),

    completeStateOperation: (id: string, expected: NovelWorkspaceCas, operation: NovelStateOperation): Promise<NovelProject> =>
      withProjectLock(id, async (): Promise<NovelProject> => {
        const current = await loadProjectInternal(id);
        const checkout = await readCheckout(fileStore, projectRoot(id));
        if (checkout === null) throw invalidInput('checkout 无效');
        assertCas(expected, checkout);
        assertNovelStateSources(operation, current, checkout.branchId);
        if (current.stateOperation?.id !== operation.id || operation.status !== 'completed'
          || operation.cursor !== operation.targets.length || operation.targets.length === 0) {
          throw invalidInput('状态任务尚未完成，未提交重建结果');
        }
        const branch = await readActiveBranchSnapshot(id, checkout.branchId);
        const rebuilt: boolean = operation.kind === 'stateRebuild';
        const next: NovelProject = { ...current, stateOperation: operation, structuredState: operation.draft,
          chapterPlots: rebuilt ? rebuildChapterPlots(current.chapters) : current.chapterPlots,
          revision: current.revision + 1, updatedAt: Date.now() };
        return installSnapshot(next, 'state_operation', [], checkout.branchId, `${operation.id}:completed`,
          rebuilt ? null : branch.unresolvedFromOrdinal, rebuilt ? false : branch.plotStale,
          false, null, false, false, rebuilt ? undefined : branch.plotSyncedTreeDigest);
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
      const next: NovelProject = {
        ...current, chapterPlots: rebuildChapterPlots(current.chapters),
        revision: current.revision + 1, updatedAt: Date.now(),
      };
      return await installSnapshot(
        next, 'plot_sync', [], checkout.branchId, commandId, null, false, true,
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
      const next: NovelProject = {
        ...current, chapterPlots: rebuildChapterPlots(current.chapters),
        revision: current.revision + 1, updatedAt: Date.now(),
      };
      return await installSnapshot(
        next, 'unresolved_resolve', [], checkout.branchId, commandId, null, false, true,
      );
    }),

    workspaceProposals: (id: string): Promise<DurableWorkspaceProposal[]> =>
      withProjectLock(id, async (): Promise<DurableWorkspaceProposal[]> => {
        const snapshot: NovelWorkspaceSnapshot = await readWorkspaceSnapshotLocked(id);
        return (await readProposalStore(id)).proposals.filter(proposal => proposal.expectedCas.branchId === snapshot.status.activeBranchId);
      }),

    proposeProjectOperation: (
      id: string, kind: NovelProjectOperationKind, args: NovelProjectToolInput, expected: NovelWorkspaceCas,
      proposalId: string, createdAt: number,
    ): Promise<DurableWorkspaceProposal> => withProjectLock(id, async () => {
      const current: NovelProject = await loadProjectInternal(id);
      const checkout: WorkspaceCheckout | null = await readCheckout(fileStore, projectRoot(id));
      if (checkout === null) throw invalidInput('checkout 无效');
      assertCas(expected, checkout);
      const operation = makeNovelProjectOperation(kind, args);
      let historical: NovelProject | undefined;
      if (kind === 'revert_recent_chapters') {
        const count = args.chapter_count;
        const working: NovelChapter[] = current.chapters.filter(chapter => !chapter.discarded);
        if (!Number.isInteger(count) || count! < 1 || count! > Math.min(64, working.length)) throw invalidInput('回退章数超出正文范围');
        const kept: string[] = working.slice(0, working.length - count!).map(chapter => chapter.id);
        const history: WorkspaceCommit[] = reachableWorkspaceHistory(await readCommits(id), checkout.head, checkout.branchId);
        for (const commit of history) {
          const snapshot: WorkspaceCommitSnapshot = await readHistoricalSnapshot(id, commit);
          const ids = snapshot.project.chapters.filter(chapter => !chapter.discarded).map(chapter => chapter.id);
          if (JSON.stringify(ids) === JSON.stringify(kept)) { operation.restoreHead = commit.commitId; historical = snapshot.project; break; }
        }
        if (historical === undefined) throw invalidInput('没有对应的真实历史检查点，未回退或删除正文');
      }
      const next: NovelProject = applyNovelSpecializedOperation(current, operation, checkout.branchId, createdAt, historical);
      const proposal: DurableWorkspaceProposal = validateDurableWorkspaceProposal({ proposalId, status: 'pending',
        expectedCas: expected, patches: [], operation, review: makeNovelSpecializedReview(current, next, operation), createdAt, resolvedAt: null });
      return await stageWorkspaceProposalLocked(id, proposal);
    }),

    createProposal: (
      id: string, expected: NovelWorkspaceCas, proposalId: string,
      patches: WorkspaceProposalPatch[], createdAt: number, review?: WorkspaceProposalReview,
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
          ...(review === undefined ? {} : { review }),
          createdAt,
          resolvedAt: null,
        });
        const branch: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, checkout.branchId);
        validateProposalTargets(proposal, branch);
        return await stageWorkspaceProposalLocked(current.id, proposal);
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
        const proposalCommits: WorkspaceCommit[] = await readCommits(id);
        if (accept) { assertProposalCheckout(proposal, checkout, proposalCommits); assertProposalSources(proposal, current); }
        else if (proposal.expectedCas.branchId !== checkout.branchId) throw invalidInput('不能操作其他分支的提案');
        const branch: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, checkout.branchId);
        if (accept) validateProposalTargets(proposal, branch);
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
        if (proposal.operation !== undefined) {
          assertNoActiveNovelJob(await readNovelJobStore(id), checkout.branchId);
          let restored: WorkspaceCommitSnapshot | undefined;
          if (proposal.operation.restoreHead !== undefined) {
            const target = selectWorkspaceCheckpoint(commits, checkout.head, checkout.branchId, proposal.operation.restoreHead);
            restored = await readHistoricalSnapshot(id, target);
          }
          let applied: NovelProject = applyNovelSpecializedOperation(current, proposal.operation, checkout.branchId, resolvedAt, restored?.project);
          applied = { ...applied, revision: current.revision + 1 };
          const changed = chapterChangedAt(current.chapters, applied.chapters);
          const next: NovelProject = restored === undefined ? projectChapterPlots(current, applied, branch) : applied;
          const status = restored === undefined ? projectedPlotStatus(next)
            : { unresolved: restored.unresolvedFromOrdinal, stale: restored.plotStale };
          if (next.branchSettings.thisChapterPlan !== current.branchSettings.thisChapterPlan) await assertChapterPlanEditable(id, checkout.branchId);
          await installSnapshot(next, restored === undefined ? 'proposal_resolve' : 'undo', [], checkout.branchId, commandId,
            status.unresolved, status.stale, true, changed === null ? null : changed + 1, false, false,
            restored?.plotSyncedTreeDigest, restored === undefined ? branch.plotContent : restored.plotContent, nextStore, null,
            proposal.operation.restoreHead);
          return resolved;
        }
        let archived: NovelProject = current;
        const archivedIds: Set<string> = new Set();
        let chapters: NovelChapter[] = current.chapters.slice();
        let plotContent: string | null = branch.plotContent;
        let thisChapterPlan: string = current.branchSettings.thisChapterPlan;
        let changedIndex: number | null = null;
        for (let i: number = 0; i < proposal.patches.length; i++) {
          const patch: WorkspaceProposalPatch = proposal.patches[i];
          const plotPath: string = `branches/${checkout.branchId}/plan/plot.md`;
          if (patch.path === plotPath) {
            plotContent = patch.operation === 'write' ? patch.content : null;
            continue;
          }
          if (patch.path === branchSettingPaths(checkout.branchId).thisChapterPlan) {
            thisChapterPlan = patch.operation === 'write' ? patch.content ?? '' : '';
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
            if (chapters[projectIndex].content !== patch.content && !archivedIds.has(chapterId)) {
              archived = saveChapterVersion(archived, chapterId, 'manual', resolvedAt).project;
              archivedIds.add(chapterId);
            }
            chapters[projectIndex] = {
              ...chapters[projectIndex],
              content: patch.content ?? '',
              updatedAt: resolvedAt,
            };
          }
        }
        if (thisChapterPlan !== current.branchSettings.thisChapterPlan) {
          await assertChapterPlanEditable(id, checkout.branchId);
        }
        const next: NovelProject = projectChapterPlots(current, {
          ...current,
          chapterVersions: archived.chapterVersions,
          chapters,
          branchSettings: reconcileNovelBranchPlans(current.branchSettings, { ...current.branchSettings, thisChapterPlan }),
          revision: current.revision + 1,
          updatedAt: Math.max(current.updatedAt, resolvedAt),
        }, branch);
        const { unresolved, stale } = projectedPlotStatus(next);
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
      const startChapterOrdinal: number = nextNovelChapterOrdinal(project.chapters);
      const frozenPlan = freezeGhostwritePlan({
        planId,
        content: confirmedChapterPlanText(project.branchSettings),
        upcomingArc: project.branchSettings.upcomingArc?.beats,
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
        startChapterOrdinal,
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
      const loaded: LoadedGhostwriteJob = await loadBoundGhostwriteJob(id, jobId, false);
      assertGhostwriteClaim(loaded.job, claim, now);
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

    reviseGhostwriteWithBrief: (
      id: string, jobId: string, brief: string, expected: NovelWorkspaceCas, now: number,
      expectedCandidateDigest?: string,
    ): Promise<DurableGhostwriteJob> => withProjectLock(id, async (): Promise<DurableGhostwriteJob> => {
      const loaded: LoadedGhostwriteJob = await loadBoundGhostwriteJob(id, jobId);
      assertCas(expected, loaded.checkout);
      if (expectedCandidateDigest !== undefined &&
        expectedCandidateDigest !== (loaded.job.candidate?.digest ?? '')) {
        throw invalidInput('代写候选已变化，请重新查看后提交修订要求');
      }
      if (loaded.job.stage !== 'paused' && loaded.job.stage !== 'failed') {
        throw invalidInput('仅暂停或失败的代写任务可提交作者修订要求');
      }
      const clean: string = brief.trim();
      if (clean.length === 0) throw invalidInput('作者修订要求不能为空');
      if (clean.length > 2400) throw invalidInput('作者修订要求不能超过 2400 字');
      const next: DurableGhostwriteJob = validateDurableGhostwriteJob({
        ...loaded.job,
        authorBrief: clean,
        resumeStage: 'writing',
        rewriteCount: loaded.job.candidate?.attempt ?? 0,
        claim: null,
        failure: null,
        updatedAt: now,
      });
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
      id: string, jobId: string, claim: GhostwriteClaimRef | null, now: number,
    ): Promise<DurableGhostwriteJob> => withProjectLock(id, async (): Promise<DurableGhostwriteJob> => {
      const loaded: LoadedGhostwriteJob = await loadBoundGhostwriteJob(id, jobId, false);
      if (novelJobIsTerminal(loaded.job)) throw invalidInput('当前 ghostwrite job 已是终态');
      if (claim !== null) assertGhostwriteClaim(loaded.job, claim, now);
      else if (loaded.job.claim !== null && now < loaded.job.claim.leaseUntil) {
        throw invalidInput('ghostwrite owner lease 仍有效，取消须提供当前 claim');
      }
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
      if (confirmedChapterPlanText(project.branchSettings).trim() !== storedJob.frozenPlan.content) {
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
      const branch: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, checkout.branchId);
      const nextProject: NovelProject = projectChapterPlots(project, withNovelProjectCompatibility({
        ...project,
        chapters: project.chapters.concat([chapter]),
        branchSettings: {
          ...project.branchSettings,
          thisChapterPlan: nextPlanContent,
          chapterContract: undefined,
          suggestedChapterCount: undefined,
        },
        revision: project.revision + 1,
        updatedAt: Math.max(project.updatedAt, now),
      }), branch);
      const { unresolved, stale } = projectedPlotStatus(nextProject);
      const plotContent: string | null = branch.plotContent;
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
        upcomingArc: nextProject.branchSettings.upcomingArc?.beats,
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
      await installSnapshot(
        nextProject, 'generation_commit', [], checkout.branchId, receipt,
        unresolved, stale, true, candidate.chapterOrdinal,
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
      selectedOrdinals?: number[],
    ): Promise<DurablePolishJob> => withProjectLock(id, async (): Promise<DurablePolishJob> => {
      if (!Number.isInteger(fromOrdinal) || !Number.isInteger(toOrdinal) ||
        fromOrdinal < 1 || toOrdinal < fromOrdinal) throw invalidInput('润色范围无效');
      const ordinals: number[] = selectedOrdinals === undefined ? [] : selectedOrdinals.slice();
      if (selectedOrdinals === undefined) {
        for (let ordinal: number = fromOrdinal; ordinal <= toOrdinal; ordinal++) ordinals.push(ordinal);
      } else {
        if (ordinals.length === 0 || ordinals.some((ordinal: number): boolean =>
          !Number.isInteger(ordinal) || ordinal < 1) || new Set<number>(ordinals).size !== ordinals.length) {
          throw invalidInput('润色选章必须是非空且不重复的正整数');
        }
        ordinals.sort((left: number, right: number): number => left - right);
      }
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
      for (const ordinal of ordinals) {
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
      if (branch.plotStale || branch.unresolvedFromOrdinal !== null) {
        throw invalidInput('润色前必须同步剧情并确认后续章节影响');
      }
      const contextSnapshot: PolishContextSnapshotItem[] = frozenPolishContext(project, checkout.branchId, contextOptions, targets, branch.plotContent);
      const warnings: PolishWarning[] = [];
      const job: DurablePolishJob = makePolishJob({
        jobId, projectId: id, branchId: checkout.branchId, targets, contextOptions,
        contextSnapshot, warnings, expectedCas: checkout,
        modelPolicyAtStart: project.modelPolicy, polishPreferenceAtStart: (project.polishPreference ?? '').trim(), now,
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
      const assessed: DurablePolishJob = applyPolishReview(loaded.job, review, now);
      const next: DurablePolishJob = assessed.stage === 'failed'
        ? recordPolishChapterResult(loaded.job, claim, review.blocking ? 'driftSkipped' : 'failed', assessed.failure!, now) : assessed;
      await saveNovelJobStoreOnly(id, replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    failPolishJob: (
      id: string, jobId: string, claim: GhostwriteClaimRef, reason: string, now: number,
    ): Promise<DurablePolishJob> => withProjectLock(id, async (): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadBoundPolishJob(id, jobId, false);
      assertPolishClaim(loaded.job, claim, now);
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

    recordPolishChapterResult: (
      id: string, jobId: string, claim: GhostwriteClaimRef, status: 'failed' | 'driftSkipped', reason: string, now: number,
    ): Promise<DurablePolishJob> => withProjectLock(id, async () => {
      const loaded = await loadClaimedPolishJob(id, jobId, claim, now);
      const next = recordPolishChapterResult(loaded.job, claim, status, reason, now);
      await saveNovelJobStoreOnly(id, replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    retryPolishJob: (
      id: string, jobId: string, now: number, chapterIds?: string[],
    ): Promise<DurablePolishJob> => withProjectLock(id, async (): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadBoundPolishJob(id, jobId);
      assertNoActiveNovelJob({ ...loaded.store, jobs: loaded.store.jobs.filter(job => job.jobId !== jobId) }, loaded.job.branchId);
      const next: DurablePolishJob = retryPolishJobDomain(loaded.job, now, chapterIds);
      await saveNovelJobStoreOnly(id, replacePolishJob(loaded.store, loaded.index, next));
      return next;
    }),

    cancelPolishJob: (
      id: string, jobId: string, now: number,
    ): Promise<DurablePolishJob> => withProjectLock(id, async (): Promise<DurablePolishJob> => {
      const loaded: LoadedPolishJob = await loadBoundPolishJob(id, jobId, false);
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
      const branch: WorkspaceBranchSnapshot = await readActiveBranchSnapshot(id, checkout.branchId);
      const nextProject: NovelProject = projectChapterPlots(project, withNovelProjectCompatibility({
        ...project,
        chapters,
        chapterVersions: project.chapterVersions.concat([backup]),
        revision: project.revision + 1,
        updatedAt: Math.max(project.updatedAt, now),
      }), branch);
      const { unresolved, stale } = projectedPlotStatus(nextProject);
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
        unresolved, stale, true, target.ordinal,
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
        if (await fileStore.exists(deletedRoot(id))) await fileStore.rename(deletedRoot(id), joinPath(ROOT, '.retained', id, novelId()));
        await fileStore.writeText(joinPath(live, '.amber/deleted-at.json'), JSON.stringify({ deletedAt: Date.now() }));
        await fileStore.rename(live, deletedRoot(id));
      }),

    async restorePrevious(): Promise<NovelProject | null> {
      const names: string[] = await fileStore.list(DELETED_ROOT);
      let best: NovelProject | null = null;
      let bestId: string = '';
      let bestLegacyFile: string = '';
      let bestDeletedAt: number = -1;
      for (let i: number = 0; i < names.length; i++) {
        try {
          const project: NovelProject = await loadAt(fileStore, deletedRoot(names[i]));
          const raw: string | null = await fileStore.readText(joinPath(deletedRoot(names[i]), '.amber/deleted-at.json'));
          const deletion: { deletedAt: number } | null = raw === null ? null : JSON.parse(raw) as { deletedAt: number };
          const deletedAt: number = deletion === null || !Number.isSafeInteger(deletion.deletedAt) ? 0 : deletion.deletedAt;
          if (best === null || deletedAt > bestDeletedAt) {
            best = project;
            bestId = names[i];
            bestLegacyFile = '';
            bestDeletedAt = deletedAt;
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
          if (best === null) {
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
        await fileStore.delete(joinPath(projectRoot(best.id), '.amber/deleted-at.json'));
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
      const requestedId: string = input.manifest.projectId;
      const requestedAvailable: boolean = await withProjectLock(requestedId, async (): Promise<boolean> => {
        await recoverProject(requestedId);
        return !await fileStore.exists(projectRoot(requestedId)) && !await fileStore.exists(deletedRoot(requestedId));
      });
      const id: string = requestedAvailable ? requestedId : novelId();
      const plan: NovelWorkspaceImportPlan = buildNovelWorkspaceImportPlan(input.manifest, input.files);
      if (id !== requestedId) {
        plan.manifest = { ...plan.manifest, projectId: id };
        plan.branches = plan.branches.map((branch: NovelWorkspaceBranchImport): NovelWorkspaceBranchImport => ({
          ...branch, project: { ...branch.project, id },
        }));
      }
      return await repository.installWorkspacePlan(plan);
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
