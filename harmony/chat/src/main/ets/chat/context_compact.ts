// context_compact.ts — 上下文压缩纯逻辑层(规划 + 摘要 payload + 注入选择 + 估算)
//
// Android 基准:
//   core/context/.../ConversationContextModels.kt(全文 128 行)— 模型
//   core/settings/.../PreferencesStore.kt ContextCompactionSetting(:236-244)
//     + toCompactPolicy(:247-254)
//   app/.../core/context/ConversationContextPlanner.kt(全文 316 行)
//   app/.../core/context/ContextFootprintEstimator.kt(全文 162 行)
//   app/.../core/context/CompactSummaryPayload.kt(全文 575 行)
//   app/.../core/context/ToolResultCompactor.kt(全文 16 行)
//
// 偏差登记:
//   - weightedTokenChars 按 UTF-16 码元迭代(Kotlin Char.code 语义;
//     JS for...of 会按码点迭代,代理对行为不同,故用 charCodeAt)
//   - normalizeModelOutput 的 parser: Json 参数省略(仅用于 parseToJsonElement,
//     等价 JSON.parse)
//   - ToolResultCompactor else 分支 part.toString()(Kotlin data class)
//     → JSON.stringify(与 D-048 previewText 同口径)
//   - inputFingerprint(Compose remember 缓存键,Java hashCode+Long 溢出语义)
//     裁剪 — 鸿蒙 UI 无对应缓存需求
//   - Float 运算为 JS number(双精度);ratio 比较/乘法语义一致

import type { JsonObject, JsonValue } from './json.ts';
import type {
  MessageRole, UIMessage, UIMessagePart, UIMessagePartAudio,
  UIMessagePartDocument, UIMessagePartImage, UIMessagePartMiniApp,
  UIMessagePartReasoning, UIMessagePartText, UIMessagePartTool,
  UIMessagePartVideo,
} from './message.ts';
import { makeSystemMessage } from './message.ts';
import type { MessageNode } from './conversation.ts';
import { limitContext, nodeCurrentMessage } from './conversation.ts';

// ===== 模型(ConversationContextModels.kt:7-28/:116-127) =====

export interface ConversationCompact {
  id: string;
  conversationId: string;
  summary: string;
  level: number;
  sourceStartIndex: number;
  sourceEndIndex: number;
  sourceMessageIds: string[];
  tokenEstimate: number;
  createdAt: number;
  updatedAt: number;
  status: string;
}

// CompactPolicy(:21-28)默认值;ContextCompactionSetting.keepRecentTurns=8
// (PreferencesStore.kt:241)→ toCompactPolicy 由设置层传入,默认值为 policy 自身
export interface CompactPolicy {
  enabled: boolean;
  notifyOnly: boolean;
  precompactRatio: number;
  forceRatio: number;
  keepRecentTurns: number;
  maxSummaryTokens: number;
}

export const makeCompactPolicy = (opts: Partial<CompactPolicy> = {}): CompactPolicy => ({
  enabled: opts.enabled ?? true,
  notifyOnly: opts.notifyOnly ?? false,
  precompactRatio: opts.precompactRatio ?? 0.70,
  forceRatio: opts.forceRatio ?? 0.85,
  keepRecentTurns: opts.keepRecentTurns ?? 12,
  maxSummaryTokens: opts.maxSummaryTokens ?? 2000,
});

export interface CompactPlan {
  shouldCompact: boolean;
  reason: string;
  estimatedTokens: number;
  contextWindowTokens: number;
  sourceStartIndex: number;
  sourceEndIndex: number;
  sourceMessageIds: string[];
}

// CompactPlan.sourceMessageCount(:125-126)
export const compactPlanSourceMessageCount = (p: CompactPlan): number =>
  p.shouldCompact ? p.sourceEndIndex - p.sourceStartIndex + 1 : 0;

// CompactResult(Models.kt:61-68)
export interface CompactResult {
  status: string;
  summaryId?: string;
  sourceMessageCount?: number;
  estimatedTokensBefore?: number;
  estimatedTokensAfter?: number;
  error?: string;
}

// ===== 字符权重(ContextFootprintEstimator.kt:18-31,UTF-16 码元迭代) =====

export const weightedTokenChars = (s: string): number => {
  if (s.length === 0) return 0;
  let total: number = 0;
  for (let i = 0; i < s.length; i++) {
    const cp: number = s.charCodeAt(i);
    // CJK Unified(4E00-9FFF)/Ext A(3400-4DBF)/CJK 符号+假名(3000-30FF)
    total += (cp >= 0x4E00 && cp <= 0x9FFF)
      || (cp >= 0x3400 && cp <= 0x4DBF)
      || (cp >= 0x3000 && cp <= 0x30FF) ? 4 : 1;
  }
  return total;
};

// ===== part 字符估算 =====

const toolIsExecuted = (p: UIMessagePartTool): boolean => p.output.length > 0;

// planner 口径(:255-294):tool 输出恒计入;document fileName 不加权
export const partEstimatedChars = (p: UIMessagePart): number => {
  switch (p.type) {
    case 'text': return weightedTokenChars((p as UIMessagePartText).text);
    case 'reasoning': return weightedTokenChars((p as UIMessagePartReasoning).reasoning);
    case 'tool': {
      const t = p as UIMessagePartTool;
      return weightedTokenChars(t.input)
        + t.output.reduce((acc: number, o: UIMessagePart): number => acc + partEstimatedChars(o), 0);
    }
    case 'image': return 4500;
    case 'video': return 4500;
    case 'audio': return 4500;
    case 'document': return (p as UIMessagePartDocument).fileName.length + 80;
    case 'mini_app': {
      const m = p as UIMessagePartMiniApp;
      return weightedTokenChars(m.title) + weightedTokenChars(m.description) + 120;
    }
    default: return 0;
  }
};

