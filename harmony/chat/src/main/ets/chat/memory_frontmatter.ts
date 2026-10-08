// memory_frontmatter — MemoryFrontmatterCodec.kt 全文移植(D-085e)
//
// Android 基准: core/memory/export/MemoryFrontmatterCodec.kt(全文 78 行)
//   - encode(:12-29):键序逐字;quote = " 包裹 + \/\" 转义(:66-67);
//     confidence = Kotlin Float toString(整数 → 'N.0');
//     formatTime(:61-64)= 本地时区 ISO_OFFSET_DATE_TIME(>0 否则 now;
//     毫秒非零才带 .SSS 小数);expires_at/source_conversation_id 仅非 null
//   - decode(:31-59):split('---', limit=3) + require ≥3(消息逐字);
//     行解析 indexOf(':') 缺 → 跳过,toMap 后值覆盖;值 trim('"')(首尾所有 ");
//     时间 Instant.parse(解析失败抛错);缺 created_at/updated_at → now
// 偏差:无(JS Date 本地时区组件手工拼 ISO 偏移,与 ISO_OFFSET_DATE_TIME 同形)

import type { MemoryRecord, MemoryScope, MemoryKind } from './memory_models.ts';
import { memoryKindFromWireName, memoryScopeFromWireName } from './memory_models.ts';
import { memoryBucketForScope } from './memory_write.ts';

const pad2 = (n: number): string => String(n).padStart(2, '0');
const pad3 = (n: number): string => String(n).padStart(3, '0');

// Kotlin Float toString:整数 → 'N.0'
const floatText = (v: number): string => Number.isInteger(v) ? `${v}.0` : String(v);

