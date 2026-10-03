// C4 工作区历史的纯域契约。持久化、原子安装和文件树变更由宿主 adapter 负责。

import { validateNovelSpecializedOperation } from './specialized_operations.ts';
import type { NovelSpecializedOperation } from './specialized_operations.ts';
import { invalidInput } from './error.ts';
import { validateNovelWorkspacePath } from './workspace_contract.ts';

export const WORKSPACE_HISTORY_VERSION: number = 1;

export interface WorkspaceCas {
  branchId: string;
  head: string;
  treeDigest: string;
}

export interface WorkspaceCommit {
  version: number;
  commitId: string;
  parent: string | null;
  branchId: string;
  treeDigest: string;
  mutation: string;
  receipt: string;
  changedPaths: string[];
  changedChapterOrdinal: number | null;
  createdAt: number;
  restoredFromHead?: string;
}

export interface WorkspaceBranchMetadata {
  branchId: string;
  name: string;
  baseHead: string;
  head: string;
  treeDigest: string;
  createdAt: number;
  updatedAt: number;
}

export type WorkspaceProposalStatus = 'pending' | 'accepted' | 'rejected';
export type WorkspaceProposalPatchOperation = 'write' | 'delete';

export interface WorkspaceProposalPatch {
  operation: WorkspaceProposalPatchOperation;
  path: string;
  content: string | null;
}

export interface WorkspaceProposalPreview {
  label: string;
  oldText: string;
  newText: string;
  chapterId?: string;
  startParagraph?: number;
  endParagraph?: number;
  start?: number;
  end?: number;
  sourceDigest?: string;
}
export interface WorkspaceProposalReview {
  summary: string;
  sourceDigest: string;
  previews: WorkspaceProposalPreview[];
}
export interface DurableWorkspaceProposal {
  proposalId: string;
  status: WorkspaceProposalStatus;
  expectedCas: WorkspaceCas;
  patches: WorkspaceProposalPatch[];
  operation?: NovelSpecializedOperation;
  review?: WorkspaceProposalReview;
  createdAt: number;
  resolvedAt: number | null;
}

export interface WorkspaceUndoSnapshot {
  branchId: string;
  head: string;
  treeDigest: string;
  createdAt: number;
}

export interface WorkspacePlotState {
  sourceTreeDigest: string;
  syncedTreeDigest: string | null;
  stale: boolean;
}

export interface WorkspaceProposalResolution {
  proposal: DurableWorkspaceProposal;
  changed: boolean;
}

export type WorkspaceReceiptReplay = 'new' | 'replay';

const isString = (value: unknown): boolean => {
  const normalized: unknown = String(value);
  return normalized === value;
};

const requireString = (value: unknown, field: string): string => {
  if (!isString(value) || (value as string).trim().length === 0) {
    throw invalidInput(`${field} 必须是非空字符串`);
  }
  return value as string;
};

const requireNonNegativeInteger = (value: unknown, field: string): number => {
  const integer: number = value as number;
  if (!Number.isSafeInteger(integer) || integer < 0) throw invalidInput(`${field} 必须是非负整数`);
  return integer;
};

const requireChapterOrdinal = (value: unknown, field: string): number => {
  const ordinal: number = requireNonNegativeInteger(value, field);
  if (ordinal < 1 || ordinal > 999) throw invalidInput(`${field} 必须在 1-999 之间`);
  return ordinal;
};

const requireBranchId = (value: unknown, field: string): string => {
  const branchId: string = requireString(value, field);
  if (branchId === '.' || branchId === '..' || branchId.startsWith('.') ||
    branchId.indexOf('/') >= 0 || branchId.indexOf('\\') >= 0 || branchId.indexOf(':') >= 0) {
    throw invalidInput(`${field} 包含非法分支标识`);
  }
  return branchId;
};

const isRecord = (value: unknown): boolean => value instanceof Object && !Array.isArray(value);

const parseRecord = (raw: string, label: string): Record<string, unknown> => {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw) as unknown;
  } catch {
    throw invalidInput(`${label} JSON 无效`);
  }
  if (!isRecord(parsed)) throw invalidInput(`${label} 必须是对象`);
  return parsed as Record<string, unknown>;
};

const validateReceipt = (receipt: string): string => {
  if (receipt.length > 256 || receipt !== receipt.trim() || receipt.indexOf('\n') >= 0) {
    throw invalidInput('receipt 无效');
  }
  return receipt;
};

