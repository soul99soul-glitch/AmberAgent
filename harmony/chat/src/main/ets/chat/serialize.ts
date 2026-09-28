// UIMessage JSON 序列化 — kotlinx 线格式 1:1(持久化 blob 与网络传输共用)
//
// Android 基准:
//   ai/util/Json.kt: ignoreUnknownKeys=true / encodeDefaults=true / explicitNulls=false
//     → 键序 = 鉴别器优先 + 声明序;默认值编码;null 省略;解析容忍未知键
//   ai/ui/Message.kt: sealed UIMessagePart(discriminator "type")/ ToolApprovalState /
//     UIMessageAnnotation / UIMessage 字段与默认值
//
// quirk Q-1(忠实复刻): Reasoning.finishedAt 声明默认 Clock.System.now(),
//   null 被省略后解码时"复活"为解析时刻。parse 的 now 参数即此来源(可注入测试)。

import { newId, nowIso } from './ids.ts';
import type { JsonObject, JsonValue } from './json.ts';
import type {
  MessageRole, ToolApprovalState, UIMessage, UIMessageAnnotation,
  UIMessagePart, UIMessagePartTool,
} from './message.ts';
import type { TokenUsage } from './usage.ts';

// ===== 窄化助手 =====

const isObj = (v: JsonValue | undefined): v is JsonObject =>
  typeof v === 'object' && v !== null && !Array.isArray(v);

const strOr = (v: JsonValue | undefined, fallback: string): string =>
  typeof v === 'string' ? v : fallback;

const reqStr = (obj: JsonObject, key: string): string => {
  const v: JsonValue | undefined = obj[key];
  if (typeof v !== 'string') throw new Error(`missing field ${key}`);
  return v;
};

const intOr = (v: JsonValue | undefined, fallback: number): number =>
  typeof v === 'number' ? v : fallback;

// ===== serialize(domain → JsonObject,键序敏感) =====

const serializeApprovalState = (s: ToolApprovalState): JsonObject => {
  switch (s.type) {
    case 'auto': return { type: 'auto' };
    case 'pending': return { type: 'pending' };
    case 'approved': return { type: 'approved' };
    case 'denied': return { type: 'denied', reason: s.reason };
    case 'answered': return { type: 'answered', answer: s.answer };
  }
};

const withMetadata = (obj: JsonObject, metadata: JsonObject | null): JsonObject => {
  if (metadata !== null) obj['metadata'] = metadata;
  return obj;
};

export const serializePart = (p: UIMessagePart): JsonObject => {
  switch (p.type) {
    case 'text':
      return withMetadata({ type: 'text', text: p.text }, p.metadata);
    case 'image':
      return withMetadata({ type: 'image', url: p.url }, p.metadata);
    case 'video':
      return withMetadata({ type: 'video', url: p.url, mime: p.mime }, p.metadata);
    case 'audio':
      return withMetadata({ type: 'audio', url: p.url, fileName: p.fileName, mime: p.mime }, p.metadata);
    case 'document':
      return withMetadata({ type: 'document', url: p.url, fileName: p.fileName, mime: p.mime }, p.metadata);
    case 'mini_app': {
      const obj: JsonObject = {
        type: 'mini_app', appId: p.appId, title: p.title, description: p.description,
      };
      if (p.iconEmoji !== null) obj['iconEmoji'] = p.iconEmoji;
      if (p.category !== null) obj['category'] = p.category;
      obj['permissions'] = p.permissions;
      if (p.htmlHash !== null) obj['htmlHash'] = p.htmlHash;
      obj['version'] = p.version;
      return withMetadata(obj, p.metadata);
    }
    case 'reasoning': {
      const obj: JsonObject = { type: 'reasoning', reasoning: p.reasoning, createdAt: p.createdAt };
      if (p.finishedAt !== null) obj['finishedAt'] = p.finishedAt;
      return withMetadata(obj, p.metadata);
    }
    case 'tool': {
      const t = p as UIMessagePartTool;
      const obj: JsonObject = {
        type: 'tool', toolCallId: t.toolCallId, toolName: t.toolName, input: t.input,
        output: t.output.map(serializePart),
        approvalState: serializeApprovalState(t.approvalState),
      };
      return withMetadata(obj, t.metadata);
    }
  }
};

