// memory_extractor — MemoryExtractor.kt 移植(D-085d)
//
// Android 基准: core/memory/extraction/MemoryExtractor.kt(全文 292 行)
//   - 门序列(:47-100):worker 关闭 → 'Memory worker disabled.';120s 去抖 →
//     'Debounced.';日配额(今日 EXTRACTION_STARTED 计数 ≥ maxDailyRuns(coerce≥1))
//     → 'Daily memory worker limit reached.';模型空 → 'No memory worker model
//     available.';provider 空 → 'Memory worker model provider not found.'
//     (均 EXTRACTION_SKIPPED + messageCount)
//   - todayStart = now - now % 86_400_000(:67 — UTC 日界,逐字)
//   - 主体(runCatching):takeLast(16) 消息 + prompt(locale) + generateText →
//     parseCandidates → filter(对全量 active records)→ rejected addCandidates;
//     accepted 逐个:shouldAutoWriteCandidate → addMemory + MEMORY_CREATED/
//     DURABLE_MEMORY_CREATED(autoWriteEventMessage)否则 addCandidate +
//     CANDIDATE_CREATED(message=reason)
//   - 失败 → EXTRACTION_FAILED(message=error.message,durationMs)
// 偏差登记:
//   - resolveMemoryModel(:184-193,worker.modelId/followCompressModel/compressModelId/
//     chatModelId 任务模型解析)→ deps.resolveWorkerModel;鸿蒙任务模型设置未移植
//     (title/suggestion 同偏差类,entry 以 chat 模型顶替 — D-025 登记)
//   - JSON 解析失败的错误文本 = JS SyntaxError 消息(非 Kotlin 异常文本)
//   - lastRunAt ConcurrentHashMap → 模块级 Map(单线程 JS 无并发竞争)

import type { Conversation } from './conversation.ts';
import { currentMessages } from './conversation.ts';
import type { UIMessage, UIMessagePartTool } from './message.ts';

import type {
  MemoryCandidate, MemoryEvent, MemoryEventType, MemoryKind, MemoryRecord, MemoryScope,
} from './memory_models.ts';
import { makeMemoryCandidate, makeMemoryEvent } from './memory_models.ts';

import { buildMemoryExtractionPrompt } from './memory_extraction_prompt.ts';
import { isSensitiveMemoryContent } from './memory_prompt_builder.ts';
import { deriveMemoryExpiresAt } from './memory_time_anchor.ts';
import type { MemoryAddParams } from './memory_write.ts';
import type { MemoryExtractionAction, MemoryExtractionActionKind, MemoryExtractionApplyResult } from './memory_extraction_actions.ts';
import { collectMemoryExtractionSources, isGroundedMemoryRewrite, memoryExpiryOn, normalizeMemoryRelativeDates } from './memory_extraction_actions.ts';

export const SHORT_TERM_PROJECT_AUTO_WRITE_CONFIDENCE: number = 0.72;
export const DURABLE_AUTO_WRITE_CONFIDENCE: number = 0.85;

// ===== parse 层(:195-253) =====

export interface ParsedMemoryCandidate {
  candidate: MemoryCandidate;
  explicitScope: boolean;
  explicitKind: boolean;
  action: MemoryExtractionActionKind;
  sourceMessageId: string | null;
  evidence: string;
  targetId: number | null;
  expiresOn: string | null;
  declaredSensitive: boolean;
}

const MEMORY_SCOPE_WIRES: string[] = ['core', 'short_term', 'long_term'];
const MEMORY_KIND_WIRES: string[] = ['user', 'feedback', 'project', 'reference', 'routine', 'note'];

const scopeFromWireOrDefault = (v: string | null): MemoryScope => {
  if (v === 'core') return 'core';
  if (v === 'short_term') return 'short_term';
  return 'long_term'; // MemoryScope.fromWireName 兜底
};

const kindFromWireOrDefault = (v: string | null): MemoryKind => {
  if (v !== null && MEMORY_KIND_WIRES.indexOf(v) >= 0) return v as MemoryKind;
  return 'note';
};

