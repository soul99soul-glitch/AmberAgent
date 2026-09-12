// builtin_local_tools — 本地工具三件(D-061)
//
// Android 基准:
//   core/ai/tools/RunPlanUpdateTool.kt(全文 52 行)— run_plan_update
//   core/ai/tools/ClipboardTool.kt(全文)— clipboard_tool
//   core/ai/tools/DeepReadOpenTool.kt(全文)+ DeepReadOpenRequest.kt
//     (createDeepReadOpenEvent + 私有助手)— deep_read_open
// 裁剪/偏差登记:
//   - java.net.URI 解析 → 手写 scheme/authority 拆分(ArkTS 无全局 URL;
//     D-056 parseUrlHost 同手法);Java URI.toString() 重建规范化未复刻,
//     合法输入下返回 trim 后原文(等价)
//   - MessageDigest SHA-256 → 注入 sha256Hex Port(entry 经
//     CryptoArchitectureKit 真实实现;deepread HAR 的 FNV topicId 为另一
//     处既有登记偏差,不复用)
//   - AppEventBus → DeepReadOpenBus Port(hasCollectors/emit);entry 以
//     router.pushUrl 实现收集器
//   - parseDeepReadSlashCommand(/deepread 斜杠命令,同文件:36-68)属发送
//     路径路由,未随本切片移植 = 残余登记
//   - LocalTools.getTools 的 LocalToolOption 门(Clipboard/TimeInfo 等
//     per-assistant 开关)→ 鸿蒙 Assistant 模型无 localTools 字段,工具
//     无条件注册(等价全选项开;门控 = P1 登记)

import type { JsonObject, JsonValue } from './json.ts';
import type { UIMessagePart, UIMessagePartText } from './message.ts';
import type { AgentTool } from './tool.ts';
import { makeAgentTool, makeInputSchemaObj } from './tool.ts';
import type { HealthMetricRecord } from './health_summary.ts';
import { buildHealthSummary, healthSummaryToolJson } from './health_summary.ts';

// ===== 输入解析(jsonPrimitive contentOrNull 语义) =====

const asObject = (input: JsonValue): JsonObject => {
  if (typeof input !== 'object' || input === null || Array.isArray(input)) return {};
  return input as JsonObject;
};

const inputStringOrNull = (input: JsonValue, key: string): string | null => {
  const v: JsonValue | undefined = asObject(input)[key];
  return typeof v === 'string' ? v : null;
};

const inputStrictTrue = (input: JsonValue, key: string): boolean => {
  const v: JsonValue | undefined = asObject(input)[key];
  return v === true;
};

// ===== run_plan_update(RunPlanUpdateTool.kt 全文) =====

export const createRunPlanUpdateTool = (): AgentTool => makeAgentTool({
  name: 'run_plan_update',
  description: 'Update the current long-task step summary for Agent UI, preview surfaces, and live status. Use concise user-visible step names.',
  parameters: () => makeInputSchemaObj(
    {
      steps: {
        type: 'array',
        description: 'Ordered task steps',
        items: { type: 'string' },
      },
      current_step_index: {
        type: 'integer',
        description: '0-based current step index',
      },
      status: {
        type: 'string',
        description: 'planning, running, waiting, completed, failed, or cancelled',
      },
    },
    ['steps', 'status'],
  ),
  execute: (input: JsonValue): Promise<UIMessagePart[]> => {
    const obj: JsonObject = asObject(input);
    // put(key, obj[key] ?: default) — 原始 JsonElement 直通(非字符串也回显)
    const payload: JsonObject = {
      status: obj['status'] ?? 'running',
      current_step_index: obj['current_step_index'] ?? 0,
      steps: obj['steps'] ?? [],
      note: 'Plan state was accepted by the tool layer. UI live rendering is stage1 and uses the normal tool timeline.',
    };
    return Promise.resolve([
      { type: 'text', text: JSON.stringify(payload), metadata: null },
    ]);
  },
});

// ===== clipboard_tool(ClipboardTool.kt 全文) =====

// readClipboardText/writeClipboardText 平台 Port(entry pasteboard 实现)
export interface ClipboardPort {
  readText: () => Promise<string>;
  writeText: (text: string) => Promise<void>;
}

