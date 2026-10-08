// ProviderSetting kotlinx JSON 线格式(DataStore providers blob)
//
// Android 基准:
//   ProviderSetting.kt(sealed:鉴别名 openai/google/claude,classDiscriminator "type" 最前)
//   Model.kt(声明序键序,枚举大写,BuiltInTools sealed,providerOverwrite 递归,
//     contextWindowTokens/providerOverwrite null 省略)
//   ai/util/Json.kt(encodeDefaults/explicitNulls=false/ignoreUnknownKeys)

import type { JsonObject, JsonValue } from './json.ts';
import type {
  ProviderSetting, ProviderModel, BalanceOption, ProviderSettingOpenAIVariant,
  ProviderSettingGoogle, ProviderSettingClaude, ModelType, ModalityFull, BuiltInTools,
  GoogleAuthMode,
} from './provider_settings.ts';
import {
  makeProviderSettingOpenAIVariant, makeProviderSettingGoogle, makeProviderSettingClaude,
  makeProviderModel, makeBalanceOption,
} from './provider_settings.ts';
import type { CustomHeader } from './assistant.ts';
import type { CustomBody, ModelAbility, OpenAIAuthMode, OpenAIBrand } from './provider_model.ts';

// ===== 枚举映射 =====

const MODEL_TYPE_WIRE: Record<ModelType, string> = { chat: 'CHAT', image: 'IMAGE', embedding: 'EMBEDDING' };
const MODALITY_WIRE: Record<ModalityFull, string> = { text: 'TEXT', image: 'IMAGE', audio: 'AUDIO' };
const ABILITY_WIRE: Record<ModelAbility, string> = { tool: 'TOOL', reasoning: 'REASONING' };

const modelTypeFromWire = (s: string): ModelType => {
  const t: Record<string, ModelType> = { CHAT: 'chat', IMAGE: 'image', EMBEDDING: 'embedding' };
  const v = t[s];
  if (v === undefined) throw new Error(`unknown ModelType: ${s}`);
  return v;
};
const modalityFromWire = (s: string): ModalityFull => {
  const t: Record<string, ModalityFull> = { TEXT: 'text', IMAGE: 'image', AUDIO: 'audio' };
  const v = t[s];
  if (v === undefined) throw new Error(`unknown Modality: ${s}`);
  return v;
};
const abilityFromWire = (s: string): ModelAbility => {
  const t: Record<string, ModelAbility> = { TOOL: 'tool', REASONING: 'reasoning' };
  const v = t[s];
  if (v === undefined) throw new Error(`unknown ModelAbility: ${s}`);
  return v;
};

const BUILT_IN_TOOLS: ReadonlyArray<string> = ['search', 'url_context', 'image_generation'];
const GOOGLE_AUTH_MODES: ReadonlyArray<string> = ['api_key', 'gemini_code_assist_oauth', 'antigravity_oauth'];
const OPENAI_AUTH_MODES: ReadonlyArray<string> = [
  'api_key', 'codex_oauth', 'grok_oauth', 'zhipu_coding_plan', 'kimi_coding_plan',
  'mimo_coding_plan', 'minimax_token_plan',
];
const OPENAI_BRANDS: ReadonlyArray<string> = [
  'generic', 'openai', 'deepseek', 'zhipu', 'kimi', 'mimo', 'minimax',
];

// ===== 小工具 =====

const isObj = (v: JsonValue | undefined): v is JsonObject =>
  v !== undefined && v !== null && typeof v === 'object' && !Array.isArray(v);

const esc = (s: string): string => JSON.stringify(s);
const strOr = (o: JsonObject, k: string, dflt: string): string =>
  typeof o[k] === 'string' ? o[k] as string : dflt;
const boolOr = (o: JsonObject, k: string, dflt: boolean): boolean =>
  typeof o[k] === 'boolean' ? o[k] as boolean : dflt;
const nullableNum = (o: JsonObject, k: string): number | null =>
  typeof o[k] === 'number' ? o[k] as number : null;
const arrOf = (o: JsonObject, k: string): JsonValue[] =>
  Array.isArray(o[k]) ? o[k] as JsonValue[] : [];

const enumOr = <T extends string>(raw: string, allowed: ReadonlyArray<string>, dflt: T): T =>
  allowed.indexOf(raw) >= 0 ? raw as T : dflt;

// ===== BalanceOption =====

const serializeBalance = (b: BalanceOption): string =>
  `{"enabled":${b.enabled},"apiPath":${esc(b.apiPath)},"resultPath":${esc(b.resultPath)}}`;

const parseBalance = (v: JsonValue | undefined): BalanceOption => {
  const o = isObj(v) ? v : {};
  return makeBalanceOption({
    enabled: boolOr(o, 'enabled', false),
    apiPath: strOr(o, 'apiPath', '/credits'),
    resultPath: strOr(o, 'resultPath', 'data.total_usage'),
  });
};

// ===== Model =====

