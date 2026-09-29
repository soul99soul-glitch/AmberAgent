// Assistant kotlinx JSON 线格式(DataStore 存储形态)
//
// Android 基准:
//   core/model/.../Assistant.kt(@Serializable,声明序键序,encodeDefaults,explicitNulls=false)
//   Avatar.kt sealed:无 @SerialName → 鉴别名为 FQN(app.amber.core.model.Avatar.X)
//   LocalToolOption.kt:@SerialName snake_case,classDiscriminator 默认 "type"
//   ai/util/Json.kt:ignoreUnknownKeys/encodeDefaults/explicitNulls=false
//
// 浮点口径(D-016):kotlinx Float 1.0 → "1.0";JSON.stringify(1) → "1"。
//   temperature/topP/backgroundOpacity 用手工键序拼接 + Float 格式化,保证字节级一致。

import type { JsonObject, JsonValue } from './json.ts';
import type {
  Assistant, AssistantRegex, Avatar, LocalToolOption, MainAgentToolProfile, CustomHeader,
} from './assistant.ts';
import type { AssistantAffectScope } from './assistant.ts';
import type { ReasoningLevel, CustomBody } from './provider_model.ts';
import { makeAssistant, makeAssistantRegex } from './assistant.ts';
import { serializeUIMessage, parseUIMessage } from './serialize.ts';
import type { UIMessage } from './message.ts';

// ===== 枚举映射 =====

const REASONING_WIRE: Record<ReasoningLevel, string> = {
  off: 'OFF', auto: 'AUTO', low: 'LOW', medium: 'MEDIUM',
  high: 'HIGH', xhigh: 'XHIGH', max: 'MAX',
};

const reasoningFromWire = (s: string): ReasoningLevel => {
  const table: Record<string, ReasoningLevel> = {
    OFF: 'off', AUTO: 'auto', LOW: 'low', MEDIUM: 'medium',
    HIGH: 'high', XHIGH: 'xhigh', MAX: 'max',
  };
  const v = table[s];
  if (v === undefined) throw new Error(`unknown ReasoningLevel: ${s}`);
  return v;
};

const SCOPE_WIRE: Record<AssistantAffectScope, string> = { user: 'USER', assistant: 'ASSISTANT' };
const scopeFromWire = (s: string): AssistantAffectScope => {
  if (s === 'USER') return 'user';
  if (s === 'ASSISTANT') return 'assistant';
  throw new Error(`unknown AssistantAffectScope: ${s}`);
};

const AVATAR_TYPE_WIRE: Record<Avatar['type'], string> = {
  dummy: 'app.amber.core.model.Avatar.Dummy',
  emoji: 'app.amber.core.model.Avatar.Emoji',
  image: 'app.amber.core.model.Avatar.Image',
};

const LOCAL_TOOL_OPTIONS: ReadonlyArray<string> = [
  'javascript_engine', 'time_info', 'clipboard', 'tts', 'ask_user', 'workspace_files',
  'terminal', 'screen_automation', 'system_access', 'webview', 'icloud_drive',
  'webmount', 'webmount_eval',
];

const TOOL_PROFILES: ReadonlyArray<string> = [
  'full', 'minimal', 'web_read', 'workspace_read', 'coding', 'mobile_control',
];

// ===== 小工具 =====

const isObj = (v: JsonValue | undefined): v is JsonObject =>
  v !== undefined && v !== null && typeof v === 'object' && !Array.isArray(v);

const strOr = (o: JsonObject, k: string, dflt: string): string =>
  typeof o[k] === 'string' ? o[k] as string : dflt;

const boolOr = (o: JsonObject, k: string, dflt: boolean): boolean =>
  typeof o[k] === 'boolean' ? o[k] as boolean : dflt;

const numOr = (o: JsonObject, k: string, dflt: number): number =>
  typeof o[k] === 'number' ? o[k] as number : dflt;

const nullableStr = (o: JsonObject, k: string): string | null =>
  typeof o[k] === 'string' ? o[k] as string : null;

const nullableNum = (o: JsonObject, k: string): number | null =>
  typeof o[k] === 'number' ? o[k] as number : null;

const strArrOr = (o: JsonObject, k: string): string[] =>
  Array.isArray(o[k]) ? (o[k] as JsonValue[]).filter((v): v is string => typeof v === 'string') : [];

const esc = (s: string): string => JSON.stringify(s);

// kotlinx Float:整数值保留 .0
const floatStr = (n: number): string => (Number.isInteger(n) ? `${n}.0` : String(n));

// ===== Avatar =====

const serializeAvatar = (a: Avatar): string => {
  if (a.type === 'dummy') return `{"type":${esc(AVATAR_TYPE_WIRE.dummy)}}`;
  if (a.type === 'emoji') {
    return `{"type":${esc(AVATAR_TYPE_WIRE.emoji)},"content":${esc(a.content)}}`;
  }
  return `{"type":${esc(AVATAR_TYPE_WIRE.image)},"url":${esc(a.url)}}`;
};

