// ProviderSetting 持久化全量模型(纯数据层,含静息 secret 字段)
//
// Android 基准: ai/provider/ProviderSetting.kt(sealed 3 变体)+ Model.kt + BalanceOption
//
// D-017:
//   - apiKey/privateKey 等是静息持久化字段(DataStore at rest),进本模型;
//     请求侧 provider_model.ts(ProviderSettingOpenAI)保持 secret-free,adapter 映射
//   - @Transient builtIn/description/shortDescription 不迁移(UI 关注点,不进线格式)
//   - 领域枚举小写 union;线格式见 provider_settings_serialize.ts

import { newId } from './ids.ts';
import type { BuiltInTools, CustomBody, ModelAbility } from './provider_model.ts';
import { defaultModelAbilities } from './provider_model.ts';
import type { CustomHeader } from './assistant.ts';
import type { OpenAIAuthMode, OpenAIBrand } from './provider_model.ts';

export type { BuiltInTools };

// ===== Model(Model.kt 全量) =====

export type ModelType = 'chat' | 'image' | 'embedding';
export type ModalityFull = 'text' | 'image' | 'audio';

export interface ProviderModel {
  modelId: string;
  displayName: string;
  id: string;
  type: ModelType;
  customHeaders: CustomHeader[];
  customBodies: CustomBody[];
  inputModalities: ModalityFull[];
  outputModalities: ModalityFull[];
  abilities: ModelAbility[];
  tools: BuiltInTools[];
  contextWindowTokens: number | null;
  providerOverwrite: ProviderSetting | null;
}

export const makeProviderModel = (opts: Partial<ProviderModel> = {}): ProviderModel => ({
  modelId: opts.modelId ?? '',
  displayName: opts.displayName ?? '',
  id: opts.id ?? newId(),
  type: opts.type ?? 'chat',
  customHeaders: opts.customHeaders ?? [],
  customBodies: opts.customBodies ?? [],
  inputModalities: opts.inputModalities ?? ['text'],
  outputModalities: opts.outputModalities ?? ['text'],
  abilities: opts.abilities ?? (opts.type === 'image' || opts.type === 'embedding'
    ? [] : defaultModelAbilities(opts.modelId ?? '')),
  tools: opts.tools ?? [],
  contextWindowTokens: opts.contextWindowTokens ?? null,
  providerOverwrite: opts.providerOverwrite ?? null,
});

// ===== BalanceOption =====

export interface BalanceOption {
  enabled: boolean;
  apiPath: string;
  resultPath: string;
}

export const makeBalanceOption = (opts: Partial<BalanceOption> = {}): BalanceOption => ({
  enabled: opts.enabled ?? false,
  apiPath: opts.apiPath ?? '/credits',
  resultPath: opts.resultPath ?? 'data.total_usage',
});

// ===== GoogleAuthMode =====

export type GoogleAuthMode = 'api_key' | 'gemini_code_assist_oauth' | 'antigravity_oauth';

// ===== ProviderSetting(sealed 3 变体) =====

interface ProviderSettingBase {
  id: string;
  enabled: boolean;
  name: string;
  models: ProviderModel[];
  balanceOption: BalanceOption;
}

export interface ProviderSettingOpenAIVariant extends ProviderSettingBase {
  type: 'openai';
  apiKey: string;
  baseUrl: string;
  chatCompletionsPath: string;
  useResponseApi: boolean;
  authMode: OpenAIAuthMode;
  brand: OpenAIBrand;
}

export interface ProviderSettingGoogle extends ProviderSettingBase {
  type: 'google';
  apiKey: string;
  baseUrl: string;
  vertexAI: boolean;
  useServiceAccount: boolean;
  privateKey: string;
  privateKeyRef: string;
  serviceAccountEmail: string;
  location: string;
  projectId: string;
  authMode: GoogleAuthMode;
  oauthClientId: string;
  oauthRedirectUri: string;
  oauthClientSecretRef: string;
}

export interface ProviderSettingClaude extends ProviderSettingBase {
  type: 'claude';
  apiKey: string;
  baseUrl: string;
  promptCaching: boolean;
}

export type ProviderSetting =
  | ProviderSettingOpenAIVariant
  | ProviderSettingGoogle
  | ProviderSettingClaude;

