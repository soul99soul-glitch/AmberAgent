import type { UIMessage } from '../agent/message.ts';
import { invalidInput } from './error.ts';
import type { DurableGhostwriteJob, GhostwriteProgress } from './ghostwrite.ts';
import { ghostwriteReceipt, validateDurableGhostwriteJob, validateGhostwriteReview } from './ghostwrite.ts';
import type {
  NovelBranch, NovelBranchSettings, NovelChapter, NovelMaterial, NovelMessage, NovelModelPolicy,
  NovelProject, NovelSettingProposal,
} from './models.ts';
import {
  NOVEL_SCHEMA_VERSION, defaultNovelModelPolicy, emptyNovelBranchSettings, makeNovelMessage,
  makeNovelProject, novelChapterOrdinal, withNovelProjectCompatibility,
} from './models.ts';
import type { DurablePolishJob } from './polish.ts';
import { validateDurablePolishJob } from './polish.ts';
import { normalizeNovelCreationMetadata } from './project_metadata.ts';
import type { NovelWorkspaceCas } from './repository_contract.ts';
import type { NovelWorkspaceManifest } from './workspace_contract.ts';
import {
  NOVEL_WORKSPACE_FORMAT, NOVEL_WORKSPACE_VERSION, chapterFileName, serializeNovelWorkspaceManifest,
} from './workspace_contract.ts';
import type { NovelWorkspaceArchiveFile } from './workspace_exchange.ts';
import type { DurableWorkspaceProposal, WorkspaceCommit } from './workspace_history.ts';
import {
  parseWorkspaceCommit, validateDurableWorkspaceProposal, validateWorkspaceReceiptLedger,
} from './workspace_history.ts';
import { workspaceStorageToken as stableDigest } from './workspace_storage.ts';

export const STATE_FILE: string = '.amber/project-state.json';
export const LEDGER_FILE: string = '.amber/ledger.jsonl';
export const CHECKOUT_FILE: string = '.amber/checkout.json';
export const PROPOSALS_FILE: string = '.amber/proposals.json';
export const GHOSTWRITE_JOBS_FILE: string = '.amber/jobs.json';
export const MAX_PROJECT_BYTES: number = 16 * 1024 * 1024;
export const WORKSPACE_STATE_VERSION: number = 1;
export const PUBLIC_ORIGINALS_PREFIX: string = '.amber/public-originals/';
export interface PersistedNovelMessage extends Omit<NovelMessage, 'content' | 'uiMessage'> {
  content?: string;
  uiMessage?: UIMessage;
}

export interface PersistedNovelProject extends Omit<NovelProject,
  'settingProposals' | 'messages' | 'modelId' | 'modelPolicy' | 'branchSettings' | 'chapterPlots' | 'discussionArchives'> {
  settingProposals?: NovelSettingProposal[];
  messages: PersistedNovelMessage[];
  modelId?: string | null;
  modelPolicy?: NovelModelPolicy;
  branchSettings?: NovelBranchSettings;
  chapterPlots?: NovelProject['chapterPlots'];
  discussionArchives?: NovelProject['discussionArchives'];
}

export interface WorkspaceChapterRecord extends Omit<NovelChapter, 'content'> {
  path: string;
}

export interface WorkspaceMaterialRecord extends Omit<NovelMaterial, 'content'> {
  path: string;
}

export interface WorkspaceProjectMeta extends Omit<NovelProject,
  'chapters' | 'materials' | 'modelId' | 'branchSettings'> { }

export interface WorkspaceProjectState {
  stateVersion: number;
  project: WorkspaceProjectMeta;
  chapters: WorkspaceChapterRecord[];
  materials: WorkspaceMaterialRecord[];
  branchSettings: NovelBranchSettings;
}

export interface WorkspaceCheckout {
  branchId: string;
  head: string;
  treeDigest: string;
}

export type WorkspaceLedgerCommit = WorkspaceCommit;