// formatTime(:61-64)— ISO_OFFSET_DATE_TIME(本地时区;纳秒非零才出小数,按 3 位组)
const formatTime = (timeMs: number, now: () => number): string => {
  const ms: number = timeMs > 0 ? timeMs : now();
  const d: Date = new Date(ms);
  const offsetMinEast: number = -d.getTimezoneOffset();
  const sign: string = offsetMinEast >= 0 ? '+' : '-';
  const abs: number = Math.abs(offsetMinEast);
  const offset: string = `${sign}${pad2(Math.trunc(abs / 60))}:${pad2(abs % 60)}`;
  const base: string = `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}` +
    `T${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
  const fraction: string = ms % 1000 !== 0 ? `.${pad3(ms % 1000)}` : '';
  return `${base}${fraction}${offset}`;
};

const quote = (value: string): string =>
  '"' + value.split('\\').join('\\\\').split('"').join('\\"') + '"';

export interface MemoryFrontmatterCodecDeps {
  now?: () => number;
}

// encode(:12-29)— appendLine = 行 + '\n';content 前空行(:27)
export const encodeMemoryFrontmatter = (
  record: MemoryRecord, deps: MemoryFrontmatterCodecDeps = {},
): string => {
  if (record.kind === 'topic') throw new Error('派生主题不能作为源记忆导出');
  const nowFn: () => number = deps.now ?? ((): number => Date.now());
  let out: string = '';
  out += '---\n';
  out += `id: ${quote(String(record.id))}\n`;
  out += `kind: ${quote(record.kind)}\n`;
  out += `scope: ${quote(record.scope)}\n`;
  out += `confidence: ${floatText(record.confidence)}\n`;
  out += `created_at: ${quote(formatTime(record.createdAt, nowFn))}\n`;
  out += `updated_at: ${quote(formatTime(record.updatedAt, nowFn))}\n`;
  if (record.expiresAt !== null) {
    out += `expires_at: ${quote(formatTime(record.expiresAt, nowFn))}\n`;
  }
  if (record.sourceConversationId !== null) {
    out += `source_conversation_id: ${quote(record.sourceConversationId)}\n`;
  }
  out += `source_message_ids: [${record.sourceMessageIds.map(quote).join(', ')}]\n`;
  out += `supersedes_ids: [${record.supersedesIds.join(', ')}]\n`;
  out += `pinned: ${record.pinned}\n`;
  out += `archived: ${record.archived}\n`;
  out += `reinforcement_count: ${record.reinforcementCount ?? 0}\n`;
  if (record.lastReinforcedAt != null) out += `last_reinforced_at: ${quote(formatTime(record.lastReinforcedAt, nowFn))}\n`;
  if (record.invalidatedAt != null) out += `invalidated_at: ${quote(formatTime(record.invalidatedAt, nowFn))}\n`;
  if (record.evidence != null) out += `evidence_json: ${quote(JSON.stringify(record.evidence))}\n`;
  if (record.lastReinforcementSource != null) out += `last_reinforcement_source: ${quote(record.lastReinforcementSource)}\n`;
  out += '---\n';
  out += '\n';
  out += `${record.content}\n`;
  return out;
};

// parseInlineList(:69-72)— "((?:\\.|[^"])*)" 全匹配;反转义顺序:\"→" 然后 \\→\(全量)
const parseInlineList = (value: string): string[] => {
  const out: string[] = [];
  const re = /"((?:\\.|[^"])*)"/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(value)) !== null) {
    out.push(m[1].split('\\"').join('"').split('\\\\').join('\\'));
  }
  return out;
};

// parseIntList(:74-77)— -?\d+ 全匹配
const parseIntList = (value: string): number[] => {
  const out: number[] = [];
  const re = /-?\d+/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(value)) !== null) {
    const n: number = Number.parseInt(m[0], 10);
    if (Number.isSafeInteger(n)) out.push(n);
  }
  return out;
};

// Instant.parse(ISO_OFFSET_DATE_TIME 文本)→ epoch ms;失败抛错(Kotlin 同)
const parseInstant = (text: string): number => {
  const ms: number = Date.parse(text);
  if (Number.isNaN(ms)) throw new Error(`Text '${text}' could not be parsed`);
  return ms;
};

// Kotlin String.trim('"'):首尾所有 '"' 移除
const trimQuotes = (text: string): string => text.replace(/^"+/, '').replace(/"+$/, '');

// 带引号 scalar 的反转义:encode 的 quote() 转义了 \ 与 ",decode 只去引号会失真
// (source_conversation_id 等含反斜杠/引号的字段 round-trip 改变内容)。
// sentinel 法避免 " 与 \ 的替换顺序歧义。
const unescapeQuotedScalar = (raw: string): string => {
  const trimmed: string = raw.trim();
  if (trimmed.length >= 2 && trimmed.startsWith('"') && trimmed.endsWith('"')) {
    // encode 的 quote() 转义了 \\ 与 \"(成对);先消费 \\ 再消费 \"，
    // 用哨兵避免还原时歧义 —— 全程用 charCode 构造,规避源码转义层级
    const bs: string = String.fromCharCode(92);
    const sentinel: string = String.fromCharCode(0);
    return trimmed.substring(1, trimmed.length - 1)
      .split(bs + bs).join(sentinel)
      .split(bs + '"').join('"')
      .split(sentinel).join(bs);
  }
  return trimQuotes(trimmed);
};

// decode(:31-59)
export const decodeMemoryFrontmatter = (
  text: string, deps: MemoryFrontmatterCodecDeps = {},
): MemoryRecord => {
  const nowFn: () => number = deps.now ?? ((): number => Date.now());
  if (!text.trimStart().startsWith('---')) throw new Error('Invalid memory frontmatter');
  // split('---', limit=3):第三段保留其余分隔符原样
  const segments: string[] = text.split('---');
  if (segments.length < 3) throw new Error('Invalid memory frontmatter');
  const frontmatterText: string = segments[1];
  const content: string = segments.slice(2).join('---').trim();
  const frontmatter: Map<string, string> = new Map<string, string>();
  for (const line of frontmatterText.split('\n')) {
    const index: number = line.indexOf(':');
    if (index < 0) continue;
    frontmatter.set(line.substring(0, index).trim(), unescapeQuotedScalar(line.substring(index + 1)));
  }
  const scope: MemoryScope = memoryScopeFromWireName(frontmatter.get('scope') ?? null);
  const kind: MemoryKind = memoryKindFromWireName(frontmatter.get('kind') ?? null);
  if (kind === 'topic') throw new Error('派生主题不能作为源记忆导入');
  const idText: string | undefined = frontmatter.get('id');
  const idParsed: number = idText !== undefined && /^-?\d+$/.test(idText)
    ? Number.parseInt(idText, 10) : 0;
  const confidenceText: string | undefined = frontmatter.get('confidence');
  // toFloatOrNull:整串须为合法浮点(parseFloat 前缀解析不可用)
  const confidenceParsed: number = confidenceText !== undefined
    && /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(confidenceText)
    ? Number.parseFloat(confidenceText) : Number.NaN;
  const createdAtText: string | undefined = frontmatter.get('created_at');
  const updatedAtText: string | undefined = frontmatter.get('updated_at');
  const expiresAtText: string | undefined = frontmatter.get('expires_at');
  return {
    id: idParsed,
    content,
    scope,
    kind,
    assistantId: memoryBucketForScope(scope),
    sourceConversationId: frontmatter.get('source_conversation_id') ?? null,
    sourceMessageIds: parseInlineList(frontmatter.get('source_message_ids') ?? ''),
    supersedesIds: parseIntList(frontmatter.get('supersedes_ids') ?? ''),
    expiresAt: expiresAtText !== undefined ? parseInstant(expiresAtText) : null,
    confidence: Number.isNaN(confidenceParsed) ? 1 : confidenceParsed,
    pinned: frontmatter.get('pinned') === 'true',
    archived: frontmatter.get('archived') === 'true',
    createdAt: createdAtText !== undefined ? parseInstant(createdAtText) : nowFn(),
    updatedAt: updatedAtText !== undefined ? parseInstant(updatedAtText) : nowFn(),
    lastUsedAt: null,
    topicTitle: null,
    memberIds: [],
    reinforcementCount: Math.max(0, Number.parseInt(frontmatter.get('reinforcement_count') ?? '0', 10) || 0),
    lastReinforcedAt: frontmatter.has('last_reinforced_at') ? parseInstant(frontmatter.get('last_reinforced_at') as string) : null,
    invalidatedAt: frontmatter.has('invalidated_at') ? parseInstant(frontmatter.get('invalidated_at') as string) : null,
    evidence: frontmatter.has('evidence_json') ? JSON.parse(frontmatter.get('evidence_json') as string) as string : null,
    lastReinforcementSource: frontmatter.get('last_reinforcement_source') ?? null,
  };
};
