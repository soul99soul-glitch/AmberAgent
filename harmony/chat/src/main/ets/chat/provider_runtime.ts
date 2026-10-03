// Shared model/parameter/auth snapshot; Entry supplies the existing protocol factory.
import type { Assistant } from './assistant.ts';
import { resolveSessionDefaults, defaultReasoningLevelForModel, mergeCustomParams, toChatModel } from './context_assembly.ts';
import type { ResolvedSessionDefaults } from './context_assembly.ts';
import type { ProviderSetting, ProviderSettingOpenAIVariant, ProviderModel } from './provider_settings.ts';
import type { TextGenerationParams } from './provider_model.ts';
import { makeTextGenerationParams } from './provider_model.ts';
import type { OpenAIChatApi } from './openai_chat_api.ts';

export interface ProviderOAuthSnapshot { accessToken: string; accountId?: string; }
export interface ProviderAuthOptions {
  provider: ProviderSetting;
  refreshCodex: (providerId: string) => Promise<ProviderOAuthSnapshot | null>;
  refreshGrok: (providerId: string) => Promise<ProviderOAuthSnapshot | null>;
}
export interface ProviderAuthSnapshot { provider: ProviderSetting; accountId: string; }
export interface ProviderRuntimeOptions extends ProviderAuthOptions {
  model: ProviderModel;
  assistant: Assistant;
  buildApi: (provider: ProviderSetting, headers: Record<string, string>, accountId: string) => OpenAIChatApi;
}
export interface ProviderRuntimeSnapshot {
  api: OpenAIChatApi;
  provider: ProviderSetting;
  model: ProviderModel;
  params: TextGenerationParams;
  defaults: ResolvedSessionDefaults;
}

// Auxiliary tasks share authentication while retaining their own request parameters.
export const prepareProviderAuth = async (options: ProviderAuthOptions): Promise<ProviderAuthSnapshot> => {
  const provider: ProviderSetting = JSON.parse(JSON.stringify(options.provider)) as ProviderSetting;
  let accountId: string = '';
  if (provider.type === 'openai') {
    const setting: ProviderSettingOpenAIVariant = provider;
    if (setting.authMode === 'codex_oauth' || setting.authMode === 'grok_oauth') {
      const tokens: ProviderOAuthSnapshot | null = setting.authMode === 'codex_oauth'
        ? await options.refreshCodex(setting.id) : await options.refreshGrok(setting.id);
      if (tokens === null || tokens.accessToken.trim().length === 0) {
        throw new Error(`${setting.authMode === 'codex_oauth' ? 'Codex' : 'Grok'} 登录已失效，请在提供商设置中重新登录。`);
      }
      setting.apiKey = tokens.accessToken;
      if (setting.authMode === 'codex_oauth') accountId = tokens.accountId ?? '';
    }
  }
  return { provider, accountId };
};

export const prepareProviderRuntime = async (options: ProviderRuntimeOptions): Promise<ProviderRuntimeSnapshot> => {
  const model: ProviderModel = JSON.parse(JSON.stringify(options.model)) as ProviderModel;
  const assistant: Assistant = JSON.parse(JSON.stringify(options.assistant)) as Assistant;
  const defaults: ResolvedSessionDefaults = resolveSessionDefaults(assistant, null, defaultReasoningLevelForModel(model));
  const merged = mergeCustomParams(assistant, model);
  const params: TextGenerationParams = makeTextGenerationParams({
    model: toChatModel(model), temperature: merged.temperature, topP: merged.topP,
    maxTokens: defaults.maxTokens, reasoningLevel: defaults.reasoningLevel, customBody: merged.customBodies,
  });
  const headers: Record<string, string> = {};
  for (const header of merged.customHeaders) {
    if (header.name.trim().length > 0) headers[header.name.trim()] = header.value.trim();
  }
  const auth: ProviderAuthSnapshot = await prepareProviderAuth(options);
  return { api: options.buildApi(auth.provider, headers, auth.accountId), provider: auth.provider, model, params, defaults };
};
