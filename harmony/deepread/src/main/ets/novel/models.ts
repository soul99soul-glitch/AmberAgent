// novel/models — 小说创作数据模型(移植自 codex/novel-under-7000 model/NovelModels.kt)
// 扁平模型(schemaVersion=2):项目内嵌 messages/chapters/materials/materialSuggestions。
// 枚举用小写字符串联合(本地存储,无需与 Android 线格式一致)。ArkTS 安全。

export const NOVEL_SCHEMA_VERSION = 2;

// 校验上限(照搬 Android NovelMutations.kt)
export const MAX_PROJECT_NAME_CHARS = 200;
export const MAX_TITLE_CHARS = 300;
export const MAX_CHAPTER_CHARS = 1_000_000;
export const MAX_MATERIAL_CHARS = 200_000;

// ===== 枚举 =====
export type NovelMessageRole = 'user' | 'assistant';
export type NovelChatMode = 'write' | 'discuss';
export type NovelGenerationGranularity = 'continuation' | 'whole_chapter';
export type NovelMaterialKind = 'world' | 'character' | 'outline' | 'requirement' | 'other';
export type NovelSuggestionStatus = 'pending' | 'accepted' | 'rejected';

// 收录目标(运行时参数,不持久化)
export type NovelCollectionTarget =
  | { kind: 'append'; chapterId: string }
  | { kind: 'replace'; chapterId: string }
  | { kind: 'new_chapter'; title: string };

// ===== 实体 =====
export interface NovelMessage {
  id: string;
  role: NovelMessageRole;
  mode: NovelChatMode;
  content: string;
  collectedChapterId: string | null;
  createdAt: number;
  granularity: NovelGenerationGranularity | null;
  interrupted: boolean;
}

export interface NovelChapter {
  id: string;
  title: string;
  content: string;
  createdAt: number;
  updatedAt: number;
}

export interface NovelMaterial {
  id: string;
  kind: NovelMaterialKind;
  title: string;
  content: string;
  enabled: boolean;
  createdAt: number;
  updatedAt: number;
}

export interface NovelMaterialSuggestion {
  id: string;
  sourceChapterId: string;
  kind: NovelMaterialKind;
  title: string;
  content: string;
  status: NovelSuggestionStatus;
  createdAt: number;
  resolvedAt: number | null;
}

export interface NovelProject {
  schemaVersion: number;
  id: string;
  name: string;
  messages: NovelMessage[];
  chapters: NovelChapter[];
  materials: NovelMaterial[];
  createdAt: number;
  updatedAt: number;
  modelId: string | null;
  lastGenerationGranularity: NovelGenerationGranularity;
  materialSuggestions: NovelMaterialSuggestion[];
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
}

export const makeNovelProject = (init: NovelProjectInit): NovelProject => {
  const p: NovelProject = {
    schemaVersion: NOVEL_SCHEMA_VERSION,
    id: init.id !== undefined && init.id.length > 0 ? init.id : novelId(),
    name: init.name,
    messages: [],
    chapters: [],
    materials: [],
    createdAt: init.now,
    updatedAt: init.now,
    modelId: init.modelId !== undefined ? init.modelId : null,
    lastGenerationGranularity: 'whole_chapter',
    materialSuggestions: [],
  };
  return p;
};

export interface NovelMessageInit {
  id?: string;
  role: NovelMessageRole;
  mode: NovelChatMode;
  content: string;
  createdAt: number;
  granularity?: NovelGenerationGranularity | null;
  collectedChapterId?: string | null;
  interrupted?: boolean;
}

export const makeNovelMessage = (init: NovelMessageInit): NovelMessage => {
  const m: NovelMessage = {
    id: init.id !== undefined && init.id.length > 0 ? init.id : novelId(),
    role: init.role,
    mode: init.mode,
    content: init.content,
    collectedChapterId: init.collectedChapterId !== undefined ? init.collectedChapterId : null,
    createdAt: init.createdAt,
    granularity: init.granularity !== undefined ? init.granularity : null,
    interrupted: init.interrupted ?? false,
  };
  return m;
};

export interface NovelChapterInit {
  id?: string;
  title: string;
  content: string;
  now: number;
}

export const makeNovelChapter = (init: NovelChapterInit): NovelChapter => {
  const c: NovelChapter = {
    id: init.id !== undefined && init.id.length > 0 ? init.id : novelId(),
    title: init.title,
    content: init.content,
    createdAt: init.now,
    updatedAt: init.now,
  };
  return c;
};

export interface NovelMaterialInit {
  id?: string;
  kind: NovelMaterialKind;
  title: string;
  content: string;
  enabled?: boolean;
  now: number;
}

export const makeNovelMaterial = (init: NovelMaterialInit): NovelMaterial => {
  const m: NovelMaterial = {
    id: init.id !== undefined && init.id.length > 0 ? init.id : novelId(),
    kind: init.kind,
    title: init.title,
    content: init.content,
    enabled: init.enabled ?? true,
    createdAt: init.now,
    updatedAt: init.now,
  };
  return m;
};

export interface NovelSuggestionInit {
  id?: string;
  sourceChapterId: string;
  kind: NovelMaterialKind;
  title: string;
  content: string;
  now: number;
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
  };
  return s;
};

// 素材 kind 显示名(中文)
export const MATERIAL_KIND_LABELS: Record<NovelMaterialKind, string> = {
  world: '世界',
  character: '人物',
  outline: '大纲',
  requirement: '写作要求',
  other: '其他',
};