// contentOrNull?.toLongOrNull 语义(仅整数文本)
const longOrNull = (v: unknown): number | null => {
  if (typeof v === 'number') return Number.isInteger(v) ? v : null;
  if (typeof v === 'string' && /^-?\d+$/.test(v)) {
    const n: number = Number.parseInt(v, 10);
    return Number.isSafeInteger(n) ? n : null;
  }
  return null;
};

const floatOrNull = (v: unknown): number | null => {
  if (typeof v === 'number') return Number.isFinite(v) ? v : null;
  if (typeof v === 'string') {
    // 严格整串浮点(kotlinx floatOrNull 语义):parseFloat 前缀解析会把
    // "0.86oops" 当 0.86,污染 confidence 自动入库
    if (!/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(v.trim())) return null;
    const n: number = Number.parseFloat(v.trim());
    return Number.isFinite(n) ? n : null;
  }
  return null;
};

const stringOrNull = (v: unknown): string | null => typeof v === 'string' ? v : null;

// resolveCandidateExpiresAt(:281-289):expiresInDays → now + days*86400000;
//   否则 scope==short_term 时 deriveExpiresAt(内容时间锚)
export const resolveCandidateExpiresAt = (
  content: string, scope: MemoryScope, expiresInDays: number | null,
  now: number = Date.now(),
): number | null => {
  if (expiresInDays !== null) return now + expiresInDays * 86_400_000;
  if (scope === 'short_term') return deriveMemoryExpiresAt(content, now);
  return null;
};

// parseCandidates(:195-235)— 围栏剥离 + 首尾花括号截取 + take(5) +
//   content 空白跳过 + confidence ?: 0.55 + explicit 标志
export const parseMemoryCandidates = (
  raw: string, conversationId: string, sourceMessageIds: string[],
  now: number = Date.now(),
): ParsedMemoryCandidate[] => {
  let cleaned: string = raw.trim();
  if (cleaned.startsWith('```json')) cleaned = cleaned.substring(7);
  else if (cleaned.startsWith('```')) cleaned = cleaned.substring(3);
  if (cleaned.endsWith('```')) cleaned = cleaned.substring(0, cleaned.length - 3);
  cleaned = cleaned.trim();
  const start: number = cleaned.indexOf('{');
  const end: number = cleaned.lastIndexOf('}');
  if (start >= 0 && end > start) cleaned = cleaned.substring(start, end + 1);
  const parsed: unknown = JSON.parse(cleaned);
  // 根非 JsonObject → 抛错(Kotlin .jsonObject getter 同语义)
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new Error('Element is not a JsonObject');
  }
  const root: Record<string, unknown> = parsed as Record<string, unknown>;
  const list: unknown = root['candidates'];
  // Kotlin jsonArray/jsonObject getter:类型不符 → 抛错(由 runCatching →
  //   EXTRACTION_FAILED 吞掉),非跳过
  if (list !== undefined && !Array.isArray(list)) {
    throw new Error('Element is not a JsonArray');
  }
  const out: ParsedMemoryCandidate[] = [];
  for (const item of ((list as unknown[] | undefined) ?? []).slice(0, 5)) {
    if (typeof item !== 'object' || item === null || Array.isArray(item)) {
      continue;
    }
    const obj: Record<string, unknown> = item as Record<string, unknown>;
    const actionValue: string = stringOrNull(obj['action']) ?? 'add';
    if (!['add', 'update', 'invalidate', 'confirm', 'noop'].includes(actionValue)) continue;
    const content: string = (stringOrNull(obj['content']) ?? '').trim();
    if (content.length === 0) continue;
    const expiresInDays: number | null = longOrNull(obj['expires_in_days']);
    const scopeValue: string | null = stringOrNull(obj['scope']);
    const kindValue: string | null = stringOrNull(obj['kind']);
    if (kindValue === 'topic') continue;
    const scope: MemoryScope = scopeFromWireOrDefault(scopeValue);
    out.push({
      action: actionValue as MemoryExtractionActionKind,
      sourceMessageId: stringOrNull(obj['source_message_id']) ?? stringOrNull(obj['sourceMessageId']),
      evidence: (stringOrNull(obj['evidence']) ?? content).trim(),
      targetId: longOrNull(obj['update_memory_id'] ?? obj['updateMemoryId']),
      expiresOn: stringOrNull(obj['expires_on'] ?? obj['expiresOn']),
      declaredSensitive: obj['sensitive'] === true,
      explicitScope: scopeValue !== null && MEMORY_SCOPE_WIRES.indexOf(scopeValue) >= 0,
      explicitKind: kindValue !== null && MEMORY_KIND_WIRES.indexOf(kindValue) >= 0,
      candidate: makeMemoryCandidate({
        content,
        scope,
        kind: kindFromWireOrDefault(kindValue),
        confidence: floatOrNull(obj['confidence']) ?? 0.55,
        reason: stringOrNull(obj['reason']) ?? '',
        sourceConversationId: conversationId,
        sourceMessageIds,
        evidence: (stringOrNull(obj['evidence']) ?? content).trim(),
        sensitive: obj['sensitive'] === true,
        createdAt: now,
        expiresAt: resolveCandidateExpiresAt(content, scope, expiresInDays, now),
      }),
    });
  }
  return out;
};

