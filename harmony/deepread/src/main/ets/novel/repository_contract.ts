import type {
  DurableGhostwriteJob, GhostwriteCandidate, GhostwriteClaimRef, GhostwriteProgress, GhostwriteReview,
  GhostwriteStage,
} from './ghostwrite.ts';
import type { NovelBranch, NovelMaterial, NovelProject } from './models.ts';
import type { NovelNativeBackupImport } from './native_backup.ts';
import type {
  DurablePolishJob, PolishCandidate, PolishContextOptions, PolishProgress, PolishReview, PolishStage,
} from './polish.ts';
import type { NovelProjectOperationKind, NovelProjectToolInput } from './specialized_operations.ts';
import type { NovelStateOperation } from './state_rebuild.ts';
import type { NovelBookExportInput } from './workspace_book_export.ts';
import type { NovelWorkspaceArchiveFile, NovelWorkspaceValidatedImport } from './workspace_exchange.ts';
import type {
  DurableWorkspaceProposal, WorkspaceCommit, WorkspaceProposalPatch, WorkspaceProposalReview,
} from './workspace_history.ts';
import type { NovelWorkspaceImportPlan } from './workspace_interop.ts';
import type {
  NovelNativeBackupSnapshot, NovelNativeRestorePreview, NovelProjectInventory, NovelProjectRecoveryPreview,
} from './workspace_storage.ts';

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
  tryWithPolishDriverLock<T>(id: string, jobId: string, op: () => Promise<T>): Promise<T | null>;
  withPolishDriverLock<T>(id: string, jobId: string, op: () => Promise<T>): Promise<T>;
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

