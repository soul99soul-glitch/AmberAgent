// 模型只提出有逐字原文的事件；人物身份澄清只能由作者明确操作产生。
import type { NovelChapter, NovelMaterial } from './models.ts';
import { chapterPlotSourceDigest } from './plot_projection.ts';
import { invalidInput, invalidModelOutput } from './error.ts';

export const NOVEL_STATE_PROTOCOL_VERSION = 'amber.novel.state.v1';

export interface NovelStateEvent {
  id: string;
  chapterId: string;
  sourceDigest: string;
  quote: string;
  summary: string;
  entityRefs: string[];
}
export interface NovelStateDelta {
  protocolVersion: 'amber.novel.state.v1';
  chapterId: string;
  sourceDigest: string;
  events: NovelStateEvent[];
  unresolvedIdentityNames: string[];
}
export type NovelIdentityAction = 'create' | 'ignore' | 'merge';
export interface NovelIdentityClarification {
  mention: string;
  action: NovelIdentityAction;
  materialId: string | null;
}
export interface NovelStateChapterSource {
  chapterId: string;
  sourceDigest: string;
}
export interface NovelStructuredState {
  protocolVersion: 'amber.novel.state.v1';
  events: NovelStateEvent[];
  unresolvedIdentityNames: string[];
  identityClarifications: NovelIdentityClarification[];
  // 即使一章没有事件，也记录已分析的正文版本。
  chapterSources: NovelStateChapterSource[];
  staleChapterIds: string[];
}
export interface NovelCharacterExperience {
  materialId: string;
  title: string;
  aliases: string[];
  events: NovelStateEvent[];
}

export const emptyNovelStructuredState = (): NovelStructuredState => ({
  protocolVersion: NOVEL_STATE_PROTOCOL_VERSION, events: [], unresolvedIdentityNames: [],
  identityClarifications: [], chapterSources: [], staleChapterIds: [],
});

const stateFailure = (message: string): never => { throw invalidModelOutput(`结构化状态失败：${message}`); };
const record = (value: unknown, fields: string[], label: string): Record<string, unknown> => {
  if (!(value instanceof Object) || Array.isArray(value)) return stateFailure(`${label} 必须是对象`);
  const item: Record<string, unknown> = value as Record<string, unknown>;
  const keys: string[] = Object.keys(item);
  if (keys.length !== fields.length || !fields.every((field: string): boolean => keys.includes(field))) {
    return stateFailure(`${label} 字段不符合协议`);
  }
  return item;
};
const requiredString = (value: unknown, label: string): string => {
  if (String(value) !== value || (value as string).trim().length === 0) {
    return stateFailure(`${label} 必须是非空字符串`);
  }
  return value as string;
};
const strings = (value: unknown, label: string, rejectDuplicates: boolean = true): string[] => {
  if (!Array.isArray(value)) return stateFailure(`${label} 必须是字符串数组`);
  const result: string[] = [];
  for (const item of value) {
    const text: string = requiredString(item, label);
    if (result.includes(text)) {
      if (rejectDuplicates) return stateFailure(`${label} 不允许重复`);
    } else result.push(text);
  }
  return result;
};
const characterNames = (materials: NovelMaterial[]): Set<string> => {
  const names: Set<string> = new Set();
  for (const material of materials) {
    if (material.kind !== 'character') continue;
    names.add(material.title);
    for (const alias of material.aliases ?? []) names.add(alias);
  }
  return names;
};

const decodeEvents = (value: unknown): NovelStateEvent[] => {
  if (!Array.isArray(value)) return stateFailure('events 必须是数组');
  const ids: Set<string> = new Set();
  const events: NovelStateEvent[] = [];
  for (const raw of value) {
    const item: Record<string, unknown> = record(raw,
      ['id', 'chapterId', 'sourceDigest', 'quote', 'summary', 'entityRefs'], '事件');
    const id: string = requiredString(item.id, '事件 id');
    if (ids.has(id)) return stateFailure('事件 id 重复');
    ids.add(id);
    events.push({ id, chapterId: requiredString(item.chapterId, '事件 chapterId'),
      sourceDigest: requiredString(item.sourceDigest, '事件 sourceDigest'),
      quote: requiredString(item.quote, '事件 quote'), summary: requiredString(item.summary, '事件 summary'),
      entityRefs: strings(item.entityRefs, '事件 entityRefs') });
  }
  return events;
};