const parseAvatar = (v: JsonValue | undefined): Avatar => {
  if (!isObj(v)) return { type: 'dummy' };
  const t = v['type'];
  if (t === AVATAR_TYPE_WIRE.emoji) {
    return { type: 'emoji', content: strOr(v, 'content', '') };
  }
  if (t === AVATAR_TYPE_WIRE.image) {
    return { type: 'image', url: strOr(v, 'url', '') };
  }
  return { type: 'dummy' };
};

// ===== AssistantRegex =====

const serializeRegex = (r: AssistantRegex): string =>
  `{"id":${esc(r.id)},"name":${esc(r.name)},"enabled":${r.enabled},`
  + `"findRegex":${esc(r.findRegex)},"replaceString":${esc(r.replaceString)},`
  + `"affectingScope":[${r.affectingScope.map((s): string => esc(SCOPE_WIRE[s])).join(',')}],`
  + `"visualOnly":${r.visualOnly}}`;

const parseRegex = (v: JsonValue): AssistantRegex => {
  const o = isObj(v) ? v : {};
  return makeAssistantRegex({
    id: strOr(o, 'id', ''),
    name: strOr(o, 'name', ''),
    enabled: boolOr(o, 'enabled', true),
    findRegex: strOr(o, 'findRegex', ''),
    replaceString: strOr(o, 'replaceString', ''),
    affectingScope: strArrOr(o, 'affectingScope').map(scopeFromWire),
    visualOnly: boolOr(o, 'visualOnly', false),
  });
};

// ===== Assistant =====

export const serializeAssistant = (a: Assistant): string => {
  const parts: string[] = [];
  parts.push(`"id":${esc(a.id)}`);
  if (a.chatModelId !== null) parts.push(`"chatModelId":${esc(a.chatModelId)}`);
  if (a.imageGenerationModelId !== null) {
    parts.push(`"imageGenerationModelId":${esc(a.imageGenerationModelId)}`);
  }
  parts.push(`"name":${esc(a.name)}`);
  parts.push(`"avatar":${serializeAvatar(a.avatar)}`);
  parts.push(`"useAssistantAvatar":${a.useAssistantAvatar}`);
  parts.push(`"tags":[${a.tags.map(esc).join(',')}]`);
  parts.push(`"systemPrompt":${esc(a.systemPrompt)}`);
  if (a.temperature !== null) parts.push(`"temperature":${floatStr(a.temperature)}`);
  if (a.topP !== null) parts.push(`"topP":${floatStr(a.topP)}`);
  parts.push(`"contextMessageSize":${a.contextMessageSize}`);
  parts.push(`"streamOutput":${a.streamOutput}`);
  parts.push(`"enableMemory":${a.enableMemory}`);
  parts.push(`"useGlobalMemory":${a.useGlobalMemory}`);
  parts.push(`"enableRecentChatsReference":${a.enableRecentChatsReference}`);
  parts.push(`"messageTemplate":${esc(a.messageTemplate)}`);
  parts.push(`"presetMessages":[${a.presetMessages.map(
    (m: UIMessage): string => JSON.stringify(serializeUIMessage(m))).join(',')}]`);
  parts.push(`"quickMessageIds":[${a.quickMessageIds.map(esc).join(',')}]`);
  parts.push(`"regexes":[${a.regexes.map(serializeRegex).join(',')}]`);
  parts.push(`"reasoningLevel":${esc(REASONING_WIRE[a.reasoningLevel])}`);
  if (a.maxTokens !== null) parts.push(`"maxTokens":${a.maxTokens}`);
  parts.push(`"customHeaders":[${a.customHeaders.map(
    (h: CustomHeader): string => `{"name":${esc(h.name)},"value":${esc(h.value)}}`).join(',')}]`);
  parts.push(`"customBodies":[${a.customBodies.map(
    (b: CustomBody): string => `{"key":${esc(b.key)},"value":${JSON.stringify(b.value)}}`).join(',')}]`);
  parts.push(`"mcpServers":[${a.mcpServers.map(esc).join(',')}]`);
  parts.push(`"localTools":[${a.localTools.map(
    (t: LocalToolOption): string => `{"type":${esc(t)}}`).join(',')}]`);
  parts.push(`"toolProfile":${esc(a.toolProfile)}`);
  if (a.background !== null) parts.push(`"background":${esc(a.background)}`);
  parts.push(`"backgroundOpacity":${floatStr(a.backgroundOpacity)}`);
  parts.push(`"enabledSkills":[${a.enabledSkills.map(esc).join(',')}]`);
  parts.push(`"enableTimeReminder":${a.enableTimeReminder}`);
  const remembered = Object.keys(a.rememberedReasoningLevelsByModelId).map(
    (k: string): string =>
      `${esc(k)}:${esc(REASONING_WIRE[a.rememberedReasoningLevelsByModelId[k]])}`,
  );
  parts.push(`"rememberedReasoningLevelsByModelId":{${remembered.join(',')}}`);
  return `{${parts.join(',')}}`;
};

