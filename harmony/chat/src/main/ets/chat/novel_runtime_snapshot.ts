import type { Assistant } from './assistant.ts';
import type { TextGenerationParams } from './provider_model.ts';
import type { ProviderSetting, ProviderModel } from './provider_settings.ts';
import { mergeCustomParams } from './context_assembly.ts';
import { defaultGhostwriteDigest } from '@amber/deepread-domain';

export interface NovelFrozenRuntimeConfiguration {
  version: 1;
  endpointFingerprint: string;
  headerFingerprint: string;
  assistant: Assistant;
  params: TextGenerationParams;
  contextWindowTokens: number | null;
}

// Credentials stay in the provider store. A changed endpoint is not the same request.
const endpointFingerprint = (provider: ProviderSetting): string => {
  if (provider.type === 'openai') return JSON.stringify([
    provider.id, provider.type, provider.baseUrl, provider.chatCompletionsPath, provider.useResponseApi, provider.authMode,
  ]);
  if (provider.type === 'google') return JSON.stringify([
    provider.id, provider.type, provider.baseUrl, provider.vertexAI, provider.useServiceAccount,
    provider.serviceAccountEmail, provider.location, provider.projectId, provider.authMode,
  ]);
  return JSON.stringify([provider.id, provider.type, provider.baseUrl, provider.promptCaching]);
};

const headerFingerprint = (assistant: Assistant, model: ProviderModel): string => {
  const headers: Record<string, string> = {};
  for (const header of mergeCustomParams(assistant, model).customHeaders) {
    if (header.name.trim().length > 0) headers[header.name.trim()] = header.value.trim();
  }
  return defaultGhostwriteDigest(JSON.stringify(Object.keys(headers).sort().map(name => [name, headers[name]])));
};

export const freezeNovelRuntimeConfiguration = (
  provider: ProviderSetting, model: ProviderModel, assistant: Assistant,
  params: TextGenerationParams, contextWindowTokens: number | null,
): string => {
  const configuration: NovelFrozenRuntimeConfiguration = {
    version: 1, endpointFingerprint: endpointFingerprint(provider),
    headerFingerprint: headerFingerprint(assistant, model),
    assistant: { ...assistant, customHeaders: [] }, params, contextWindowTokens,
  };
  return JSON.stringify(configuration);
};

export const readNovelRuntimeConfiguration = (
  configurationJson: string, provider: ProviderSetting, model: ProviderModel, assistant: Assistant,
): NovelFrozenRuntimeConfiguration => {
  let configuration: NovelFrozenRuntimeConfiguration;
  try { configuration = JSON.parse(configurationJson) as NovelFrozenRuntimeConfiguration; }
  catch { throw new Error('原生成设置无法读取，请重新发起创作'); }
  if (configuration?.version !== 1 || typeof configuration.assistant?.id !== 'string' ||
    typeof configuration.params?.model?.modelId !== 'string' || configuration.params.model.modelId.length === 0 ||
    (configuration.contextWindowTokens !== null &&
      (typeof configuration.contextWindowTokens !== 'number' || configuration.contextWindowTokens <= 0))) {
    throw new Error('原生成设置不完整，请重新发起创作');
  }
  if (configuration.endpointFingerprint !== endpointFingerprint(provider)) {
    throw new Error('原提供商的接口设置已改变，无法恢复同一生成请求');
  }
  if (configuration.headerFingerprint !== headerFingerprint(assistant, model)) {
    throw new Error('原生成的自定义请求头已改变，请恢复原设置或重新发起创作');
  }
  return configuration;
};