const decodeJSON = (text: string): unknown => {
  try { return JSON.parse(text) as unknown; }
  catch { return stateFailure('必须是完整严格 JSON'); }
};

export const parseNovelStateDelta = (
  text: string, chapter: NovelChapter, materials: NovelMaterial[],
): NovelStateDelta => {
  const root: Record<string, unknown> = record(decodeJSON(text),
    ['protocolVersion', 'chapterId', 'sourceDigest', 'events', 'unresolvedIdentityNames'], '状态');
  if (root.protocolVersion !== NOVEL_STATE_PROTOCOL_VERSION) return stateFailure('协议版本不匹配');
  if (chapter.discarded || root.chapterId !== chapter.id ||
    root.sourceDigest !== chapterPlotSourceDigest(chapter.content)) return stateFailure('章节原文版本不匹配');
  const materialIds: Set<string> = new Set(materials.map((item: NovelMaterial): string => item.id));
  const events: NovelStateEvent[] = decodeEvents(root.events);
  for (const item of events) {
    if (item.chapterId !== chapter.id || item.sourceDigest !== root.sourceDigest) {
      return stateFailure('事件来源与章节原文版本不匹配');
    }
    if (!chapter.content.includes(item.quote)) return stateFailure('事件 quote 必须逐字出现在章节正文');
    if (item.entityRefs.some((ref: string): boolean => !materialIds.has(ref))) {
      return stateFailure('事件 entityRefs 必须引用当前分支有效资料 ID');
    }
  }
  const names: string[] = strings(root.unresolvedIdentityNames, 'unresolvedIdentityNames', false);
  if (names.some((name: string): boolean => !chapter.content.includes(name))) {
    return stateFailure('待确认身份必须逐字出现在章节正文');
  }
  const known: Set<string> = characterNames(materials);
  return { protocolVersion: NOVEL_STATE_PROTOCOL_VERSION, chapterId: chapter.id,
    sourceDigest: root.sourceDigest as string, events,
    unresolvedIdentityNames: names.filter((name: string): boolean => !known.has(name)) };
};

// 公开工作区/冷读取都经过同一解码；旧正文证据只允许以失效历史读取。
export const parseNovelStructuredState = (
  text: string, chapters: NovelChapter[], materials: NovelMaterial[],
): NovelStructuredState => {
  const root: Record<string, unknown> = record(decodeJSON(text), ['protocolVersion', 'events',
    'unresolvedIdentityNames', 'identityClarifications', 'chapterSources', 'staleChapterIds'], '完整状态');
  if (root.protocolVersion !== NOVEL_STATE_PROTOCOL_VERSION) return stateFailure('协议版本不匹配');
  const events: NovelStateEvent[] = decodeEvents(root.events);
  const unresolved: string[] = strings(root.unresolvedIdentityNames, 'unresolvedIdentityNames');
  const stale: string[] = strings(root.staleChapterIds, 'staleChapterIds');
  if (!Array.isArray(root.chapterSources)) return stateFailure('chapterSources 必须是数组');
  const sources: NovelStateChapterSource[] = [];
  const sourceById: Map<string, string> = new Map();
  for (const raw of root.chapterSources) {
    const item: Record<string, unknown> = record(raw, ['chapterId', 'sourceDigest'], '章节来源');
    const chapterId: string = requiredString(item.chapterId, '章节来源 chapterId');
    const sourceDigest: string = requiredString(item.sourceDigest, '章节来源 sourceDigest');
    if (sourceById.has(chapterId)) return stateFailure('章节来源 chapterId 重复');
    sourceById.set(chapterId, sourceDigest);
    sources.push({ chapterId, sourceDigest });
  }
  const currentById: Map<string, NovelChapter> = new Map(chapters.map(
    (chapter: NovelChapter): [string, NovelChapter] => [chapter.id, chapter]));
  const digests: Map<string, string> = new Map(chapters.map((chapter: NovelChapter): [string, string] =>
    [chapter.id, chapterPlotSourceDigest(chapter.content)]));
  for (const event of events) {
    if (sourceById.get(event.chapterId) !== event.sourceDigest) return stateFailure('事件来源与章节来源版本不一致');
    const chapter: NovelChapter | undefined = currentById.get(event.chapterId);
    if (chapter !== undefined && event.sourceDigest === digests.get(event.chapterId) && !chapter.content.includes(event.quote)) {
      return stateFailure('当前事件 quote 必须逐字出现在章节正文');
    }
  }
  if (!Array.isArray(root.identityClarifications)) return stateFailure('identityClarifications 必须是数组');
  const clarifications: NovelIdentityClarification[] = [];
  const mentions: Set<string> = new Set();
  for (const raw of root.identityClarifications) {
    const item: Record<string, unknown> = record(raw, ['mention', 'action', 'materialId'], '身份澄清');
    const mention: string = requiredString(item.mention, '身份澄清 mention');
    if (mentions.has(mention)) return stateFailure('身份澄清 mention 重复');
    mentions.add(mention);
    if (item.action !== 'create' && item.action !== 'ignore' && item.action !== 'merge') return stateFailure('身份澄清 action 无效');
    const action: NovelIdentityAction = item.action;
    if (action === 'ignore' && item.materialId !== null) return stateFailure('忽略身份不能指定人物');
    const materialId: string | null = action === 'ignore' ? null : requiredString(item.materialId, '身份澄清 materialId');
    clarifications.push({ mention, action, materialId });
  }
  const state: NovelStructuredState = { protocolVersion: NOVEL_STATE_PROTOCOL_VERSION, events,
    unresolvedIdentityNames: unresolved, identityClarifications: clarifications,
    chapterSources: sources, staleChapterIds: stale };
  return pruneNovelStructuredState(state, chapters, materials);
};