// ===== auto-write 判定(:243-279) =====

export const isDurableAutoWriteCandidate = (candidate: MemoryCandidate): boolean =>
  candidate.scope === 'long_term'
  && (candidate.kind === 'user' || candidate.kind === 'feedback')
  && candidate.confidence >= DURABLE_AUTO_WRITE_CONFIDENCE;

export const autoWriteEventMessage = (candidate: MemoryCandidate): string => {
  if (isDurableAutoWriteCandidate(candidate) && candidate.kind === 'user') {
    return 'Auto-created durable user memory.';
  }
  if (isDurableAutoWriteCandidate(candidate) && candidate.kind === 'feedback') {
    return 'Auto-created durable feedback memory.';
  }
  return 'Auto-created short-term project memory.';
};

export const shouldAutoWriteCandidate = (
  candidate: MemoryCandidate, explicitScope: boolean, explicitKind: boolean,
): boolean => {
  if (candidate.sensitive || isSensitiveMemoryContent(candidate.content)) return false;
  if (!explicitScope || !explicitKind) return false;
  const shortTermProject: boolean = candidate.scope === 'short_term'
    && candidate.kind === 'project'
    && candidate.confidence >= SHORT_TERM_PROJECT_AUTO_WRITE_CONFIDENCE;
  const durableMemory: boolean = isDurableAutoWriteCandidate(candidate);
  return shortTermProject || durableMemory;
};

// ===== 编排(:43-182) =====

export type MemoryWorkerModelResolution =
  | { kind: 'ok'; modelId: string }
  | { kind: 'no_model' }
  | { kind: 'no_provider'; modelId: string };

export interface MemoryWorkerGate {
  enabled: boolean;
  extractionEnabled: boolean;
  maxDailyRuns: number;
}

export interface MemoryExtractionDeps {
  worker: MemoryWorkerGate;
  locale: string;
  resolveWorkerModel: () => Promise<MemoryWorkerModelResolution>;
  generateText: (prompt: string) => Promise<string>;
  getAllActiveRecords: (now: number) => Promise<MemoryRecord[]>;
  addCandidates: (candidates: MemoryCandidate[]) => Promise<void>;
  addCandidate: (candidate: MemoryCandidate) => Promise<void>;
  addMemory: (params: MemoryAddParams) => Promise<MemoryRecord>;
  addEvent: (event: MemoryEvent) => Promise<void>;
  countEventsSince: (type: MemoryEventType, createdAfter: number) => Promise<number>;
  // Platform runs the pure apply helper against its latest full snapshot inside
  // its write queue and commits once, including archived history and evidence.
  applyActions?: (actions: MemoryExtractionAction[], now: number) => Promise<MemoryExtractionApplyResult>;
  // Host rechecks durable conversation and runtime state after model work yields.
  canApply?: () => Promise<boolean>;
  now?: () => number;
}

// lastRunAt(:41)— ConcurrentHashMap<Uuid, Long> 等价(键 = conversation.id 文本)
const lastRunAt: Map<string, number> = new Map<string, number>();

// 测试钩子:清空去抖状态
export const resetMemoryExtractionDebounce = (): void => {
  lastRunAt.clear();
};

interface ExtractionEventFields {
  conversationId: string;
  memoryId?: number | null;
  candidateId?: string | null;
  modelId?: string | null;
  message?: string;
  durationMs?: number | null;
  messageCount?: number | null;
}