export const createClipboardTool = (clipboard: ClipboardPort): AgentTool => makeAgentTool({
  name: 'clipboard_tool',
  // trimIndent().replace("\n", " ") 逐字
  description: 'Read or write plain text from the device clipboard. Use action: read or write. For write, provide text. Do NOT write to the clipboard unless the user has explicitly requested it.',
  parameters: () => makeInputSchemaObj(
    {
      action: {
        type: 'string',
        enum: ['read', 'write'],
        description: 'Operation to perform: read or write',
      },
      text: {
        type: 'string',
        description: 'Text to write to the clipboard (required for write)',
      },
    },
    ['action'],
  ),
  execute: async (input: JsonValue): Promise<UIMessagePart[]> => {
    const action: string | null = inputStringOrNull(input, 'action');
    if (action === null) throw new Error('action is required');
    if (action === 'read') {
      const payload: JsonObject = { text: await clipboard.readText() };
      return [{ type: 'text', text: JSON.stringify(payload), metadata: null }];
    }
    if (action === 'write') {
      const text: string | null = inputStringOrNull(input, 'text');
      if (text === null) throw new Error('text is required');
      await clipboard.writeText(text);
      const payload: JsonObject = { success: true, text };
      return [{ type: 'text', text: JSON.stringify(payload), metadata: null }];
    }
    throw new Error(`unknown action: ${action}, must be one of [read, write]`);
  },
});

// ===== deep_read_open(DeepReadOpenTool.kt + DeepReadOpenRequest.kt) =====

export const DEEP_READ_TITLE_MAX_CHARS: number = 120;

// AppEvent.OpenDeepRead(topicId/title/sourceUrl/forceRegenerate)
export interface DeepReadOpenEvent {
  topicId: string;
  title: string;
  sourceUrl: string | null;
  forceRegenerate: boolean;
}

// AppEventBus Port(hasCollectors → emit / 无收集者 → not_opened)
export interface DeepReadOpenBus {
  hasCollectors: () => boolean;
  emit: (event: DeepReadOpenEvent) => void;
}

export interface DeepReadOpenToolDeps {
  bus: DeepReadOpenBus;
  // MessageDigest SHA-256 UTF-8 全 hex(工具内 take(24))
  sha256Hex: (input: string) => string;
}

// scheme://authority 拆分(java.net.URI 最小等价;file 头偏差登记)
const splitSchemeAuthority = (raw: string): { scheme: string; rest: string } | null => {
  const m: RegExpExecArray | null = /^([A-Za-z][A-Za-z0-9+.-]*):\/\/(.+)$/.exec(raw);
  if (m === null) return null;
  return { scheme: m[1], rest: m[2] };
};

// authority → host(去 userinfo/端口;IPv6 保留方括号)
const hostFromAuthority = (rest: string): string => {
  const authority: string = rest.split('/')[0].split('?')[0].split('#')[0];
  const noUserinfo: string = authority.split('@').pop() ?? '';
  if (noUserinfo.startsWith('[')) {
    const close: number = noUserinfo.indexOf(']');
    return close >= 0 ? noUserinfo.slice(0, close + 1) : noUserinfo;
  }
  return noUserinfo.split(':')[0];
};

// normalizeDeepReadSourceUrl(:71-78;URI.toString() 重建未复刻 → trim 原文)
const normalizeDeepReadSourceUrl = (raw: string): string => {
  const trimmed: string = raw.trim();
  const parts = splitSchemeAuthority(trimmed);
  if (parts === null) throw new Error('source_url must be a valid HTTP(S) URL');
  const scheme: string = parts.scheme.toLowerCase();
  if (scheme !== 'http' && scheme !== 'https') {
    throw new Error('source_url must be HTTP(S)');
  }
  if (hostFromAuthority(parts.rest).length === 0) {
    throw new Error('source_url host is required');
  }
  return trimmed;
};

// titleFromDeepReadUrl(:80-96)
const titleFromDeepReadUrl = (url: string): string => {
  const parts = splitSchemeAuthority(url.trim());
  const host: string = parts !== null
    ? hostFromAuthority(parts.rest).replace(/^www\./, '')
    : '';
  let slug: string = '';
  if (parts !== null) {
    // URI.path = authority 之后、?# 之前(authority 不属于路径段)
    const noQuery: string = parts.rest.split('?')[0].split('#')[0];
    const slashIdx: number = noQuery.indexOf('/');
    const pathPart: string = slashIdx >= 0 ? noQuery.slice(slashIdx) : '';
    const segments: string[] = pathPart.split('/')
      .filter((s: string): boolean => s.trim().length > 0);
    const last: string = segments.length > 0 ? segments[segments.length - 1] : '';
    // substringBeforeLast(".") — 无 '.' 时返回原串(Kotlin 语义)
    const dot: number = last.lastIndexOf('.');
    slug = (dot >= 0 ? last.slice(0, dot) : last)
      .replace(/-/g, ' ')
      .replace(/_/g, ' ')
      .trim();
  }
  const picked: string = slug.length > 0 ? slug : host;
  if (picked.length === 0) return '深度阅读';
  return picked.slice(0, DEEP_READ_TITLE_MAX_CHARS);
};