type Opts<T> = Partial<T>;

export const makeProviderSettingOpenAIVariant = (
  opts: Opts<ProviderSettingOpenAIVariant> = {},
): ProviderSettingOpenAIVariant => ({
  type: 'openai',
  id: opts.id ?? newId(),
  enabled: opts.enabled ?? true,
  name: opts.name ?? 'OpenAI',
  models: opts.models ?? [],
  balanceOption: opts.balanceOption ?? makeBalanceOption(),
  apiKey: opts.apiKey ?? '',
  baseUrl: opts.baseUrl ?? 'https://api.openai.com/v1',
  chatCompletionsPath: opts.chatCompletionsPath ?? '/chat/completions',
  useResponseApi: opts.useResponseApi ?? false,
  authMode: opts.authMode ?? 'api_key',
  brand: opts.brand ?? 'generic',
});

export const makeProviderSettingGoogle = (
  opts: Opts<ProviderSettingGoogle> = {},
): ProviderSettingGoogle => ({
  type: 'google',
  id: opts.id ?? newId(),
  enabled: opts.enabled ?? true,
  name: opts.name ?? 'Google',
  models: opts.models ?? [],
  balanceOption: opts.balanceOption ?? makeBalanceOption(),
  apiKey: opts.apiKey ?? '',
  baseUrl: opts.baseUrl ?? 'https://generativelanguage.googleapis.com/v1beta',
  vertexAI: opts.vertexAI ?? false,
  useServiceAccount: opts.useServiceAccount ?? false,
  privateKey: opts.privateKey ?? '',
  privateKeyRef: opts.privateKeyRef ?? '',
  serviceAccountEmail: opts.serviceAccountEmail ?? '',
  location: opts.location ?? 'us-central1',
  projectId: opts.projectId ?? '',
  authMode: opts.authMode ?? 'api_key',
  oauthClientId: opts.oauthClientId ?? '',
  oauthRedirectUri: opts.oauthRedirectUri ?? '',
  oauthClientSecretRef: opts.oauthClientSecretRef ?? '',
});

export const makeProviderSettingClaude = (
  opts: Opts<ProviderSettingClaude> = {},
): ProviderSettingClaude => ({
  type: 'claude',
  id: opts.id ?? newId(),
  enabled: opts.enabled ?? true,
  name: opts.name ?? 'Claude',
  models: opts.models ?? [],
  balanceOption: opts.balanceOption ?? makeBalanceOption(),
  apiKey: opts.apiKey ?? '',
  baseUrl: opts.baseUrl ?? 'https://api.anthropic.com/v1',
  promptCaching: opts.promptCaching ?? false,
});

// ===== 纯逻辑 helper(ProviderSetting.kt:117-138, 389-408) =====

export const openAIBrandAvailableAuthModes = (brand: OpenAIBrand): OpenAIAuthMode[] => {
  switch (brand) {
    case 'generic':
    case 'deepseek': return ['api_key'];
    case 'openai': return ['api_key', 'codex_oauth'];
    case 'zhipu': return ['api_key', 'zhipu_coding_plan'];
    case 'kimi': return ['api_key', 'kimi_coding_plan'];
    case 'mimo': return ['api_key', 'mimo_coding_plan'];
    case 'minimax': return ['api_key', 'minimax_token_plan'];
  }
};

export const openAIAuthModeFixedBaseUrl = (mode: OpenAIAuthMode): string | null => {
  switch (mode) {
    case 'api_key': return null;
    case 'codex_oauth': return 'https://chatgpt.com/backend-api/codex';
    case 'grok_oauth': return 'https://cli-chat-proxy.grok.com/v1';
    case 'zhipu_coding_plan': return 'https://open.bigmodel.cn/api/coding/paas/v4';
    case 'kimi_coding_plan': return 'https://api.kimi.com/coding/v1';
    case 'mimo_coding_plan': return 'https://token-plan-cn.xiaomimimo.com/v1';
    case 'minimax_token_plan': return 'https://api.minimaxi.com/v1';
  }
};