const validateDigest = (digest: string, field: string): string => {
  if (digest.length > 512 || digest !== digest.trim()) throw invalidInput(`${field} 无效`);
  return digest;
};

export const validateWorkspaceCas = (cas: WorkspaceCas): WorkspaceCas => {
  return {
    branchId: requireBranchId(cas.branchId, 'branchId'),
    head: validateDigest(requireString(cas.head, 'head'), 'head'),
    treeDigest: validateDigest(requireString(cas.treeDigest, 'treeDigest'), 'treeDigest'),
  };
};

export const parseWorkspaceCas = (raw: string): WorkspaceCas => {
  const value: Record<string, unknown> = parseRecord(raw, 'CAS');
  return validateWorkspaceCas({
    branchId: value.branchId as string,
    head: value.head as string,
    treeDigest: value.treeDigest as string,
  });
};

export const assertWorkspaceCasMatch = (expected: WorkspaceCas, actual: WorkspaceCas): void => {
  const checkedExpected: WorkspaceCas = validateWorkspaceCas(expected);
  const checkedActual: WorkspaceCas = validateWorkspaceCas(actual);
  if (checkedExpected.branchId !== checkedActual.branchId || checkedExpected.head !== checkedActual.head ||
    checkedExpected.treeDigest !== checkedActual.treeDigest) {
    throw invalidInput('工作区版本已漂移，拒绝覆盖');
  }
};

const validateChangedPath = (path: string): string => {
  const checked: string = validateNovelWorkspacePath(path);
  if (checked === 'manifest.yaml' || checked === 'project.md' || checked.startsWith('.amber/')) {
    throw invalidInput(`commit changedPaths 不允许宿主路径: ${checked}`);
  }
  return checked;
};

const validateChangedPaths = (paths: string[]): string[] => {
  if (paths.length === 0) throw invalidInput('commit changedPaths 不能为空');
  const seen: Set<string> = new Set<string>();
  const checked: string[] = [];
  for (let i: number = 0; i < paths.length; i++) {
    const path: string = validateChangedPath(requireString(paths[i], 'changedPaths'));
    if (seen.has(path)) throw invalidInput(`commit changedPaths 重复: ${path}`);
    seen.add(path);
    checked.push(path);
  }
  return checked;
};

export const validateWorkspaceCommit = (commit: WorkspaceCommit): WorkspaceCommit => {
  const version: number = requireNonNegativeInteger(commit.version, 'commit version');
  if (version !== WORKSPACE_HISTORY_VERSION) throw invalidInput('commit 版本不受支持');
  const parent: string | null = commit.parent === null
    ? null : validateDigest(requireString(commit.parent, 'parent'), 'parent');
  const changedChapterOrdinal: number | null = commit.changedChapterOrdinal === null
    ? null : requireChapterOrdinal(commit.changedChapterOrdinal, 'changedChapterOrdinal');
  return {
    version,
    commitId: validateDigest(requireString(commit.commitId, 'commitId'), 'commitId'),
    parent,
    branchId: requireBranchId(commit.branchId, 'branchId'),
    treeDigest: validateDigest(requireString(commit.treeDigest, 'treeDigest'), 'treeDigest'),
    mutation: requireString(commit.mutation, 'mutation'),
    receipt: validateReceipt(requireString(commit.receipt, 'receipt')),
    changedPaths: validateChangedPaths(commit.changedPaths),
    changedChapterOrdinal,
    createdAt: requireNonNegativeInteger(commit.createdAt, 'createdAt'),
    ...(commit.restoredFromHead === undefined ? {} : {
      restoredFromHead: validateDigest(requireString(commit.restoredFromHead, 'restoredFromHead'), 'restoredFromHead'),
    }),
  };
};

export const parseWorkspaceCommit = (raw: string): WorkspaceCommit => {
  const value: Record<string, unknown> = parseRecord(raw, 'commit');
  if (!Array.isArray(value.changedPaths)) throw invalidInput('commit changedPaths 必须是数组');
  return validateWorkspaceCommit({
    version: value.version as number,
    commitId: value.commitId as string,
    parent: value.parent === null ? null : value.parent as string,
    branchId: value.branchId as string,
    treeDigest: value.treeDigest as string,
    mutation: value.mutation as string,
    receipt: value.receipt as string,
    changedPaths: value.changedPaths as string[],
    changedChapterOrdinal: value.changedChapterOrdinal === null ? null : value.changedChapterOrdinal as number,
    createdAt: value.createdAt as number,
    restoredFromHead: value.restoredFromHead as string | undefined,
  });
};