// MemoryEventLogger.log(:10-32)内联 — addEvent(makeMemoryEvent(...))
const logEvent = (
  deps: MemoryExtractionDeps, type: MemoryEventType, fields: ExtractionEventFields,
): Promise<void> => deps.addEvent(makeMemoryEvent({
  type,
  conversationId: fields.conversationId,
  memoryId: fields.memoryId ?? null,
  candidateId: fields.candidateId ?? null,
  modelId: fields.modelId ?? null,
  message: fields.message ?? '',
  durationMs: fields.durationMs ?? null,
  messageCount: fields.messageCount ?? null,
}));

// 按会话单飞:同一会话并发完成生成时,check-then-set 的去抖门会被同时穿过
// (双跑双入库);跨会话不互相拦截(对齐 Android 按会话去抖语义)
const extractionInFlight: Set<string> = new Set<string>();

export const runMemoryExtraction = async (
  conversation: Conversation, deps: MemoryExtractionDeps,
): Promise<void> => {
  if (extractionInFlight.has(conversation.id)) return;
  extractionInFlight.add(conversation.id);
  try {
    await runMemoryExtractionGuarded(conversation, deps);
  } finally {
    extractionInFlight.delete(conversation.id);
  }
};

export const extractionMessagesContaminated = (messages: UIMessage[]): boolean => {
  for (const message of messages) {
    for (const part of message.parts) {
      if (part.type !== 'tool') continue;
      const name: string = (part as UIMessagePartTool).toolName;
      if (name === 'search_web' || name === 'scrape_web' || name.startsWith('mcp__') || name === 'mcp_call_tool') return true;
    }
  }
  return false;
};

