// History selection is a projection of the existing commit ledger, not another history store.
import { invalidInput } from './error.ts';
import type { WorkspaceCommit } from './workspace_history.ts';
import type { NovelBranch } from './models.ts';

export const reachableWorkspaceHistory = (
  commits: WorkspaceCommit[], head: string, branchId: string,
): WorkspaceCommit[] => {
  const byHead: Map<string, WorkspaceCommit> = new Map(commits.map(commit => [commit.commitId, commit]));
  const history: WorkspaceCommit[] = [];
  let cursor: string | null = head;
  while (cursor !== null) {
    const commit: WorkspaceCommit | undefined = byHead.get(cursor);
    if (commit === undefined) throw invalidInput('历史提交链不完整');
    if (commit.branchId === branchId) history.push(commit);
    cursor = commit.parent;
  }
  return history;
};

const NON_EDIT_MUTATIONS: string[] = [
  'transcript_checkpoint', 'branch_rename', 'branch_main', 'branch_delete',
  'rename', 'model_change', 'project_setup_change',
];

export const previousWorkspaceCheckpoint = (
  commits: WorkspaceCommit[], head: string, branchId: string,
): WorkspaceCommit | null => {
  const byHead: Map<string, WorkspaceCommit> = new Map(commits.map(commit => [commit.commitId, commit]));
  let current: WorkspaceCommit | undefined = byHead.get(head);
  // Undo records the restored checkpoint. A second undo moves before that checkpoint,
  // so undo itself never toggles the last two contents.
  while (current !== undefined && current.branchId === branchId) {
    if (current.restoredFromHead !== undefined) current = byHead.get(current.restoredFromHead);
    else if (NON_EDIT_MUTATIONS.includes(current.mutation)) current = current.parent === null ? undefined : byHead.get(current.parent);
    else break;
  }
  if (current === undefined || current.branchId !== branchId) return null;
  let cursor: string | null = current.parent;
  while (cursor !== null) {
    const commit: WorkspaceCommit | undefined = byHead.get(cursor);
    if (commit === undefined || commit.branchId !== branchId) return null;
    if (!NON_EDIT_MUTATIONS.includes(commit.mutation)) {
      return commit;
    }
    cursor = commit.parent;
  }
  return null;
};

export const selectWorkspaceCheckpoint = (
  commits: WorkspaceCommit[], head: string, branchId: string, targetHead: string,
): WorkspaceCommit => {
  const target: WorkspaceCommit | undefined = reachableWorkspaceHistory(commits, head, branchId)
    .find(commit => commit.commitId === targetHead);
  if (target === undefined) throw invalidInput('检查点不属于当前分支的可达历史');
  return target;
};

export const mutateNovelBranchMetadata = (
  branches: NovelBranch[], activeBranchId: string, branchId: string,
  operation: 'rename' | 'main' | 'delete', name: string = '',
): NovelBranch[] => {
  const target: NovelBranch | undefined = branches.find(branch => branch.id === branchId && branch.lifecycle === 'active');
  if (target === undefined) throw invalidInput('分支不存在或已删除');
  const clean: string = name.trim();
  if (operation === 'rename' && clean.length === 0) throw invalidInput('分支名不能为空');
  if (operation === 'delete' && (target.id === activeBranchId || target.isMain)) {
    throw invalidInput('不能删除当前分支或主线，请先切换并设置其他主线');
  }
  return branches.map(branch => {
    if (operation === 'main') return { ...branch, isMain: branch.id === branchId };
    if (branch.id !== branchId) return branch;
    return operation === 'rename' ? { ...branch, name: clean } : { ...branch, lifecycle: 'deleted' };
  });
};