// estimator 口径(:111-131):tool 输出仅 executed 计入;document fileName 加权
export const partInputFootprintChars = (p: UIMessagePart): number => {
  switch (p.type) {
    case 'text': return weightedTokenChars((p as UIMessagePartText).text);
    case 'reasoning': return weightedTokenChars((p as UIMessagePartReasoning).reasoning);
    case 'tool': {
      const t = p as UIMessagePartTool;
      const outputChars: number = toolIsExecuted(t)
        ? t.output.reduce((acc: number, o: UIMessagePart): number => acc + partInputFootprintChars(o), 0)
        : 0;
      return weightedTokenChars(t.input) + outputChars;
    }
    case 'image': return 4500;
    case 'video': return 4500;
    case 'audio': return 4500;
    case 'document': return weightedTokenChars((p as UIMessagePartDocument).fileName) + 80;
    case 'mini_app': {
      const m = p as UIMessagePartMiniApp;
      return weightedTokenChars(m.title) + weightedTokenChars(m.description) + 120;
    }
    default: return 0;
  }
};

// ===== token 估算(planner:12-21 / estimator:104-109) =====

const DEFAULT_CONTEXT_WINDOW_TOKENS: number = 128000;

export const estimateTokens = (messages: UIMessage[]): number => {
  const chars: number = messages.reduce((acc: number, m: UIMessage): number =>
    acc + m.role.length
      + m.parts.reduce((a: number, p: UIMessagePart): number => a + partEstimatedChars(p), 0), 0);
  return Math.max(Math.floor(chars / 4), messages.length * 4);
};

export const estimateContextWindow = (modelContextWindowTokens: number | null): number =>
  modelContextWindowTokens !== null && modelContextWindowTokens > 0
    ? modelContextWindowTokens
    : DEFAULT_CONTEXT_WINDOW_TOKENS;

// estimator:104-109(inputFootprintChars 口径)
export const estimateMessagesFootprint = (messages: UIMessage[]): number => {
  const weighted: number = messages.reduce((acc: number, m: UIMessage): number =>
    acc + m.role.length
      + m.parts.reduce((a: number, p: UIMessagePart): number => a + partInputFootprintChars(p), 0), 0);
  return Math.max(Math.floor(weighted / 4), messages.length * 4);
};

// ===== takeMiddle(planner:311-315,Int 除法截断) =====

export const takeMiddle = (s: string, maxChars: number): string => {
  if (s.length <= maxChars) return s;
  const half: number = Math.floor(Math.max(maxChars - 40, 16) / 2);
  return `${s.slice(0, half)}\n... [${s.length - half * 2} chars omitted] ...\n${s.slice(-half)}`;
};

// ===== ToolResultCompactor(全文 16 行) =====

export const toolResultSummarize = (parts: UIMessagePart[], maxChars: number = 8000): string => {
  const raw: string = parts.map((part: UIMessagePart): string => {
    if (part.type === 'text') return (part as UIMessagePartText).text;
    if (part.type === 'tool') {
      const t = part as UIMessagePartTool;
      return `nested_tool:${t.toolName}:${toolResultSummarize(t.output, Math.floor(maxChars / 2))}`;
    }
    return JSON.stringify(part);
  }).join('\n');
  return takeMiddle(raw, maxChars);
};

// ===== summaryLine + buildCompressionInput(planner:191-199/:296-308) =====

export const partSummaryLine = (p: UIMessagePart): string => {
  switch (p.type) {
    case 'text': return `text: ${takeMiddle((p as UIMessagePartText).text, 8000)}`;
    case 'reasoning': return `reasoning_marker: ${(p as UIMessagePartReasoning).reasoning.length} chars`;
    case 'tool': {
      const t = p as UIMessagePartTool;
      return `tool: ${t.toolName} id=${t.toolCallId} executed=${toolIsExecuted(t)}`
        + ` input=${takeMiddle(t.input, 2000)} output=${toolResultSummarize(t.output)}`;
    }
    case 'image': return `image: ${(p as UIMessagePartImage).url.slice(-80)}`;
    case 'video': return `video: ${(p as UIMessagePartVideo).url.slice(-80)}`;
    case 'audio': return `audio: ${(p as UIMessagePartAudio).url.slice(-80)}`;
    case 'document': {
      const d = p as UIMessagePartDocument;
      return `document: ${d.fileName} mime=${d.mime}`;
    }
    case 'mini_app': {
      const m = p as UIMessagePartMiniApp;
      return `mini_app: ${m.title} id=${m.appId}`;
    }
    default: return '';
  }
};

export const buildCompressionInput = (messages: UIMessage[]): string =>
  messages.map((m: UIMessage): string => {
    let block: string = `message_id: ${m.id}\nrole: ${m.role.toLowerCase()}\n`;
    for (const part of m.parts) {
      block += `${partSummaryLine(part)}\n`;
    }
    return block.replace(/\n+$/, '');
  }).join('\n\n');

// ===== 文本清洗(payload:436-494) =====

const WHITESPACE: RegExp = /\s+/g;
const SENTENCE_END: RegExp = /[。！？.!?]+/g;

export const cleanRaw = (summary: string): string => {
  let s: string = summary.trim();
  if (s.startsWith('```json')) s = s.slice(7);
  else if (s.startsWith('```')) s = s.slice(3);
  if (s.endsWith('```')) s = s.slice(0, -3);
  return s.trim();
};