const copyEvent = (event: NovelStateEvent): NovelStateEvent => ({
  id: event.id, chapterId: event.chapterId, sourceDigest: event.sourceDigest, quote: event.quote,
  summary: event.summary, entityRefs: event.entityRefs.slice(),
});
const copyClarification = (item: NovelIdentityClarification): NovelIdentityClarification => ({
  mention: item.mention, action: item.action, materialId: item.materialId,
});
const validClarification = (item: NovelIdentityClarification, materials: NovelMaterial[]): boolean =>
  item.action === 'ignore' ? item.materialId === null : materials.some(
    (material: NovelMaterial): boolean => material.kind === 'character' && material.id === item.materialId);

export const mergeNovelStateDelta = (
  state: NovelStructuredState, delta: NovelStateDelta, chapter: NovelChapter, materials: NovelMaterial[],
): NovelStructuredState => {
  // 再次校验提交时正文/资料，避免把已失效的预览直接应用。
  const checked: NovelStateDelta = parseNovelStateDelta(JSON.stringify(delta), chapter, materials);
  const retained: NovelStateEvent[] = state.events.filter((event: NovelStateEvent): boolean => event.chapterId !== chapter.id);
  if (checked.events.some((event: NovelStateEvent): boolean => retained.some(
    (other: NovelStateEvent): boolean => other.id === event.id))) return stateFailure('事件 id 与其他章节重复');
  const clarifications: NovelIdentityClarification[] = state.identityClarifications.filter(
    (item: NovelIdentityClarification): boolean => validClarification(item, materials));
  const resolved: Set<string> = new Set(clarifications.map((item: NovelIdentityClarification): string => item.mention));
  const names: Set<string> = new Set(state.unresolvedIdentityNames.concat(checked.unresolvedIdentityNames));
  const known: Set<string> = characterNames(materials);
  return { protocolVersion: NOVEL_STATE_PROTOCOL_VERSION,
    events: retained.concat(checked.events).map(copyEvent),
    unresolvedIdentityNames: Array.from(names).filter((name: string): boolean => !resolved.has(name) && !known.has(name)),
    identityClarifications: clarifications.map(copyClarification),
    chapterSources: state.chapterSources.filter((item: NovelStateChapterSource): boolean => item.chapterId !== chapter.id)
      .map((item: NovelStateChapterSource): NovelStateChapterSource => ({ chapterId: item.chapterId, sourceDigest: item.sourceDigest }))
      .concat({ chapterId: chapter.id, sourceDigest: checked.sourceDigest }),
    staleChapterIds: state.staleChapterIds.filter((id: string): boolean => id !== chapter.id),
  };
};

