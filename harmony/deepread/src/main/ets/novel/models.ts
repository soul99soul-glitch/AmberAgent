// novel/models — 小说创作数据模型(移植自 codex/novel-under-7000 model/NovelModels.kt)
// 扁平模型(schemaVersion=4):项目内嵌分支快照所需的领域状态。
// 枚举用小写字符串联合(本地存储,无需与 Android 线格式一致)。ArkTS 安全。

import type { UIMessage } from '../agent/message.ts';
import { makeUIMessage } from '../agent/message.ts';
import type { NovelChapterContract, NovelUpcomingArc } from './chapter_contract.ts';
import type { NovelDiscussionArchive } from './discussion_archive.ts';
import type { NovelChapterPlotPointer } from './plot_projection.ts';
import type { NovelStructuredState } from './structured_state.ts';
import type { NovelStateOperation } from './state_rebuild.ts';
import type { NovelOrdinaryRun } from './ordinary_run.ts';
import type { NovelRunKind } from './prompt_catalog.ts';
import { invalidInput } from './error.ts';
import { normalizeNovelMaterialFields, optionalNovelMaterialFields } from './material_fields.ts';

export const NOVEL_SCHEMA_VERSION = 4;

// 校验上限(照搬 Android NovelMutations.kt)
export const MAX_PROJECT_NAME_CHARS = 200;
export const MAX_TITLE_CHARS = 300;
export const MAX_CHAPTER_CHARS = 1_000_000;
export const MAX_MATERIAL_CHARS = 200_000;

// ===== 枚举 =====
export type NovelMessageRole = 'user' | 'assistant';
export type NovelChatMode = 'write' | 'discuss';
export type NovelGenerationGranularity = 'continuation' | 'whole_chapter';
export type NovelMaterialKind = 'world' | 'character' | 'relationship' | 'outline' | 'requirement' | 'other';
export type NovelSuggestionStatus = 'pending' | 'accepted' | 'rejected';
export type NovelSettingProposalStatus = 'pending' | 'accepted' | 'rejected';

// 固定模型必须同时携带 provider，避免旧 modelId 在迁移时被错误路由到任意 provider。
export type NovelModelTarget =
  | { kind: 'global' }
  | { kind: 'fixed'; providerId: string; modelId: string };

export interface NovelModelPolicy {
  writing: NovelModelTarget;
  // null 表示跟随 writing，不是跟随应用默认值。
  review: NovelModelTarget | null;
  stateSync: NovelModelTarget | null;
}

export type NovelSettingFilePath =
  | 'plan/this-chapter.md'
  | 'plan/future.md'
  | 'setting/preferences.md';

export type NovelForeshadowStatus = 'open' | 'resolved';

export interface NovelForeshadow {
  id: string;
  title: string;
  content: string;
  status: NovelForeshadowStatus;
  createdAt: number;
  resolvedAt: number | null;
}

export interface NovelConfirmedDecision {
  id: string;
  title: string;
  content: string;
  confirmedAt: number;
}

export interface NovelBranchSettings {
  chapterContract?: NovelChapterContract;
  upcomingArc?: NovelUpcomingArc;
  suggestedChapterCount?: number;
  thisChapterPlan: string;
  futurePlan: string;
  preferences: string;
  foreshadows: NovelForeshadow[];
  confirmedDecisions: NovelConfirmedDecision[];
}

// 收录目标(运行时参数,不持久化)
export type NovelCollectionTarget =
  | { kind: 'append'; chapterId: string }
  | { kind: 'replace'; chapterId: string }
  | { kind: 'new_chapter'; title: string };

// ===== 实体 =====
export interface NovelCandidateProvenance {
  kind: 'write' | 'regenerate' | 'polish';
  branchId: string;
  baseManuscriptDigest: string;
  basePlanDigest: string;
  sourceChapterId: string | null;
  sourceDigest: string | null;
  // 润色只有收到完整结束标记后才可采用；普通写作中断的片段仍可手动收录。
  complete: boolean;
}

export interface NovelMessage {
  id: string;
  role: NovelMessageRole;
  mode: NovelChatMode;
  // 唯一持久化消息负载。包含 reasoning、tool output/approval、annotation、usage 等全部 UI 事实。
  uiMessage: UIMessage;
  // 仅为尚未迁移完的调用方提供的派生读取值；工厂创建时为不可枚举字段，绝不作为持久化格式。
  readonly content: string;
  collectedChapterId: string | null;
  createdAt: number;
  granularity: NovelGenerationGranularity | null;
  interrupted: boolean;
  // 旧消息无来源元数据；新写作候选的基线不包含对话 checkpoint/revision。
  candidate?: NovelCandidateProvenance;
  // Actual assistant run origin; presentation can hide incomplete protocol output without guessing JSON.
  runKind?: NovelRunKind | null;
  clonedFromMessageId?: string;
  rootMessageId?: string;
}