export const cleanMarkdown = (s: string): string =>
  s.replace(/```markdown/g, ' ').replace(/```md/g, ' ').replace(/```/g, ' ').trim();

export const cleanHumanText = (s: string): string => {
  const t: string = s
    .replace(/```json/g, ' ')
    .replace(/```markdown/g, ' ')
    .replace(/```md/g, ' ')
    .replace(/```/g, ' ')
    .replace(/\[Summary of previous conversation\]/g, ' ')
    .replace(/\[Summary\]/g, ' ');
  const joined: string = t.split('\n')
    .map((line: string): string => line.trim())
    .filter((line: string): boolean => line.length > 0)
    .join(' ');
  return joined.replace(WHITESPACE, ' ').trim();
};

export const ensureTerminalPeriod = (s: string): string => {
  if (s.trim().length === 0) return s;
  const last: string = s[s.length - 1];
  return ['.', '!', '?', '。', '！', '？'].includes(last) ? s : `${s}.`;
};

const MAX_TIMELINE_SUMMARY_CHARS: number = 1200;
const MAX_HANDOFF_CHARS: number = 24000;

const coerceTimelineSummary = (s: string): string =>
  ensureTerminalPeriod(cleanHumanText(s).slice(0, MAX_TIMELINE_SUMMARY_CHARS).trim());

export const looksLikeJsonFragment = (s: string): boolean => {
  const t: string = s.trimStart();
  return t.startsWith('{') || t.startsWith('[')
    || t.includes('"timeline_summary"') || t.includes('"handoff_markdown"')
    || t.includes('"schema_version"');
};

interface LocatedJson {
  startIndex: number;
  jsonText: string;
}

const locateJsonObject = (text: string): LocatedJson | null => {
  const start: number = text.indexOf('{');
  const end: number = text.lastIndexOf('}');
  if (start < 0 || end <= start) return null;
  return { startIndex: start, jsonText: text.substring(start, end + 1) };
};

// ===== JSON 窄化(payload:546-569) =====

const isObj = (v: JsonValue | undefined): v is JsonObject =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

const elemStringValue = (v: JsonValue | undefined): string | null => {
  if (typeof v === 'string') {
    const t: string = v.trim();
    return t.length > 0 ? t : null;
  }
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  if (Array.isArray(v)) {
    for (const item of v) {
      const s: string | null = elemStringValue(item);
      if (s !== null) return s;
    }
    return null;
  }
  if (isObj(v)) {
    for (const key of ['summary', 'text', 'content', 'description', 'title', 'value']) {
      const s: string | null = elemStringValue(v[key]);
      if (s !== null) return s;
    }
    return null;
  }
  return null;
};

const objStringValue = (obj: JsonObject, key: string): string | null => elemStringValue(obj[key]);

const elemStringList = (v: JsonValue | undefined): string[] => {
  if (Array.isArray(v)) {
    const out: string[] = [];
    for (const item of v) {
      out.push(...elemStringList(item));
    }
    return out;
  }
  if (typeof v === 'string') {
    const t: string = v.trim();
    return t.length > 0 ? [t] : [];
  }
  if (isObj(v)) {
    const s: string | null = elemStringValue(v);
    return s !== null ? [s] : [];
  }
  return [];
};

const objStringList = (obj: JsonObject, key: string): string[] => elemStringList(obj[key]);

const objIntValue = (obj: JsonObject, key: string): number | null => {
  const v: JsonValue | undefined = obj[key];
  if (typeof v === 'number' && Number.isFinite(v)) return Math.trunc(v);
  if (typeof v === 'string') {
    const n: number = Number(v);
    return Number.isFinite(n) ? Math.trunc(n) : null;
  }
  return null;
};

const objLongValue = (obj: JsonObject, key: string): number | null => objIntValue(obj, key);

const tryParseObject = (text: string): JsonObject | null => {
  try {
    const v: JsonValue = JSON.parse(text) as JsonValue;
    return isObj(v) ? v : null;
  } catch {
    return null;
  }
};

// ===== payload 模型 + parse(:16-23/:160-182) =====

export interface CompactSummaryPayload {
  schemaVersion: number;
  timelineSummary: string;
  handoffMarkdown: string;
  coveredCompactIds: string[];
  sourceMessageIds: string[];
  createdAt: number;
}

export const COMPACT_SCHEMA_VERSION: number = 2;

const hasV2Shape = (obj: JsonObject): boolean =>
  obj['timeline_summary'] !== undefined || obj['handoff_markdown'] !== undefined
  || objIntValue(obj, 'schema_version') === COMPACT_SCHEMA_VERSION;

// timelineFromParsed(:291-314):summary 键序 + 章节拼装(≤5 句,ensureTerminalPeriod)
const timelineFromParsed = (obj: JsonObject, preamble: string): string => {
  const direct: string | null = objStringValue(obj, 'timeline_summary');
  if (direct !== null) {
    const c: string = cleanHumanText(direct);
    if (c.length > 0) return c;
  }
  const display: string | null = objStringValue(obj, 'display_summary');
  if (display !== null) {
    const c: string = cleanHumanText(display);
    if (c.length > 0) return c;
  }
  const summary: string | null = objStringValue(obj, 'summary');
  if (summary !== null) {
    const c: string = cleanHumanText(summary);
    if (c.length > 0) return c;
  }
  const sentences: string[] = [];
  if (preamble.trim().length > 0) sentences.push(preamble);
  const addSection = (label: string, key: string): void => {
    if (sentences.length >= 5) return;
    const values: string[] = objStringList(obj, key)
      .map(cleanHumanText)
      .filter((v: string): boolean => v.length > 0);
    if (values.length > 0) {
      sentences.push(`${label}: ${values.slice(0, 3).join('; ')}`);
    }
  };
  addSection('Goals', 'goals');
  addSection('Facts', 'facts');
  addSection('Decisions', 'decisions');
  addSection('Open tasks', 'open_tasks');
  addSection('Tool results', 'tool_results');
  addSection('Timeline', 'timeline');
  addSection('Entities', 'entities');
  const joined: string = ensureTerminalPeriod(sentences.slice(0, 5).join('. '));
  return joined.trim().length > 0
    ? joined
    : 'Conversation history was compacted into a continuation handoff.';
};

// appendLegacyLines(:523-535)
const legacyLines = (
  obj: JsonObject, key: string, prefix: string, fallback: string | null,
): string => {
  const values: string[] = objStringList(obj, key)
    .map(cleanHumanText)
    .filter((v: string): boolean => v.length > 0);
  if (values.length === 0) {
    return fallback !== null ? `${prefix}${fallback}\n` : '';
  }
  return values.slice(0, 8).map((v: string): string => `${prefix}${v}\n`).join('');
};

// handoffFromLegacy(:316-349,模板逐字)
const handoffFromLegacy = (
  obj: JsonObject, timelineSummary: string, sourceMessageIds: string[],
): string => {
  let s: string = '## Goal\n';
  s += legacyLines(obj, 'goals', '- ', 'Continue the conversation using the compacted history.');
  s += '\n## Constraints\n';
  s += legacyLines(obj, 'entities', '- ',
    'Preserve user preferences, concrete names, files, commands, and decisions.');
  s += '\n## Progress\n';
  s += `- ${cleanHumanText(timelineSummary)}\n`;
  s += legacyLines(obj, 'timeline', '- ', null);
  s += '\n## Decisions\n';
  s += legacyLines(obj, 'decisions', '- ', 'No explicit decisions were captured.');
  s += '\n## Current State\n';
  s += legacyLines(obj, 'facts', '- ', 'Use the compacted source messages as prior context.');
  s += legacyLines(obj, 'tool_results', '- ', null);
  s += '\n## Next Steps\n';
  s += legacyLines(obj, 'open_tasks', '- ', 'Continue from the latest user request.');
  s += '\n## Critical Context\n';
  s += legacyLines(obj, 'failed_attempts', '- ', 'No failed attempts were captured.');
  if (sourceMessageIds.length > 0) {
    s += `- Source message ids: ${sourceMessageIds.join(', ')}\n`;
  }
  s += '\n## Relevant Files\n';
  s += '- None captured unless named above.\n';
  return s.trim();
};

// plainTextHandoff(:351-389,模板逐字)
const plainTextHandoff = (
  summary: string, sourceMessageIds: string[], carriedHandoffMarkdown: string = '',
): string => {
  let s: string = '## Goal\n';
  s += '- Continue the conversation using the compacted history.\n';
  s += '\n## Constraints\n';
  s += "- Preserve the user's stated preferences and unresolved requests.\n";
  s += '\n## Progress\n';
  s += `- ${cleanHumanText(summary)}\n`;
  s += '\n## Decisions\n';
  s += '- No structured decisions were captured.\n';
  s += '\n## Current State\n';
  s += '- The previous conversation segment has been compacted.\n';
  s += '\n## Next Steps\n';
  s += '- Continue from the latest visible user request.\n';
  s += '\n## Critical Context\n';
  if (sourceMessageIds.length > 0) {
    s += `- Source message ids: ${sourceMessageIds.join(', ')}\n`;
  } else {
    s += '- No source message ids were captured.\n';
  }
  s += '\n## Relevant Files\n';
  s += '- None captured unless named above.\n';
  const carried: string = cleanMarkdown(carriedHandoffMarkdown);
  if (carried.length > 0) {
    s += '\n## Previous Compact Handoffs\n';
    s += `${carried}\n`;
  }
  return s.trim();
};

const HAN_PATTERN: RegExp = /\p{Script=Han}/u;

// fallbackTimelineFromSource(:391-425,文案逐字)
const fallbackTimelineFromSource = (sourceContent: string): string => {
  const seen: Set<string> = new Set<string>();
  const snippets: string[] = [];
  for (const rawLine of sourceContent.split('\n')) {
    const line: string = rawLine.trim();
    if (line.length === 0) continue;
    if (line.startsWith('message_id:') || line.startsWith('role:')
      || line.startsWith('reasoning_marker:')) continue;
    const cleaned: string = line.replace(/^text:/, '').replace(/\s+/g, ' ').trim().slice(0, 180);
    if (cleaned.length === 0 || seen.has(cleaned)) continue;
    seen.add(cleaned);
    snippets.push(cleaned);
    if (snippets.length >= 4) break;
  }
  if (snippets.length === 0) return '';
  const chinese: boolean = HAN_PATTERN.test(sourceContent);
  if (chinese) {
    return `已压缩的历史包含 ${snippets.length} 段可读内容。`
      + `开头内容包括：“${snippets[0] ?? '无可读文本'}”。`
      + `后续内容包括：“${snippets[1] ?? snippets[0]}”。`
      + '这段历史已经被写入 handoff，后续回复会以摘要形式保留它。'
      + '如果需要追溯细节，可以通过原始消息继续展开。';
  }
  return `The compacted history contains ${snippets.length} readable snippets.`
    + ` It begins with: "${snippets[0] ?? 'no readable text'}".`
    + ` It also includes: "${snippets[1] ?? snippets[0]}".`
    + ' This segment has been preserved in the handoff for continuation.'
    + ' Original messages can still be expanded if details are needed.';
};

// fallbackGenericSummary(:427-434,文案逐字)
const fallbackGenericSummary = (sourceContent: string): string =>
  HAN_PATTERN.test(sourceContent)
    ? '历史消息已经压缩完成。模型没有返回合格的结构化摘要。系统已保留被压缩消息的 ID 和基础 handoff。后续对话会继续使用这段压缩上下文。需要时仍可展开原始消息。'
    : 'Conversation history was compacted. The model did not return a valid structured summary. The system preserved source message ids and a basic handoff. Future turns can continue from this compacted context. Original messages remain expandable if needed.';

export const parseCompactSummary = (summary: string): CompactSummaryPayload | null => {
  const cleaned: string = cleanRaw(summary);
  const located: LocatedJson | null = locateJsonObject(cleaned);
  if (located === null) return null;
  const obj: JsonObject | null = tryParseObject(located.jsonText);
  if (obj === null || !hasV2Shape(obj)) return null;
  const preamble: string = cleanHumanText(cleaned.substring(0, located.startIndex));
  const timeline: string = coerceTimelineSummary(timelineFromParsed(obj, preamble));
  const handoffRaw: string | null = objStringValue(obj, 'handoff_markdown');
  const handoffCleaned: string | null = handoffRaw !== null ? cleanMarkdown(handoffRaw) : null;
  const handoff: string = handoffCleaned !== null && handoffCleaned.length > 0
    ? handoffCleaned
    : handoffFromLegacy(obj, timeline, objStringList(obj, 'source_message_ids'));
  return {
    schemaVersion: objIntValue(obj, 'schema_version') ?? COMPACT_SCHEMA_VERSION,
    timelineSummary: timeline,
    handoffMarkdown: handoff,
    coveredCompactIds: objStringList(obj, 'covered_compact_ids'),
    sourceMessageIds: objStringList(obj, 'source_message_ids'),
    createdAt: objLongValue(obj, 'created_at') ?? 0,
  };
};

// timelineSummary(:184-198)
export const compactTimelineSummary = (summary: string): string | null => {
  const payload: CompactSummaryPayload | null = parseCompactSummary(summary);
  if (payload !== null && payload.timelineSummary.trim().length > 0) {
    return payload.timelineSummary;
  }
  const cleaned: string = cleanRaw(summary);
  if (cleaned.length === 0) return null;
  const located: LocatedJson | null = locateJsonObject(cleaned);
  const preamble: string = located !== null
    ? cleanHumanText(cleaned.substring(0, located.startIndex))
    : '';
  if (preamble.length > 0) return coerceTimelineSummary(preamble);
  const parsed: JsonObject | null = located !== null ? tryParseObject(located.jsonText) : null;
  if (parsed !== null) return coerceTimelineSummary(timelineFromParsed(parsed, ''));
  if (looksLikeJsonFragment(cleaned)) return null;
  const human: string = cleanHumanText(cleaned);
  return human.length > 0 ? coerceTimelineSummary(human) : null;
};

// searchableText(:200-209)
export const compactSearchableText = (summary: string): string => {
  const payload: CompactSummaryPayload | null = parseCompactSummary(summary);
  if (payload !== null) {
    return `${payload.timelineSummary}\n${payload.handoffMarkdown}\n`.trim();
  }
  return compactTimelineSummary(summary) ?? summary;
};

// injectionText(:211-233,模板逐字)
export const compactInjectionText = (compact: ConversationCompact): string =>
  compactInjectionTextParts(compact.id, compact.summary, compact.sourceMessageIds);

export const compactInjectionTextParts = (
  id: string, summary: string, sourceMessageIds: string[],
): string => {
  const payload: CompactSummaryPayload | null = parseCompactSummary(summary);
  const payloadHandoff: string | null = payload !== null && payload.handoffMarkdown.trim().length > 0
    ? payload.handoffMarkdown
    : null;
  const handoff: string = payloadHandoff
    ?? compactTimelineSummary(summary)
    ?? cleanHumanText(summary);
  const covered: string[] = payload !== null ? payload.coveredCompactIds : [];
  let s: string = `[Conversation compact handoff: ${id}]\n`;
  s += `Source message ids: ${sourceMessageIds.join(', ')}\n`;
  if (covered.length > 0) {
    s += `Covered compact ids: ${covered.join(', ')}\n`;
  }
  s += `\n${handoff.trim()}`;
  return s.trim();
};

// validCompletedCompacts(:235-245,稳定排序 sourceEndIndex → createdAt)
export const validCompletedCompacts = (
  activeCompacts: ConversationCompact[], existingMessageIds: Set<string>,
): ConversationCompact[] =>
  activeCompacts
    .filter((c: ConversationCompact): boolean =>
      c.status === 'completed' && c.sourceMessageIds.length > 0
      && c.sourceMessageIds.every((id: string): boolean => existingMessageIds.has(id)))
    .sort((a: ConversationCompact, b: ConversationCompact): number =>
      a.sourceEndIndex - b.sourceEndIndex || a.createdAt - b.createdAt);

// transitiveCoveredIds(:274-289,不含自身)
const transitiveCoveredIds = (
  compact: ConversationCompact, byId: Map<string, ConversationCompact>,
): Set<string> => {
  const seen: Set<string> = new Set<string>();
  const visit = (id: string): void => {
    if (seen.has(id)) return;
    seen.add(id);
    const parent: ConversationCompact | undefined = byId.get(id);
    if (parent !== undefined) {
      const payload: CompactSummaryPayload | null = parseCompactSummary(parent.summary);
      for (const cid of payload !== null ? payload.coveredCompactIds : []) visit(cid);
    }
  };
  const seed: CompactSummaryPayload | null = parseCompactSummary(compact.summary);
  for (const cid of seed !== null ? seed.coveredCompactIds : []) visit(cid);
  return seen;
};

// selectCompactsForInjection(:247-260)
export const selectCompactsForInjection = (
  activeCompacts: ConversationCompact[], existingMessageIds: Set<string>,
): ConversationCompact[] => {
  const completed: ConversationCompact[] = validCompletedCompacts(activeCompacts, existingMessageIds);
  if (completed.length === 0) return [];
  let latestPayloadCompact: ConversationCompact | null = null;
  for (let i = completed.length - 1; i >= 0; i--) {
    const payload: CompactSummaryPayload | null = parseCompactSummary(completed[i].summary);
    if (payload !== null && payload.handoffMarkdown.trim().length > 0) {
      latestPayloadCompact = completed[i];
      break;
    }
  }
  if (latestPayloadCompact === null) return completed;
  const byId: Map<string, ConversationCompact> = new Map<string, ConversationCompact>();
  for (const c of completed) byId.set(c.id, c);
  const covered: Set<string> = transitiveCoveredIds(latestPayloadCompact, byId);
  const kept: ConversationCompact[] = completed.filter(
    (c: ConversationCompact): boolean =>
      c.id !== (latestPayloadCompact as ConversationCompact).id && !covered.has(c.id));
  kept.push(latestPayloadCompact);
  return kept;
};

// sentenceCount(:267-272)/isHighQualityPayload(:262-265)
export const compactSentenceCount = (text: string): number => {
  const cleaned: string = cleanHumanText(text);
  if (cleaned.length === 0) return 0;
  const matches: RegExpMatchArray | null = cleaned.match(SENTENCE_END);
  return Math.max(matches !== null ? matches.length : 0, 1);
};

export const isHighQualityPayload = (summary: string): boolean => {
  const payload: CompactSummaryPayload | null = parseCompactSummary(summary);
  if (payload === null) return false;
  return payload.handoffMarkdown.length >= 80
    && compactSentenceCount(payload.timelineSummary) >= 4;
};

// ===== normalizeModelOutput(:86-128) =====

const LEGACY_KEYS: string[] = [
  'goals', 'facts', 'decisions', 'open_tasks', 'failed_attempts',
  'tool_results', 'entities', 'timeline', 'source_message_ids',
];
const PAYLOAD_KEYS: Set<string> = new Set<string>([
  'schema_version', 'timeline_summary', 'handoff_markdown',
  'covered_compact_ids', 'source_message_ids', 'created_at',
]);

const putStringArrayField = (obj: JsonObject, key: string, values: string[]): void => {
  obj[key] = values.filter((v: string): boolean => v.trim().length > 0);
};

// putLegacyFields(:502-521)
const putLegacyFields = (out: JsonObject, parsed: JsonObject, sourceMessageIds: string[]): void => {
  for (const key of LEGACY_KEYS) {
    if (key === 'source_message_ids') {
      putStringArrayField(out, key, sourceMessageIds);
    } else {
      const value: JsonValue | undefined = parsed[key];
      if (value === undefined) {
        out[key] = [];
      } else if (Array.isArray(value)) {
        out[key] = value;
      } else {
        out[key] = [value];
      }
    }
  }
  for (const key of Object.keys(parsed)) {
    if (!LEGACY_KEYS.includes(key) && !PAYLOAD_KEYS.has(key)) {
      out[key] = parsed[key];
    }
  }
};

const distinctStrings = (list: string[]): string[] => [...new Set<string>(list)];

export const normalizeCompactModelOutput = (
  summary: string, sourceMessageIds: string[],
  coveredCompactIds: string[], createdAt: number,
): string | null => {
  const cleaned: string = cleanRaw(summary);
  if (cleaned.length === 0) return null;
  const located: LocatedJson | null = locateJsonObject(cleaned);
  const parsed: JsonObject | null = located !== null ? tryParseObject(located.jsonText) : null;
  const preamble: string = located !== null
    ? cleanHumanText(cleaned.substring(0, located.startIndex))
    : '';
  let timelineSummary: string;
  if (parsed !== null) {
    timelineSummary = timelineFromParsed(parsed, preamble);
  } else if (looksLikeJsonFragment(cleaned)) {
    timelineSummary = 'Conversation history was compacted, but the model returned malformed JSON.';
  } else {
    const human: string = cleanHumanText(cleaned);
    timelineSummary = human.length > 0
      ? human
      : 'Conversation history was compacted, but the model returned no readable summary.';
  }
  timelineSummary = coerceTimelineSummary(timelineSummary);
  let handoffMarkdown: string;
  if (parsed !== null) {
    const raw: string | null = objStringValue(parsed, 'handoff_markdown');
    const cleanedHandoff: string | null = raw !== null ? cleanMarkdown(raw) : null;
    handoffMarkdown = cleanedHandoff !== null && cleanedHandoff.length > 0
      ? cleanedHandoff
      : handoffFromLegacy(parsed, timelineSummary, sourceMessageIds);
  } else {
    handoffMarkdown = plainTextHandoff(timelineSummary, sourceMessageIds);
  }
  handoffMarkdown = handoffMarkdown.slice(0, MAX_HANDOFF_CHARS);
  const out: JsonObject = {
    schema_version: COMPACT_SCHEMA_VERSION,
    timeline_summary: timelineSummary,
    handoff_markdown: handoffMarkdown,
    covered_compact_ids: distinctStrings(coveredCompactIds),
    source_message_ids: distinctStrings(sourceMessageIds),
    created_at: createdAt,
  };
  if (parsed !== null) putLegacyFields(out, parsed, sourceMessageIds);
  return JSON.stringify(out);
};

// ===== fallbackPayload(:130-158) =====

export const compactFallbackPayload = (
  summary: string, sourceMessageIds: string[],
  coveredCompactIds: string[] = [], createdAt: number = Date.now(),
  sourceContent: string = '', carriedHandoffMarkdown: string = '',
): string => {
  let timelineSummary: string = looksLikeJsonFragment(cleanRaw(summary))
    ? ''
    : cleanHumanText(summary);
  if (timelineSummary.length === 0) timelineSummary = fallbackTimelineFromSource(sourceContent);
  if (timelineSummary.length === 0) timelineSummary = fallbackGenericSummary(sourceContent);
  timelineSummary = coerceTimelineSummary(timelineSummary);
  const safeCoveredCompactIds: string[] = carriedHandoffMarkdown.trim().length > 0
    ? coveredCompactIds
    : [];
  const out: JsonObject = {
    schema_version: COMPACT_SCHEMA_VERSION,
    timeline_summary: timelineSummary,
    handoff_markdown:
      plainTextHandoff(timelineSummary, sourceMessageIds, carriedHandoffMarkdown)
        .slice(0, MAX_HANDOFF_CHARS),
    covered_compact_ids: distinctStrings(safeCoveredCompactIds),
    source_message_ids: distinctStrings(sourceMessageIds),
    created_at: createdAt,
  };
  return JSON.stringify(out);
};

// ===== remapCoveredCompactIds(:53-84,fork 复制用) =====

const replaceCompactIdReferences = (text: string, idMapping: Map<string, string>): string => {
  let out: string = text;
  for (const [oldId, newId] of idMapping) {
    out = out.split(oldId).join(newId);
  }
  return out;
};

export const remapCoveredCompactIds = (
  summary: string, idMapping: Map<string, string>,
): string => {
  if (idMapping.size === 0) return summary;
  const cleaned: string = cleanRaw(summary);
  const located: LocatedJson | null = locateJsonObject(cleaned);
  if (located === null) return summary;
  const obj: JsonObject | null = tryParseObject(located.jsonText);
  if (obj === null || !hasV2Shape(obj)) return summary;
  const remappedCoveredIds: string[] = distinctStrings(
    objStringList(obj, 'covered_compact_ids')
      .map((id: string): string => idMapping.get(id) ?? '')
      .filter((id: string): boolean => id.length > 0));
  const remapped: JsonObject = {};
  for (const key of Object.keys(obj)) {
    if (key === 'covered_compact_ids') continue;
    if (key === 'handoff_markdown') {
      const sv: string | null = elemStringValue(obj[key]);
      const base: string = sv !== null ? sv : JSON.stringify(obj[key]);
      remapped[key] = replaceCompactIdReferences(base, idMapping);
    } else {
      remapped[key] = obj[key];
    }
  }
  putStringArrayField(remapped, 'covered_compact_ids', remappedCoveredIds);
  const remappedText: string = JSON.stringify(remapped);
  const preamble: string = cleaned.substring(0, located.startIndex).trim();
  return preamble.length === 0 ? remappedText : `${preamble}\n${remappedText}`;
};

// ===== planCompaction(planner:23-78) =====

const nodeTools = (n: MessageNode): UIMessagePartTool[] =>
  nodeCurrentMessage(n).parts
    .filter((p: UIMessagePart): boolean => p.type === 'tool')
    .map((p: UIMessagePart): UIMessagePartTool => p as UIMessagePartTool);

const skippedPlan = (
  reason: string, nodes: MessageNode[], modelContextWindowTokens: number | null,
): CompactPlan => {
  const messages: UIMessage[] = nodes.map(nodeCurrentMessage);
  return {
    shouldCompact: false,
    reason,
    estimatedTokens: estimateTokens(messages),
    contextWindowTokens: estimateContextWindow(modelContextWindowTokens),
    sourceStartIndex: 0,
    sourceEndIndex: -1,
    sourceMessageIds: [],
  };
};

export const planCompaction = (
  nodes: MessageNode[], activeCompacts: ConversationCompact[],
  policy: CompactPolicy, modelContextWindowTokens: number | null,
  extraTokenEstimate: number = 0,
): CompactPlan => {
  if (!policy.enabled || nodes.length === 0) {
    return skippedPlan('disabled', nodes, modelContextWindowTokens);
  }
  const messages: UIMessage[] = nodes.map(nodeCurrentMessage);
  const estimatedTokens: number =
    estimateTokens(messages) + Math.max(extraTokenEstimate, 0);
  const contextWindow: number = estimateContextWindow(modelContextWindowTokens);
  const ratio: number = estimatedTokens / contextWindow;
  if (ratio < policy.precompactRatio) {
    return {
      shouldCompact: false, reason: 'below_threshold',
      estimatedTokens, contextWindowTokens: contextWindow,
      sourceStartIndex: 0, sourceEndIndex: -1, sourceMessageIds: [],
    };
  }
  const keepCount: number = Math.max(policy.keepRecentTurns * 2, 2);
  const sourceEnd: number = Math.min(nodes.length - 1 - keepCount, nodes.length - 1);
  if (sourceEnd < 1) {
    return {
      shouldCompact: false, reason: 'not_enough_history',
      estimatedTokens, contextWindowTokens: contextWindow,
      sourceStartIndex: 0, sourceEndIndex: -1, sourceMessageIds: [],
    };
  }
  const completedEnds: number[] = activeCompacts
    .filter((c: ConversationCompact): boolean => c.status === 'completed')
    .map((c: ConversationCompact): number => c.sourceEndIndex);
  const latestCoveredEnd: number = completedEnds.length > 0 ? Math.max(...completedEnds) : -1;
  if (latestCoveredEnd >= sourceEnd) {
    return {
      shouldCompact: false, reason: 'already_compacted',
      estimatedTokens, contextWindowTokens: contextWindow,
      sourceStartIndex: 0, sourceEndIndex: -1, sourceMessageIds: [],
    };
  }
  let start: number = Math.max(latestCoveredEnd + 1, 0);
  let end: number = sourceEnd;
  while (start <= end
    && nodeCurrentMessage(nodes[start]).role === 'assistant'
    && nodeTools(nodes[start]).some((t: UIMessagePartTool): boolean => toolIsExecuted(t))) {
    start++;
  }
  while (end >= start
    && nodeTools(nodes[end]).some((t: UIMessagePartTool): boolean => !toolIsExecuted(t))) {
    end--;
  }
  if (end - start + 1 < 2) {
    return {
      shouldCompact: false, reason: 'not_enough_new_history',
      estimatedTokens, contextWindowTokens: contextWindow,
      sourceStartIndex: 0, sourceEndIndex: -1, sourceMessageIds: [],
    };
  }
  return {
    shouldCompact: true,
    reason: ratio >= policy.forceRatio ? 'force_threshold' : 'precompact_threshold',
    estimatedTokens,
    contextWindowTokens: contextWindow,
    sourceStartIndex: start,
    sourceEndIndex: end,
    sourceMessageIds: nodes.slice(start, end + 1).map(
      (n: MessageNode): string => nodeCurrentMessage(n).id),
  };
};

// ===== estimateAfterCompaction(planner:216-239)+ transitiveCompactIds(:241-253,含自身) =====

const transitiveCompactIds = (compacts: ConversationCompact[]): Set<string> => {
  if (compacts.length === 0) return new Set<string>();
  const byId: Map<string, ConversationCompact> = new Map<string, ConversationCompact>();
  for (const c of compacts) byId.set(c.id, c);
  const seen: Set<string> = new Set<string>();
  const visit = (id: string): void => {
    if (seen.has(id)) return;
    seen.add(id);
    const compact: ConversationCompact | undefined = byId.get(id);
    if (compact !== undefined) {
      const payload: CompactSummaryPayload | null = parseCompactSummary(compact.summary);
      for (const cid of payload !== null ? payload.coveredCompactIds : []) visit(cid);
    }
  };
  for (const c of compacts) visit(c.id);
  return seen;
};

const estimateAfterCompaction = (
  nodes: MessageNode[], activeCompacts: ConversationCompact[],
  plan: CompactPlan, maxSummaryTokens: number,
): number => {
  if (!plan.shouldCompact) return plan.estimatedTokens;
  const messages: UIMessage[] = nodes.map(nodeCurrentMessage);
  const existingMessageIds: Set<string> = new Set<string>(messages.map((m: UIMessage): string => m.id));
  const completedCompacts: ConversationCompact[] =
    validCompletedCompacts(activeCompacts, existingMessageIds);
  const carriedCompacts: ConversationCompact[] =
    selectCompactsForInjection(activeCompacts, existingMessageIds);
  const carriedCompactIds: Set<string> = transitiveCompactIds(carriedCompacts);
  const remainingSummaryMessages: UIMessage[] = completedCompacts
    .filter((c: ConversationCompact): boolean => !carriedCompactIds.has(c.id))
    .map((c: ConversationCompact): UIMessage => makeSystemMessage(compactInjectionText(c)));
  const coveredMessageIds: Set<string> = new Set<string>();
  for (const c of completedCompacts) {
    for (const id of c.sourceMessageIds) coveredMessageIds.add(id);
  }
  for (const id of plan.sourceMessageIds) coveredMessageIds.add(id);
  const recentMessages: UIMessage[] = messages.filter(
    (m: UIMessage): boolean => !coveredMessageIds.has(m.id));
  return estimateTokens(remainingSummaryMessages)
    + estimateTokens(recentMessages)
    + Math.max(maxSummaryTokens, 256);
};

// ===== planForceCompaction(planner:80-135) =====

export const planForceCompaction = (
  nodes: MessageNode[], activeCompacts: ConversationCompact[],
  policy: CompactPolicy, modelContextWindowTokens: number | null,
): CompactPlan => {
  const turns: number[] = [];
  {
    let currentTurns: number = Math.max(policy.keepRecentTurns, 1);
    while (currentTurns > 1) {
      turns.push(currentTurns);
      currentTurns = Math.max(Math.floor(currentTurns / 2), 1);
    }
    turns.push(1);
  }
  const distinctTurns: number[] = [...new Set<number>(turns)];
  const contextWindow: number = estimateContextWindow(modelContextWindowTokens);
  const targetTokens: number = Math.max(Math.trunc(contextWindow * policy.forceRatio), 1);
  let deepestPlan: CompactPlan | null = null;
  let lastPlan: CompactPlan | null = null;
  for (const keepRecentTurns of distinctTurns) {
    const plan: CompactPlan = planCompaction(nodes, activeCompacts, {
      ...policy,
      enabled: true,
      keepRecentTurns,
      precompactRatio: 0,
      forceRatio: Number.MAX_VALUE,
    }, modelContextWindowTokens);
    lastPlan = plan;
    if (plan.shouldCompact) {
      deepestPlan = plan;
      if (estimateAfterCompaction(nodes, activeCompacts, plan, policy.maxSummaryTokens)
        <= targetTokens) {
        return { ...plan, reason: 'force_threshold' };
      }
    }
  }
  if (deepestPlan !== null) {
    const dp: CompactPlan = deepestPlan as CompactPlan;
    return { ...dp, reason: 'force_threshold' };
  }
  if (lastPlan !== null) return lastPlan;
  return planCompaction(nodes, activeCompacts, {
    ...policy,
    enabled: true,
    precompactRatio: 0,
    forceRatio: Number.MAX_VALUE,
  }, modelContextWindowTokens);
};

// ===== prepareMessages(planner:137-163) =====

export const prepareMessagesWithCompacts = (
  messages: UIMessage[], activeCompacts: ConversationCompact[],
  policy: CompactPolicy, contextMessageSize: number,
): UIMessage[] => {
  if (!policy.enabled || activeCompacts.length === 0) {
    return limitContext(messages, contextMessageSize);
  }
  const existingMessageIds: Set<string> = new Set<string>(messages.map((m: UIMessage): string => m.id));
  const completedCompacts: ConversationCompact[] =
    validCompletedCompacts(activeCompacts, existingMessageIds);
  if (completedCompacts.length === 0) return limitContext(messages, contextMessageSize);
  const compactSummaryMessages: UIMessage[] =
    selectCompactsForInjection(activeCompacts, existingMessageIds)
      .map((c: ConversationCompact): UIMessage => makeSystemMessage(compactInjectionText(c)));
  const coveredMessageIds: Set<string> = new Set<string>();
  for (const c of completedCompacts) {
    for (const id of c.sourceMessageIds) coveredMessageIds.add(id);
  }
  const recentMessages: UIMessage[] = messages.filter(
    (m: UIMessage): boolean => !coveredMessageIds.has(m.id));
  const keepLimit: number = contextMessageSize > 0
    ? contextMessageSize
    : Math.max(policy.keepRecentTurns * 2, 12);
  return [...compactSummaryMessages, ...limitContext(recentMessages, keepLimit)];
};

// ===== fitMessagesToTokenBudget(planner:165-189) =====

export const fitMessagesToTokenBudget = (
  messages: UIMessage[], maxTokens: number,
): UIMessage[] => {
  if (maxTokens <= 0 || messages.length === 0) return messages.slice(-1);
  if (estimateTokens(messages) <= maxTokens) return messages;
  const systemMessages: UIMessage[] = messages.filter(
    (m: UIMessage): boolean => m.role === 'system');
  const tail: UIMessage[] = messages.filter(
    (m: UIMessage): boolean => m.role !== 'system');
  const selected: UIMessage[] = [];
  for (let i = tail.length - 1; i >= 0; i--) {
    selected.unshift(tail[i]);
    const candidate: UIMessage[] = [...systemMessages, ...selected];
    if (estimateTokens(candidate) > maxTokens) {
      selected.shift();
      if (selected.length === 0) {
        selected.unshift(tail[i]);
      }
      break;
    }
  }
  return [...systemMessages, ...selected];
};

// ===== UI 估算(estimator:57-102,compact 替换口径) =====

export const estimateConversationInputTokens = (
  messages: UIMessage[], activeCompacts: ConversationCompact[] = [],
): number => {
  if (activeCompacts.length === 0) return estimateMessagesFootprint(messages);
  const existingMessageIds: Set<string> = new Set<string>(messages.map((m: UIMessage): string => m.id));
  const completedCompacts: ConversationCompact[] =
    validCompletedCompacts(activeCompacts, existingMessageIds);
  if (completedCompacts.length === 0) return estimateMessagesFootprint(messages);
  const coveredMessageIds: Set<string> = new Set<string>();
  for (const c of completedCompacts) {
    for (const id of c.sourceMessageIds) coveredMessageIds.add(id);
  }
  const recentMessages: UIMessage[] = messages.filter(
    (m: UIMessage): boolean => !coveredMessageIds.has(m.id));
  const summaryMessages: UIMessage[] =
    selectCompactsForInjection(activeCompacts, existingMessageIds)
      .map((c: ConversationCompact): UIMessage => makeSystemMessage(compactInjectionText(c)));
  return estimateMessagesFootprint(summaryMessages) + estimateMessagesFootprint(recentMessages);
};

// 导出供测试与后续 engine 切片复用的内部常量
export const MAX_COMPACT_TIMELINE_SUMMARY_CHARS: number = MAX_TIMELINE_SUMMARY_CHARS;
export const MAX_COMPACT_HANDOFF_CHARS: number = MAX_HANDOFF_CHARS;
export const DEFAULT_COMPACT_CONTEXT_WINDOW_TOKENS: number = DEFAULT_CONTEXT_WINDOW_TOKENS;

// MessageRole 重导出(engine 切片用)
export type { MessageRole };