// 服务已校验分块范围后，用本函数累计同章分块；整章替换仍用 merge。
export const appendNovelStateDelta = (
  state: NovelStructuredState, delta: NovelStateDelta, chapter: NovelChapter, materials: NovelMaterial[],
): NovelStructuredState => {
  const checked: NovelStateDelta = parseNovelStateDelta(JSON.stringify(delta), chapter, materials);
  const accumulated: NovelStateEvent[] = state.events.filter((event: NovelStateEvent): boolean => event.chapterId === chapter.id);
  for (const event of checked.events) {
    const previous: NovelStateEvent | undefined = accumulated.find((item: NovelStateEvent): boolean => item.id === event.id);
    if (previous === undefined) accumulated.push(event);
    else if (JSON.stringify(previous) !== JSON.stringify(event)) return stateFailure('同章分块事件 id 冲突');
  }
  const combined: NovelStateDelta = { protocolVersion: NOVEL_STATE_PROTOCOL_VERSION, chapterId: chapter.id,
    sourceDigest: checked.sourceDigest, events: accumulated, unresolvedIdentityNames: checked.unresolvedIdentityNames };
  return mergeNovelStateDelta(state, combined, chapter, materials);
};

export const pruneNovelStructuredState = (
  state: NovelStructuredState, chapters: NovelChapter[], materials: NovelMaterial[],
): NovelStructuredState => {
  const current: NovelChapter[] = chapters.filter((item: NovelChapter): boolean => !item.discarded);
  const byId: Map<string, NovelChapter> = new Map(current.map((item: NovelChapter): [string, NovelChapter] => [item.id, item]));
  const digests: Map<string, string> = new Map(current.map((item: NovelChapter): [string, string] =>
    [item.id, chapterPlotSourceDigest(item.content)]));
  const stale: Set<string> = new Set(state.staleChapterIds.filter((id: string): boolean => byId.has(id)));
  const mismatched: Set<string> = new Set();
  const sources: NovelStateChapterSource[] = state.chapterSources.filter((source: NovelStateChapterSource): boolean => {
    const chapter: NovelChapter | undefined = byId.get(source.chapterId);
    if (chapter === undefined) return false;
    if (source.sourceDigest !== digests.get(chapter.id)) { mismatched.add(chapter.id); return false; }
    return true;
  });
  let affected: boolean = false;
  for (const chapter of current) {
    if (mismatched.has(chapter.id)) affected = true;
    if (affected) stale.add(chapter.id);
  }
  const checkedIds: Set<string> = new Set(sources.map((item: NovelStateChapterSource): string => item.chapterId));
  const materialIds: Set<string> = new Set(materials.map((item: NovelMaterial): string => item.id));
  const events: NovelStateEvent[] = state.events.filter((event: NovelStateEvent): boolean => {
    const chapter: NovelChapter | undefined = byId.get(event.chapterId);
    return chapter !== undefined && checkedIds.has(chapter.id) && !stale.has(chapter.id) &&
      event.sourceDigest === digests.get(chapter.id) && event.quote.trim().length > 0 &&
      chapter.content.includes(event.quote) && event.entityRefs.every((id: string): boolean => materialIds.has(id));
  });
  const clarifications: NovelIdentityClarification[] = state.identityClarifications.filter(
    (item: NovelIdentityClarification): boolean => validClarification(item, materials));
  const lost: string[] = state.identityClarifications.filter((item: NovelIdentityClarification): boolean =>
    !validClarification(item, materials)).map((item: NovelIdentityClarification): string => item.mention);
  const resolved: Set<string> = new Set(clarifications.map((item: NovelIdentityClarification): string => item.mention));
  const known: Set<string> = characterNames(materials);
  const names: Set<string> = new Set(state.unresolvedIdentityNames.concat(lost));
  return { protocolVersion: NOVEL_STATE_PROTOCOL_VERSION, events: events.map(copyEvent),
    unresolvedIdentityNames: Array.from(names).filter((name: string): boolean => !resolved.has(name) && !known.has(name) &&
      current.some((chapter: NovelChapter): boolean => checkedIds.has(chapter.id) && !stale.has(chapter.id) && chapter.content.includes(name))),
    identityClarifications: clarifications.map(copyClarification),
    chapterSources: sources.map((item: NovelStateChapterSource): NovelStateChapterSource => ({ chapterId: item.chapterId, sourceDigest: item.sourceDigest })),
    staleChapterIds: current.filter((chapter: NovelChapter): boolean => stale.has(chapter.id))
      .map((chapter: NovelChapter): string => chapter.id),
  };
};