export interface NovelChapter {
  id: string;
  // 公开工作区可携带独立章号；旧项目继续从 chapter-XXX id 或所在顺序解析。
  ordinal?: number;
  title: string;
  content: string;
  createdAt: number;
  updatedAt: number;
  // N1:章节废弃标记(Android setChapterDiscarded);false = 正常
  discarded: boolean;
  suggestionWarning?: string | null;
}

export const novelChapterOrdinal = (chapter: NovelChapter, fallback: number): number => {
  if (chapter.ordinal !== undefined) return chapter.ordinal;
  const match: RegExpMatchArray | null = chapter.id.match(/^chapter-(\d{3})(?:-|$)/);
  if (match === null) return fallback;
  const parsed: number = Number.parseInt(match[1], 10);
  return parsed >= 1 && parsed <= 999 ? parsed : fallback;
};

// Allocate after the highest existing chapter, including discarded chapters. Deleted highest
// ordinals may be reused by a new UUID; this is not a permanent ordinal reservation ledger.
export const nextNovelChapterOrdinal = (chapters: NovelChapter[]): number => {
  let highest: number = 0;
  chapters.forEach((chapter: NovelChapter, index: number): void => {
    highest = Math.max(highest, novelChapterOrdinal(chapter, index + 1));
  });
  if (highest >= 999) throw invalidInput('章节序号已达到 999，无法新建下一章');
  return highest + 1;
};

// 章节版本历史(Android NovelChapterVersionKind:manual/polish/regenerate/collection)
export type NovelChapterVersionKind = 'manual' | 'polish' | 'regenerate' | 'collection';

export interface NovelChapterVersion {
  id: string;
  chapterId: string;
  kind: NovelChapterVersionKind;
  title: string;
  content: string;
  createdAt: number;
}

// 分支(Android NovelBranchLifecycle:active/deleted;fork 继承 head 状态)
export interface NovelBranch {
  id: string;
  name: string;
  lifecycle: 'active' | 'deleted';
  isMain: boolean;
  // 分支起点:fork 来源章节 id(initial = 空分支)
  forkFromChapterId: string | null;
  createdAt: number;
}

export type NovelMaterialInjectionMode = 'always' | 'smart' | 'off';
export interface NovelMaterialFields {
  aliases?: string[];
  tags?: string[];
  customKind?: string;
  injectionMode?: NovelMaterialInjectionMode;
}

export interface NovelMaterial extends NovelMaterialFields {
  id: string;
  kind: NovelMaterialKind;
  title: string;
  content: string;
  enabled: boolean;
  createdAt: number;
  updatedAt: number;
}

export interface NovelMaterialSuggestion extends NovelMaterialFields {
  id: string;
  sourceChapterId: string;
  kind: NovelMaterialKind;
  title: string;
  content: string;
  status: NovelSuggestionStatus;
  createdAt: number;
  resolvedAt: number | null;
  sourceDigest?: string;
}

export interface NovelSettingProposal extends NovelMaterialFields {
  id: string;
  sourceMessageId: string;
  kind: NovelMaterialKind;
  title: string;
  content: string;
  status: NovelSettingProposalStatus;
  createdAt: number;
  resolvedAt: number | null;
}

export type NovelProjectCreationMode = 'blank' | 'quickStart';
export interface NovelStorySeed {
  genre: string;
  coreIdea: string;
  world: string;
  characters: string;
  direction: string;
}