// deepReadChatTopicId(:98-107):key=url?trim.lowercase ?: title.trim.lowercase
const deepReadChatTopicId = (
  title: string, sourceUrl: string | null, sha256Hex: (input: string) => string,
): string => {
  const key: string = sourceUrl !== null
    ? sourceUrl.trim().toLowerCase()
    : title.trim().toLowerCase();
  return `chat_deep_read_${sha256Hex(key).slice(0, 24)}`;
};

// createDeepReadOpenEvent(:13-34)
export const createDeepReadOpenEvent = (
  topicTitle: string | null, sourceUrl: string | null,
  forceRegenerate: boolean, sha256Hex: (input: string) => string,
): DeepReadOpenEvent => {
  const trimmedUrl: string | null = sourceUrl !== null ? sourceUrl.trim() : null;
  const normalizedUrl: string | null =
    trimmedUrl !== null && trimmedUrl.length > 0
      ? normalizeDeepReadSourceUrl(trimmedUrl)
      : null;
  const trimmedTitle: string | null = topicTitle !== null ? topicTitle.trim() : null;
  let title: string;
  if (trimmedTitle !== null && trimmedTitle.length > 0 && trimmedTitle !== normalizedUrl) {
    title = trimmedTitle;
  } else if (normalizedUrl !== null) {
    title = titleFromDeepReadUrl(normalizedUrl);
  } else {
    throw new Error('topic_title or source_url is required');
  }
  return {
    topicId: deepReadChatTopicId(title, normalizedUrl, sha256Hex),
    title: title.slice(0, DEEP_READ_TITLE_MAX_CHARS),
    sourceUrl: normalizedUrl,
    forceRegenerate,
  };
};

export const createDeepReadOpenTool = (deps: DeepReadOpenToolDeps): AgentTool =>
  makeAgentTool({
    name: 'deep_read_open',
    // trimIndent().replace("\n", " ") 逐字
    description: 'Open AmberAgent\'s full-screen Deep Read panel for a topic or URL. Use this when the user asks to 深度阅读, 深读, deep read, investigate a link/topic in magazine format, or wants a full-screen research reading view. This tool only opens the Deep Read route; the panel then uses the standard hidden-agent Deep Read pipeline, search/scrape tools, segmented writes, and 24h cache. If the user provided a URL, pass it as source_url so Deep Read scrapes that source first, then cross-checks with search.',
    parameters: () => makeInputSchemaObj({
      topic_title: {
        type: 'string',
        description: 'User-visible topic title. If only a URL is known, use a short title derived from the URL or omit it.',
      },
      source_url: {
        type: 'string',
        description: 'Optional HTTP(S) URL provided by the user. Deep Read will scrape this first as a seed source.',
      },
      force_regenerate: {
        type: 'boolean',
        description: 'Set true only when the user explicitly asks to regenerate or ignore the 24h cache.',
      },
    }),
    allowsAutoApproval: true,
    execute: (input: JsonValue): Promise<UIMessagePart[]> => {
      const forceRegenerate: boolean = inputStrictTrue(input, 'force_regenerate');
      const event: DeepReadOpenEvent = createDeepReadOpenEvent(
        inputStringOrNull(input, 'topic_title'),
        inputStringOrNull(input, 'source_url'),
        forceRegenerate,
        deps.sha256Hex,
      );
      const opened: boolean = deps.bus.hasCollectors();
      if (opened) deps.bus.emit(event);
      const payload: JsonObject = {
        status: opened ? 'opened' : 'not_opened',
        topic_id: event.topicId,
        title: event.title,
      };
      if (event.sourceUrl !== null) payload['source_url'] = event.sourceUrl;
      payload['cache_ttl_hours'] = 24;
      payload['force_regenerate'] = event.forceRegenerate;
      payload['note'] = opened
        ? 'Deep Read panel opened. The panel will stream segmented generation through the hidden-agent pipeline.'
        : 'Deep Read UI was not active, so the panel could not be opened.';
      return Promise.resolve([
        { type: 'text', text: JSON.stringify(payload), metadata: null },
      ]);
    },
  });

// ===== parseDeepReadSlashCommand(DeepReadOpenRequest.kt:31-66;D-064) =====
//
// 发送路径斜杠命令:SendMessageOrchestrator.kt:26-41 — send() 先解析,
//   命中且 bus.tryEmit 成功 → return true(不入队、不发给模型);
//   entry 在 ChatPage send/sendWithoutAnswer 组 parts 后拦截(编辑态除外,
//   对齐 Android 编辑走 handleMessageEdit 不经 orchestrator)
// Kotlin 语义复刻:
//   - startsWith(prefix, ignoreCase=true) 大小写不敏感前缀
//   - rest.first().isWhitespace():Char.isWhitespace 常用集近似(NBSP 等
//     异域空白差异登记;真实输入为 ASCII 空白)
//   - force 检测 = 旗标 ∈ body.lowercase()(子串,折叠前);
//     折叠 = flags 列表序字面量全量替换(ignoreCase)—— '--regen' 先于
//     '--regenerate' 折叠,后者残留 'erate'(Android quirk,测试钉住)
//   - URL 提取正则 https?://\S+(IGNORE_CASE)首个命中;标题 = cleaned
//     去除 URL 字面量(大小写敏感)trim 后非空,否则 null → 事件内 URL 派生
//   - runCatching{...}.getOrNull():事件构造抛错 → null