export const googleAuthModeFixedBaseUrl = (mode: GoogleAuthMode): string | null => {
  switch (mode) {
    case 'api_key': return null;
    case 'gemini_code_assist_oauth': return 'https://cloudcode-pa.googleapis.com';
    case 'antigravity_oauth': return 'https://daily-cloudcode-pa.googleapis.com';
  }
};

// hasUsableAuth(ProviderSetting.kt:389-408):picker 与 data 层 fallback 共用判定
export const hasUsableAuth = (setting: ProviderSetting): boolean => {
  if (!setting.enabled) return false;
  if (setting.type === 'openai') {
    // API Key/Token Plan 使用保存的 Key，OAuth 使用保存的 access token；
    // 请求层按 authMode 生成鉴权头，OAuth 在运行前刷新凭据。
    return setting.apiKey.trim().length > 0;
  }
  if (setting.type === 'google') {
    if (setting.authMode !== 'api_key') {
      return setting.oauthClientId.trim().length > 0 && setting.oauthRedirectUri.trim().length > 0;
    }
    if (setting.useServiceAccount) {
      return setting.vertexAI && setting.serviceAccountEmail.trim().length > 0
        && setting.projectId.trim().length > 0 && setting.location.trim().length > 0
        && (setting.privateKey.length > 0 || setting.privateKeyRef.length > 0);
    }
    return setting.apiKey.trim().length > 0;
  }
  return setting.apiKey.trim().length > 0;
};

// ===== Model.findProvider(PreferencesStore.kt:500-518) =====

// copyProvider(models = …)(ProviderSetting.kt:156-165):data class copy —
//   变体字段全保留,仅覆盖列出字段(此处仅 models;builtIn/description/
//   shortDescription 为 @Transient UI 字段,鸿蒙模型层本就不携带)
export const copyProviderSettingWithModels = (
  setting: ProviderSetting, models: ProviderModel[],
): ProviderSetting => {
  switch (setting.type) {
    case 'openai': {
      const s: ProviderSettingOpenAIVariant = setting;
      return {
        type: 'openai', id: s.id, enabled: s.enabled, name: s.name, models,
        balanceOption: s.balanceOption, apiKey: s.apiKey, baseUrl: s.baseUrl,
        chatCompletionsPath: s.chatCompletionsPath, useResponseApi: s.useResponseApi,
        authMode: s.authMode, brand: s.brand,
      };
    }
    case 'google': {
      const s: ProviderSettingGoogle = setting;
      return {
        type: 'google', id: s.id, enabled: s.enabled, name: s.name, models,
        balanceOption: s.balanceOption, apiKey: s.apiKey, baseUrl: s.baseUrl,
        vertexAI: s.vertexAI, useServiceAccount: s.useServiceAccount,
        privateKey: s.privateKey, serviceAccountEmail: s.serviceAccountEmail,
        privateKeyRef: s.privateKeyRef,
        location: s.location, projectId: s.projectId, authMode: s.authMode,
        oauthClientId: s.oauthClientId, oauthRedirectUri: s.oauthRedirectUri,
        oauthClientSecretRef: s.oauthClientSecretRef,
      };
    }
    case 'claude': {
      const s: ProviderSettingClaude = setting;
      return {
        type: 'claude', id: s.id, enabled: s.enabled, name: s.name, models,
        balanceOption: s.balanceOption, apiKey: s.apiKey, baseUrl: s.baseUrl,
        promptCaching: s.promptCaching,
      };
    }
  }
};

// findProvider(:500-507 逐字):先按 model.id 反查容器 provider(:509-518
//   providers.forEach × models.forEach 序);未命中 → null(overwrite 不兜底);
//   命中且 checkOverwrite 且 providerOverwrite != null → overwrite 副本(models=[])
export const findProviderForModel = (
  providers: ProviderSetting[], model: ProviderModel,
  checkOverwrite: boolean = true,
): ProviderSetting | null => {
  let found: ProviderSetting | null = null;
  for (const p of providers) {
    let hit: boolean = false;
    for (const m of p.models) {
      if (m.id === model.id) {
        found = p;
        hit = true;
        break;
      }
    }
    if (hit) break;
  }
  if (found === null) return null;
  if (checkOverwrite && model.providerOverwrite !== null) {
    return copyProviderSettingWithModels(model.providerOverwrite, []);
  }
  return found;
};