const serializeModel = (m: ProviderModel): string => {
  const parts: string[] = [];
  parts.push(`"modelId":${esc(m.modelId)}`);
  parts.push(`"displayName":${esc(m.displayName)}`);
  parts.push(`"id":${esc(m.id)}`);
  parts.push(`"type":${esc(MODEL_TYPE_WIRE[m.type])}`);
  parts.push(`"customHeaders":[${m.customHeaders.map(
    (h: CustomHeader): string => `{"name":${esc(h.name)},"value":${esc(h.value)}}`).join(',')}]`);
  parts.push(`"customBodies":[${m.customBodies.map(
    (b: CustomBody): string => `{"key":${esc(b.key)},"value":${JSON.stringify(b.value)}}`).join(',')}]`);
  parts.push(`"inputModalities":[${m.inputModalities.map(
    (v): string => esc(MODALITY_WIRE[v])).join(',')}]`);
  parts.push(`"outputModalities":[${m.outputModalities.map(
    (v): string => esc(MODALITY_WIRE[v])).join(',')}]`);
  parts.push(`"abilities":[${m.abilities.map((v): string => esc(ABILITY_WIRE[v])).join(',')}]`);
  // 旧版空能力列表没有关闭开关。保存显式关闭标记，避免下次读取恢复默认开启。
  if (m.type === 'chat' && !m.abilities.includes('tool')) parts.push('"toolCallingDisabled":true');
  parts.push(`"tools":[${m.tools.map(
    (t: BuiltInTools): string => `{"type":${esc(t)}}`).join(',')}]`);
  if (m.contextWindowTokens !== null) parts.push(`"contextWindowTokens":${m.contextWindowTokens}`);
  if (m.providerOverwrite !== null) {
    parts.push(`"providerOverwrite":${serializeProviderSetting(m.providerOverwrite)}`);
  }
  return `{${parts.join(',')}}`;
};

const parseModel = (v: JsonValue): ProviderModel => {
  const o = isObj(v) ? v : {};
  const overwriteRaw = o['providerOverwrite'];
  const type: ModelType = modelTypeFromWire(strOr(o, 'type', 'CHAT'));
  const abilities: ModelAbility[] = arrOf(o, 'abilities').map(
    (v): ModelAbility => abilityFromWire(typeof v === 'string' ? v : ''));
  if (type === 'chat' && !boolOr(o, 'toolCallingDisabled', false) && !abilities.includes('tool')) {
    abilities.push('tool');
  }
  return makeProviderModel({
    modelId: strOr(o, 'modelId', ''),
    displayName: strOr(o, 'displayName', ''),
    id: strOr(o, 'id', ''),
    type,
    customHeaders: arrOf(o, 'customHeaders').map((h: JsonValue): CustomHeader => {
      const x = isObj(h) ? h : {};
      return { name: strOr(x, 'name', ''), value: strOr(x, 'value', '') };
    }),
    customBodies: arrOf(o, 'customBodies').map((b: JsonValue): CustomBody => {
      const x = isObj(b) ? b : {};
      return { key: strOr(x, 'key', ''), value: x['value'] ?? null };
    }),
    inputModalities: arrOf(o, 'inputModalities').map(
      (v): ModalityFull => modalityFromWire(typeof v === 'string' ? v : 'TEXT')),
    outputModalities: arrOf(o, 'outputModalities').map(
      (v): ModalityFull => modalityFromWire(typeof v === 'string' ? v : 'TEXT')),
    abilities,
    tools: arrOf(o, 'tools').map((v): BuiltInTools => {
      const t = isObj(v) && typeof v['type'] === 'string' ? v['type'] : '';
      if (BUILT_IN_TOOLS.indexOf(t) < 0) throw new Error(`unknown BuiltInTools: ${t}`);
      return t as BuiltInTools;
    }),
    contextWindowTokens: nullableNum(o, 'contextWindowTokens'),
    providerOverwrite: overwriteRaw === undefined || overwriteRaw === null
      ? null
      : parseProviderSettingFromObject(isObj(overwriteRaw) ? overwriteRaw : {}),
  });
};

// ===== ProviderSetting =====

const serializeBase = (s: ProviderSetting): string[] => [
  `"id":${esc(s.id)}`,
  `"enabled":${s.enabled}`,
  `"name":${esc(s.name)}`,
  `"models":[${s.models.map(serializeModel).join(',')}]`,
  `"balanceOption":${serializeBalance(s.balanceOption)}`,
];

