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

// decode(:31-59)
export const decodeMemoryFrontmatter = (
  text: string, deps: MemoryFrontmatterCodecDeps = {},
): MemoryRecord => {
  const nowFn: () => number = deps.now ?? ((): number => Date.now());
  // split('---', limit=3):第三段保留其余分隔符原样
  const segments: string[] = text.split('---');
  if (segments.length < 3) throw new Error('Invalid memory frontmatter');
  const frontmatterText: string = segments[1];
  const content: string = segments.slice(2).join('---').trim();
  const frontmatter: Map<string, string> = new Map<string, string>();
  for (const line of frontmatterText.split('\n')) {
    const index: number = line.indexOf(':');
    if (index < 0) continue;
    frontmatter.set(line.substring(0, index).trim(), trimQuotes(line.substring(index + 1).trim()));
  }
  const scope: MemoryScope = memoryScopeFromWireName(frontmatter.get('scope') ?? null);
  const kind: MemoryKind = memoryKindFromWireName(frontmatter.get('kind') ?? null);
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
  };
};