export const classifyReceiptReplay = (commits: WorkspaceCommit[], receipt: string): WorkspaceReceiptReplay => {
  const checkedReceipt: string = validateReceipt(requireString(receipt, 'receipt'));
  validateWorkspaceReceiptLedger(commits);
  for (let i: number = 0; i < commits.length; i++) {
    const commit: WorkspaceCommit = validateWorkspaceCommit(commits[i]);
    if (commit.receipt === checkedReceipt) return 'replay';
  }
  return 'new';
};

export const validateWorkspaceReceiptLedger = (commits: WorkspaceCommit[]): WorkspaceCommit[] => {
  const receipts: Set<string> = new Set<string>();
  const checked: WorkspaceCommit[] = [];
  for (let i: number = 0; i < commits.length; i++) {
    const commit: WorkspaceCommit = validateWorkspaceCommit(commits[i]);
    if (receipts.has(commit.receipt)) throw invalidInput(`receipt 重复: ${commit.receipt}`);
    receipts.add(commit.receipt);
    checked.push(commit);
  }
  return checked;
};

export const validateWorkspaceBranchMetadata = (branch: WorkspaceBranchMetadata): WorkspaceBranchMetadata => {
  const createdAt: number = requireNonNegativeInteger(branch.createdAt, 'createdAt');
  const updatedAt: number = requireNonNegativeInteger(branch.updatedAt, 'updatedAt');
  if (updatedAt < createdAt) throw invalidInput('branch updatedAt 早于 createdAt');
  return {
    branchId: requireBranchId(branch.branchId, 'branchId'),
    name: requireString(branch.name, 'branch name'),
    baseHead: validateDigest(requireString(branch.baseHead, 'baseHead'), 'baseHead'),
    head: validateDigest(requireString(branch.head, 'head'), 'head'),
    treeDigest: validateDigest(requireString(branch.treeDigest, 'treeDigest'), 'treeDigest'),
    createdAt,
    updatedAt,
  };
};

export const parseWorkspaceBranchMetadata = (raw: string): WorkspaceBranchMetadata => {
  const value: Record<string, unknown> = parseRecord(raw, 'branch metadata');
  return validateWorkspaceBranchMetadata({
    branchId: value.branchId as string,
    name: value.name as string,
    baseHead: value.baseHead as string,
    head: value.head as string,
    treeDigest: value.treeDigest as string,
    createdAt: value.createdAt as number,
    updatedAt: value.updatedAt as number,
  });
};

const validateProposalPatchPath = (path: string, branchId: string): string => {
  const checked: string = validateNovelWorkspacePath(path);
  const prefix: string = `branches/${branchId}/`;
  if (!checked.startsWith(prefix)) throw invalidInput('proposal patch 必须属于预期分支');
  const relative: string = checked.slice(prefix.length);
  const chapter: RegExpMatchArray | null = relative.match(/^chapters\/\d{3}-.+\.md$/);
  if (chapter !== null || relative === 'plan/plot.md' || relative === 'plan/this-chapter.md') return checked;
  throw invalidInput(`proposal patch 不是受控正文、剧情或本章计划路径: ${checked}`);
};

export const validateWorkspaceProposalPatch = (patch: WorkspaceProposalPatch,
  branchId: string): WorkspaceProposalPatch => {
  const operation: string = requireString(patch.operation, 'proposal patch operation');
  if (operation !== 'write' && operation !== 'delete') throw invalidInput('proposal patch operation 无效');
  const path: string = validateProposalPatchPath(requireString(patch.path, 'proposal patch path'), branchId);
  if (operation === 'write') {
    if (!isString(patch.content)) throw invalidInput('write proposal patch 必须带文本内容');
    return { operation: 'write', path, content: patch.content as string };
  }
  if (patch.content !== null) throw invalidInput('delete proposal patch 不能带文本内容');
  return { operation: 'delete', path, content: null };
};