export interface WorkspaceBranchSnapshot {
  branchId: string;
  state: WorkspaceProjectState;
  unresolvedFromOrdinal: number | null;
  plotStale: boolean;
  plotSourceTreeDigest: string;
  plotSyncedTreeDigest: string | null;
  plotContent: string | null;
}

export interface WorkspaceCommitSnapshot {
  project: NovelProject;
  unresolvedFromOrdinal: number | null;
  plotStale: boolean;
  plotSourceTreeDigest: string;
  plotSyncedTreeDigest: string | null;
  plotContent: string | null;
}

export interface WorkspaceUndoRecord {
  branchId: string;
  snapshot: WorkspaceCommitSnapshot;
}

export interface WorkspaceProposalDecision {
  commandId: string;
  proposalId: string;
  accept: boolean;
}

export interface WorkspaceProposalStore {
  version: number;
  proposals: DurableWorkspaceProposal[];
  decisions: WorkspaceProposalDecision[];
}

export type DurableNovelJob = DurableGhostwriteJob | DurablePolishJob;

export interface WorkspaceNovelJobStore {
  version: 2;
  jobs: DurableNovelJob[];
}

export interface SnapshotFile {
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

export const validateProject = (project: NovelProject): NovelProject => {
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

export const mainBranch = (project: NovelProject): NovelBranch => {
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

export const normalizeProject = (project: NovelProject): NovelProject => {
  if (project.branches.length > 0) return project;
  return { ...project, branches: [mainBranch(project)] };
};

export const withAuthorPlot = (project: NovelProject, content: string | null): NovelProject => {
  Object.defineProperty(project, 'authorPlot', { value: content, enumerable: false, configurable: true });
  return project;
};

export const branchSnapshotPath = (branchId: string): string => `.amber/branches/${branchId}.json`;
export const commitSnapshotPath = (head: string): string => `.amber/commits/${head}.json`;
export const undoPath = (branchId: string): string => `.amber/undo/${branchId}.json`;

export const encodeUtf8 = (text: string): Uint8Array => {
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


export const parseLedger = (raw: string): WorkspaceLedgerCommit[] => {
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

export const findCommit = (commits: WorkspaceLedgerCommit[], head: string): WorkspaceLedgerCommit | null => {
  for (let i: number = commits.length - 1; i >= 0; i--) {
    if (commits[i].commitId === head) return commits[i];
  }
  return null;
};

export const findReceipt = (commits: WorkspaceLedgerCommit[], receipt: string): WorkspaceLedgerCommit | null => {
  for (let i: number = commits.length - 1; i >= 0; i--) {
    if (commits[i].receipt === receipt) return commits[i];
  }
  return null;
};

export const ancestryCommitIds = (commits: WorkspaceLedgerCommit[], head: string): Set<string> => {
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

export const assertCas = (expected: NovelWorkspaceCas, actual: WorkspaceCheckout): void => {
  if (expected.branchId !== actual.branchId || expected.head !== actual.head ||
    expected.treeDigest !== actual.treeDigest) {
    throw invalidInput('工作区已变化，拒绝覆盖');
  }
};

export const assertCommandId = (commandId: string): void => {
  if (commandId.trim().length === 0 || commandId.length > 256 || commandId.indexOf('\n') >= 0) {
    throw invalidInput('commandId 无效');
  }
};

export const emptyProposalStore = (): WorkspaceProposalStore => ({
  version: 1,
  proposals: [],
  decisions: [],
});

export const parseProposalStore = (raw: string): WorkspaceProposalStore => {
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

export const emptyNovelJobStore = (): WorkspaceNovelJobStore => ({ version: 2, jobs: [] });

export const isGhostwriteJob = (job: DurableNovelJob): job is DurableGhostwriteJob =>
  job.kind === 'ghostwrite';

export const isPolishJob = (job: DurableNovelJob): job is DurablePolishJob =>
  job.kind === 'batch_polish';

export const novelJobIsTerminal = (job: DurableNovelJob): boolean =>
  job.stage === 'completed' || job.stage === 'cancelled';

export const parseNovelJobStore = (raw: string): WorkspaceNovelJobStore => {
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

export const materialPath = (material: NovelMaterial): string =>
  `setting/${material.kind}/${material.id}.md`;

export const branchMaterialPath = (branchId: string, material: NovelMaterial): string =>
  `branches/${branchId}/setting/${material.kind}/${material.id}.md`;

export const branchSettingPaths = (branchId: string): Record<
  'thisChapterPlan' | 'futurePlan' | 'preferences' | 'catalog', string
> => ({
  thisChapterPlan: `branches/${branchId}/plan/this-chapter.md`,
  futurePlan: `branches/${branchId}/plan/future.md`,
  preferences: `branches/${branchId}/setting/preferences.md`,
  // Host-generated structured catalog. The three Markdown paths above remain the only editable setting files.
  catalog: `branches/${branchId}/setting/catalog.json`,
});

export const chapterOrdinal = novelChapterOrdinal;

export const buildSnapshotFiles = (
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

export const snapshotTreeDigest = (files: SnapshotFile[]): string => stableDigest(files
  .filter((file: SnapshotFile): boolean =>
    !file.path.startsWith('.amber/') && file.path !== 'manifest.yaml' && file.path !== 'project.md')
  .map((file: SnapshotFile): NovelWorkspaceArchiveFile => ({
    path: file.path,
    bytes: encodeUtf8(file.content),
  })));

export const chapterContentsMatch = (actual: NovelChapter[], expected: NovelChapter[]): boolean =>
  actual.length === expected.length && actual.every((chapter: NovelChapter, index: number): boolean =>
    chapter.id === expected[index].id && chapter.content === expected[index].content);

export const branchChapterRecordsMatch = (actual: WorkspaceChapterRecord[], expected: WorkspaceChapterRecord[]): boolean =>
  actual.length === expected.length && actual.every((chapter: WorkspaceChapterRecord, index: number): boolean => {
    const other: WorkspaceChapterRecord = expected[index];
    return chapter.id === other.id && chapter.title === other.title && chapter.createdAt === other.createdAt &&
      chapter.updatedAt === other.updatedAt && chapter.discarded === other.discarded &&
      chapter.suggestionWarning === other.suggestionWarning && chapter.ordinal === other.ordinal &&
      (chapter.discarded || chapter.path === other.path);
  });

export const branchStateMatchesCommit = (
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

export const decodeLegacy = (raw: string): NovelProject => {
  const stored: PersistedNovelProject = JSON.parse(raw) as PersistedNovelProject;
  return validateProject(migrateNovelProject(stored));
};





export const assertNoActiveNovelJob = (store: WorkspaceNovelJobStore, branchId: string): void => {
  const active: DurableNovelJob | undefined = store.jobs.find(
    (job: DurableNovelJob): boolean => job.branchId === branchId && !novelJobIsTerminal(job));
  if (active !== undefined) throw invalidInput('当前分支已有未结束的小说作业');
};

export const latestBranchCommit = (
  commits: WorkspaceLedgerCommit[], branchId: string,
): WorkspaceLedgerCommit | null => {
  for (let i: number = commits.length - 1; i >= 0; i--) {
    if (commits[i].branchId === branchId) return commits[i];
  }
  return null;
};

export const assertBackupJobsIdle = (jobs: WorkspaceNovelJobStore): void => {
  const active: DurableNovelJob | undefined = jobs.jobs.find((job: DurableNovelJob): boolean =>
    !['paused', 'failed', 'waiting_system', 'completed', 'cancelled'].includes(job.stage));
  if (active !== undefined) throw invalidInput('创作任务运行中，请暂停后备份或恢复');
};