export interface NovelProject {
  schemaVersion: number;
  id: string;
  name: string;
  // Creation metadata is immutable; omitted fields keep pre-standalone projects compatible.
  creationMode?: NovelProjectCreationMode;
  quickStartSeed?: NovelStorySeed | null;
  polishPreference?: string;
  structuredState?: NovelStructuredState;
  stateOperation?: NovelStateOperation | null;
  ordinaryRun?: NovelOrdinaryRun | null;
  stateSyncReasoningEnabled?: boolean;
  messages: NovelMessage[];
  chapters: NovelChapter[];
  materials: NovelMaterial[];
  // Project-wide base plus the current checkout's explicit overrides and hidden IDs.
  // Absent baseMaterials identifies legacy whole-branch material snapshots.
  baseMaterials?: NovelMaterial[];
  materialOverrides?: NovelMaterial[];
  hiddenMaterialIds?: string[];
  createdAt: number;
  updatedAt: number;
  modelPolicy: NovelModelPolicy;
  // 兼容旧调用方的派生值；仅 fixed writing 返回 modelId，永不持久化。
  readonly modelId: string | null;
  // 当前分支 plan/plot.md 的只读投影；branch snapshot 仍是唯一存储来源。
  readonly authorPlot?: string;
  lastGenerationGranularity: NovelGenerationGranularity;
  materialSuggestions: NovelMaterialSuggestion[];
  settingProposals: NovelSettingProposal[];
  // N1:分支与章节版本(Android Branch/Checkpoint 语义的轻量等价)
  branches: NovelBranch[];
  chapterVersions: NovelChapterVersion[];
  // 当前 checkout 分支的设定目录；切换分支时从目标快照恢复。
  branchSettings: NovelBranchSettings;
  revision: number;
  chapterPlots: NovelChapterPlotPointer[];
  discussionArchives: NovelDiscussionArchive[];
}

// ===== id 生成 =====
let novelSeq = 0;
export const novelId = (): string => {
  novelSeq += 1;
  const rand: string = Math.random().toString(36).slice(2, 10);
  return `nv_${Date.now().toString(36)}_${novelSeq}_${rand}`;
};

// ===== 工厂 =====
export interface NovelProjectInit {
  id?: string;
  name: string;
  now: number;
  modelId?: string | null;
  modelPolicy?: NovelModelPolicy;
}

export const globalNovelModelTarget = (): NovelModelTarget => ({ kind: 'global' });

export const defaultNovelModelPolicy = (): NovelModelPolicy => ({
  writing: globalNovelModelTarget(),
  review: null,
  stateSync: null,
});

export const emptyNovelBranchSettings = (): NovelBranchSettings => ({
  thisChapterPlan: '',
  futurePlan: '',
  preferences: '',
  foreshadows: [],
  confirmedDecisions: [],
});

const legacyModelId = (policy: NovelModelPolicy): string | null =>
  policy.writing.kind === 'fixed' ? policy.writing.modelId : null;

const uiMessageText = (message: UIMessage): string => message.parts
  .filter((part): part is Extract<typeof part, { type: 'text' }> => part.type === 'text')
  .map((part): string => part.text)
  .join('\n');

export const withNovelProjectCompatibility = (project: Omit<NovelProject, 'modelId'>): NovelProject => {
  Object.defineProperty(project, 'modelId', {
    configurable: true,
    enumerable: false,
    get: (): string | null => legacyModelId(project.modelPolicy),
  });
  return project as NovelProject;
};

export const makeNovelProject = (init: NovelProjectInit): NovelProject => {
  const p: Omit<NovelProject, 'modelId'> = {
    schemaVersion: NOVEL_SCHEMA_VERSION,
    id: init.id !== undefined && init.id.length > 0 ? init.id : novelId(),
    name: init.name,
    messages: [],
    chapters: [],
    materials: [],
    createdAt: init.now,
    updatedAt: init.now,
    // 旧 modelId 无法安全推出 provider，故只作为迁移输入，不升级为 fixed target。
    modelPolicy: init.modelPolicy ?? defaultNovelModelPolicy(),
    lastGenerationGranularity: 'whole_chapter',
    materialSuggestions: [],
    settingProposals: [],
    branches: [],
    chapterVersions: [],
    branchSettings: emptyNovelBranchSettings(),
    revision: 0,
    chapterPlots: [],
    discussionArchives: [],
  };
  return withNovelProjectCompatibility(p);
};

export interface NovelMessageInit {
  id?: string;
  role: NovelMessageRole;
  mode: NovelChatMode;
  // content 仅兼容旧 producer；新调用必须传 uiMessage。
  content?: string;
  uiMessage?: UIMessage;
  createdAt: number;
  granularity?: NovelGenerationGranularity | null;
  collectedChapterId?: string | null;
  interrupted?: boolean;
  candidate?: NovelCandidateProvenance;
  // Actual assistant run origin; presentation can hide incomplete protocol output without guessing JSON.
  runKind?: NovelRunKind | null;
  clonedFromMessageId?: string;
  rootMessageId?: string;
}