const serializeAnnotation = (a: UIMessageAnnotation): JsonObject => {
  if (a.type === 'url_citation') return { type: 'url_citation', title: a.title, url: a.url };
  if (a.type === 'url_context') return { type: 'url_context', url: a.url, status: a.status };
  if (a.type === 'google_search_suggestions') return { type: 'google_search_suggestions', html: a.html };
  return { type: 'generation_interrupted', reason: a.reason };
};

const serializeUsage = (u: TokenUsage): JsonObject => ({
  promptTokens: u.promptTokens,
  completionTokens: u.completionTokens,
  cachedTokens: u.cachedTokens,
  totalTokens: u.totalTokens,
});

export const serializeUIMessage = (m: UIMessage): JsonObject => {
  const obj: JsonObject = {
    id: m.id,
    role: m.role,
    parts: m.parts.map(serializePart),
    annotations: m.annotations.map(serializeAnnotation),
    createdAt: m.createdAt,
  };
  if (m.finishedAt !== null) obj['finishedAt'] = m.finishedAt;
  if (m.modelId !== null) obj['modelId'] = m.modelId;
  if (m.usage !== null) obj['usage'] = serializeUsage(m.usage);
  if (m.translation !== null) obj['translation'] = m.translation;
  return obj;
};

export const serializeMessageList = (messages: UIMessage[]): string =>
  JSON.stringify(messages.map(serializeUIMessage));

// ===== parse(JsonObject → domain,默认值与未知键容忍) =====

const ROLE_MAP: Record<string, MessageRole> = {
  system: 'system', user: 'user', assistant: 'assistant', tool: 'tool',
};

const parseApprovalState = (v: JsonValue | undefined): ToolApprovalState => {
  if (!isObj(v)) return { type: 'auto' }; // 缺省 = 默认 Auto
  const type: string = strOr(v['type'], '');
  switch (type) {
    case 'auto': return { type: 'auto' };
    case 'pending': return { type: 'pending' };
    case 'approved': return { type: 'approved' };
    case 'denied': return { type: 'denied', reason: strOr(v['reason'], '') };
    case 'answered': return { type: 'answered', answer: reqStr(v, 'answer') };
    default: throw new Error(`unknown approval state type: ${type}`);
  }
};

export const parsePart = (v: JsonValue, now: () => string): UIMessagePart => {
  if (!isObj(v)) throw new Error('part is not an object');
  const type: string = strOr(v['type'], '');
  const metadata: JsonObject | null = isObj(v['metadata']) ? v['metadata'] : null;
  switch (type) {
    case 'text':
      return { type: 'text', text: reqStr(v, 'text'), metadata };
    case 'image':
      return { type: 'image', url: reqStr(v, 'url'), metadata };
    case 'video':
      return { type: 'video', url: reqStr(v, 'url'), mime: strOr(v['mime'], 'video/mp4'), metadata };
    case 'audio':
      return {
        type: 'audio', url: reqStr(v, 'url'),
        fileName: strOr(v['fileName'], ''), mime: strOr(v['mime'], 'audio/mpeg'), metadata,
      };
    case 'document':
      return {
        type: 'document', url: reqStr(v, 'url'),
        fileName: reqStr(v, 'fileName'), mime: strOr(v['mime'], 'text/*'), metadata,
      };
    case 'mini_app': {
      const perms: JsonValue | undefined = v['permissions'];
      return {
        type: 'mini_app',
        appId: reqStr(v, 'appId'), title: reqStr(v, 'title'), description: reqStr(v, 'description'),
        iconEmoji: typeof v['iconEmoji'] === 'string' ? v['iconEmoji'] : null,
        category: typeof v['category'] === 'string' ? v['category'] : null,
        permissions: Array.isArray(perms)
          ? perms.filter((x: JsonValue): x is string => typeof x === 'string')
          : [],
        htmlHash: typeof v['htmlHash'] === 'string' ? v['htmlHash'] : null,
        version: intOr(v['version'], 1),
        metadata,
      };
    }
    case 'reasoning':
      return {
        type: 'reasoning',
        reasoning: reqStr(v, 'reasoning'),
        createdAt: strOr(v['createdAt'], now()),
        finishedAt: typeof v['finishedAt'] === 'string' ? v['finishedAt'] : now(), // quirk Q-1
        metadata,
      };
    case 'tool': {
      const output: JsonValue | undefined = v['output'];
      return {
        type: 'tool',
        toolCallId: reqStr(v, 'toolCallId'),
        toolName: reqStr(v, 'toolName'),
        input: reqStr(v, 'input'),
        output: Array.isArray(output) ? output.map((p: JsonValue): UIMessagePart => parsePart(p, now)) : [],
        approvalState: parseApprovalState(v['approvalState']),
        metadata,
      };
    }
    default:
      throw new Error(`unknown part type: ${type}`);
  }
};