export const parseAssistant = (json: string): Assistant => {
  const parsed: unknown = JSON.parse(json);
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('assistant json is not an object');
  }
  return parseAssistantFromObject(parsed as JsonObject);
};

const parseAssistantFromObject = (o: JsonObject): Assistant => {
  if (typeof o['id'] !== 'string') throw new Error("missing field 'id'");

  const rawReasoning = strOr(o, 'reasoningLevel', 'AUTO');
  const rawProfile = strOr(o, 'toolProfile', 'full');
  if (TOOL_PROFILES.indexOf(rawProfile) < 0) throw new Error(`unknown MainAgentToolProfile: ${rawProfile}`);

  const rawTools = Array.isArray(o['localTools']) ? o['localTools'] as JsonValue[] : null;
  const localTools: LocalToolOption[] = rawTools === null
    ? ['time_info']
    : rawTools.map((v: JsonValue): LocalToolOption => {
      const t = isObj(v) && typeof v['type'] === 'string' ? v['type'] : '';
      if (LOCAL_TOOL_OPTIONS.indexOf(t) < 0) throw new Error(`unknown LocalToolOption: ${t}`);
      return t as LocalToolOption;
    });

  const rememberedRaw = isObj(o['rememberedReasoningLevelsByModelId'])
    ? o['rememberedReasoningLevelsByModelId'] as JsonObject
    : {};
  const remembered: Record<string, ReasoningLevel> = {};
  for (const k of Object.keys(rememberedRaw)) {
    const v = rememberedRaw[k];
    if (typeof v === 'string') remembered[k] = reasoningFromWire(v);
  }

  const presetRaw = Array.isArray(o['presetMessages']) ? o['presetMessages'] as JsonValue[] : [];
  const regexRaw = Array.isArray(o['regexes']) ? o['regexes'] as JsonValue[] : [];
  const headersRaw = Array.isArray(o['customHeaders']) ? o['customHeaders'] as JsonValue[] : [];
  const bodiesRaw = Array.isArray(o['customBodies']) ? o['customBodies'] as JsonValue[] : [];

  return makeAssistant({
    id: o['id'] as string,
    chatModelId: nullableStr(o, 'chatModelId'),
    imageGenerationModelId: nullableStr(o, 'imageGenerationModelId'),
    name: strOr(o, 'name', ''),
    avatar: parseAvatar(o['avatar']),
    useAssistantAvatar: boolOr(o, 'useAssistantAvatar', false),
    tags: strArrOr(o, 'tags'),
    systemPrompt: strOr(o, 'systemPrompt', ''),
    temperature: nullableNum(o, 'temperature'),
    topP: nullableNum(o, 'topP'),
    contextMessageSize: numOr(o, 'contextMessageSize', 0),
    streamOutput: boolOr(o, 'streamOutput', true),
    enableMemory: boolOr(o, 'enableMemory', false),
    useGlobalMemory: boolOr(o, 'useGlobalMemory', false),
    enableRecentChatsReference: boolOr(o, 'enableRecentChatsReference', false),
    messageTemplate: strOr(o, 'messageTemplate', '{{ message }}'),
    presetMessages: presetRaw.map(
      (m: JsonValue): UIMessage => parseUIMessage(isObj(m) ? m : {})),
    quickMessageIds: strArrOr(o, 'quickMessageIds'),
    regexes: regexRaw.map(parseRegex),
    reasoningLevel: reasoningFromWire(rawReasoning),
    maxTokens: nullableNum(o, 'maxTokens'),
    customHeaders: headersRaw.map((v: JsonValue): CustomHeader => {
      const h = isObj(v) ? v : {};
      return { name: strOr(h, 'name', ''), value: strOr(h, 'value', '') };
    }),
    customBodies: bodiesRaw.map((v: JsonValue): CustomBody => {
      const b = isObj(v) ? v : {};
      return { key: strOr(b, 'key', ''), value: b['value'] ?? null };
    }),
    mcpServers: strArrOr(o, 'mcpServers'),
    localTools,
    toolProfile: rawProfile as MainAgentToolProfile,
    background: nullableStr(o, 'background'),
    backgroundOpacity: numOr(o, 'backgroundOpacity', 1),
    enabledSkills: strArrOr(o, 'enabledSkills'),
    enableTimeReminder: boolOr(o, 'enableTimeReminder', false),
    rememberedReasoningLevelsByModelId: remembered,
  });
};

// ===== List(Settings.assistants blob) =====

export const serializeAssistantList = (list: Assistant[]): string =>
  `[${list.map(serializeAssistant).join(',')}]`;

export const parseAssistantList = (json: string): Assistant[] => {
  const parsed: unknown = JSON.parse(json);
  if (!Array.isArray(parsed)) throw new Error('assistants json is not an array');
  return parsed.map((v: JsonValue): Assistant => parseAssistantFromObject(isObj(v) ? v : {}));
};