export const invalidateNovelStructuredState = (
  before: NovelChapter[], after: NovelChapter[], state: NovelStructuredState,
): NovelStructuredState => {
  const previous: NovelChapter[] = before.filter((item: NovelChapter): boolean => !item.discarded);
  const current: NovelChapter[] = after.filter((item: NovelChapter): boolean => !item.discarded);
  const stale: Set<string> = new Set(state.staleChapterIds);
  let affected: boolean = false;
  for (let i: number = 0; i < current.length; i++) {
    if (previous[i]?.id !== current[i].id || previous[i]?.content !== current[i].content) affected = true;
    if (affected) stale.add(current[i].id);
  }
  const activeIds: Set<string> = new Set(current.map((item: NovelChapter): string => item.id));
  return { protocolVersion: NOVEL_STATE_PROTOCOL_VERSION,
    events: state.events.filter((event: NovelStateEvent): boolean => activeIds.has(event.chapterId)).map(copyEvent),
    unresolvedIdentityNames: state.unresolvedIdentityNames.slice(),
    identityClarifications: state.identityClarifications.map(copyClarification),
    chapterSources: state.chapterSources.filter((item: NovelStateChapterSource): boolean => activeIds.has(item.chapterId))
      .map((item: NovelStateChapterSource): NovelStateChapterSource => ({ chapterId: item.chapterId, sourceDigest: item.sourceDigest })),
    staleChapterIds: current.filter((item: NovelChapter): boolean => stale.has(item.id)).map((item: NovelChapter): string => item.id),
  };
};

export const projectNovelCharacterExperiences = (
  state: NovelStructuredState, chapters: NovelChapter[], materials: NovelMaterial[],
): NovelCharacterExperience[] => {
  const checked: NovelStructuredState = pruneNovelStructuredState(state, chapters, materials);
  const order: Map<string, number> = new Map(chapters.map((chapter: NovelChapter, index: number): [string, number] => [chapter.id, index]));
  return materials.filter((item: NovelMaterial): boolean => item.kind === 'character').map(
    (material: NovelMaterial): NovelCharacterExperience => ({ materialId: material.id, title: material.title,
      aliases: (material.aliases ?? []).slice(), events: checked.events.filter(
        (event: NovelStateEvent): boolean => event.entityRefs.includes(material.id)).sort(
        (left: NovelStateEvent, right: NovelStateEvent): number => (order.get(left.chapterId) ?? 0) - (order.get(right.chapterId) ?? 0)) }));
};

export const applyNovelIdentityClarification = (
  state: NovelStructuredState, mention: string, action: NovelIdentityAction,
  materialId: string | null, materials: NovelMaterial[],
): NovelStructuredState => {
  if (!state.unresolvedIdentityNames.includes(mention)) throw invalidInput('身份候选已变化，请刷新后确认');
  if (action !== 'create' && action !== 'ignore' && action !== 'merge') throw invalidInput('身份操作无效');
  const clarification: NovelIdentityClarification = { mention, action, materialId };
  if (!validClarification(clarification, materials)) throw invalidInput('身份澄清必须选择有效人物资料；忽略操作不能指定人物');
  return { protocolVersion: NOVEL_STATE_PROTOCOL_VERSION, events: state.events.map(copyEvent),
    unresolvedIdentityNames: state.unresolvedIdentityNames.filter((name: string): boolean => name !== mention),
    identityClarifications: state.identityClarifications.filter((item: NovelIdentityClarification): boolean => item.mention !== mention)
      .map(copyClarification).concat(clarification),
    chapterSources: state.chapterSources.map((item: NovelStateChapterSource): NovelStateChapterSource => ({ chapterId: item.chapterId, sourceDigest: item.sourceDigest })),
    staleChapterIds: state.staleChapterIds.slice(),
  };
};