export const serializeProviderSetting = (s: ProviderSetting): string => {
  const parts: string[] = [`"type":${esc(s.type)}`, ...serializeBase(s)];
  if (s.type === 'openai') {
    parts.push(`"apiKey":${esc(s.apiKey)}`);
    parts.push(`"baseUrl":${esc(s.baseUrl)}`);
    parts.push(`"chatCompletionsPath":${esc(s.chatCompletionsPath)}`);
    parts.push(`"useResponseApi":${s.useResponseApi}`);
    parts.push(`"authMode":${esc(s.authMode)}`);
    parts.push(`"brand":${esc(s.brand)}`);
  } else if (s.type === 'google') {
    parts.push(`"apiKey":${esc(s.apiKey)}`);
    parts.push(`"baseUrl":${esc(s.baseUrl)}`);
    parts.push(`"vertexAI":${s.vertexAI}`);
    parts.push(`"useServiceAccount":${s.useServiceAccount}`);
    parts.push(`"privateKey":${esc(s.privateKey)}`);
    parts.push(`"privateKeyRef":${esc(s.privateKeyRef)}`);
    parts.push(`"serviceAccountEmail":${esc(s.serviceAccountEmail)}`);
    parts.push(`"location":${esc(s.location)}`);
    parts.push(`"projectId":${esc(s.projectId)}`);
    parts.push(`"authMode":${esc(s.authMode)}`);
    parts.push(`"oauthClientId":${esc(s.oauthClientId)}`);
    parts.push(`"oauthRedirectUri":${esc(s.oauthRedirectUri)}`);
    parts.push(`"oauthClientSecretRef":${esc(s.oauthClientSecretRef)}`);
  } else {
    parts.push(`"apiKey":${esc(s.apiKey)}`);
    parts.push(`"baseUrl":${esc(s.baseUrl)}`);
    parts.push(`"promptCaching":${s.promptCaching}`);
  }
  return `{${parts.join(',')}}`;
};

const parseProviderSettingFromObject = (o: JsonObject): ProviderSetting => {
  const type = strOr(o, 'type', '');
  const base = {
    id: strOr(o, 'id', ''),
    enabled: boolOr(o, 'enabled', true),
    models: arrOf(o, 'models').map(parseModel),
    balanceOption: parseBalance(o['balanceOption']),
  };
  if (type === 'openai') {
    return makeProviderSettingOpenAIVariant({
      ...base,
      name: strOr(o, 'name', 'OpenAI'),
      apiKey: strOr(o, 'apiKey', ''),
      baseUrl: strOr(o, 'baseUrl', 'https://api.openai.com/v1'),
      chatCompletionsPath: strOr(o, 'chatCompletionsPath', '/chat/completions'),
      useResponseApi: boolOr(o, 'useResponseApi', false),
      authMode: enumOr<OpenAIAuthMode>(strOr(o, 'authMode', 'api_key'), OPENAI_AUTH_MODES, 'api_key'),
      brand: enumOr<OpenAIBrand>(strOr(o, 'brand', 'generic'), OPENAI_BRANDS, 'generic'),
    });
  }
  if (type === 'google') {
    return makeProviderSettingGoogle({
      ...base,
      name: strOr(o, 'name', 'Google'),
      apiKey: strOr(o, 'apiKey', ''),
      baseUrl: strOr(o, 'baseUrl', 'https://generativelanguage.googleapis.com/v1beta'),
      vertexAI: boolOr(o, 'vertexAI', false),
      useServiceAccount: boolOr(o, 'useServiceAccount', false),
      privateKey: strOr(o, 'privateKey', ''),
      privateKeyRef: strOr(o, 'privateKeyRef', ''),
      serviceAccountEmail: strOr(o, 'serviceAccountEmail', ''),
      location: strOr(o, 'location', 'us-central1'),
      projectId: strOr(o, 'projectId', ''),
      authMode: enumOr<GoogleAuthMode>(
        strOr(o, 'authMode', 'api_key'), GOOGLE_AUTH_MODES, 'api_key'),
      oauthClientId: strOr(o, 'oauthClientId', ''),
      oauthRedirectUri: strOr(o, 'oauthRedirectUri', ''),
      oauthClientSecretRef: strOr(o, 'oauthClientSecretRef', ''),
    });
  }
  if (type === 'claude') {
    return makeProviderSettingClaude({
      ...base,
      name: strOr(o, 'name', 'Claude'),
      apiKey: strOr(o, 'apiKey', ''),
      baseUrl: strOr(o, 'baseUrl', 'https://api.anthropic.com/v1'),
      promptCaching: boolOr(o, 'promptCaching', false),
    });
  }
  throw new Error(`unknown ProviderSetting type: ${type}`);
};

export const parseProviderSetting = (json: string): ProviderSetting => {
  const parsed: unknown = JSON.parse(json);
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('provider setting json is not an object');
  }
  return parseProviderSettingFromObject(parsed as JsonObject);
};

export const serializeProviderSettingList = (list: ProviderSetting[]): string =>
  `[${list.map(serializeProviderSetting).join(',')}]`;

export const parseProviderSettingList = (json: string): ProviderSetting[] => {
  const parsed: unknown = JSON.parse(json);
  if (!Array.isArray(parsed)) throw new Error('providers json is not an array');
  return parsed.map((v: JsonValue): ProviderSetting =>
    parseProviderSettingFromObject(isObj(v) ? v : {}));
};