const validateWorkspaceProposalReview = (review: WorkspaceProposalReview): WorkspaceProposalReview => {
  if (!isString(review.summary) || !isString(review.sourceDigest) || !Array.isArray(review.previews)) throw invalidInput('作者预览无效');
  for (const preview of review.previews) {
    if (!isString(preview.label) || !isString(preview.oldText) || !isString(preview.newText)) throw invalidInput('作者预览缺少原文与新稿');
    if (preview.start !== undefined) requireNonNegativeInteger(preview.start, 'preview start');
    if (preview.end !== undefined) requireNonNegativeInteger(preview.end, 'preview end');
    if ((preview.start === undefined) !== (preview.end === undefined) ||
      (preview.start !== undefined && preview.end! < preview.start)) throw invalidInput('作者预览范围无效');
  }
  return review;
};

export const validateDurableWorkspaceProposal = (proposal: DurableWorkspaceProposal): DurableWorkspaceProposal => {
  const status: string = requireString(proposal.status, 'proposal status');
  if (status !== 'pending' && status !== 'accepted' && status !== 'rejected') {
    throw invalidInput('proposal status 无效');
  }
  const expectedCas: WorkspaceCas = validateWorkspaceCas(proposal.expectedCas);
  if (proposal.patches.length === 0 && proposal.operation === undefined) throw invalidInput('proposal patches 不能为空');
  if (proposal.operation !== undefined && (proposal.patches.length !== 0 || proposal.review === undefined)) throw invalidInput('专属操作需要独立的作者预览');
  const patches: WorkspaceProposalPatch[] = [];
  const paths: Set<string> = new Set<string>();
  for (let i: number = 0; i < proposal.patches.length; i++) {
    const patch: WorkspaceProposalPatch = validateWorkspaceProposalPatch(proposal.patches[i], expectedCas.branchId);
    if (paths.has(patch.path)) throw invalidInput(`proposal patch 重复: ${patch.path}`);
    paths.add(patch.path);
    patches.push(patch);
  }
  const createdAt: number = requireNonNegativeInteger(proposal.createdAt, 'createdAt');
  const resolvedAt: number | null = proposal.resolvedAt === null
    ? null : requireNonNegativeInteger(proposal.resolvedAt, 'resolvedAt');
  if (status === 'pending' && resolvedAt !== null) throw invalidInput('pending proposal 不应有 resolvedAt');
  if (status !== 'pending' && (resolvedAt === null || resolvedAt < createdAt)) {
    throw invalidInput('已结案 proposal 缺少有效 resolvedAt');
  }
  return {
    proposalId: requireString(proposal.proposalId, 'proposalId'),
    status: status as WorkspaceProposalStatus,
    expectedCas,
    patches,
    ...(proposal.operation === undefined ? {} : { operation: validateNovelSpecializedOperation(proposal.operation) }),
    ...(proposal.review === undefined ? {} : { review: validateWorkspaceProposalReview(proposal.review) }),
    createdAt,
    resolvedAt,
  };
};

export const parseDurableWorkspaceProposal = (raw: string): DurableWorkspaceProposal => {
  const value: Record<string, unknown> = parseRecord(raw, 'proposal');
  if (!isRecord(value.expectedCas) || !Array.isArray(value.patches)) {
    throw invalidInput('proposal 缺少 expectedCas 或 patches');
  }
  const sourcePatches: WorkspaceProposalPatch[] = [];
  for (let i: number = 0; i < value.patches.length; i++) {
    const source: unknown = value.patches[i];
    if (!isRecord(source)) throw invalidInput('proposal patch 必须是对象');
    const patch: Record<string, unknown> = source as Record<string, unknown>;
    sourcePatches.push({
      operation: patch.operation as WorkspaceProposalPatchOperation,
      path: patch.path as string,
      content: patch.content === null ? null : patch.content as string,
    });
  }
  const cas: Record<string, unknown> = value.expectedCas as Record<string, unknown>;
  return validateDurableWorkspaceProposal({
    proposalId: value.proposalId as string,
    status: value.status as WorkspaceProposalStatus,
    expectedCas: { branchId: cas.branchId as string, head: cas.head as string, treeDigest: cas.treeDigest as string },
    patches: sourcePatches,
    operation: value.operation as NovelSpecializedOperation | undefined,
    review: value.review as WorkspaceProposalReview | undefined,
    createdAt: value.createdAt as number,
    resolvedAt: value.resolvedAt === null ? null : value.resolvedAt as number,
  });
};