const parseAnnotation = (v: JsonValue): UIMessageAnnotation => {
  if (!isObj(v)) throw new Error('annotation is not an object');
  const type: string = strOr(v['type'], '');
  if (type === 'url_citation') {
    return { type: 'url_citation', title: reqStr(v, 'title'), url: reqStr(v, 'url') };
  }
  if (type === 'generation_interrupted') {
    return { type: 'generation_interrupted', reason: strOr(v['reason'], '') };
  }
  if (type === 'url_context') {
    return { type: 'url_context', url: reqStr(v, 'url'), status: reqStr(v, 'status') };
  }
  if (type === 'google_search_suggestions') {
    return { type: 'google_search_suggestions', html: reqStr(v, 'html') };
  }
  throw new Error(`unknown annotation type: ${type}`);
};

const parseUsage = (v: JsonValue | undefined): TokenUsage | null => {
  if (!isObj(v)) return null;
  return {
    promptTokens: intOr(v['promptTokens'], 0),
    completionTokens: intOr(v['completionTokens'], 0),
    cachedTokens: intOr(v['cachedTokens'], 0),
    totalTokens: intOr(v['totalTokens'], 0),
  };
};

export const parseUIMessage = (obj: JsonObject, now: () => string = nowIso): UIMessage => {
  const roleRaw: string = reqStr(obj, 'role');
  const role: MessageRole | undefined = ROLE_MAP[roleRaw];
  if (role === undefined) throw new Error(`unknown role: ${roleRaw}`);
  const partsRaw: JsonValue | undefined = obj['parts'];
  if (!Array.isArray(partsRaw)) throw new Error('missing field parts');
  const annotationsRaw: JsonValue | undefined = obj['annotations'];
  return {
    id: strOr(obj['id'], newId()),
    role,
    parts: partsRaw.map((p: JsonValue): UIMessagePart => parsePart(p, now)),
    annotations: Array.isArray(annotationsRaw) ? annotationsRaw.map(parseAnnotation) : [],
    createdAt: strOr(obj['createdAt'], now()),
    finishedAt: typeof obj['finishedAt'] === 'string' ? obj['finishedAt'] : null,
    modelId: typeof obj['modelId'] === 'string' ? obj['modelId'] : null,
    usage: parseUsage(obj['usage']),
    translation: typeof obj['translation'] === 'string' ? obj['translation'] : null,
  };
};

export const parseMessageList = (blob: string, now: () => string = nowIso): UIMessage[] => {
  const parsed: unknown = JSON.parse(blob);
  if (!Array.isArray(parsed)) throw new Error('message list blob is not an array');
  return parsed.map((v): UIMessage => {
    if (v === null || typeof v !== 'object' || Array.isArray(v)) {
      throw new Error('message entry is not an object');
    }
    return parseUIMessage(v as JsonObject, now);
  });
};