export const makeNovelMessage = (init: NovelMessageInit): NovelMessage => {
  const uiMessage: UIMessage = init.uiMessage === undefined
    ? makeUIMessage(init.role, [{ type: 'text', text: init.content ?? '', metadata: null }], {
      id: init.id,
      createdAt: new Date(init.createdAt).toISOString(),
    })
    : init.uiMessage;
  const m: Omit<NovelMessage, 'content'> = {
    id: init.id !== undefined && init.id.length > 0 ? init.id : novelId(),
    role: init.role,
    mode: init.mode,
    uiMessage,
    collectedChapterId: init.collectedChapterId !== undefined ? init.collectedChapterId : null,
    createdAt: init.createdAt,
    granularity: init.granularity !== undefined ? init.granularity : null,
    interrupted: init.interrupted ?? false,
    candidate: init.candidate,
    runKind: init.runKind,
    clonedFromMessageId: init.clonedFromMessageId,
    rootMessageId: init.rootMessageId,
  };
  Object.defineProperty(m, 'content', {
    configurable: true,
    enumerable: false,
    get: (): string => uiMessageText(m.uiMessage),
  });
  return m as NovelMessage;
};

export interface NovelChapterInit {
  id?: string;
  ordinal?: number;
  title: string;
  content: string;
  now: number;
}

export const makeNovelChapter = (init: NovelChapterInit): NovelChapter => {
  const c: NovelChapter = {
    id: init.id !== undefined && init.id.length > 0 ? init.id : novelId(),
    ordinal: init.ordinal,
    title: init.title,
    content: init.content,
    createdAt: init.now,
    updatedAt: init.now,
    discarded: false,
  };
  return c;
};

// N1:章节版本(mutation 用)
export const makeNovelChapterVersion = (
  chapterId: string, kind: NovelChapterVersionKind, title: string, content: string, now: number,
): NovelChapterVersion => ({
  id: novelId(),
  chapterId: chapterId,
  kind: kind,
  title: title,
  content: content,
  createdAt: now,
});

// N1:分支
export const makeNovelBranch = (
  name: string, isMain: boolean, forkFromChapterId: string | null, now: number,
): NovelBranch => ({
  id: novelId(),
  name: name,
  lifecycle: 'active',
  isMain: isMain,
  forkFromChapterId: forkFromChapterId,
  createdAt: now,
});

export interface NovelMaterialInit extends NovelMaterialFields {
  id?: string;
  kind: NovelMaterialKind;
  title: string;
  content: string;
  enabled?: boolean;
  now: number;
}

export const makeNovelMaterial = (init: NovelMaterialInit): NovelMaterial => {
  const fields = normalizeNovelMaterialFields(init, init.enabled ?? true);
  const m: NovelMaterial = {
    id: init.id !== undefined && init.id.length > 0 ? init.id : novelId(),
    kind: init.kind,
    title: init.title,
    content: init.content,
    ...fields,
    enabled: fields.injectionMode !== 'off',
    createdAt: init.now,
    updatedAt: init.now,
  };
  return m;
};

export interface NovelSuggestionInit extends NovelMaterialFields {
  id?: string;
  sourceChapterId: string;
  kind: NovelMaterialKind;
  title: string;
  content: string;
  now: number;
  sourceDigest?: string;
}

export const makeNovelSuggestion = (init: NovelSuggestionInit): NovelMaterialSuggestion => {
  const s: NovelMaterialSuggestion = {
    id: init.id !== undefined && init.id.length > 0 ? init.id : novelId(),
    sourceChapterId: init.sourceChapterId,
    kind: init.kind,
    title: init.title,
    content: init.content,
    status: 'pending',
    createdAt: init.now,
    resolvedAt: null,
    sourceDigest: init.sourceDigest,
    ...optionalNovelMaterialFields(init),
  };
  return s;
};

export interface NovelSettingProposalInit extends NovelMaterialFields {
  id?: string;
  sourceMessageId: string;
  kind: NovelMaterialKind;
  title: string;
  content: string;
  now: number;
}

export const makeNovelSettingProposal = (init: NovelSettingProposalInit): NovelSettingProposal => ({
  ...optionalNovelMaterialFields(init),
  id: init.id !== undefined && init.id.length > 0 ? init.id : novelId(),
  sourceMessageId: init.sourceMessageId,
  kind: init.kind,
  title: init.title,
  content: init.content,
  status: 'pending',
  createdAt: init.now,
  resolvedAt: null,
});

// 素材 kind 显示名(中文)
export const MATERIAL_KIND_LABELS: Record<NovelMaterialKind, string> = {
  world: '世界',
  character: '人物',
  relationship: '关系',
  outline: '大纲',
  requirement: '写作要求',
  other: '其他',
};
