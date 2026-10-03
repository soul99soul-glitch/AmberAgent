// Project ID lives only in defined project records, the native manifest and jobs.
// Canonical tree digests exclude .amber/, manifest.yaml and project.md, so a copy
// keeps all commit, CAS, plan, progress and receipt identities intact.
import type { NovelNativeBackupImport } from './native_backup.ts';
import { makeNovelNativeBackupImport } from './native_backup.ts';
import type { NovelWorkspaceArchiveFile } from './workspace_exchange.ts';
import { decodeNovelWorkspaceUtf8 } from './workspace_exchange.ts';
import { validateNovelWorkspacePath, parseNovelWorkspaceManifest, serializeNovelWorkspaceManifest } from './workspace_contract.ts';
import { invalidInput } from './error.ts';
interface ProjectRecord { project: { id: string; ordinaryRun?: unknown }; }
interface BranchRecord { state: ProjectRecord; }
interface UndoRecord { snapshot: ProjectRecord; }
interface Jobs { jobs: Array<{ projectId: string }>; }

const utf8 = (value: string): Uint8Array => {
  const bytes: number[] = [];
  for (const character of value) {
    const point: number = character.codePointAt(0)!;
    if (point < 128) bytes.push(point);
    else if (point < 2048) bytes.push(192 | point >> 6, 128 | point & 63);
    else if (point < 65536) bytes.push(224 | point >> 12, 128 | point >> 6 & 63, 128 | point & 63);
    else bytes.push(240 | point >> 18, 128 | point >> 12 & 63, 128 | point >> 6 & 63, 128 | point & 63);
  }
  return new Uint8Array(bytes);
};

export const copyNovelNativeBackup = (input: NovelNativeBackupImport,
  projectId: string): NovelNativeBackupImport => {
  validateNovelWorkspacePath(projectId);
  if (projectId.includes('/') || projectId.startsWith('.') || projectId === input.projectId) throw invalidInput('副本项目标识无效');
  const ledgerFile = input.files.find(file => file.path === '.amber/ledger.jsonl');
  if (ledgerFile === undefined) throw invalidInput('副本缺少提交台账');
  const commits: Array<{ commitId: string; branchId: string }> = decodeNovelWorkspaceUtf8(ledgerFile.bytes)
    .trim().split('\n').filter(Boolean).map(raw => JSON.parse(raw) as { commitId: string; branchId: string });
  const commitPaths: Set<string> = new Set(commits.map(commit => `.amber/commits/${commit.commitId}.json`));
  const branchPaths: Set<string> = new Set(commits.map(commit => `.amber/branches/${commit.branchId}.json`));
  const undoPaths: Set<string> = new Set(commits.map(commit => `.amber/undo/${commit.branchId}.json`));
  const files: NovelWorkspaceArchiveFile[] = input.files.map(file => {
    let record: object | null = null;
    let bytes: Uint8Array = new Uint8Array(file.bytes);
    if (file.path === 'manifest.yaml') {
      const manifest = parseNovelWorkspaceManifest(decodeNovelWorkspaceUtf8(bytes));
      bytes = utf8(serializeNovelWorkspaceManifest({ ...manifest, projectId }));
    } else if (file.path === '.amber/project-state.json' || commitPaths.has(file.path)) {
      const project: ProjectRecord = JSON.parse(decodeNovelWorkspaceUtf8(bytes)) as ProjectRecord;
      project.project.id = projectId;
      delete project.project.ordinaryRun;
      record = project;
    } else if (branchPaths.has(file.path)) {
      const branch: BranchRecord = JSON.parse(decodeNovelWorkspaceUtf8(bytes)) as BranchRecord;
      branch.state.project.id = projectId;
      delete branch.state.project.ordinaryRun;
      record = branch;
    } else if (undoPaths.has(file.path)) {
      const undo: UndoRecord = JSON.parse(decodeNovelWorkspaceUtf8(bytes)) as UndoRecord;
      undo.snapshot.project.id = projectId;
      delete undo.snapshot.project.ordinaryRun;
      record = undo;
    } else if (file.path === '.amber/jobs.json') {
      const jobs: Jobs = JSON.parse(decodeNovelWorkspaceUtf8(bytes)) as Jobs;
      for (const job of jobs.jobs) job.projectId = projectId;
      record = jobs;
    }
    if (record !== null) bytes = utf8(JSON.stringify(record));
    return { path: file.path, bytes };
  });
  return makeNovelNativeBackupImport({ ...input.manifest, projectId }, files);
};