export const DEEP_READ_ROUTE_TAG: string = '[ROUTE:deepread]';
export const DEEP_READ_FORCE_FLAGS: string[] =
  ['--force', '--regen', '--regenerate', '重新生成', '强制刷新'];
const DEEP_READ_HTTP_URL_REGEX: RegExp = /https?:\/\/\S+/i;

const startsWithIgnoreCase = (text: string, prefix: string): boolean =>
  text.slice(0, prefix.length).toLowerCase() === prefix.toLowerCase();

// Kotlin Char.isWhitespace 近似:ASCII 空白 + C0 分隔符 \u001C-\u001F
const isKotlinWhitespace = (ch: string): boolean =>
  ch === ' ' || (ch >= '\t' && ch <= '\r') || (ch >= '\u001C' && ch <= '\u001F');

// Kotlin String.replace(oldValue, "", ignoreCase=true) — 字面量全量替换
const replaceAllIgnoreCase = (text: string, needle: string): string => {
  const lowerText: string = text.toLowerCase();
  const lowerNeedle: string = needle.toLowerCase();
  let out: string = '';
  let cursor: number = 0;
  for (;;) {
    const idx: number = lowerText.indexOf(lowerNeedle, cursor);
    if (idx < 0) return out + text.slice(cursor);
    out += text.slice(cursor, idx);
    cursor = idx + needle.length;
  }
};

export const parseDeepReadSlashCommand = (
  parts: UIMessagePart[], sha256Hex: (input: string) => string,
): DeepReadOpenEvent | null => {
  const text: string = parts
    .filter((p: UIMessagePart): boolean => p.type === 'text')
    .map((p: UIMessagePart): string => (p as UIMessagePartText).text)
    .join('\n')
    .trim();
  if (text.length === 0) return null;
  let body: string;
  if (startsWithIgnoreCase(text, DEEP_READ_ROUTE_TAG)) {
    body = text.slice(DEEP_READ_ROUTE_TAG.length).trim();
  } else if (startsWithIgnoreCase(text, '/deepread')) {
    const rest: string = text.slice('/deepread'.length);
    if (rest.length > 0 && !isKotlinWhitespace(rest.charAt(0))) return null;
    body = rest.trim();
  } else {
    return null;
  }
  if (body.length === 0) return null;
  const lowerBody: string = body.toLowerCase();
  const force: boolean =
    DEEP_READ_FORCE_FLAGS.some((flag: string): boolean => lowerBody.includes(flag));
  let cleaned: string = body;
  for (const flag of DEEP_READ_FORCE_FLAGS) {
    cleaned = replaceAllIgnoreCase(cleaned, flag);
  }
  cleaned = cleaned.trim();
  if (cleaned.length === 0) return null;
  const m: RegExpExecArray | null = DEEP_READ_HTTP_URL_REGEX.exec(cleaned);
  const sourceUrl: string | null = m !== null ? m[0] : null;
  let title: string | null;
  if (sourceUrl !== null) {
    const removed: string = cleaned.split(sourceUrl).join('').trim();
    title = removed.length > 0 ? removed : null;
  } else {
    title = cleaned;
  }
  try {
    return createDeepReadOpenEvent(title, sourceUrl, force, sha256Hex);
  } catch {
    return null;
  }
};

// ===== health_summary（只读聚合；Port 由 entry 注入） =====

export interface HealthReadPort {
  /** 聚合窗口内的原始指标；不可用返回空数组并带 available=false */
  readRecent(nowMs: number): Promise<{ available: boolean; records: HealthMetricRecord[]; note: string }>;
}

export const createHealthSummaryTool = (port: HealthReadPort): AgentTool => makeAgentTool({
  name: 'health_summary',
  description: 'Read-only 7-day local health summary (steps, heart rate, sleep, weight) when the user granted access. Never fabricates numbers; unavailable sources return ok=false with a reason.',
  parameters: () => makeInputSchemaObj({}, []),
  execute: async (): Promise<UIMessagePart[]> => {
    const result = await port.readRecent(Date.now());
    if (!result.available) {
      return [{
        type: 'text',
        text: JSON.stringify({
          ok: false,
          tool: 'health_summary',
          error: result.note.length > 0 ? result.note : 'health data unavailable',
        }),
        metadata: null,
      }];
    }
    const summary = buildHealthSummary(result.records);
    return [{ type: 'text', text: healthSummaryToolJson(summary), metadata: null }];
  },
});