const runMemoryExtractionGuarded = async (
  conversation: Conversation, deps: MemoryExtractionDeps,
): Promise<void> => {
  const nowFn: () => number = deps.now ?? ((): number => Date.now());
  const conversationId: string = conversation.id;
  const messages: UIMessage[] = currentMessages(conversation);
  const messageCount: number = messages.length;

  // :47-55 worker 关闭门
  if (!deps.worker.enabled || !deps.worker.extractionEnabled) {
    await logEvent(deps, 'extraction_skipped', {
      conversationId, message: 'Memory worker disabled.', messageCount,
    });
    return;
  }
  // :56-66 去抖门(120s)
  const now: number = nowFn();
  const previous: number = lastRunAt.get(conversationId) ?? 0;
  if (now - previous < 120_000) {
    await logEvent(deps, 'extraction_skipped', {
      conversationId, message: 'Debounced.', messageCount,
    });
    return;
  }
  // :67-77 日配额门
  const todayStart: number = now - (now % 86_400_000);
  const runsToday: number = await deps.countEventsSince('extraction_started', todayStart);
  if (runsToday >= Math.max(1, deps.worker.maxDailyRuns)) {
    await logEvent(deps, 'extraction_skipped', {
      conversationId, message: 'Daily memory worker limit reached.', messageCount,
    });
    return;
  }
  // Sources that touched external tools never become automatically extracted facts.
  const contaminated: boolean = extractionMessagesContaminated(messages);
  if (contaminated) {
    await logEvent(deps, 'extraction_skipped', {
      conversationId,
      message: 'Conversation touched web search or MCP tools; extraction paused (contamination guard).',
      messageCount,
    });
    return;
  }
  lastRunAt.set(conversationId, now);

  // :80-100 模型/provider 门
  const resolution: MemoryWorkerModelResolution = await deps.resolveWorkerModel();
  if (resolution.kind === 'no_model') {
    await logEvent(deps, 'extraction_skipped', {
      conversationId, message: 'No memory worker model available.', messageCount,
    });
    return;
  }
  if (resolution.kind === 'no_provider') {
    await logEvent(deps, 'extraction_skipped', {
      conversationId, modelId: resolution.modelId,
      message: 'Memory worker model provider not found.', messageCount,
    });
    return;
  }
  const modelId: string = resolution.modelId;

  const startedAt: number = nowFn();
  await logEvent(deps, 'extraction_started', { conversationId, modelId, messageCount });

  // :110-181 主体(runCatching → onFailure EXTRACTION_FAILED)
  try {
    const sourceIds: string[] = messages.slice(-16)
      .filter((message: UIMessage): boolean => message.role === 'user')
      .map((message: UIMessage): string => message.id).slice(-1);
    const sources = collectMemoryExtractionSources(messages, sourceIds);
    if (sources.length === 0) return;
    const related: MemoryRecord[] = (await deps.getAllActiveRecords(nowFn()))
      .filter((record: MemoryRecord): boolean => record.kind !== 'topic' && !record.archived
        && record.invalidatedAt == null && (record.expiresAt === null || record.expiresAt > nowFn()))
      .slice(-24);
    const sourceMessages: UIMessage[] = messages.filter((message: UIMessage): boolean => sourceIds.includes(message.id));
    const prompt: string = buildMemoryExtractionPrompt(sourceMessages, sourceIds, deps.locale, related, sources, nowFn());
    const text: string = await deps.generateText(prompt);
    if (deps.canApply !== undefined && !(await deps.canApply())) {
      await logEvent(deps, 'extraction_skipped', {
        conversationId, modelId, message: 'Live source or memory write permissions changed during extraction.',
      });
      return;
    }
    // Worker settings and the source object may change while model work yields.
    if (!deps.worker.enabled || !deps.worker.extractionEnabled) return;
    const currentMessagesSnapshot: UIMessage[] = currentMessages(conversation);
    if (extractionMessagesContaminated(currentMessagesSnapshot)) {
      await logEvent(deps, 'extraction_skipped', { conversationId, modelId, message: 'External tool content arrived during extraction; writes paused.' });
      return;
    }
    const currentSources = collectMemoryExtractionSources(currentMessagesSnapshot, sourceIds);
    if (JSON.stringify(currentSources) !== JSON.stringify(sources)) {
      await logEvent(deps, 'extraction_skipped', { conversationId, modelId, message: 'Source messages changed during extraction.' });
      return;
    }
    const parsed: ParsedMemoryCandidate[] = parseMemoryCandidates(text, conversationId, sourceIds, nowFn());
    const actions: MemoryExtractionAction[] = [];
    const rejected: MemoryCandidate[] = [];
    const pending: MemoryCandidate[] = [];
    for (const meta of parsed) {
      if (meta.action === 'noop') continue;
      const source = sources.find((item): boolean => item.id === meta.sourceMessageId);
      const candidate: MemoryCandidate = meta.candidate;
      const evidence: string = meta.evidence;
      let issue: string | null = null;
      if (source === undefined || evidence.length === 0 || !source.evidenceText.includes(evidence)) issue = 'unverified_user_evidence';
      const resolvedEvidence: string = source === undefined ? evidence
        : normalizeMemoryRelativeDates(evidence, source.createdAt);
      if (issue === null && !isGroundedMemoryRewrite(candidate.content, evidence, source?.assistantContext ?? '', resolvedEvidence)) {
        issue = 'ungrounded_rewrite';
      }
      if (meta.declaredSensitive || isSensitiveMemoryContent(candidate.content) || isSensitiveMemoryContent(evidence)) issue = 'sensitive';
      if (candidate.content.length > 500 || candidate.confidence < 0.45) issue = 'low_value';
      const target: MemoryRecord | undefined = related.find((record: MemoryRecord): boolean => record.id === meta.targetId);
      if (meta.action !== 'add' && target === undefined) issue = 'target_not_shown';
      if (meta.action === 'add' && (!meta.explicitScope || !meta.explicitKind || candidate.scope === 'core')) issue = 'invalid_add_fields';
      const content: string = source === undefined ? candidate.content
        : normalizeMemoryRelativeDates(candidate.content, source.createdAt);
      let expiresAt: number | null = candidate.expiresAt;
      if (meta.expiresOn !== null && meta.expiresOn.trim().length > 0) {
        expiresAt = memoryExpiryOn(meta.expiresOn.trim());
        if (meta.action !== 'confirm' && meta.action !== 'invalidate'
          && (expiresAt === null || expiresAt <= nowFn())) issue = 'invalid_or_expired_date';
      } else if (candidate.scope === 'short_term' && source !== undefined) {
        // Legacy expires_in_days is anchored to message time, not worker time.
        if (candidate.expiresAt !== null) {
          const sentAt: number = new Date(source.createdAt).getTime();
          if (Number.isFinite(sentAt)) expiresAt = sentAt + (candidate.expiresAt - candidate.createdAt);
        } else {
          const sentAt: number = new Date(source.createdAt).getTime();
          expiresAt = deriveMemoryExpiresAt(content, Number.isFinite(sentAt) ? sentAt : nowFn());
        }
      }
      if ((meta.action === 'add' || meta.action === 'update') && expiresAt !== null && expiresAt <= nowFn()) issue = 'expired_write';
      if (issue !== null) {
        rejected.push({ ...candidate, sourceMessageIds: meta.sourceMessageId === null ? [] : [meta.sourceMessageId],
          status: 'filtered', reason: [candidate.reason, issue].filter((part: string): boolean => part.length > 0).join('; ') });
        continue;
      }
      const verified: MemoryCandidate = { ...candidate, content, evidence, expiresAt,
        sourceMessageIds: [source!.id] };
      if (meta.action === 'add' && !shouldAutoWriteCandidate(verified, meta.explicitScope, meta.explicitKind)
        && !related.some((record: MemoryRecord): boolean => record.content.trim() === content.trim())) {
        pending.push(verified);
        continue;
      }
      actions.push({ action: meta.action, content, evidence, sourceMessageId: source!.id,
        sourceConversationId: conversationId, scope: candidate.scope, kind: candidate.kind,
        confidence: candidate.confidence, expiresAt, targetId: target?.id ?? null,
        targetUpdatedAt: target?.updatedAt ?? null });
    }
    await deps.addCandidates(rejected);
    for (const candidate of pending) {
      await deps.addCandidate(candidate);
      await logEvent(deps, 'candidate_created', { conversationId, candidateId: candidate.id, modelId, message: candidate.reason });
    }
    if (deps.applyActions !== undefined) {
      const applied: MemoryExtractionApplyResult = await deps.applyActions(actions, nowFn());
      for (const memory of applied.added) await logEvent(deps,
        memory.scope === 'long_term' && (memory.kind === 'user' || memory.kind === 'feedback')
          ? 'durable_memory_created' : 'memory_created',
        { conversationId, memoryId: memory.id, modelId, message: 'Auto-created evidence-backed memory.' });
      for (const memory of applied.updated) await logEvent(deps, 'memory_updated',
        { conversationId, memoryId: memory.id, modelId, message: `Preserved prior version: ${memory.supersedesIds.join(', ')}.` });
      for (const memory of applied.invalidated) await logEvent(deps, 'memory_invalidated',
        { conversationId, memoryId: memory.id, modelId, message: `Invalidated by user evidence: ${actions.find((item: MemoryExtractionAction): boolean => item.targetId === memory.id && item.action === 'invalidate')?.evidence ?? ''}` });
      for (const memory of applied.confirmed) await logEvent(deps, 'memory_updated',
        { conversationId, memoryId: memory.id, modelId, message: 'Confirmed by independent user evidence.' });
      if (applied.rejectedReasons.length > 0) await logEvent(deps, 'extraction_skipped',
        { conversationId, modelId, message: applied.rejectedReasons.join('; ') });
    } else {
      // Compatibility with older hosts: targeted writes require an atomic writer.
      for (const item of actions) {
        if (item.action !== 'add') continue;
        const memory: MemoryRecord = await deps.addMemory({ scope: item.scope, kind: item.kind,
          content: item.content, evidence: item.evidence, sourceConversationId: conversationId,
          sourceMessageIds: [item.sourceMessageId], expiresAt: item.expiresAt, confidence: item.confidence });
        await logEvent(deps, isDurableAutoWriteCandidate(makeMemoryCandidate(item)) ? 'durable_memory_created' : 'memory_created',
          { conversationId, memoryId: memory.id, modelId, message: autoWriteEventMessage(makeMemoryCandidate(item)) });
      }
    }
  } catch (error) {
    await logEvent(deps, 'extraction_failed', {
      conversationId, modelId,
      message: error instanceof Error ? error.message : String(error),
      durationMs: nowFn() - startedAt,
      messageCount,
    });
  }
};