export const resolveDurableWorkspaceProposal = (proposal: DurableWorkspaceProposal,
  accept: boolean, now: number): WorkspaceProposalResolution => {
  const checked: DurableWorkspaceProposal = validateDurableWorkspaceProposal(proposal);
  const resolvedAt: number = requireNonNegativeInteger(now, 'resolvedAt');
  if (checked.status !== 'pending') return { proposal: checked, changed: false };
  if (resolvedAt < checked.createdAt) throw invalidInput('resolvedAt 早于 createdAt');
  return {
    proposal: {
      proposalId: checked.proposalId,
      status: accept ? 'accepted' : 'rejected',
      expectedCas: checked.expectedCas,
      patches: checked.patches,
      ...(checked.operation === undefined ? {} : { operation: checked.operation }),
      ...(checked.review === undefined ? {} : { review: checked.review }),
      createdAt: checked.createdAt,
      resolvedAt,
    },
    changed: true,
  };
};

export const validateWorkspaceUndoSnapshot = (snapshot: WorkspaceUndoSnapshot): WorkspaceUndoSnapshot => {
  return {
    branchId: requireBranchId(snapshot.branchId, 'branchId'),
    head: validateDigest(requireString(snapshot.head, 'head'), 'head'),
    treeDigest: validateDigest(requireString(snapshot.treeDigest, 'treeDigest'), 'treeDigest'),
    createdAt: requireNonNegativeInteger(snapshot.createdAt, 'createdAt'),
  };
};

export const parseWorkspaceUndoSnapshot = (raw: string): WorkspaceUndoSnapshot => {
  const value: Record<string, unknown> = parseRecord(raw, 'undo snapshot');
  return validateWorkspaceUndoSnapshot({
    branchId: value.branchId as string,
    head: value.head as string,
    treeDigest: value.treeDigest as string,
    createdAt: value.createdAt as number,
  });
};

export const unresolvedFromOrdinal = (changedChapterOrdinal: number, chapterCount: number): number | null => {
  const changed: number = requireChapterOrdinal(changedChapterOrdinal, 'changedChapterOrdinal');
  const count: number = requireNonNegativeInteger(chapterCount, 'chapterCount');
  if (changed > count) throw invalidInput('changedChapterOrdinal 超出当前章节数');
  return changed < count ? changed : null;
};

export const isWorkspacePlotStale = (sourceTreeDigest: string, syncedTreeDigest: string | null): boolean => {
  const source: string = validateDigest(requireString(sourceTreeDigest, 'sourceTreeDigest'), 'sourceTreeDigest');
  if (syncedTreeDigest === null) return true;
  return source !== validateDigest(requireString(syncedTreeDigest, 'syncedTreeDigest'), 'syncedTreeDigest');
};

export const refreshWorkspacePlotState = (sourceTreeDigest: string,
  syncedTreeDigest: string | null): WorkspacePlotState => {
  const source: string = validateDigest(requireString(sourceTreeDigest, 'sourceTreeDigest'), 'sourceTreeDigest');
  const synced: string | null = syncedTreeDigest === null
    ? null : validateDigest(requireString(syncedTreeDigest, 'syncedTreeDigest'), 'syncedTreeDigest');
  return { sourceTreeDigest: source, syncedTreeDigest: synced, stale: isWorkspacePlotStale(source, synced) };
};

export const validateWorkspacePlotState = (state: WorkspacePlotState): WorkspacePlotState => {
  const refreshed: WorkspacePlotState = refreshWorkspacePlotState(state.sourceTreeDigest, state.syncedTreeDigest);
  if (state.stale !== refreshed.stale) throw invalidInput('plot stale 标记与 digest 不一致');
  return refreshed;
};

export const parseWorkspacePlotState = (raw: string): WorkspacePlotState => {
  const value: Record<string, unknown> = parseRecord(raw, 'plot state');
  if (value.syncedTreeDigest !== null && !isString(value.syncedTreeDigest)) {
    throw invalidInput('plot syncedTreeDigest 无效');
  }
  if (value.stale !== true && value.stale !== false) throw invalidInput('plot stale 必须是布尔值');
  return validateWorkspacePlotState({
    sourceTreeDigest: value.sourceTreeDigest as string,
    syncedTreeDigest: value.syncedTreeDigest === null ? null : value.syncedTreeDigest as string,
    stale: value.stale as boolean,
  });
};

export const syncWorkspacePlot = (sourceTreeDigest: string): WorkspacePlotState => {
  const source: string = validateDigest(requireString(sourceTreeDigest, 'sourceTreeDigest'), 'sourceTreeDigest');
  return { sourceTreeDigest: source, syncedTreeDigest: source, stale: false };
};
